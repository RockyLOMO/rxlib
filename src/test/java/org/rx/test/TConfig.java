package org.rx.test;

import org.rx.io.Files;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;

public class TConfig {
    static final String BASE_DIR = "./target/";
    static final String NAME_WYF = "王湵范 wyf520";
    static final InetSocketAddress endpoint_3307 = Sockets.parseEndpoint("127.0.0.1:3307");
    static final InetSocketAddress endpoint_3308 = Sockets.parseEndpoint("127.0.0.1:3308");
    final String host_devops = "devops.f-li.cn";
    final String host_cloud = "cloud.f-li.cn";

    public static String path(String... paths) {
        Files.createDirectory(BASE_DIR);
        return Files.concatPath(BASE_DIR, paths);
    }

    protected synchronized void _exit() {
        notify();
    }
}
