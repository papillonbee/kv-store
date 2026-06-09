package com.kvstore;


import com.kvstore.persistence.Persistence;
import com.kvstore.router.KeyRouter;
import com.kvstore.service.KvService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
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
    // Domain classes (KvService, KeyRouter) carry no Spring annotations — all
    // mode-specific wiring lives here, gated by @Profile, so the same jar can
    // run as either a storage node or the router depending on the active profile.

    /**
     * Persistence bean — only created when {@code kvstore.persistence.dir} is
     * set. The directory is namespaced by {@code kvstore.node-id} so multiple
     * nodes on the same machine don't trample each other.
     */
    @Bean(destroyMethod = "close")
    @Profile("node")
    public Persistence persistence(
        @Value("${kvstore.persistence.dir:#{null}}") String persistenceDir,
        @Value("${kvstore.node-id:node-default}") String nodeId
    ) throws IOException {
        if (persistenceDir == null || persistenceDir.isBlank()) {
            return null;
        }
        return Persistence.openAndRecover(Path.of(persistenceDir, nodeId));
    }

    @Bean
    @Profile("node")
    public KvService kvService(
        @Autowired(required = false) Persistence persistence,
        @Value("${kvstore.persistence.snapshot-interval-seconds:60}") long snapshotIntervalSeconds
    ) {
        KvService service = (persistence == null) ? new KvService() : new KvService(persistence);
        if (persistence != null) {
            persistence.schedulePeriodicSnapshots(service::snapshotState, Duration.ofSeconds(snapshotIntervalSeconds));
        }
        return service;
    }

    @Bean
    @Profile("router")
    public KeyRouter keyRouter(@Value("${kvstore.nodes}") List<String> nodes) {
        return new KeyRouter(nodes);
    }
}
