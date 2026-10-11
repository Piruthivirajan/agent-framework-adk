package com.demo.agentfw.agents.rag;

import com.demo.agentfw.core.AgentContext;
import com.demo.agentfw.core.AgentPlugin;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.FunctionTool;
import org.springframework.stereotype.Component;

/**
 * Answers questions from the indexed reference documents. The agent's output is a strict JSON
 * contract, not prose: each claim carries the chunk reference it relies on and a verbatim quote,
 * which {@link GroundingVerifier} checks against the retrieved text before anything is shown.
 */
@Component
public class RagAgentPlugin implements AgentPlugin {

    public static final String NAME = "rag_agent";

    static final String INSTRUCTION = """
            You answer questions strictly from the reference documents, using the searchDocuments tool.

            Procedure:
            1. Call searchDocuments with a short search query for the user's question. If the results do not \
            contain the answer you may rewrite the query and call it again, at most 3 calls in total.
            2. If the tool returns status NO_RELEVANT_CONTEXT, or the returned chunks do not contain the answer, \
            the answer is not in the documents.
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
            - Any number in a claim's text must also appear in its quote.
            - A claim that you cannot support with a quote must not be made.
            """;

    private final SearchDocumentsTool searchTool;

    public RagAgentPlugin(SearchDocumentsTool searchTool) {
        this.searchTool = searchTool;
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
        return LlmAgent.builder()
                .name(NAME)
                .description(description())
                .model(context.model())
                .instruction(INSTRUCTION)
                .generateContentConfig(context.generateContentConfig())
                .tools(FunctionTool.create(searchTool, SearchDocumentsTool.METHOD))
                .build();
    }
}
