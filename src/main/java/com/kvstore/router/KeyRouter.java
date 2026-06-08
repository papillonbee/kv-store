package com.kvstore.router;


import java.util.List;

/**
 * KeyRouter — maps a key to the storage node URL that owns it.
 *
 * <p>Strategy: static modulo over {@code hashCode()}. Stable across requests as
 * long as the node list does not change. If the list size changes, virtually
 * every key remaps — adequate for an assignment, but consistent hashing is the
 * obvious upgrade for production (Part 3).
 */
public class KeyRouter {

    private final List<String> nodes;

    public KeyRouter(List<String> nodes) {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("at least one node is required");
        }
        this.nodes = List.copyOf(nodes);
    }

    public String nodeFor(String key) {
        int idx = Math.floorMod(key.hashCode(), nodes.size());
        return nodes.get(idx);
    }

    public List<String> all() {
        return nodes;
    }
}
