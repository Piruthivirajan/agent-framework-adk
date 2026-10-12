package com.demo.agentfw.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The egress rule is shell, so what can be checked here is its text and its dry run. Whether the packets
 * are really dropped has to be checked on the VM:
 * {@code docker run --rm curlimages/curl -m 3 http://169.254.169.254/} must time out.
 */
class MetadataBlockScriptTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    void theScriptDropsTheMetadataAddressInDockersUserChainAndIsIdempotent() throws IOException {
        String script = read("scripts/block-metadata.sh");

        assertThat(script).contains("169.254.169.254");
        assertThat(script).contains("iptables -I DOCKER-USER 1 -d \"$ADDR\" -j DROP");
        assertThat(script).contains("iptables -C DOCKER-USER -d \"$ADDR\" -j DROP");     // checks before adding
        assertThat(script).contains("--install-service").contains("After=docker.service");
    }

    @Test
    void theDeploymentInstallsItUnlessSwitchedOff() throws IOException {
        String deploy = read("scripts/deploy.sh");

        assertThat(deploy).contains("scripts/block-metadata.sh --install-service");
        assertThat(deploy).contains("--no-metadata-block").contains("METADATA_BLOCK");
    }

    @Test
    void aDryRunPrintsTheCommandsAndChangesNothing() throws Exception {
        Process probe;
        try {
            probe = new ProcessBuilder("bash", "--version").redirectErrorStream(true).start();
            probe.getInputStream().readAllBytes();
            assumeTrue(probe.waitFor() == 0, "bash is not available");
        } catch (IOException e) {
            assumeTrue(false, "bash is not available");
        }

        Process p = new ProcessBuilder("bash", "scripts/block-metadata.sh", "--dry-run", "--install-service")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(p.waitFor()).isZero();
        assertThat(out).contains("+ iptables -I DOCKER-USER 1 -d 169.254.169.254 -j DROP")
                .contains("agentfw-block-metadata.service").contains("systemctl enable");
    }
}
