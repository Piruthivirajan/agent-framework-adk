package com.demo.agentfw.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the one property of the page that is easy to break unnoticed: everything that comes from the
 * model or from a document is put on the page as text, never as HTML.
 */
class StaticPageTest {

    private static String page() throws IOException {
        return page("/static/index.html");
    }

    private static String page(String path) throws IOException {
        try (InputStream in = StaticPageTest.class.getResourceAsStream(path)) {
            assertThat(in).as(path + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theModelAndDocumentTextIsNeverInsertedAsHtml() throws IOException {
        String html = page();

        assertThat(html).doesNotContain("innerHTML").doesNotContain("outerHTML").doesNotContain("insertAdjacentHTML")
                .doesNotContain("document.write").doesNotContain("eval(").doesNotContain("new Function(");
    }

    @Test
    void thePageHasTheControlsTheScenariosNeed() throws IOException {
        String html = page();

        assertThat(html).contains("id=\"input\"").contains("id=\"send\"").contains("id=\"attach\"")
                .contains("id=\"paste\"").contains("id=\"docInput\"").contains("/api/chat/stream")
                .contains("/api/files").contains("/api/kb/documents")
                .contains("How this answer was produced").contains("not quote-checked");
    }

    @Test
    void theMcpSettingsPageAlsoNeverInsertsHtmlAndStatesTheLimits() throws IOException {
        String html = page("/static/mcp.html");

        assertThat(html).doesNotContain("innerHTML").doesNotContain("outerHTML").doesNotContain("insertAdjacentHTML")
                .doesNotContain("document.write").doesNotContain("eval(").doesNotContain("new Function(");
        assertThat(html).contains("stdio").contains("Not supported").contains("values hidden")
                .contains("Streamable HTTP").contains("SSE").contains("/api/mcp/servers")
                .contains("type: 'password'");              // header values are typed into password fields
    }
}
