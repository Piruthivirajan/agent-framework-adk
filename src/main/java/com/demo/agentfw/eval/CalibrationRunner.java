package com.demo.agentfw.eval;

import com.demo.agentfw.retrieval.RetrievalProperties;
import com.demo.agentfw.retrieval.RetrievalResult;
import com.demo.agentfw.retrieval.RetrievalService;
import com.demo.agentfw.retrieval.RetrievalTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Runs every calibration question through the real retrieval pipeline and writes the report. Only
 * active with {@code agentfw.calibration.run=true}, so it never runs by accident (it calls the
 * embedding API once per question).
 *
 * <p>Each run is saved as {@code <dir>/<label>.json}. With {@code compare-with=<other label>} the
 * report is the side-by-side comparison of that saved run and this one, with {@code <dir>/findings.md}
 * (hand-written interpretation) placed first when it exists. With {@code render-only=true} nothing is
 * retrieved: the report is redrawn from the saved runs.
 *
 * <pre>
 * ./mvnw spring-boot:run "-Dspring-boot.run.arguments=--agentfw.calibration.run=true --agentfw.calibration.label=baseline --spring.main.web-application-type=none"
 * </pre>
 */
@Component
@ConditionalOnProperty(prefix = "agentfw.calibration", name = "run", havingValue = "true")
public class CalibrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CalibrationRunner.class);

    private final RetrievalService retrieval;
    private final RetrievalProperties defaults;
    private final String label;
    private final String note;
    private final String compareWith;
    private final String dir;
    private final String output;
    private final boolean renderOnly;

    public CalibrationRunner(RetrievalService retrieval, RetrievalProperties defaults,
                             @Value("${agentfw.calibration.label:run}") String label,
                             @Value("${agentfw.calibration.note:}") String note,
                             @Value("${agentfw.calibration.compare-with:}") String compareWith,
                             @Value("${agentfw.calibration.dir:docs/calibration}") String dir,
                             @Value("${agentfw.calibration.output:docs/CALIBRATION.md}") String output,
                             @Value("${agentfw.calibration.render-only:false}") boolean renderOnly) {
        this.retrieval = retrieval;
        this.defaults = defaults;
        this.label = label;
        this.note = note;
        this.compareWith = compareWith;
        this.dir = dir;
        this.output = output;
        this.renderOnly = renderOnly;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        Path saved = Path.of(dir, label + ".json");
        RetrievalProperties settings = defaults.withRerankDepth(defaults.fusedLimit()).withMinRerankScore(null);

        CalibrationRun current;
        List<QuestionOutcome> outcomes;
        CalibrationReport.Context context;
        if (renderOnly) {
            // no retrieval and no API call: redraw the report from a saved run
            current = mapper.readValue(Files.readString(saved, StandardCharsets.UTF_8), CalibrationRun.class);
            outcomes = current.outcomes();
            context = current.context();
            log.info("Re-rendering saved run '{}' from {}", label, saved.toAbsolutePath());
        } else {
            // score all fused candidates once, with no threshold: depth 12 is derived from the same scores
            List<CalibrationQuestion> questions = CalibrationQuestions.load();
            log.info("Calibrating '{}' on {} questions", label, questions.size());

            outcomes = new ArrayList<>();
            String embeddingModel = "";
            String rerankModel = "";
            for (CalibrationQuestion q : questions) {
                RetrievalResult r = retrieval.retrieve(q.question(), settings);
                outcomes.add(outcome(q, r.trace(), settings));
                embeddingModel = r.trace().config().embeddingModel();
                rerankModel = r.trace().config().rerankModel();
            }
            context = new CalibrationReport.Context(Instant.now().toString(), embeddingModel,
                    rerankModel, settings.fusion().name(), settings.fusedLimit(), settings.finalTopN());
            current = new CalibrationRun(label, note, context, outcomes);
            Files.createDirectories(saved.getParent());
            Files.writeString(saved, mapper.writeValueAsString(current), StandardCharsets.UTF_8);
            log.info("Saved run to {}", saved.toAbsolutePath());
        }

        String markdown;
        Path other = compareWith.isBlank() ? null : Path.of(dir, compareWith + ".json");
        if (other != null && Files.isRegularFile(other)) {
            CalibrationRun baseline = mapper.readValue(Files.readString(other, StandardCharsets.UTF_8),
                    CalibrationRun.class);
            Path findingsFile = Path.of(dir, "findings.md");
            String findings = Files.isRegularFile(findingsFile) ? Files.readString(findingsFile, StandardCharsets.UTF_8) : null;
            markdown = CalibrationReport.renderComparison(baseline, current, findings);
        } else {
            if (other != null) {
                log.warn("Run '{}' to compare with was not found at {}; writing a single-run report", compareWith, other);
            }
            markdown = CalibrationReport.render(outcomes, context);
        }
        Path target = Path.of(output);
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, markdown, StandardCharsets.UTF_8);
        log.info("Calibration report written to {}", target.toAbsolutePath());
    }

    static QuestionOutcome outcome(CalibrationQuestion q, RetrievalTrace trace, RetrievalProperties s) {
        List<RetrievalTrace.ScoredPassage> byScore = trace.rerank().scored();   // best first, depth 20
        RetrievalTrace.ScoredPassage best = byScore.get(0);

        List<RetrievalTrace.ScoredPassage> within12 = byScore.stream().filter(p -> p.fusedRank() <= 12)
                .sorted(Comparator.comparingInt(RetrievalTrace.ScoredPassage::rerankRank)).toList();
        double top12 = within12.isEmpty() ? best.rerankScore() : within12.get(0).rerankScore();

        Integer expectedRank = null;
        boolean in5d20 = false;
        boolean in5d12 = false;
        if (q.inCorpus()) {
            expectedRank = trace.search().fusedTop().stream()
                    .filter(c -> q.isExpected(c.file(), c.page()))
                    .map(RetrievalTrace.FusedCandidate::fusedRank).min(Integer::compare).orElse(null);
            in5d20 = byScore.stream().limit(s.finalTopN()).anyMatch(p -> q.isExpected(p.file(), p.page()));
            in5d12 = within12.stream().limit(s.finalTopN()).anyMatch(p -> q.isExpected(p.file(), p.page()));
        }
        return new QuestionOutcome(q.id(), q.type(), q.split(), q.question(), best.rerankScore(),
                shortName(best.file()) + " p" + best.page(), top12, expectedRank, in5d20, in5d12,
                trace.rerank().truncatedPairs(), trace.rerank().millis(), trace.totalMillis(), q.paraphrase());
    }

    private static String shortName(String file) {
        return file.replace("wellarchitected-", "wa-").replace(".pdf", "");
    }
}
