package com.kvstore.router;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-logic tests for {@link KeyRouter}. No Spring, no HTTP — just the hash
 * mapping.
 */
class KeyRouterTest {

    @Test
    void emptyNodeListIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new KeyRouter(List.of()));
    }

    @Test
    void sameKeyAlwaysRoutesToSameNode() {
        KeyRouter r = new KeyRouter(List.of("a", "b", "c"));
        String first = r.nodeFor("user:42");
        // Repeat a bunch of times; routing is stateless so it must be stable.
        for (int i = 0; i < 100; i++) {
            Assertions.assertEquals(first, r.nodeFor("user:42"));
        }
    }

    @Test
    void differentKeysSpreadAcrossNodes() {
        // The hash function isn't required to be uniform, but with a handful of
        // keys over 3 buckets it must hit at least two of them — otherwise it's
        // not doing its job.
        KeyRouter r = new KeyRouter(List.of("a", "b", "c"));
        Map<String, Integer> hits = new HashMap<>();
        for (String key : List.of("apple", "banana", "carrot", "user:1", "user:2", "user:3",
                                   "foo", "bar", "baz", "quux")) {
            hits.merge(r.nodeFor(key), 1, Integer::sum);
        }
        Assertions.assertTrue(hits.size() >= 2,
            "expected keys to land on at least 2 nodes, saw: " + hits);
    }

    @Test
    void singleNodeAlwaysWins() {
        KeyRouter r = new KeyRouter(List.of("only"));
        Assertions.assertEquals("only", r.nodeFor("anything"));
        Assertions.assertEquals("only", r.nodeFor(""));
    }

    @Test
    void allReturnsTheNodeListInOrder() {
        List<String> nodes = List.of("a", "b", "c");
        Assertions.assertEquals(nodes, new KeyRouter(nodes).all());
    }

    @Test
    void negativeHashCodeIsHandled() {
        // String.hashCode() can be negative; Math.floorMod must keep the index
        // in range. "polygenelubricants" famously has hashCode == Integer.MIN_VALUE,
        // which is the worst case for naive modulo.
        KeyRouter r = new KeyRouter(List.of("a", "b", "c"));
        Assertions.assertDoesNotThrow(() -> r.nodeFor("polygenelubricants"));
    }
}
