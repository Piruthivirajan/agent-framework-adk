package com.demo.agentfw.api;

import com.demo.agentfw.agents.rag.GroundingVerifier;
import com.demo.agentfw.core.ChatResult;
import com.demo.agentfw.core.ChatService;
import com.demo.agentfw.core.StageEvent;
import com.demo.agentfw.files.FileProperties;
import com.mongodb.MongoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerTest {

    private ChatService chat;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        chat = mock(ChatService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(chat))
                .setControllerAdvice(new GlobalExceptionHandler(FileProperties.defaults())).build();
    }

    private static ChatResult answer() {
        return new ChatResult("The tool reviews workloads. [wellarchitected-framework.pdf, p. 7]", "rag_agent", true,
                List.of(new GroundingVerifier.Source("r1c1", "wellarchitected-framework.pdf", 7, "a quote")),
                List.of(), new ChatResult.Verification(List.of(), false), List.of("rag_agent"),
                "{\"RAW\":\"model output\"}", "req-1", 1234);
    }

    private static String body(String sessionId, String message, String fileId) {
        return "{\"sessionId\":" + (sessionId == null ? "null" : "\"" + sessionId + "\"")
                + ",\"message\":" + (message == null ? "null" : "\"" + message + "\"")
                + ",\"fileId\":" + (fileId == null ? "null" : "\"" + fileId + "\"") + "}";
    }

    @Test
    void aTurnIsReturnedWithTheVerifiedAnswerAndSourcesButNotTheRawModelOutput() throws Exception {
        when(chat.chat(eq("tab-1"), eq("What is it?"), eq(null), eq(null))).thenReturn(answer());

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body("tab-1", "What is it?", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("answer"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("[wellarchitected")))
                .andExpect(jsonPath("$.sources[0].page").value(7))
                .andExpect(jsonPath("$.sources[0].quote").value("a quote"))
                .andExpect(jsonPath("$.route").value("rag_agent"))
                .andExpect(jsonPath("$.modelOutput").doesNotExist());
    }

    @Test
    void anAttachmentWithoutTextGetsTheDefaultInstruction() throws Exception {
        String fileId = "123e4567-e89b-12d3-a456-426614174000";
        when(chat.chat(any(), any(), any(), any())).thenReturn(answer());

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content(body("tab-1", "  ", fileId))).andExpect(status().isOk());

        verify(chat).chat(eq("tab-1"), eq("Summarize this file."), eq(fileId), eq(null));
    }

    @Test
    void invalidRequestsAre400WithAMessageAndNeverReachTheService() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body(null, "hi", null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body("tab 1!", "hi", null)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body("tab-1", "  ", null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("message is required"));
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body("tab-1", "hi", "not-an-id")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        org.mockito.Mockito.verifyNoInteractions(chat);
    }

    @Test
    void aModelFailureIs502WithTheReasonAndAnyApiKeyMasked() throws Exception {
        when(chat.chat(any(), any(), any(), any())).thenThrow(new ChatService.ChatFailedException(
                "The model could not complete the request: 429 quota exceeded for key AIzaSyA1234567890abcdefghijkl\n"
                        + "at com.google.genai.Client.call(Client.java:99)", null));

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body("tab-1", "hi", null)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("CHAT_FAILED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("429 quota exceeded")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("AIza"))))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Client.java"))));
    }

    @Test
    void anUnexpectedErrorIs500WithAGenericSentenceNotTheExceptionText() throws Exception {
        when(chat.chat(any(), any(), any(), any())).thenThrow(new IllegalStateException("secret internal detail"));

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body("tab-1", "hi", null)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret"))));
    }

    @Test
    void aDatabaseOutageIs503() throws Exception {
        when(chat.chat(any(), any(), any(), any())).thenThrow(new MongoException("connection refused"));

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body("tab-1", "hi", null)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theStreamSendsOneEventPerStageThenTheResult() throws Exception {
        doAnswer(inv -> {
            Consumer<StageEvent> listener = inv.getArgument(3);
            listener.accept(new StageEvent("routing", "", 5));
            listener.accept(new StageEvent("searching", "", 2100));
            listener.accept(new StageEvent("reranking", "", 2900));
            listener.accept(new StageEvent("verifying", "", 6000));
            return answer();
        }).when(chat).chat(eq("tab-1"), eq("What is it?"), eq(null), any());

        MvcResult started = mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM).content(body("tab-1", "What is it?", null)))
                .andExpect(request().asyncStarted()).andReturn();

        String stream = awaitContaining(started, "event:result");
        assertThat(stream).contains("event:stage").contains("\"stage\":\"routing\"").contains("\"stage\":\"searching\"")
                .contains("\"stage\":\"reranking\"").contains("\"stage\":\"verifying\"");
        assertThat(stream.indexOf("routing")).isLessThan(stream.indexOf("verifying"));
        assertThat(stream.indexOf("verifying")).isLessThan(stream.indexOf("event:result"));
        assertThat(stream).contains("\"kind\":\"answer\"").doesNotContain("RAW");
    }

    @Test
    void aFailureInTheStreamIsAnErrorEventWithTheSameWordsAsThePlainEndpoint() throws Exception {
        when(chat.chat(any(), any(), any(), any())).thenThrow(new ChatService.ChatFailedException(
                "The model could not complete the request: timeout", null));

        MvcResult started = mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM).content(body("tab-1", "hi", null)))
                .andExpect(request().asyncStarted()).andReturn();

        String stream = awaitContaining(started, "event:error");
        assertThat(stream).contains("\"code\":\"CHAT_FAILED\"").contains("timeout").doesNotContain("event:result");
    }

    @Test
    void theStreamRejectsAnInvalidRequestBeforeItStarts() throws Exception {
        mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM).content(body("tab-1", null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        ArgumentCaptor<Object> none = ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verifyNoInteractions(chat);
        assertThat(none.getAllValues()).isEmpty();
    }

    private static String awaitContaining(MvcResult result, String marker) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        String content = "";
        while (System.currentTimeMillis() < deadline) {
            content = result.getResponse().getContentAsString();
            if (content.contains(marker)) {
                return content;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("no '" + marker + "' in the stream: " + content);
    }
}
