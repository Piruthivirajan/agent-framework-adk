package com.demo.agentfw.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Renders an end-to-end run as Markdown. Per question type: correct answers, false answers and
 * false "not found" (an answerable question that got no answer). All numbers come from the saved
 * outcomes, so they can be reproduced and unit-tested.
 */
public final class EndToEndReport {

    static final List<String> TYPES = List.of("IN_CORPUS", "OUT_FAR", "OUT_NEAR", "OUT_AWS",
            "OUT_ADVERSARIAL");

    private EndToEndReport() {
    }

    public static String render(E2eRun run, String findings) {
        List<E2eOutcome> all = run.outcomes();
        StringBuilder md = new StringBuilder();
        md.append("# End-to-end results: ").append(run.label()).append("\n\n");
        if (findings != null && !findings.isBlank()) {
            md.append(findings.strip()).append("\n\n");
        }
        md.append("Generated ").append(run.startedAt()).append(". Model `").append(run.model())
                .append("`, coordinator thinking budget ").append(run.coordinatorThinkingBudget() == null ? "default"
                        : String.valueOf(run.coordinatorThinkingBudget()))
                .append(", agent thinking budget ").append(run.agentThinkingBudget() == null ? "default"
                        : String.valueOf(run.agentThinkingBudget()))
                .append(". Every question went through the real coordinator, RAG agent, retrieval and verifier.\n\n");

        summary(md, all);
        verifier(md, all);
        falseNotFound(md, all);
        falseAnswers(md, all);
        latency(md, all);
        everyAnswer(md, all);
        return md.toString();
    }

    // ---- sections -------------------------------------------------------------------------------

    private static void summary(StringBuilder md, List<E2eOutcome> all) {
        md.append("## Per question type\n\n");
        md.append("In-corpus: **correct** = answered and cited an acceptable page (strict and widened rules both shown); "
                + "**false answer** = answered but cited no acceptable page; **false \"not found\"** = not answered. "
                + "Out-of-corpus: **correct** = not found; **false answer** = any answer.\n\n");
        md.append("| type | n | correct | of which strict pages | false answers | false \"not found\" | errors |\n");
        md.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (String type : TYPES) {
            List<E2eOutcome> g = all.stream().filter(o -> o.type().equals(type)).toList();
            if (g.isEmpty()) {
                continue;
            }
            md.append(String.format(Locale.ROOT, "| %s | %d | %d | %s | %d | %d | %d |%n", type, g.size(),
                    count(g, "CORRECT"), type.equals("IN_CORPUS") ? String.valueOf(g.stream().filter(E2eOutcome::strictCorrect).count()) : "-",
                    count(g, "FALSE_ANSWER"), count(g, "FALSE_NOT_FOUND"), count(g, "ERROR")));
        }
        md.append("\n");
    }

    private static void verifier(StringBuilder md, List<E2eOutcome> all) {
        List<E2eOutcome> rejected = all.stream().filter(o -> !o.problems().isEmpty() && !o.answered()
                && o.notFoundReason().startsWith("verifier")).toList();
        long model = all.stream().filter(o -> !o.answered() && o.notFoundReason().equals("model")).count();
        long floor = all.stream().filter(o -> !o.answered() && o.notFoundReason().equals("retrieval-floor")).count();
        long noTool = all.stream().filter(o -> !o.answered() && o.notFoundReason().equals("no-tool")).count();
        long inCorpus = all.stream().filter(E2eOutcome::inCorpus).count();
        long inRejected = rejected.stream().filter(E2eOutcome::inCorpus).count();

        md.append("## Why questions were not answered\n\n");
        md.append("| reason | questions |\n| --- | ---: |\n");
        md.append("| retrieval floor (nothing relevant retrieved) | ").append(floor).append(" |\n");
        md.append("| the model itself answered NOT_FOUND | ").append(model).append(" |\n");
        md.append("| **the verifier replaced the answer** | ").append(rejected.size()).append(" |\n");
        md.append("| no tool was called | ").append(noTool).append(" |\n\n");
        if (inCorpus > 0) {
            md.append(String.format(Locale.ROOT,
                    "**False \"not found\" caused by the verifier: %d of %d in-corpus questions (%.1f%%).** "
                            + "This is the number that decides whether the one-retry option is worth testing.%n%n",
                    inRejected, inCorpus, 100.0 * inRejected / inCorpus));
        }
        if (!rejected.isEmpty()) {
            md.append("Questions where the verifier replaced an answer (the model's raw output is kept in the saved run):\n\n");
            md.append("| id | type | first problem | question |\n| --- | --- | --- | --- |\n");
            for (E2eOutcome o : rejected) {
                md.append(String.format(Locale.ROOT, "| %s | %s | %s | %s |%n", o.id(), o.type(),
                        escape(o.problems().get(0)), escape(o.question())));
            }
            md.append("\n");
        }
    }

