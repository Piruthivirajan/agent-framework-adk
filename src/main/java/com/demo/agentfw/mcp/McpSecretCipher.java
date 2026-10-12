package com.demo.agentfw.mcp;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM for the secrets of a saved MCP server (header values, and the URL, which can carry a token in
 * its query string). A stored value is {@code v1:} + base64(iv ‖ ciphertext ‖ tag) with a fresh random
 * 96-bit IV per value. The {@code context} (server id and field) is authenticated but not stored, so a
 * ciphertext copied to another field or another server fails to decrypt instead of being accepted.
 *
 * <p>The key is 16, 24 or 32 random bytes, base64 encoded, from {@code AGENTFW_MCP_SECRET_KEY}
 * ({@code openssl rand -base64 32}). It is deliberately not a passphrase: a passphrase would need a
 * slow key derivation and a stored salt, and a random key needs neither.
 */
public final class McpSecretCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    /** A stored value that cannot be decrypted: wrong key, tampered, or moved to another field. */
    public static final class McpCryptoException extends RuntimeException {
        McpCryptoException(String message) {
            super(message);
        }
    }

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    private McpSecretCipher(byte[] keyBytes) {
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * @throws IllegalArgumentException when the text is not base64 of 16, 24 or 32 bytes; the message
     *                                  never contains the text
     */
    public static McpSecretCipher fromBase64(String base64Key) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key == null ? "" : base64Key.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The key is not valid base64.");
        }
        if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32) {
            throw new IllegalArgumentException("The key must be 16, 24 or 32 bytes (base64 of 32 bytes is "
                    + "recommended); it is " + bytes.length + " bytes.");
        }
        return new McpSecretCipher(bytes);
    }

    public String encrypt(String plaintext, String context) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_BYTES + encrypted.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(encrypted, 0, out, IV_BYTES, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed: " + e.getClass().getSimpleName());
        }
    }

    public String decrypt(String stored, String context) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new McpCryptoException("The stored value is not in the expected format.");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (all.length < IV_BYTES + TAG_BITS / 8) {
                throw new McpCryptoException("The stored value is too short.");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new McpCryptoException("The stored value could not be decrypted with the configured key.");
        }
    }
}
