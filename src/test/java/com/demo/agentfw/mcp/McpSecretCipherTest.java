package com.demo.agentfw.mcp;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpSecretCipherTest {

    private static final String KEY_A = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String KEY_B = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    @Test
    void aValueRoundTripsAndTheStoredFormHidesIt() {
        McpSecretCipher cipher = McpSecretCipher.fromBase64(KEY_B);

        String stored = cipher.encrypt("Bearer super-secret-token", "mcp-server:s1:header:Authorization");

        assertThat(stored).startsWith("v1:").doesNotContain("super-secret-token").doesNotContain("Bearer");
        assertThat(cipher.decrypt(stored, "mcp-server:s1:header:Authorization")).isEqualTo("Bearer super-secret-token");
    }

    @Test
    void everyEncryptionUsesAFreshIvSoEqualValuesLookDifferent() {
        McpSecretCipher cipher = McpSecretCipher.fromBase64(KEY_B);

        assertThat(cipher.encrypt("same", "ctx")).isNotEqualTo(cipher.encrypt("same", "ctx"));
    }

    @Test
    void anotherKeyCannotReadTheValue() {
        String stored = McpSecretCipher.fromBase64(KEY_A).encrypt("secret", "ctx");

        assertThatThrownBy(() -> McpSecretCipher.fromBase64(KEY_B).decrypt(stored, "ctx"))
                .isInstanceOf(McpSecretCipher.McpCryptoException.class);
    }

    @Test
    void aValueMovedToAnotherFieldOrServerIsRejected() {
        McpSecretCipher cipher = McpSecretCipher.fromBase64(KEY_B);
        String stored = cipher.encrypt("secret", "mcp-server:s1:header:Authorization");

        assertThatThrownBy(() -> cipher.decrypt(stored, "mcp-server:s2:header:Authorization"))
                .isInstanceOf(McpSecretCipher.McpCryptoException.class);
        assertThatThrownBy(() -> cipher.decrypt(stored, "mcp-server:s1:url"))
                .isInstanceOf(McpSecretCipher.McpCryptoException.class);
    }

    @Test
    void aTamperedValueIsRejected() {
        McpSecretCipher cipher = McpSecretCipher.fromBase64(KEY_B);
        String stored = cipher.encrypt("secret", "ctx");
        byte[] raw = Base64.getDecoder().decode(stored.substring(3));
        raw[raw.length - 1] ^= 1;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered, "ctx"))
                .isInstanceOf(McpSecretCipher.McpCryptoException.class);
        assertThatThrownBy(() -> cipher.decrypt("plain text", "ctx"))
                .isInstanceOf(McpSecretCipher.McpCryptoException.class);
        assertThatThrownBy(() -> cipher.decrypt("v1:AAAA", "ctx"))
                .isInstanceOf(McpSecretCipher.McpCryptoException.class);
    }

    @Test
    void onlyRandomKeysOfAnAesSizeAreAcceptedAndTheMessageNeverEchoesTheKey() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[10]);

        assertThatThrownBy(() -> McpSecretCipher.fromBase64(shortKey))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("16, 24 or 32 bytes")
                .hasMessageNotContaining(shortKey);
        assertThatThrownBy(() -> McpSecretCipher.fromBase64("not base64 !!"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("not base64 !!");
        assertThatThrownBy(() -> McpSecretCipher.fromBase64(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(McpSecretCipher.fromBase64(Base64.getEncoder().encodeToString(new byte[16]))).isNotNull();
        assertThat(McpSecretCipher.fromBase64(Base64.getEncoder().encodeToString(new byte[24]))).isNotNull();
    }

    @Test
    void thePropertiesNeverPrintTheKey() {
        assertThat(new McpSecretProperties(KEY_B).toString()).doesNotContain(KEY_B).contains("(set)");
        assertThat(new McpSecretProperties("").toString()).contains("(not set)");
    }
}
