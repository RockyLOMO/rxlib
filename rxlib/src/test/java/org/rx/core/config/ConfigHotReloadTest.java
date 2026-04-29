package org.rx.core.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

public class ConfigHotReloadTest {
    public static class ServerConfig {
        public int port;
        public String name;
    }

    static final class ManualConfigSource extends ConfigSource<ServerConfig> {
        final AtomicReference<ServerConfig> current = new AtomicReference<>();
        long version;

        ManualConfigSource(ServerConfig config) {
            super("manual");
            current.set(config);
            version = 1;
        }

        @Override
        public ServerConfig current() {
            return current.get();
        }

        @Override
        public long version() {
            return version;
        }

        @Override
        public ConfigSource<ServerConfig> start() {
            return this;
        }

        void update(ServerConfig config) {
            ServerConfig old = current.getAndSet(config);
            long v = ++version;
            publishEvent(onChanged, new ConfigChangedEventArgs<>("manual", "manual", v, old, config, 0));
        }

        @Override
        protected void dispose() {
        }
    }

    static final class ManagedResource implements AutoCloseable {
        final int port;
        final AtomicInteger closeCount = new AtomicInteger();

        ManagedResource(int port) {
            this.port = port;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }

    static final class MutableRoutes {
        volatile int port;

        MutableRoutes(int port) {
            this.port = port;
        }
    }

    @Test
    public void yamlSourceLoadsDtoAndPublishesChange() throws Exception {
        Path file = newConfigFile("server:\n  port: 1001\n  name: a\n");
        YamlConfigSource<ServerConfig> source = new YamlConfigSource<ServerConfig>("server", "server",
                ServerConfig.class, file.toString())
                .setValidator(c -> c.port > 0)
                .setChangeDetector((oldConfig, newConfig) -> oldConfig == null || oldConfig.port != newConfig.port);
        try {
            source.start();
            assertEquals(1001, source.current().port);

            AtomicReference<ConfigChangedEventArgs<ServerConfig>> changed = new AtomicReference<>();
            source.onChanged.add((s, e) -> changed.set(e));

            Files.write(file, "server:\n  port: 1002\n  name: b\n".getBytes(StandardCharsets.UTF_8));
            source.reload();

            assertEquals(1002, source.current().port);
            assertNotNull(changed.get());
            assertEquals(1001, changed.get().getOldConfig().port);
            assertEquals(1002, changed.get().getNewConfig().port);
        } finally {
            source.close();
        }
    }

    @Test
    public void yamlSourceKeepsPreviousConfigWhenValidatorRejects() throws Exception {
        Path file = newConfigFile("server:\n  port: 1001\n  name: a\n");
        YamlConfigSource<ServerConfig> source = new YamlConfigSource<ServerConfig>("server", "server",
                ServerConfig.class, file.toString())
                .setValidator(c -> c.port > 0);
        try {
            source.start();
            AtomicInteger changedCount = new AtomicInteger();
            source.onChanged.add((s, e) -> changedCount.incrementAndGet());

            Files.write(file, "server:\n  port: -1\n  name: bad\n".getBytes(StandardCharsets.UTF_8));
            source.reload();

            assertEquals(1001, source.current().port);
            assertEquals(0, changedCount.get());
        } finally {
            source.close();
        }
    }

    @Test
    public void resourceBindingRestartsLargeResourceAndClosesOldInstance() throws Exception {
        ManualConfigSource source = new ManualConfigSource(config(1001, "a"));
        AtomicInteger creates = new AtomicInteger();
        ConfigResourceBinding<ServerConfig, ManagedResource> binding =
                new ConfigResourceBinding<>(source, config -> {
                    creates.incrementAndGet();
                    return new ManagedResource(config.port);
                });
        try {
            binding.start();
            ManagedResource old = binding.current();
            CountDownLatch reloaded = new CountDownLatch(1);
            binding.onReloaded.add((s, e) -> reloaded.countDown());

            source.update(config(1002, "b"));

            assertTrue(reloaded.await(3, TimeUnit.SECONDS));
            waitUntil(() -> binding.current().port == 1002);
            assertNotSame(old, binding.current());
            assertEquals(1, old.closeCount.get());
            assertEquals(2, creates.get());
        } finally {
            binding.close();
        }
    }

    @Test
    public void resourceBindingKeepsOldResourceWhenCreateFails() throws Exception {
        ManualConfigSource source = new ManualConfigSource(config(1001, "a"));
        ConfigResourceBinding<ServerConfig, ManagedResource> binding =
                new ConfigResourceBinding<>(source, config -> {
                    if (config.port == 1002) {
                        throw new IllegalStateException("bad port");
                    }
                    return new ManagedResource(config.port);
                });
        try {
            binding.start();
            ManagedResource old = binding.current();
            CountDownLatch failed = new CountDownLatch(1);
            AtomicReference<ConfigReloadEventArgs<ServerConfig, ManagedResource>> failure = new AtomicReference<>();
            binding.onReloadFailed.add((s, e) -> {
                failure.set(e);
                failed.countDown();
            });

            source.update(config(1002, "bad"));

            assertTrue(failed.await(3, TimeUnit.SECONDS));
            assertSame(old, binding.current());
            assertEquals(1001, binding.currentConfig().port);
            assertEquals(0, old.closeCount.get());
            assertNotNull(failure.get().getError());
        } finally {
            binding.close();
        }
    }

    @Test
    public void resourceBindingAppliesSmallMutableResourceWithoutRebuild() throws Exception {
        ManualConfigSource source = new ManualConfigSource(config(1001, "a"));
        AtomicInteger creates = new AtomicInteger();
        AtomicInteger applies = new AtomicInteger();
        ConfigResourceBinding<ServerConfig, MutableRoutes> binding =
                new ConfigResourceBinding<>(source, new ConfigResource<ServerConfig, MutableRoutes>() {
                    @Override
                    public MutableRoutes create(ServerConfig config) {
                        creates.incrementAndGet();
                        return new MutableRoutes(config.port);
                    }

                    @Override
                    public boolean restartRequired(ServerConfig oldConfig, ServerConfig newConfig, MutableRoutes current) {
                        return false;
                    }

                    @Override
                    public void apply(ServerConfig oldConfig, ServerConfig newConfig, MutableRoutes current) {
                        applies.incrementAndGet();
                        current.port = newConfig.port;
                    }
                });
        try {
            binding.start();
            MutableRoutes routes = binding.current();
            CountDownLatch reloaded = new CountDownLatch(1);
            binding.onReloaded.add((s, e) -> reloaded.countDown());

            source.update(config(1002, "b"));

            assertTrue(reloaded.await(3, TimeUnit.SECONDS));
            assertSame(routes, binding.current());
            assertEquals(1002, routes.port);
            assertEquals(1, creates.get());
            assertEquals(1, applies.get());
            assertEquals(1002, binding.currentConfig().port);
        } finally {
            binding.close();
        }
    }

    private static ServerConfig config(int port, String name) {
        ServerConfig config = new ServerConfig();
        config.port = port;
        config.name = name;
        return config;
    }

    private static Path newConfigFile(String content) throws Exception {
        Path file = Files.createTempFile("rx-config-hot-reload", ".yml");
        file.toFile().deleteOnExit();
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static void waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("wait timeout");
    }
}
