package org.rx.test;

import org.rx.io.Files;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;

public class TConfig {
    //    static final String baseDir = "D:\\download";
    static final String baseDir = "D:\\home\\RxSocks";
    static final InetSocketAddress endpoint_3307 = Sockets.parseEndpoint("127.0.0.1:3307");
    static final InetSocketAddress endpoint_3308 = Sockets.parseEndpoint("127.0.0.1:3308");
    final String host_devops = "devops.f-li.cn";
    final String host_cloud = "cloud.f-li.cn";

    public static String path(String... paths) {
        return Files.concatPath(baseDir, paths);
    }

    protected synchronized void _exit() {
        notify();
    }
}
