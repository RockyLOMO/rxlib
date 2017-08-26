package org.rx.socket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import static org.rx.common.Contract.require;
import static org.rx.util.App.logError;
import static org.rx.util.App.sleep;
import static org.rx.util.AsyncTask.TaskFactory;

/**
 * Created by IntelliJ IDEA. User: wangxiaoming Date: 2017/8/25
 */
public class DirectSocket implements AutoCloseable {
    private static class ClientItem implements AutoCloseable {
        public final Socket       sock;
        public final InputStream  inputStream;
        public final Socket       directSock;
        public final OutputStream outputStream;
        public final byte[]       buffer;
        public volatile boolean   isBusy;

        public boolean isRun() {
            return sock.isConnected() && !sock.isClosed() && directSock.isConnected() && !directSock.isClosed();
        }

        public boolean canRead() {
            try {
                return !isBusy && inputStream.available() > 0;
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }

        public ClientItem(Socket client, DirectSocket parent) {
            sock = client;
            try {
                inputStream = sock.getInputStream();
                directSock = new Socket();
                directSock.connect(parent.directAddress, parent.connectTimeout);
                outputStream = directSock.getOutputStream();
                buffer = new byte[1024];
            } catch (IOException ex) {
                throw new SocketException((InetSocketAddress) sock.getLocalSocketAddress(), ex);
            }
        }

        @Override
        public void close() {
            try {
                sock.close();
                directSock.close();
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    public static final InetAddress LocalAddress, AnyAddress;
    private static final int        DefaultBacklog     = 128;
    private static final int        DefaultCheckMillis = 200;

    static {
        LocalAddress = InetAddress.getLoopbackAddress();
        try {
            AnyAddress = InetAddress.getByName("0.0.0.0");
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private volatile boolean       isRun;
    private InetSocketAddress      directAddress;
    private final ServerSocket     server;
    private final List<ClientItem> clients;
    private volatile boolean       sockCheck;
    private volatile int           connectTimeout;

    public boolean isRun() {
        return isRun && !server.isClosed();
    }

    public InetSocketAddress getDirectAddress() {
        return directAddress;
    }

    public InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) server.getLocalSocketAddress();
    }

    public Stream<Socket> getClients() {
        return getClientsCopy().stream().map(p -> p.sock);
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public synchronized void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public DirectSocket(InetSocketAddress directAddr, int port) {
        this(directAddr, new InetSocketAddress(AnyAddress, port));
    }

    public DirectSocket(InetSocketAddress directAddr, InetSocketAddress bindAddr) {
        require(directAddr, bindAddr);

        try {
            server = new ServerSocket();
            server.bind(bindAddr, DefaultBacklog);
        } catch (IOException ex) {
            throw new SocketException(bindAddr, ex);
        }
        isRun = true;
        directAddress = directAddr;
        clients = Collections.synchronizedList(new ArrayList<>());
        connectTimeout = 30000;
        String taskName = String.format("DirectSocket[%s to %s]", bindAddr, directAddr);
        TaskFactory.run(() -> {
            while (isRun()) {
                try {
                    clients.add(new ClientItem(server.accept(), this));
                    if (sockCheck) {
                        continue;
                    }
                    synchronized (clients) {
                        if (sockCheck) {
                            continue;
                        }
                        String checkTaskName = String.format("%s[SockCheck]", taskName);
                        TaskFactory.run(() -> {
                            while (isRun()) {
                                for (ClientItem client : getClientsCopy()) {
                                    try {
                                        if (!client.isRun()) {
                                            client.close();
                                            clients.remove(client);
                                        }
                                        if (!client.canRead()) {
                                            continue;
                                        }
                                        synchronized (client) {
                                            if (!client.canRead()) {
                                                continue;
                                            }
                                            TaskFactory.run(() -> onReceive(client),
                                                    String.format("%s[onReceive]", taskName));
                                            client.isBusy = true;
                                        }
                                    } catch (Exception ex) {
                                        logError(ex, checkTaskName);
                                    }
                                }
                                sleep(DefaultCheckMillis);
                            }
                        }, checkTaskName);
                        sockCheck = true;
                    }
                } catch (IOException ex) {
                    logError(ex, taskName);
                }
            }
            close();
        }, taskName);
    }

    private List<ClientItem> getClientsCopy() {
        return new ArrayList<>(clients);
    }

    private void onReceive(ClientItem client) {
        byte[] buffer = client.buffer;
        try {
            while (client.isRun() && client.canRead()) {
                int recv = client.inputStream.read(buffer, 0, buffer.length);
                client.outputStream.write(buffer, 0, recv);
            }
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        } finally {
            client.isBusy = false;
        }
    }

    @Override
    public synchronized void close() {
        if (!isRun) {
            return;
        }
        isRun = false;
        try {
            for (ClientItem client : getClientsCopy()) {
                try {
                    client.close();
                } catch (Exception ex) {
                    logError(ex, "server-client.close()");
                }
            }
            server.close();
        } catch (IOException ex) {
            logError(ex, "server.close()");
        }
    }
}
