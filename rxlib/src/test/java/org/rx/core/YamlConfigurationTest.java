package org.rx.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
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
        conf.onChanged.combine((s, e) -> changedCount.incrementAndGet());
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
        conf.onChanged.combine((s, e) -> changedCount.incrementAndGet());
        assertDoesNotThrow(() -> conf.raiseChange(confFile.toString()));

        assertEquals(0, changedCount.get());
        assertEquals(Integer.valueOf(1), conf.read("version", 0));
    }
}
