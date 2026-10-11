package com.demo.agentfw.agents.file;

import com.demo.agentfw.agents.file.FileSummaryLedger.Outcome;
import com.demo.agentfw.core.AgentContext;
import com.demo.agentfw.core.AgentPlugin;
import com.demo.agentfw.core.TurnState;
import com.demo.agentfw.files.MapReduceSummarizer;
import com.demo.agentfw.files.UploadService;
import com.demo.agentfw.files.UploadedFile;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Summarizes a file the user uploaded in this chat session (or answers a request about it). It is
 * reached two ways: by a code rule when a message carries an attachment, and as a coordinator tool
 * when the user refers to a file they uploaded earlier ("make it shorter", "what does that file say
 * about backups").
 *
 * <p>The agent is deterministic code, not a model that chooses tools: the step 0 spike measured an
 * {@code LlmAgent} with a summarize tool at +4 s (two extra model calls) over the same work done
 * directly. Which file is used is decided in code, in this order: the attached file of this turn; a
 * {@code fileId=} in the request; an uploaded file whose name the request mentions; the most recent
 * upload of the session.
 */
@Component
public class FileAgentPlugin implements AgentPlugin {

    public static final String NAME = "file_agent";

    static final String NO_FILE_MESSAGE = "No file has been uploaded in this session. Attach a PDF, a DOCX or a "
            + "text file, or paste the text, and ask again.";
    static final String GONE_MESSAGE = "That file is no longer available (uploads are kept for a limited time). "
            + "Please upload it again.";

    private static final Pattern FILE_ID = Pattern.compile(
            "fileId=([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");
    private static final Logger log = LoggerFactory.getLogger(FileAgentPlugin.class);

    private final UploadService uploads;
    private final MapReduceSummarizer summarizer;
    private final FileSummaryLedger ledger;

    public FileAgentPlugin(UploadService uploads, MapReduceSummarizer summarizer, FileSummaryLedger ledger) {
        this.uploads = uploads;
        this.summarizer = summarizer;
        this.ledger = ledger;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Summarizes, or answers a request about, a file or pasted text that the user attached or "
                + "uploaded in this conversation (PDF, DOCX or text). Use it whenever the user refers to their "
                + "own uploaded file, for example \"summarize this file\", \"make that summary shorter\" or "
                + "\"what does the file I uploaded say about backups\". Do not use it for questions about the "
                + "reference documents.";
    }

    @Override
    public BaseAgent build(AgentContext context) {
        return new FileAgent();
    }

    // ---- resolution (package-private for tests) -------------------------------------------------

    /** The file this request is about, by the order described in the class comment. */
    Optional<UploadedFile> resolve(String sessionId, String attachedFileId, String request) {
        if (attachedFileId != null && !attachedFileId.isBlank()) {
            return uploads.find(sessionId, attachedFileId);
        }
        Matcher m = FILE_ID.matcher(request);
        if (m.find()) {
            Optional<UploadedFile> byId = uploads.find(sessionId, m.group(1));
            if (byId.isPresent()) {
                return byId;
            }
        }
        List<UploadedFile.Summary> files = uploads.list(sessionId);
        if (files.isEmpty()) {
            return Optional.empty();
        }
        String lower = request.toLowerCase(Locale.ROOT);
        Optional<UploadedFile.Summary> byName = files.stream()
                .filter(f -> lower.contains(f.name().toLowerCase(Locale.ROOT)))
                .reduce((first, second) -> second);          // the later upload wins on a tie
        UploadedFile.Summary chosen = byName.orElse(files.get(files.size() - 1));
        return uploads.find(sessionId, chosen.id());
    }

    /** Runs the summarizer for a request and records the outcome; returns the text for the event. */
    String handle(String sessionId, String requestId, String attachedFileId, String request,
                  java.util.function.Consumer<MapReduceSummarizer.Progress> progress) {
        boolean attached = attachedFileId != null && !attachedFileId.isBlank();
        Optional<UploadedFile> file = resolve(sessionId, attachedFileId, request);
        if (file.isEmpty()) {
            String message = attached ? GONE_MESSAGE : NO_FILE_MESSAGE;
            ledger.record(requestId, Outcome.noFile(message));
            return message;
        }
        UploadedFile f = file.get();
        try {
            MapReduceSummarizer.Result r = summarizer.summarize(f.text(), request, progress);
            ledger.record(requestId, new Outcome(FileSummaryLedger.Status.SUMMARIZED, null, r.summary(), f.id(),
                    f.name(), f.contentType(), f.chars(), r.strategy(), r.segments(), r.modelCalls(), r.millis()));
            log.info("file_agent request={} file={} chars={} strategy={} segments={} calls={} ms={}", requestId,
                    f.id(), f.chars(), r.strategy(), r.segments(), r.modelCalls(), r.millis());
            return r.summary();
        } catch (RuntimeException e) {
            String message = e instanceof MapReduceSummarizer.SummarizationFailedException
                    ? e.getMessage() : "The file could not be summarized: " + e.getMessage();
            log.error("file_agent request={} file={} failed: {}", requestId, f.id(), e.toString());
            ledger.record(requestId, Outcome.failed(message));
            return message;
        }
    }

    // ---- the agent ------------------------------------------------------------------------------

    private final class FileAgent extends BaseAgent {

        FileAgent() {
            super(NAME, FileAgentPlugin.this.description(), List.of(), List.of(), List.of());
        }

        @Override
        protected Flowable<Event> runAsyncImpl(InvocationContext context) {
            return Flowable.fromCallable(() -> {
                Map<String, Object> state = context.session().state();
                String request = context.userContent().flatMap(Content::parts)
                        .map(parts -> parts.isEmpty() ? "" : parts.get(0).text().orElse("")).orElse("");
                String requestId = str(state.get(TurnState.REQUEST_ID));
                String sessionId = str(state.get(TurnState.SESSION_ID));
                String text = handle(sessionId, requestId, str(state.get(TurnState.FILE_ID)), request,
                        progress -> { });
                return Event.builder().id(Event.generateEventId()).invocationId(context.invocationId())
                        .author(NAME).content(Content.builder().role("model").parts(Part.fromText(text)).build())
                        .build();
            });
        }

        @Override
        protected Flowable<Event> runLiveImpl(InvocationContext context) {
            return Flowable.error(new UnsupportedOperationException("Live mode is not used by the file agent"));
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
