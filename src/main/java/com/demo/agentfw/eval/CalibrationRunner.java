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
 * <pre>
 * ./mvnw spring-boot:run "-Dspring-boot.run.arguments=--agentfw.calibration.run=true --spring.main.web-application-type=none"
 * </pre>
 */
@Component
@ConditionalOnProperty(prefix = "agentfw.calibration", name = "run", havingValue = "true")
public class CalibrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CalibrationRunner.class);

    private final RetrievalService retrieval;
    private final RetrievalProperties defaults;
    private final String output;

    public CalibrationRunner(RetrievalService retrieval, RetrievalProperties defaults,
                             @Value("${agentfw.calibration.output:docs/CALIBRATION.md}") String output) {
        this.retrieval = retrieval;
        this.defaults = defaults;
        this.output = output;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        // score all fused candidates once, with no threshold: depth 12 is derived from the same scores
        RetrievalProperties settings = defaults.withRerankDepth(defaults.fusedLimit()).withMinRerankScore(null);
        List<CalibrationQuestion> questions = CalibrationQuestions.load();
        log.info("Calibrating on {} questions", questions.size());

        List<QuestionOutcome> outcomes = new ArrayList<>();
        String embeddingModel = "";
        String rerankModel = "";
        for (CalibrationQuestion q : questions) {
            RetrievalResult r = retrieval.retrieve(q.question(), settings);
            outcomes.add(outcome(q, r.trace(), settings));
            embeddingModel = r.trace().config().embeddingModel();
            rerankModel = r.trace().config().rerankModel();
        }

        String markdown = CalibrationReport.render(outcomes, new CalibrationReport.Context(
                Instant.now().toString(), embeddingModel, rerankModel, settings.fusion().name(),
                settings.fusedLimit(), settings.finalTopN()));
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
                trace.rerank().truncatedPairs(), trace.rerank().millis(), trace.totalMillis());
    }

    private static String shortName(String file) {
        return file.replace("wellarchitected-", "wa-").replace(".pdf", "");
    }
}
