package com.demo.agentfw.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Address literals only (plus localhost), so the tests need no DNS. */
class McpUrlGuardTest {

    private final McpUrlGuard guard = new McpUrlGuard(new McpProperties(List.of()));

    @ParameterizedTest
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data/",           // cloud metadata (AWS, GCP, Azure)
            "http://169.254.0.1/mcp",                              // anywhere in link-local
            "http://127.0.0.1:8080/actuator",                      // loopback
            "http://127.1.2.3/mcp",
            "http://localhost:27017/",
            "http://10.0.0.5/mcp",                                 // RFC 1918
            "http://172.16.0.1/mcp",
            "http://172.31.255.255/mcp",
            "http://192.168.1.10/mcp",
            "http://100.64.0.1/mcp",                               // carrier-grade NAT
            "http://0.0.0.0/mcp",                                  // unspecified
            "http://198.18.0.1/mcp",                               // benchmarking
            "http://240.0.0.1/mcp",                                // reserved
            "http://224.0.0.1/mcp",                                // multicast
            "http://[::1]/mcp",                                    // IPv6 loopback
            "http://[fe80::1]/mcp",                                // IPv6 link-local
            "http://[fd00::1234]/mcp",                             // IPv6 unique local
            "http://[fc00::1]/mcp",
            "http://[::ffff:127.0.0.1]/mcp",                       // IPv4-mapped loopback
            "http://[::ffff:169.254.169.254]/mcp",                 // IPv4-mapped metadata address
            "http://[64:ff9b::7f00:1]/mcp",                        // NAT64 prefix embedding 127.0.0.1
            "http://[2002:7f00:1::]/mcp",                          // 6to4 embedding 127.0.0.1
            "http://2130706433/mcp",                               // 127.0.0.1 written as a number
            "http://0x7f000001/mcp",                               // ... as hex
            "http://user:pass@127.0.0.1/mcp",                      // credentials in the URL change nothing
    })
    void internalAddressesAreRefusedWithAClearMessage(String url) {
        var failure = guard.check(url);

        assertThat(failure).as(url).isPresent();
        assertThat(failure.get().code()).isEqualTo(McpFailure.BLOCKED_ADDRESS);
        assertThat(failure.get().message()).contains("private, loopback or link-local")
                .contains("AGENTFW_MCP_ALLOWED_HOSTS");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://93.184.216.34/mcp",                           // public IPv4 literals
            "http://8.8.8.8:8080/sse",
            "http://172.15.0.1/mcp",                               // just outside 172.16.0.0/12
            "http://172.32.0.1/mcp",
            "http://100.63.255.255/mcp",                           // just below 100.64.0.0/10
            "http://100.128.0.1/mcp",                              // just above it
            "http://[2606:4700:4700::1111]/mcp",                   // public IPv6
    })
    void publicAddressesPass(String url) {
        assertThat(guard.check(url)).as(url).isEmpty();
    }

    @Test
    void anAllowedHostIsExemptByNameOnly() {
        McpUrlGuard withAllowlist = new McpUrlGuard(new McpProperties(List.of("localhost", " Demo-MCP ", "")));

        assertThat(withAllowlist.check("http://localhost:8099/mcp")).isEmpty();
        assertThat(withAllowlist.check("http://LOCALHOST:8099/mcp")).isEmpty();
        // the exemption is for the host that was listed, not for what it resolves to
        assertThat(withAllowlist.check("http://127.0.0.1:8099/mcp")).isPresent();
        assertThat(withAllowlist.check("http://169.254.169.254/")).isPresent();
        assertThat(withAllowlist.check("http://10.0.0.5/mcp")).isPresent();
    }

    @Test
    void aHostThatDoesNotResolveIsLeftToTheConnectionAttempt() {
        assertThat(guard.check("http://no-such-host.invalid/mcp")).isEmpty();
    }

    @Test
    void theRegistryRefusesToSaveATestOrConnectToABlockedAddress() {
        List<Object> events = new ArrayList<>();
        McpRegistry registry = new McpRegistry(events::add, new McpCallLedger(), guard);
        McpServerConfig metadata = new McpServerConfig(null, "metadata", "http://169.254.169.254/latest/meta-data/",
                McpTransport.STREAMABLE_HTTP, Map.of(), true);

        McpRegistry.TestResult test = registry.test(metadata);
        assertThat(test.ok()).isFalse();
        assertThat(test.error().code()).isEqualTo(McpFailure.BLOCKED_ADDRESS);

        assertThatThrownBy(() -> registry.add(metadata)).isInstanceOf(McpRegistry.McpBlockedException.class)
                .hasMessageContaining("not allowed");
        assertThat(registry.list()).isEmpty();
        assertThat(events).isEmpty();                              // nothing was saved, nothing to rebuild
    }

    @Test
    void anUpdateCannotRedirectASavedServerToABlockedAddress() {
        McpRegistry open = new McpRegistry(e -> { }, new McpCallLedger());                 // allows everything
        McpServerConfig fine = new McpServerConfig(null, "x", "http://127.0.0.1:9/mcp", McpTransport.STREAMABLE_HTTP,
                Map.of(), false);                                                          // disabled: no connection
        String id = open.add(fine).id();
        McpRegistry guarded = new McpRegistry(e -> { }, new McpCallLedger(), guard);
        guarded.add(new McpServerConfig(null, "y", "https://93.184.216.34/mcp", McpTransport.STREAMABLE_HTTP,
                Map.of(), false));
        String guardedId = guarded.list().get(0).id();

        assertThatThrownBy(() -> guarded.update(guardedId, null, "http://169.254.169.254/", null, null, null))
                .isInstanceOf(McpRegistry.McpBlockedException.class);
        assertThat(guarded.list().get(0).url()).isEqualTo("https://93.184.216.34/mcp");   // unchanged
        assertThat(id).isNotBlank();
    }
}
