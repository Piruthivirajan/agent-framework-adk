package com.demo.agentfw.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A syntax error in a page's script makes the whole page silently dead (no list, no buttons), and no
 * Java test would notice. When Node.js is on the machine, each page's script is syntax-checked with
 * {@code node --check}; without Node the test is skipped.
 */
class PageScriptSyntaxTest {

    private static boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static String script(String resource) throws IOException {
        try (InputStream in = PageScriptSyntaxTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int from = html.indexOf("<script>");
            int to = html.lastIndexOf("</script>");
            assertThat(from).as("a script in " + resource).isGreaterThan(-1);
            return html.substring(from + "<script>".length(), to);
        }
    }

    @Test
    void everyPageScriptParses() throws Exception {
        assumeTrue(nodeAvailable(), "Node.js is not installed; page scripts were not syntax-checked");
        for (String page : List.of("/static/index.html", "/static/mcp.html", "/static/login.html")) {
            Path file = Files.createTempFile("page-script-", ".js");
            try {
                Files.writeString(file, script(page), StandardCharsets.UTF_8);
                Process p = new ProcessBuilder("node", "--check", file.toString()).redirectErrorStream(true).start();
                String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
                assertThat(p.exitValue()).as(page + " script:\n" + output).isZero();
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }
}
