package com.demo.agentfw.mcp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real MCP clients against the in-process demo server: no network beyond localhost, no model. */
class McpRegistryTest {

    private static final String TOKEN = "demo-secret-token-123";

    private static DemoMcpServer server;
    private static String base;

    private final List<Object> events = new ArrayList<>();
    private McpRegistry registry;

    @BeforeAll
    static void startServer() throws IOException {
        server = new DemoMcpServer(0, TOKEN);
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void setUp() {
        registry = new McpRegistry(events::add, new McpCallLedger());
    }

    private static McpServerConfig config(String name, String url, McpTransport transport, String token) {
        Map<String, String> headers = token == null ? Map.of() : Map.of("Authorization", "Bearer " + token);
        return new McpServerConfig(null, name, url, transport, headers, true);
    }

    @Test
    void streamableHttpConnectsAndListsTheServersTools() {
        McpRegistry.TestResult r = registry.test(config("demo", base + "/mcp", McpTransport.STREAMABLE_HTTP, TOKEN));

        assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
        assertThat(r.tools()).extracting(McpRegistry.ToolInfo::name)
                .containsExactlyInAnyOrder("lookup_order", "oncall_engineer", "billing_report");
        assertThat(r.tools().get(0).description()).isNotBlank();
    }

    @Test
    void legacySseConnectsAndListsTheServersTools() {
        McpRegistry.TestResult r = registry.test(config("demo-sse", base + "/sse", McpTransport.SSE, TOKEN));

        assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
        assertThat(r.tools()).extracting(McpRegistry.ToolInfo::name).contains("lookup_order");
    }

    @Test
    void aWrongTokenIsReportedAsInvalidCredentials() {
        for (McpTransport t : McpTransport.values()) {
            String path = t == McpTransport.SSE ? "/sse" : "/mcp";
            McpRegistry.TestResult r = registry.test(config("demo", base + path, t, "wrong-token"));

            assertThat(r.ok()).isFalse();
            assertThat(r.error().code()).as(t.name()).isEqualTo(McpFailure.INVALID_CREDENTIALS);
            assertThat(r.error().message()).startsWith("Invalid credentials");
        }
    }

    @Test
    void aMissingTokenIsAlsoInvalidCredentials() {
        McpRegistry.TestResult r = registry.test(config("demo", base + "/mcp", McpTransport.STREAMABLE_HTTP, null));

        assertThat(r.error().code()).isEqualTo(McpFailure.INVALID_CREDENTIALS);
    }

    @Test
    void aServerThatIsNotRunningIsUnableToConnect() throws IOException {
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) {
            freePort = s.getLocalPort();
        }

        for (McpTransport t : McpTransport.values()) {
            McpRegistry.TestResult r = registry.test(config("down", "http://127.0.0.1:" + freePort + "/mcp", t, TOKEN));

            assertThat(r.ok()).isFalse();
            assertThat(r.error().code()).as(t.name()).isEqualTo(McpFailure.UNABLE_TO_CONNECT);
            assertThat(r.error().message()).startsWith("Unable to connect to MCP server");
        }
    }

    @Test
    void aWrongPathIsReportedAsAnAddressProblemNotAsACrash() {
        McpRegistry.TestResult r = registry.test(config("demo", base + "/nothing-here",
                McpTransport.STREAMABLE_HTTP, TOKEN));

        assertThat(r.ok()).isFalse();
        assertThat(r.error().code()).isIn(McpFailure.WRONG_URL, McpFailure.NOT_MCP, McpFailure.OTHER);
        assertThat(r.error().message()).doesNotContain("Exception").doesNotContain("at com.");
    }

    @Test
    void theWrongTransportForTheEndpointIsAnErrorWithAHint() {
        McpRegistry.TestResult streamableAsSse = registry.test(config("demo", base + "/mcp", McpTransport.SSE, TOKEN));

        assertThat(streamableAsSse.ok()).isFalse();
        assertThat(streamableAsSse.error().message()).isNotBlank().doesNotContain("Exception");
    }

    @Test
    void savedServersLoadTheirToolsAtOnceAndTheCoordinatorIsToldToRebuild() {
        McpRegistry.ServerView v = registry.add(config("Orders", base + "/mcp", McpTransport.STREAMABLE_HTTP, TOKEN));

        assertThat(v.status()).isEqualTo("CONNECTED");
        assertThat(v.tools()).hasSize(3);
        assertThat(registry.hasTools()).isTrue();
        assertThat(registry.tools()).extracting(t -> t.name()).contains("lookup_order");
        assertThat(registry.toolSummary()).contains("lookup_order").contains("oncall_engineer");
        assertThat(events).hasSize(1).first().isInstanceOf(McpRegistry.McpConfigChanged.class);
    }

