package org.rx.util;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * Created by IntelliJ IDEA. User: wangxiaoming Date: 2017/8/25
 */
public class DirectSocket {
    public static final InetAddress LocalAddress, AnyAddress;

    static {
        LocalAddress = InetAddress.getLoopbackAddress();
        try {
            AnyAddress = InetAddress.getByName("0.0.0.0");
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private InetSocketAddress listenAddr, directAddr;
    private ServerSocket      serverSocket;

//    public DirectSocket(int port) {
//                serverSocket = new ServerSocket().accept().getInputStream().available();
//    }
}
