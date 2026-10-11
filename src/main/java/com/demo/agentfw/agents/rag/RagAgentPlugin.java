package com.demo.agentfw.agents.rag;

import com.demo.agentfw.core.AgentContext;
import com.demo.agentfw.core.AgentPlugin;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.FunctionTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Answers questions from the indexed reference documents. The agent's output is a strict JSON
 * contract, not prose: each claim carries the chunk reference it relies on and a verbatim quote,
 * which {@link GroundingVerifier} checks against the retrieved text before anything is shown.
 */
@Component
public class RagAgentPlugin implements AgentPlugin {

    public static final String NAME = "rag_agent";

    private static final Logger log = LoggerFactory.getLogger(RagAgentPlugin.class);

    private static final String INSTRUCTION_TEMPLATE = """
            You answer questions strictly from the reference documents, using the searchDocuments tool.

            Procedure:
            1. Call searchDocuments with a short search query for the user's question. If the results do not \
            contain the answer you may rewrite the query and call it again, at most %d calls in total.
            2. If the tool returns status NO_RELEVANT_CONTEXT or SEARCH_LIMIT_REACHED, or the returned chunks do \
            not contain the answer, the answer is not in the documents.
            3. Use ONLY the text of the returned chunks. Never use your own knowledge, never guess and never fill \
            gaps. Numbers, limits, names and prices must come from the chunk text.

            Output: reply with ONLY one JSON object, with no markdown fences and no other text.
            - If you can answer: {"status":"ANSWERED","claims":[{"text":"<one factual statement>","ref":"<ref of \
            the chunk that supports it>","quote":"<an exact excerpt copied character for character from that \
            chunk, 30 to 250 characters, that directly supports the statement>"}]}
            - Otherwise: {"status":"NOT_FOUND"}

            Rules for claims:
            - Every statement of your answer is its own claim with its own ref and its own quote. Use at most 6 claims.
            - The quote must appear verbatim in the chunk with that ref: copy it exactly, including punctuation, \
            and never join text from different places or correct it.
            - Every number in a claim's text must appear in that claim's quote, including digits inside names             such as EC2 or Route 53. If the quote does not contain a number or a name with a digit, leave that             detail out of the claim.
            - A claim that you cannot support with a quote must not be made.
            """;

    private final SearchDocumentsTool searchTool;
    private final RagProperties props;

    public RagAgentPlugin(SearchDocumentsTool searchTool, RagProperties props) {
        this.searchTool = searchTool;
        this.props = props;
    }

    /** The instruction with the configured search limit and the optional (experiment-only) suffix. */
    String instruction() {
        String base = String.format(INSTRUCTION_TEMPLATE, props.maxSearches());
        return props.instructionSuffix().isBlank() ? base : base + "\n" + props.instructionSuffix().strip() + "\n";
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Answers questions from the indexed reference documents and supports every statement with a "
                + "verbatim quote. Use it for any question that could be answered by those documents.";
    }

    @Override
    public BaseAgent build(AgentContext context) {
        log.info("rag agent built: max searches {}, stop after floor miss {}, instruction suffix {}",
                props.maxSearches(), props.stopAfterFloorMiss(),
                props.instructionSuffix().isBlank() ? "none" : "ACTIVE (" + props.instructionSuffix().length() + " chars)");
        return LlmAgent.builder()
                .name(NAME)
                .description(description())
                .model(context.model())
                .instruction(instruction())
                .generateContentConfig(context.generateContentConfig())
                .tools(FunctionTool.create(searchTool, SearchDocumentsTool.METHOD))
                .build();
    }
}
