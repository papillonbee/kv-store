package com.kvstore;


import com.kvstore.router.KeyRouter;
import com.kvstore.store.KvStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import java.util.List;

/**
 * KvStoreApplication — single Spring Boot entry point. Pick mode at runtime
 * with {@code --spring.profiles.active=node} or
 * {@code --spring.profiles.active=router}; the {@code @Profile}-gated
 * controllers ({@code KvController}, {@code RouterController}) decide which
 * routes are active.
 *
 * <p>Examples:
 * <pre>
 * # storage node on 7001
 * java -jar kv-store.jar --spring.profiles.active=node \
 *   --server.port=7001 --kvstore.node-id=node-1
 *
 * # router on 7000, pointing at three nodes
 * java -jar kv-store.jar --spring.profiles.active=router \
 *   --server.port=7000 \
 *   --kvstore.nodes=http://localhost:7001,http://localhost:7002,http://localhost:7003
 * </pre>
 */
@SpringBootApplication
public class KvStoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(KvStoreApplication.class, args);
    }

    // ---------- bean factories ----------
    //
    // Domain classes (KvStore, KeyRouter) carry no Spring annotations — all
    // mode-specific wiring lives here, gated by @Profile, so the same jar can
    // run as either a storage node or the router depending on the active profile.

    @Bean
    @Profile("node")
    public KvStore kvStore() {
        return new KvStore();
    }

    @Bean
    @Profile("router")
    public KeyRouter keyRouter(@Value("${kvstore.nodes}") List<String> nodes) {
        return new KeyRouter(nodes);
    }
}
