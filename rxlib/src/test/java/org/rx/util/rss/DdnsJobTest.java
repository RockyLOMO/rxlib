package org.rx.util.rss;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import com.alibaba.fastjson2.JSON;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class DdnsJobTest {
    static RssClientConf conf() {
        RssClientConf conf = new RssClientConf(); conf.ddnsJobSeconds = 15;
        conf.ddnsDomains = Collections.singletonList("cloud.f-li.cn"); conf.ddnsApiKey = "fixture";
        conf.ddnsLocalCommand = new String[]{"fixture-program", "fixture-argument"};
        return conf;
    }

    @Test void unchangedIpSkipsProviderAndFailuresRetryButLocalAlwaysRuns() {
        AtomicReference<String> ip = new AtomicReference<>("58.209.44.49");
        AtomicInteger providerCalls = new AtomicInteger();
        List<Map<String, String>> local = new ArrayList<>();
        DdnsJob job = new DdnsJob(ip::get, (c, p) -> providerCalls.incrementAndGet() != 1, (c, e) -> local.add(e));
        RssClientConf conf = conf();
        job.run(conf); assertEquals(1, providerCalls.get());
        assertEquals("1", local.get(0).get("RSS_DDNS_CHANGED"));
        assertEquals("0", local.get(0).get("RSS_DDNS_PROVIDER_SUCCESS"));
        job.run(conf); assertEquals(2, providerCalls.get());
        assertEquals("0", local.get(1).get("RSS_DDNS_CHANGED"));
        assertEquals("1", local.get(1).get("RSS_DDNS_PROVIDER_UPDATED"));
        job.run(conf); assertEquals(2, providerCalls.get()); assertEquals(3, local.size());
        assertEquals("0", local.get(2).get("RSS_DDNS_PROVIDER_UPDATED"));
        assertEquals("1", local.get(2).get("RSS_DDNS_PROVIDER_SUCCESS"));
        ip.set("58.209.44.50"); job.run(conf); assertEquals(3, providerCalls.get());
        assertEquals("1", local.get(3).get("RSS_DDNS_CHANGED"));
        conf.ddnsApiKey = "rotated-fixture"; job.run(conf); assertEquals(4, providerCalls.get());
        conf.ddnsDomains = Arrays.asList("cloud.f-li.cn", "new.f-li.cn"); job.run(conf); assertEquals(5, providerCalls.get());
    }

    @Test void invalidPublicObservationClearsHookEnvironmentWithoutProviderAndRecovers() {
        AtomicReference<String> ip = new AtomicReference<>("192.168.31.4");
        AtomicInteger providerCalls = new AtomicInteger();
        List<Map<String, String>> local = new ArrayList<>();
        DdnsJob job = new DdnsJob(ip::get, (c, p) -> { providerCalls.incrementAndGet(); throw new IllegalStateException("fixture-error"); }, (c, e) -> local.add(e));
        RssClientConf conf = conf(); job.run(conf);
        assertEquals(0, providerCalls.get()); assertEquals("", local.get(0).get("RSS_DDNS_IPV4"));
        assertEquals("0", local.get(0).get("RSS_DDNS_OBSERVED_SUCCESS"));
        ip.set("58.209.44.49"); job.run(conf);
        assertEquals(1, providerCalls.get()); assertEquals("58.209.44.49", local.get(1).get("RSS_DDNS_IPV4"));
        assertEquals("0", local.get(1).get("RSS_DDNS_PROVIDER_SUCCESS"));
        conf.ddnsDomains = Collections.emptyList(); job.run(conf); assertEquals("1", local.get(2).get("RSS_DDNS_PROVIDER_SUCCESS"));
        assertTrue(RssClient.shouldScheduleDdns(conf)); conf.ddnsLocalCommand = null; assertFalse(RssClient.shouldScheduleDdns(conf));
    }

    @Test void concurrentTicksDoNotReenterAndCommandConfigurationCanChange() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger local = new AtomicInteger(), observes = new AtomicInteger();
        DdnsJob job = new DdnsJob(() -> { observes.incrementAndGet(); entered.countDown(); release.await(3, TimeUnit.SECONDS); return "58.209.44.49"; }, (c, ip) -> true, (c, e) -> local.incrementAndGet());
        RssClientConf conf = conf(); Thread worker = new Thread(() -> job.run(conf)); worker.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS)); job.run(conf); assertEquals(1, observes.get());
        release.countDown(); worker.join(4000); assertFalse(worker.isAlive()); assertEquals(1, local.get());
        RssClientConf replacement = conf(); replacement.ddnsLocalCommand = null; job.run(replacement);
        assertEquals(2, observes.get()); assertEquals(1, local.get());
    }

    @Test void newConfigurationSecretsDeserializeButNeverPrint() {
        RssClientConf conf = JSON.parseObject("{\"wowEndpointWriterKey\":\"secret-fixture-writer\",\"ddnsLocalCommand\":[\"private-program\",\"private-argument\"]}", RssClientConf.class);
        assertEquals("secret-fixture-writer", conf.wowEndpointWriterKey);
        assertArrayEquals(new String[]{"private-program", "private-argument"}, conf.ddnsLocalCommand);
        assertFalse(JSON.toJSONString(conf).contains("secret-fixture-writer")); assertFalse(conf.toString().contains("secret-fixture-writer"));
        assertFalse(JSON.toJSONString(conf).contains("private-program")); assertFalse(conf.toString().contains("private-argument"));
    }

    @Test void localProcessDeadlineAndEnvironment(@TempDir Path temp) throws Exception {
        boolean windows = File.separatorChar == '\\';
        if (windows) {
            try { Process.class.getMethod("pid"); } catch (NoSuchMethodException ignored) { Assumptions.assumeTrue(false, "Java8 Windows intentionally refuses unsupervised local process trees"); }
        }
        RssClientConf conf = conf(); conf.ddnsLocalCommandTimeoutSeconds = 1;
        Path script = temp.resolve(windows ? "hook.ps1" : "hook.sh"), output = temp.resolve("env.txt");
        String source = windows ? "param([string]$out)\r\n[IO.File]::WriteAllText($out,$env:RSS_DDNS_IPV4)\r\nStart-Sleep -Seconds 60\r\n"
                : "#!/bin/sh\nprintf '%s' \"$RSS_DDNS_IPV4\" > \"$1\"\nsleep 60\n";
        Files.write(script, source.getBytes(StandardCharsets.UTF_8));
        conf.ddnsLocalCommand = windows ? new String[]{"powershell.exe", "-NoProfile", "-File", script.toString(), output.toString()}
                : new String[]{"/bin/sh", script.toString(), output.toString()};
        long started = System.nanoTime();
        assertThrows(java.util.concurrent.TimeoutException.class, () -> LocalDdnsCommand.run(conf, Collections.singletonMap("RSS_DDNS_IPV4", "58.209.44.49")));
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 7);
        assertEquals("58.209.44.49", new String(Files.readAllBytes(output), StandardCharsets.UTF_8));
    }
}
