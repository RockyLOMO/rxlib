package org.rx.util.rss;

import lombok.extern.slf4j.Slf4j;
import org.rx.core.Reflects;
import org.rx.core.Sys;

import java.util.Map;

import static org.rx.core.Extends.eq;

@Slf4j
public final class RssLauncher {
    private RssLauncher() {
    }

    public static void main(String[] args) {
        run(args);
    }

    public static void run(String[] args) {
        try {
            RssSupport.bootstrapRuntime();

            Map<String, String> options = Sys.mainOptions(args);
            Integer port = Reflects.convertQuietly(options.get("port"), Integer.class);
            if (port == null) {
                log.info("Invalid port arg");
                return;
            }

            String mode = options.get("shadowMode");
            if (eq(mode, "1")) {
                RssServer.launch(options, port);
                return;
            }
            RssClient.launch(options, port);
        } catch (Throwable e) {
            log.error("Main error", e);
            System.exit(-1);
        }
    }
}
