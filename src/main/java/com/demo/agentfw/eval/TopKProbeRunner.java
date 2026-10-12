package com.demo.agentfw.eval;

import com.demo.agentfw.retrieval.RetrievalProperties;
import com.demo.agentfw.retrieval.RetrievalResult;
import com.demo.agentfw.retrieval.RetrievalService;
import com.demo.agentfw.retrieval.RetrievalTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Answers one question about the retrieval funnel without any model call: how many of the in-corpus
 * questions have an answer page among the first N reranked passages, for N from 3 to 12. It retrieves each
 * question once (one embedding call), keeps all 12 reranked passages, and counts. This is what "send 8
 * passages to the model instead of 5" would buy, measured on the same data as {@link CalibrationRunner}.
 * Only active with {@code agentfw.topk-probe.run=true}.
 *
 * <pre>
 * ./mvnw spring-boot:run "-Dspring-boot.run.arguments=--agentfw.topk-probe.run=true --spring.main.web-application-type=none --agentfw.warmup.enabled=false"
 * </pre>
 */
@Component
@ConditionalOnProperty(prefix = "agentfw.topk-probe", name = "run", havingValue = "true")
public class TopKProbeRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TopKProbeRunner.class);
    private static final int[] SIZES = {3, 5, 6, 7, 8, 10, 12};

    private final RetrievalService retrieval;
    private final RetrievalProperties defaults;

    public TopKProbeRunner(RetrievalService retrieval, RetrievalProperties defaults) {
        this.retrieval = retrieval;
        this.defaults = defaults;
    }

    @Override
    public void run(ApplicationArguments args) {
        RetrievalProperties settings = defaults.withRerankDepth(12).withMinRerankScore(null);
        List<CalibrationQuestion> questions = CalibrationQuestions.load().stream()
                .filter(CalibrationQuestion::inCorpus).toList();
        int[] strict = new int[SIZES.length];
        int[] widened = new int[SIZES.length];
        List<String> lines = new ArrayList<>();
        for (CalibrationQuestion q : questions) {
            RetrievalResult r = retrieval.retrieve(q.question(), settings);
            List<RetrievalTrace.ScoredPassage> scored = r.trace().rerank().scored();
            StringBuilder row = new StringBuilder(String.format("%-7s", q.id()));
            for (int s = 0; s < SIZES.length; s++) {
                int n = Math.min(SIZES[s], scored.size());
                boolean st = scored.subList(0, n).stream().anyMatch(p -> q.isExpected(p.file(), p.page()));
                boolean wi = scored.subList(0, n).stream().anyMatch(p -> q.isAcceptable(p.file(), p.page()));
                strict[s] += st ? 1 : 0;
                widened[s] += wi ? 1 : 0;
                row.append(String.format(" N=%-2d %s/%s", SIZES[s], st ? "S" : "-", wi ? "W" : "-"));
            }
            lines.add(row.toString());
        }
        StringBuilder out = new StringBuilder("\nTOPK-PROBE (answer page among the first N reranked passages, depth 12)\n");
        lines.forEach(l -> out.append(l).append('\n'));
        out.append(String.format("%-7s", "TOTAL"));
        for (int s = 0; s < SIZES.length; s++) {
            out.append(String.format(" N=%-2d %d/%d", SIZES[s], strict[s], widened[s]));
        }
        out.append("   (strict/widened, of ").append(questions.size()).append(")\n");
        log.info(out.toString());
    }
}