    @Test
    void credentialsAreNeverPartOfAViewOrAString() {
        McpServerConfig input = config("Orders", base + "/mcp?apikey=TOPSECRET", McpTransport.STREAMABLE_HTTP, TOKEN);
        McpRegistry.ServerView v = registry.add(input);

        String everything = v + " " + registry.list() + " " + input + " " + v.url();
        assertThat(everything).doesNotContain(TOKEN).doesNotContain("TOPSECRET");
        assertThat(v.headerNames()).containsExactly("Authorization");
        assertThat(v.url()).endsWith("/mcp?***");
    }

    @Test
    void anUnreachableServerIsSavedWithItsErrorAndOffersNoTools() throws IOException {
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) {
            freePort = s.getLocalPort();
        }

        McpRegistry.ServerView v = registry.add(config("Gone", "http://127.0.0.1:" + freePort + "/mcp",
                McpTransport.STREAMABLE_HTTP, TOKEN));

        assertThat(v.status()).isEqualTo("ERROR");
        assertThat(v.error().code()).isEqualTo(McpFailure.UNABLE_TO_CONNECT);
        assertThat(registry.hasTools()).isFalse();
        assertThat(registry.tools()).isEmpty();
    }

    @Test
    void updatingWithoutHeadersKeepsTheStoredCredentialsAndANewTokenReplacesThem() {
        McpRegistry.ServerView v = registry.add(config("Orders", base + "/mcp", McpTransport.STREAMABLE_HTTP, TOKEN));

        McpRegistry.ServerView renamed = registry.update(v.id(), "Orders (prod)", null, null, null, null);
        assertThat(renamed.status()).isEqualTo("CONNECTED");        // still authenticated: headers were kept
        assertThat(renamed.name()).isEqualTo("Orders (prod)");

        McpRegistry.ServerView broken = registry.update(v.id(), null, null, null,
                Map.of("Authorization", "Bearer wrong"), null);
        assertThat(broken.status()).isEqualTo("ERROR");
        assertThat(broken.error().code()).isEqualTo(McpFailure.INVALID_CREDENTIALS);

        McpRegistry.ServerView fixed = registry.update(v.id(), null, null, null,
                Map.of("Authorization", "Bearer " + TOKEN), null);
        assertThat(fixed.status()).isEqualTo("CONNECTED");
    }

    @Test
    void aDisabledServerKeepsItsConfigurationButOffersNoTools() {
        McpRegistry.ServerView v = registry.add(config("Orders", base + "/mcp", McpTransport.STREAMABLE_HTTP, TOKEN));

        McpRegistry.ServerView off = registry.update(v.id(), null, null, null, null, false);

        assertThat(off.status()).isEqualTo("DISABLED");
        assertThat(registry.hasTools()).isFalse();
        assertThat(registry.update(v.id(), null, null, null, null, true).status()).isEqualTo("CONNECTED");
    }

    @Test
    void removingAServerRemovesItsToolsAndAnUnknownIdIsAClearError() {
        McpRegistry.ServerView v = registry.add(config("Orders", base + "/mcp", McpTransport.STREAMABLE_HTTP, TOKEN));

        registry.remove(v.id());

        assertThat(registry.list()).isEmpty();
        assertThat(registry.hasTools()).isFalse();
        assertThatThrownBy(() -> registry.remove(v.id())).isInstanceOf(McpRegistry.NoSuchElementException.class);
    }

    @Test
    void invalidInputIsRejectedWithASentenceForTheUser() {
        assertThatThrownBy(() -> registry.test(config("x", "ftp://host/mcp", McpTransport.STREAMABLE_HTTP, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("http://");
        assertThatThrownBy(() -> registry.test(config("x", "npx some-server", McpTransport.STREAMABLE_HTTP, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stdio");
        assertThatThrownBy(() -> registry.test(new McpServerConfig(null, "x", base + "/mcp",
                McpTransport.STREAMABLE_HTTP, Map.of("Bad Header", "v"), true)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("header name");
        assertThatThrownBy(() -> registry.test(new McpServerConfig(null, "x", base + "/mcp",
                McpTransport.STREAMABLE_HTTP, Map.of("X-Key", "a\r\nInjected: 1"), true)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
