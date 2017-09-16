package org.rx.socket;

import org.rx.App;
import org.rx.SystemException;
import org.rx.Tuple;
import org.rx.cache.BufferSegment;
import org.rx.util.AsyncTask;
import org.rx.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.rx.Contract.as;
import static org.rx.Contract.require;

public class DirectSocket extends Traceable implements AutoCloseable {
    private static class ClientItem implements AutoCloseable {
        private final DirectSocket  owner;
        public final Socket         sock;
        public final IOStream       ioStream;
        public final SocketPool.PooledSocket directSock;
        public final IOStream       directIoStream;
        private final BufferSegment segment;

        public boolean isClosed() {
            return !(sock.isConnected() && !sock.isClosed() && directSock.isConnected());
        }

        public ClientItem(Socket client, DirectSocket owner) {
            sock = client;
            segment = new BufferSegment(BufferSegment.DefaultBufferSize, 2);
            try {
                directSock = App.retry(p -> SocketPool.Pool.borrowSocket(p.directAddress), owner, owner.connectRetryCount);
                ioStream = new IOStream(sock.getInputStream(), directSock.socket.getOutputStream(), segment.alloc());
                directIoStream = new IOStream(directSock.socket.getInputStream(), sock.getOutputStream(),
                        segment.alloc());
            } catch (IOException ex) {
                throw new SocketException((InetSocketAddress) sock.getLocalSocketAddress(), ex);
            }
            this.owner = owner;
        }

        @Override
        public void close() {
            owner.getTracer().writeLine("%s client[%s->%s] close..", owner.getTimeString(), Sockets.getId(sock, false),
                    Sockets.getId(directSock.socket, false));
            try {
                sock.close();
                directSock.close();
            } catch (IOException ex) {
                Logger.info("DirectSocket item close error: %s", ex.getMessage());
            }
        }
    }

    private static final int       DefaultBacklog           = 128;
    private static final int       DefaultConnectRetryCount = 4;
    private volatile boolean       isClosed;
    private InetSocketAddress      directAddress;
    private final ServerSocket     server;
    private final List<ClientItem> clients;
    private volatile int           connectRetryCount;

    public boolean isClosed() {
        return !(!isClosed && !server.isClosed());
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

    public int getConnectRetryCount() {
        return connectRetryCount;
    }

    public void setConnectRetryCount(int connectRetryCount) {
        if (connectRetryCount <= 0) {
            connectRetryCount = 1;
        }
        this.connectRetryCount = connectRetryCount;
    }

    public DirectSocket(int listenPort, InetSocketAddress directAddr) {
        this(new InetSocketAddress(Sockets.AnyAddress, listenPort), directAddr);
    }

    public DirectSocket(InetSocketAddress listenAddr, InetSocketAddress directAddr) {
        require(listenAddr, directAddr);

        try {
            server = new ServerSocket();
            server.bind(listenAddr, DefaultBacklog);
        } catch (IOException ex) {
            throw new SocketException(listenAddr, ex);
        }
        directAddress = directAddr;
        clients = Collections.synchronizedList(new ArrayList<>());
        connectRetryCount = DefaultConnectRetryCount;
        String taskName = String.format("DirectSocket[%s->%s]", listenAddr, directAddr);
        Tracer tracer = new Tracer();
        tracer.setPrefix(taskName + " ");
        setTracer(tracer);
        AsyncTask.TaskFactory.run(() -> {
            getTracer().writeLine("%s start..", getTimeString());
            while (!isClosed()) {
                try {
                    ClientItem client = new ClientItem(server.accept(), this);
                    clients.add(client);
                    onReceive(client, taskName);
                } catch (IOException ex) {
                    Logger.error(ex, taskName);
                }
            }
            close();
        }, taskName);
    }

    @Override
    public synchronized void close() {
        if (isClosed) {
            return;
        }
        isClosed = true;
        try {
            for (ClientItem client : getClientsCopy()) {
                client.close();
            }
            clients.clear();
            server.close();
        } catch (IOException ex) {
            Logger.error(ex, "DirectSocket close");
        }
        getTracer().writeLine("%s close..", getTimeString());
    }

    private List<ClientItem> getClientsCopy() {
        return new ArrayList<>(clients);
    }

    private void onReceive(ClientItem client, String taskName) {
        AsyncTask.TaskFactory.run(() -> {
            int recv = -1;
            try {
                recv = client.ioStream.directData(p -> !client.isClosed(), p -> {
                    getTracer().writeLine("%s sent %s bytes from %s to %s..", getTimeString(), p,
                            Sockets.getId(client.sock, true), Sockets.getId(client.directSock.socket, false));
                    return true;
                });
            } catch (Exception ex) {
                boolean check = false;
                java.net.SocketException sockEx = as(ex, java.net.SocketException.class);
                if (sockEx != null) {
                    String msg = sockEx.getMessage();
                    if (msg != null && msg.toLowerCase().contains("connection reset")) {
                        check = true;
                        recv = 0;
                        Logger.error(sockEx, "onReceive");
                    }
                }
                if (!check) {
                    throw new SystemException(ex);
                }
            }
            if (recv == 0) {
                client.close();
            }
        }, String.format("%s[ioStream]", taskName));
        AsyncTask.TaskFactory.run(() -> {
            int recv = client.directIoStream.directData(p -> !client.isClosed(), p -> {
                getTracer().writeLine("%s recv %s bytes from %s to %s..", getTimeString(), p,
                        Sockets.getId(client.directSock.socket, false), Sockets.getId(client.sock, true));
                return true;
            });
            if (recv == 0) {
                client.close();
            }
        }, String.format("%s[directIoStream]", taskName));
    }
}
