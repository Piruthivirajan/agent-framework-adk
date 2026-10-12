package com.demo.agentfw.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecretsTest {

    @Test
    void googleKeysAreMaskedByPattern() {
        assertThat(Secrets.mask("key=AIzaSyA1234567890abcdefghijklmnopqrstuv1 end"))
                .isEqualTo("key=*** end");
        assertThat(Secrets.mask(null)).isNull();
    }

    @Test
    void trackedValuesAreMaskedEverywhereLongestFirst() {
        Object owner = new Object();
        Secrets.track(owner, List.of("Bearer abc-123-token", "abc-123-token"));

        assertThat(Secrets.mask("sent Bearer abc-123-token; echo abc-123-token")).isEqualTo("sent ***; echo ***");

        Secrets.untrack(owner);
        assertThat(Secrets.mask("abc-123-token")).isEqualTo("abc-123-token");
    }

    @Test
    void veryShortValuesAreNotRegisteredBecauseTheyWouldDamageOrdinaryText() {
        Object owner = new Object();
        Secrets.track(owner, List.of("ops", "json"));       // 3 and 4 characters: only the 4-character one counts

        assertThat(Secrets.mask("operations of ops")).isEqualTo("operations of ops");
        assertThat(Secrets.mask("application/json")).isEqualTo("application/***");
        Secrets.untrack(owner);
    }

    @Test
    void anOwnerReplacesItsOwnValuesAndOwnersDoNotEraseEachOther() {
        Object a = new Object();
        Object b = new Object();
        Secrets.track(a, List.of("secret-of-a-1"));
        Secrets.track(b, List.of("secret-of-b-1"));
        Secrets.track(a, List.of("secret-of-a-2"));

        assertThat(Secrets.mask("secret-of-a-1 secret-of-a-2 secret-of-b-1"))
                .isEqualTo("secret-of-a-1 *** ***");
        Secrets.untrack(a);
        Secrets.untrack(b);
    }

    @Test
    void nestedResultsAreMaskedInKeysValuesAndLists() {
        Object owner = new Object();
        Secrets.track(owner, List.of("tok-987654"));
        Map<String, Object> result = Map.of("content", List.of(Map.of("type", "text", "text", "hello tok-987654")),
                "isError", false, "n", 3);

        @SuppressWarnings("unchecked")
        Map<String, Object> masked = (Map<String, Object>) Secrets.maskDeep(result);

        assertThat(masked.toString()).doesNotContain("tok-987654").contains("hello ***");
        assertThat(masked.get("isError")).isEqualTo(false);
        assertThat(masked.get("n")).isEqualTo(3);
        Secrets.untrack(owner);
    }
}
