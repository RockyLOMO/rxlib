package org.rx.test;

import org.rx.io.Files;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;

public class TConfig {
    static final String baseDir = "D:\\download";
    static final InetSocketAddress endpoint0 = Sockets.parseEndpoint("127.0.0.1:3307");
    static final InetSocketAddress endpoint1 = Sockets.parseEndpoint("127.0.0.1:3308");

    public static String path(String... paths) {
        return Files.concatPath(baseDir, paths);
    }

    protected synchronized void _exit() {
        notify();
    }
}
