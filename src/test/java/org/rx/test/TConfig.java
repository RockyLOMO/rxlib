package org.rx.test;

import org.rx.io.Files;

public class TConfig {
    static final String baseDir = "D:\\download";

    public static String path(String... paths) {
        return Files.concatPath(baseDir, paths);
    }

    protected synchronized void _exit() {
        notify();
    }
}