    private static void falseNotFound(StringBuilder md, List<E2eOutcome> all) {
        List<E2eOutcome> list = all.stream().filter(o -> o.verdict().equals("FALSE_NOT_FOUND")).toList();
        md.append("## False \"not found\" (answerable questions that got no answer)\n\n");
        if (list.isEmpty()) {
            md.append("None.\n\n");
            return;
        }
        md.append("| id | reason | best rerank score | searches | question |\n| --- | --- | ---: | ---: | --- |\n");
        for (E2eOutcome o : list) {
            md.append(String.format(Locale.ROOT, "| %s | %s | %s | %d | %s |%n", o.id(), escape(o.notFoundReason()),
                    o.topRerankScore() == null ? "-" : String.format(Locale.ROOT, "%.2f", o.topRerankScore()),
                    o.retrievalCount(), escape(o.question())));
        }
        md.append("\n");
    }

    private static void falseAnswers(StringBuilder md, List<E2eOutcome> all) {
        List<E2eOutcome> list = all.stream().filter(o -> o.verdict().equals("FALSE_ANSWER")).toList();
        md.append("## False answers (an answer that should not exist, or cites no acceptable page)\n\n");
        if (list.isEmpty()) {
            md.append("None.\n\n");
            return;
        }
        for (E2eOutcome o : list) {
            md.append("- **").append(o.id()).append("** (").append(o.type()).append(") ").append(escape(o.question()))
                    .append("\n  - answer: ").append(escape(trim(o.answer(), 500)))
                    .append("\n  - cited: ").append(o.citedPages()).append("\n");
        }
        md.append("\n");
    }

    private static void latency(StringBuilder md, List<E2eOutcome> all) {
        md.append("## Latency per question (ms, whole turn: routing, searches, model calls, verification)\n\n");
        md.append("| set | n | median | mean | p90 | max | mean searches |\n| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        List<List<E2eOutcome>> groups = new ArrayList<>();
        List<String> names = new ArrayList<>();
        names.add("all");
        groups.add(all);
        for (String type : TYPES) {
            names.add(type);
            groups.add(all.stream().filter(o -> o.type().equals(type)).toList());
        }
        names.add("answered");
        groups.add(all.stream().filter(E2eOutcome::answered).toList());
        names.add("not answered");
        groups.add(all.stream().filter(o -> !o.answered() && o.error() == null).toList());
        for (int i = 0; i < groups.size(); i++) {
            List<Long> ms = groups.get(i).stream().filter(o -> o.error() == null).map(E2eOutcome::totalMillis)
                    .sorted().toList();
            if (ms.isEmpty()) {
                continue;
            }
            double mean = ms.stream().mapToLong(Long::longValue).average().orElse(0);
            double searches = groups.get(i).stream().mapToInt(E2eOutcome::retrievalCount).average().orElse(0);
            md.append(String.format(Locale.ROOT, "| %s | %d | %d | %.0f | %d | %d | %.1f |%n", names.get(i), ms.size(),
                    ms.get(ms.size() / 2), mean, ms.get((int) Math.floor((ms.size() - 1) * 0.9)),
                    ms.get(ms.size() - 1), searches));
        }
        md.append("\n");
    }

    private static void everyAnswer(StringBuilder md, List<E2eOutcome> all) {
        md.append("## Every question and answer\n\n");
        md.append("| id | type | verdict | strict | cited | ms | answer or reason |\n| --- | --- | --- | :---: | --- | ---: | --- |\n");
        for (E2eOutcome o : all) {
            String text = o.error() != null ? "ERROR: " + o.error()
                    : o.answered() ? o.answer() : "(not answered: " + o.notFoundReason() + ")";
            md.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %d | %s |%n", o.id(), o.type(), o.verdict(),
                    o.inCorpus() ? (o.strictCorrect() ? "yes" : "no") : "-",
                    o.citedPages().isEmpty() ? "-" : escape(String.join("; ", o.citedPages())), o.totalMillis(),
                    escape(trim(text, 600))));
        }
        md.append("\n");
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static long count(List<E2eOutcome> g, String verdict) {
        return g.stream().filter(o -> o.verdict().equals(verdict)).count();
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        String flat = s.replace("\r", " ").replace("\n", " / ");
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("|", "\\|");
    }

    /** Questions sorted by id, for stable output in tests. */
    static List<E2eOutcome> byId(List<E2eOutcome> list) {
        return list.stream().sorted(Comparator.comparing(E2eOutcome::id)).collect(Collectors.toList());
    }
}
