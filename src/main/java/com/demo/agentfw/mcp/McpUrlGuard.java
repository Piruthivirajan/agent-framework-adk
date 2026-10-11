package com.demo.agentfw.mcp;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Server-side request forgery guard for the MCP URLs a user types in. The application fetches those
 * URLs from inside its own network, so without a guard anyone who can use the page could point it at the
 * cloud metadata service (169.254.169.254, which hands out the VM's credentials), at the database, or at
 * other private services.
 *
 * <p>A URL is refused when its host resolves to <em>any</em> address in a private, loopback, link-local,
 * carrier-grade NAT, benchmark, multicast or unspecified range (IPv4 and IPv6, including IPv4 written
 * as a number or mapped into IPv6). A host listed in {@code agentfw.mcp.allowed-hosts} is exempt,
 * which is how the demo server is reached.
 *
 * <p>Honest limits: the address is checked when the URL is saved or tested and again before each
 * connection, but the SDK resolves the name once more when it connects, so a DNS answer that changes
 * between the two (rebinding) is not covered; and redirects are not followed by the pre-flight probe
 * but are by the SDK's HTTP client. A network-level egress rule is the complete answer in production.
 */
@Component
public class McpUrlGuard {

    private final Set<String> allowedHosts;

    @Autowired
    public McpUrlGuard(McpProperties props) {
        this.allowedHosts = props.allowedHosts().stream().map(h -> h.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** A guard that allows everything: for tests that talk to a server on localhost. */
    public static McpUrlGuard allowAll() {
        return new McpUrlGuard(new McpProperties(java.util.List.of())) {
            @Override
            public Optional<McpFailure> check(String url) {
                return Optional.empty();
            }
        };
    }

    /** Empty when the URL may be used; otherwise the failure to show the user. */
    public Optional<McpFailure> check(String url) {
        String host;
        try {
            host = URI.create(url.strip()).getHost();
        } catch (RuntimeException e) {
            return Optional.empty();                 // validation reports an unparseable URL separately
        }
        if (host == null) {
            return Optional.empty();
        }
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (allowedHosts.contains(bare.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(bare);
        } catch (UnknownHostException e) {
            // a name that does not resolve is left to the connection attempt, but a host written purely
            // as numbers (0x7f000001, 017700000001, 127.1) is an obfuscated address: refuse it
            return NUMERIC_HOST.matcher(bare).matches() ? Optional.of(blocked()) : Optional.empty();
        }
        for (InetAddress a : addresses) {
            if (isInternal(a)) {
                return Optional.of(blocked());
            }
        }
        return Optional.empty();
    }

    private static final java.util.regex.Pattern NUMERIC_HOST =
            java.util.regex.Pattern.compile("(?i)(0x[0-9a-f]+|[0-9]+)(\\.(0x[0-9a-f]+|[0-9]+))*");

    private static McpFailure blocked() {
        return new McpFailure(McpFailure.BLOCKED_ADDRESS, "This address is not allowed: it points to a private, "
                + "loopback or link-local network (for example the cloud metadata address 169.254.169.254). Only "
                + "public servers can be connected. If this is your own server on a private network, the "
                + "administrator must allow its host name in AGENTFW_MCP_ALLOWED_HOSTS.");
    }

    static boolean isInternal(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            return b0 == 0                                           // 0.0.0.0/8 "this network"
                    || (b0 == 100 && b1 >= 64 && b1 <= 127)          // 100.64.0.0/10 carrier-grade NAT
                    || (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0)  // 192.0.0.0/24 IETF protocol assignments
                    || (b0 == 198 && (b1 == 18 || b1 == 19))         // 198.18.0.0/15 benchmarking
                    || b0 >= 240;                                    // 240.0.0.0/4 reserved and broadcast
        }
        if (a instanceof Inet6Address) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            return (b0 & 0xfe) == 0xfc                               // fc00::/7 unique local
                    || (b0 == 0x20 && b1 == 0x02)                    // 2002::/16 6to4 (embeds an IPv4 address)
                    || (b0 == 0x00 && b1 == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b); // 64:ff9b::/96
        }
        return false;
    }
}
