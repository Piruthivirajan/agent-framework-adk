package com.demo.agentfw.core;

/**
 * Keys of the per-turn state that {@link ChatService} passes to the run (as the state delta). ADK
 * copies it into the nested agents, so a tool or agent can find the turn it belongs to without the
 * model ever seeing these values.
 */
public final class TurnState {

    /** Unique id of this turn; keys the retrieval ledger and the file summary ledger. */
    public static final String REQUEST_ID = "requestId";

    /** The chat session (the browser tab); a file can only be read from the session that uploaded it. */
    public static final String SESSION_ID = "chatSessionId";

    /** Set only on the attachment path: the file the user attached to this message. */
    public static final String FILE_ID = "attachedFileId";

    private TurnState() {
    }
}
