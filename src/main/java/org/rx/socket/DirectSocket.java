package org.rx.socket;

import org.rx.common.Tuple;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.rx.common.Contract.require;
import static org.rx.util.App.logError;
import static org.rx.util.AsyncTask.TaskFactory;
import static org.rx.socket.SocketPool.Pool;
import static org.rx.socket.SocketPool.PooledSocket;

/**
 * Created by IntelliJ IDEA. User: wangxiaoming Date: 2017/8/25
 */
public class DirectSocket implements AutoCloseable {
    private static class ClientItem implements AutoCloseable {
        public final Socket       sock;
        public final IOStream     ioStream;
        public final PooledSocket directSock;
        public final IOStream     directIoStream;

        public boolean isRun() {
            return sock.isConnected() && !sock.isClosed() && directSock.isConnected();
        }

        public ClientItem(Socket client, DirectSocket owner) {
            sock = client;
            try {
                directSock = Pool.borrowSocket(owner.directAddress);
                ioStream = new IOStream(sock.getInputStream(), directSock.socket.getOutputStream());
                directIoStream = new IOStream(directSock.socket.getInputStream(), sock.getOutputStream());
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
                throw new SocketException((InetSocketAddress) sock.getLocalSocketAddress(), ex);
            }
        }
    }

    public static final InetAddress LocalAddress, AnyAddress;
    private static final int        DefaultBacklog = 128;

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

    public Stream<Tuple<Socket, Socket>> getClients() {
        return getClientsCopy().stream().map(p -> Tuple.of(p.sock, p.directSock.socket));
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

    private List<ClientItem> getClientsCopy() {
        return new ArrayList<>(clients);
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
                    ClientItem client = new ClientItem(server.accept(), this);
                    clients.add(client);
                    onReceive(client, taskName);
                } catch (IOException ex) {
                    logError(ex, taskName);
                }
            }
            close();
        }, taskName);
    }

    private void onReceive(ClientItem client, String taskName) {
        TaskFactory.run(() -> {
            int recv = client.ioStream.directDate(p -> client.isRun());
            if (recv == 0) {
                client.close();
            }
        }, String.format("%s[ioStream]", taskName));
        TaskFactory.run(() -> {
            int recv = client.directIoStream.directDate(p -> client.isRun());
            if (recv == 0) {
                client.close();
            }
        }, String.format("%s[directIoStream]", taskName));
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
            clients.clear();
            server.close();
        } catch (IOException ex) {
            logError(ex, "server.close()");
        }
    }
}
