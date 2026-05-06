package org.rx.core;

import com.alibaba.fastjson2.TypeReference;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class YamlConfigurationTest {
    static final class MockYamlConfiguration extends YamlConfiguration {
        final Queue<Map<String, Object>> snapshots = new ArrayDeque<>();

        MockYamlConfiguration(String fileName) {
            super(fileName);
        }

        @Override
        protected Map<String, Object> loadYamlSnapshot(String fileName, boolean includeOutputFile) {
            Map<String, Object> next = snapshots.poll();
            return next == null ? new LinkedHashMap<>() : new LinkedHashMap<>(next);
        }
    }

    public static class Route {
        public String host;
        public int port;
    }

    static final class CloseTrackingInputStream extends ByteArrayInputStream {
        boolean closed;

        CloseTrackingInputStream(String value) {
            super(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    @Test
    public void testRaiseChange_RetryUntilValidatorPasses() throws Exception {
        Path confFile = Files.createTempFile("yaml-watch", ".yml");
        confFile.toFile().deleteOnExit();
        Files.write(confFile, "version: 1\n".getBytes(StandardCharsets.UTF_8));

        MockYamlConfiguration conf = new MockYamlConfiguration(confFile.toString());
        conf.setWatchRetry(1, 0)
                .setWatchValidator(c -> c.read("version", 0) > 0);
        conf.snapshots.add(new LinkedHashMap<String, Object>());
        Map<String, Object> valid = new LinkedHashMap<>();
        valid.put("version", 2);
        conf.snapshots.add(valid);

        AtomicInteger changedCount = new AtomicInteger();
        conf.onChanged.add((s, e) -> changedCount.incrementAndGet());
        conf.raiseChange(confFile.toString());

        assertEquals(1, changedCount.get());
        assertEquals(Integer.valueOf(2), conf.read("version", 0));
    }

    @Test
    public void testRaiseChange_InvalidSnapshotKeepsPreviousYaml() throws Exception {
        Path confFile = Files.createTempFile("yaml-watch", ".yml");
        confFile.toFile().deleteOnExit();
        Files.write(confFile, "version: 1\n".getBytes(StandardCharsets.UTF_8));

        MockYamlConfiguration conf = new MockYamlConfiguration(confFile.toString());
        conf.getYaml().clear();
        conf.getYaml().put("version", 1);
        conf.setWatchRetry(0, 0)
                .setWatchValidator(c -> c.read("version", 0) > 0);
        conf.snapshots.add(new LinkedHashMap<String, Object>());

        AtomicInteger changedCount = new AtomicInteger();
        conf.onChanged.add((s, e) -> changedCount.incrementAndGet());
        assertDoesNotThrow(() -> conf.raiseChange(confFile.toString()));

        assertEquals(0, changedCount.get());
        assertEquals(Integer.valueOf(1), conf.read("version", 0));
    }

    @Test
    public void testReadAs_GenericList() throws Exception {
        Path confFile = Files.createTempFile("yaml-generic-list", ".yml");
        confFile.toFile().deleteOnExit();
        Files.write(confFile, ("routes:\n"
                + "  - host: a\n"
                + "    port: 1001\n"
                + "  - host: b\n"
                + "    port: 1002\n").getBytes(StandardCharsets.UTF_8));

        YamlConfiguration conf = new YamlConfiguration(confFile.toString());
        List<Route> routes = conf.readAs("routes", new TypeReference<List<Route>>() {
        }.getType(), true);

        assertEquals(2, routes.size());
        assertEquals("a", routes.get(0).host);
        assertEquals(1002, routes.get(1).port);
    }

    @Test
    public void testLoadYaml_ClosesInputStreams() {
        CloseTrackingInputStream in = new CloseTrackingInputStream("version: 1\n");

        Map<String, Object> yaml = YamlConfiguration.loadYaml(Arrays.asList(in));

        assertEquals(1, yaml.get("version"));
        assertTrue(in.closed);
    }
}
