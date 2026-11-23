package org.rx.util.rss;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/** The helper owns its nft table until the JVM closes its input, including abrupt JVM exit. */
final class RssUdpFirewall {
    private RssUdpFirewall() { }

    static boolean start(int first, int last) throws Exception {
        String script;
        try (InputStream resource = RssUdpFirewall.class.getResourceAsStream("/rss-udp-hops.sh");
             Scanner scanner = new Scanner(resource, "UTF-8")) {
            script = scanner.useDelimiter("\\A").next().replace("\r", "");
        }
        Process process = new ProcessBuilder("sh", "-c", script, "rss-udp-hops",
                Integer.toString(first), Integer.toString(last)).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        boolean ready = false;
        try (BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.US_ASCII))) {
            ready = "ready".equals(output.readLine());
            if (ready) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        process.getOutputStream().close();
                        process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception ignored) { }
                }, "rss-udp-nft-cleanup"));
            }
            return ready;
        } finally {
            if (!ready) process.destroy();
        }
    }
}
