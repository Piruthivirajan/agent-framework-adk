package com.demo.agentfw.eval;

import com.demo.agentfw.core.ChatResult;
import com.demo.agentfw.core.ChatService;
import com.demo.agentfw.core.LlmProperties;
import com.demo.agentfw.retrieval.RetrievalTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs calibration questions end to end through the real model. It calls the language model a few
 * times per question, so it only exists with {@code agentfw.e2e.run=true}.
 *
 * <pre>
 * ./mvnw spring-boot:run "-Dspring-boot.run.arguments=--agentfw.e2e.run=true --agentfw.e2e.label=full
 *     --agentfw.e2e.ids=in-04,out-aws-01 --spring.main.web-application-type=none"
 * </pre>
 * With no {@code ids} every question of the calibration set is asked.
 */
@Component
@ConditionalOnProperty(prefix = "agentfw.e2e", name = "run", havingValue = "true")
public class EndToEndRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EndToEndRunner.class);

    private final ChatService chat;
    private final LlmProperties llm;
    private final String label;
    private final String ids;
    private final String dir;
    private final int retries;
    private final String output;
    private final boolean renderOnly;

    public EndToEndRunner(ChatService chat, LlmProperties llm,
                          @Value("${agentfw.e2e.label:run}") String label,
                          @Value("${agentfw.e2e.ids:}") String ids,
                          @Value("${agentfw.e2e.dir:docs/e2e}") String dir,
                          @Value("${agentfw.e2e.retries:2}") int retries,
                          @Value("${agentfw.e2e.output:}") String output,
                          @Value("${agentfw.e2e.render-only:false}") boolean renderOnly) {
        this.chat = chat;
        this.llm = llm;
        this.label = label;
        this.ids = ids;
        this.dir = dir;
        this.retries = retries;
        this.output = output;
        this.renderOnly = renderOnly;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
        Path file = Path.of(dir, label + ".json");
        E2eRun run;
        if (renderOnly) {
            run = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), E2eRun.class);
            log.info("Re-rendering saved end-to-end run '{}' from {}", label, file.toAbsolutePath());
        } else {
            run = execute();
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writeValueAsString(run), StandardCharsets.UTF_8);
            log.info("Saved end-to-end run to {}", file.toAbsolutePath());
        }
        if (!output.isBlank()) {
            Path findingsFile = Path.of(dir, "findings.md");
            String findings = Files.isRegularFile(findingsFile)
                    ? Files.readString(findingsFile, StandardCharsets.UTF_8) : null;
            Path target = Path.of(output);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, EndToEndReport.render(run, findings), StandardCharsets.UTF_8);
            log.info("Report written to {}", target.toAbsolutePath());
        }
    }

    private E2eRun execute() {
        Set<String> wanted = ids.isBlank() ? Set.of()
                : Arrays.stream(ids.split(",")).map(String::strip).collect(Collectors.toSet());
        List<CalibrationQuestion> questions = CalibrationQuestions.load().stream()
                .filter(q -> wanted.isEmpty() || wanted.contains(q.id())).toList();
        log.info("End-to-end '{}': {} questions, model {}, coordinator thinking budget {}", label, questions.size(),
                llm.model(), llm.coordinatorThinkingBudget());

        List<E2eOutcome> outcomes = new ArrayList<>();
        for (CalibrationQuestion q : questions) {
            E2eOutcome o = ask(q);
            outcomes.add(o);
            log.info("e2e {} [{}] {} | {} ms | {} | {}", q.id(), q.type(), o.verdict(), o.totalMillis(),
                    o.answered() ? "answered " + o.citedPages() : "not answered (" + o.notFoundReason() + ")",
                    q.question());
        }
        return new E2eRun(label, llm.model(), llm.coordinatorThinkingBudget(), llm.agentThinkingBudget(),
                Instant.now().toString(), outcomes);
    }

    private E2eOutcome ask(CalibrationQuestion q) {
        ChatResult result = null;
        String error = null;
        for (int attempt = 0; attempt <= retries && result == null; attempt++) {
            try {
                result = chat.chat("e2e-" + label + "-" + q.id(), q.question());
            } catch (ChatService.ChatFailedException e) {
                error = e.getMessage();
                log.warn("e2e {} attempt {} failed: {}", q.id(), attempt + 1, error);
                sleep(8_000L * (attempt + 1));
            }
        }
        if (result == null) {
            return new E2eOutcome(q.id(), q.type(), q.split(), q.question(), "ERROR", false, false, null, List.of(),
                    "", "", List.of(), 0, null, 0, null, error);
        }
        return classify(q, result);
    }

    static E2eOutcome classify(CalibrationQuestion q, ChatResult r) {
        boolean answered = r.grounded();
        List<String> cited = r.sources().stream().map(s -> s.file() + " p. " + s.page()).distinct().toList();
        boolean citesStrict = r.sources().stream().anyMatch(s -> q.isExpected(s.file(), s.page()));
        boolean citesWidened = r.sources().stream().anyMatch(s -> q.isAcceptable(s.file(), s.page()));

        String verdict;
        if (q.inCorpus()) {
            verdict = answered ? (citesWidened ? "CORRECT" : "FALSE_ANSWER") : "FALSE_NOT_FOUND";
        } else {
            verdict = answered ? "FALSE_ANSWER" : "CORRECT";
        }

        String reason = "";
        if (!answered) {
            if (r.verification().modelSaidNotFound()) {
                boolean floor = !r.retrievals().isEmpty() && r.retrievals().stream()
                        .allMatch(t -> t.grounding().decision().name().equals("NO_RELEVANT_CONTEXT"));
                reason = floor ? "retrieval-floor" : "model";
            } else if ("none".equals(r.route())) {
                reason = "no-tool";
            } else if (!r.verification().problems().isEmpty()) {
                reason = "verifier: " + r.verification().problems().get(0);
            }
        }
        Double top = r.retrievals().isEmpty() ? null : r.retrievals().stream()
                .map(RetrievalTrace::grounding).map(RetrievalTrace.Grounding::topScore)
                .filter(s -> s != null).max(Double::compare).orElse(null);
        return new E2eOutcome(q.id(), q.type(), q.split(), q.question(), verdict,
                q.inCorpus() && answered && citesStrict, answered, r.answer(), cited, reason, r.route(),
                r.verification().problems(), r.retrievals().size(), top, r.totalMillis(), r.modelOutput(), null);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
