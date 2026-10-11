package com.demo.agentfw.api;

import com.demo.agentfw.files.FileProperties;
import com.demo.agentfw.mcp.DemoMcpServer;
import com.demo.agentfw.mcp.McpCallLedger;
import com.demo.agentfw.mcp.McpRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The settings API over the real registry and the in-process demo MCP server. */
class McpControllerTest {

    private static final String TOKEN = "controller-secret-token";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static DemoMcpServer server;
    private static String base;

    private final List<Object> events = new ArrayList<>();
    private MockMvc mvc;

    @BeforeAll
    static void start() throws IOException {
        server = new DemoMcpServer(0, TOKEN);
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void setUp() {
        McpRegistry registry = new McpRegistry(events::add, new McpCallLedger());
        mvc = MockMvcBuilders.standaloneSetup(new McpController(registry))
                .setControllerAdvice(new GlobalExceptionHandler(FileProperties.defaults())).build();
    }

    private static String body(String name, String url, String transport, String token) {
        return "{\"name\":\"" + name + "\",\"url\":\"" + url + "\",\"transport\":\"" + transport + "\","
                + "\"headers\":{\"Authorization\":\"Bearer " + token + "\"}}";
    }

    @Test
    void testConnectionListsToolsWithoutSavingAnything() throws Exception {
        mvc.perform(post("/api/mcp/servers/test").contentType(MediaType.APPLICATION_JSON)
                        .content(body("Orders", base + "/mcp", "STREAMABLE_HTTP", TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.tools.length()").value(3))
                .andExpect(jsonPath("$.tools[?(@.name=='lookup_order')]").exists());

        mvc.perform(get("/api/mcp/servers")).andExpect(jsonPath("$.length()").value(0));
        assertThat(events).isEmpty();
    }

    @Test
    void aWrongTokenIsAClearErrorInTheTestResult() throws Exception {
        mvc.perform(post("/api/mcp/servers/test").contentType(MediaType.APPLICATION_JSON)
                        .content(body("Orders", base + "/mcp", "STREAMABLE_HTTP", "nope")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.error.message").value(containsString("Invalid credentials")));
    }

    @Test
    void savedServersAreListedWithHeaderNamesButNeverTheirValues() throws Exception {
        MvcResult created = mvc.perform(post("/api/mcp/servers").contentType(MediaType.APPLICATION_JSON)
                        .content(body("Orders", base + "/mcp?key=QUERYSECRET", "STREAMABLE_HTTP", TOKEN)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONNECTED"))
                .andExpect(jsonPath("$.headerNames[0]").value("Authorization"))
                .andReturn();
        String listed = mvc.perform(get("/api/mcp/servers")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();

        String everything = created.getResponse().getContentAsString() + listed;
        assertThat(everything).doesNotContain(TOKEN).doesNotContain("Bearer").doesNotContain("QUERYSECRET");
        assertThat(everything).contains("\"headerNames\":[\"Authorization\"]").contains("?***");
        assertThat(events).hasSize(1);                          // the coordinator is told to rebuild
    }

    @Test
    void updateKeepsTheCredentialsUnlessNewOnesAreSentAndDeleteRemovesTheServer() throws Exception {
        MvcResult created = mvc.perform(post("/api/mcp/servers").contentType(MediaType.APPLICATION_JSON)
                .content(body("Orders", base + "/mcp", "STREAMABLE_HTTP", TOKEN))).andReturn();
        JsonNode view = JSON.readTree(created.getResponse().getContentAsString());
        String id = view.get("id").asString();

        mvc.perform(put("/api/mcp/servers/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Orders prod\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Orders prod"))
                .andExpect(jsonPath("$.status").value("CONNECTED"));
        mvc.perform(put("/api/mcp/servers/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"headers\":{\"Authorization\":\"Bearer wrong\"}}"))
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/mcp/servers/" + id + "/test")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ERROR"));
        mvc.perform(delete("/api/mcp/servers/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/mcp/servers")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(delete("/api/mcp/servers/" + id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void invalidInputIs400WithASentenceAndStdioIsExplicitlyRefused() throws Exception {
        mvc.perform(post("/api/mcp/servers").contentType(MediaType.APPLICATION_JSON)
                        .content(body("x", "npx @some/mcp-server", "STREAMABLE_HTTP", "t")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("stdio")));
        mvc.perform(post("/api/mcp/servers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"url\":\"http://localhost:1/mcp\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("transport")));
        mvc.perform(post("/api/mcp/servers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"url\":\"file:///etc/passwd\",\"transport\":\"SSE\"}"))
                .andExpect(status().isBadRequest());
    }
}
