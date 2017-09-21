package org.rx.socket;

import org.rx.*;
import org.rx.bean.Tuple;
import org.rx.cache.BufferSegment;
import org.rx.util.AsyncTask;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.rx.Contract.require;
import static org.rx.socket.Sockets.shutdown;

public class DirectSocket extends Traceable implements AutoCloseable {
    private static class ClientItem extends Disposable {
        private final DirectSocket           owner;
        private final BufferSegment          segment;
        public final NetworkStream           networkStream;
        public final SocketPool.PooledSocket toSock;
        public final NetworkStream           toNetworkStream;

        public ClientItem(Socket client, DirectSocket owner) {
            segment = new BufferSegment(BufferSegment.DefaultBufferSize, 2);
            try {
                toSock = App.retry(p -> SocketPool.Pool.borrowSocket(p.directAddress), owner, owner.connectRetryCount);
                networkStream = new NetworkStream(client, segment.alloc());
                toNetworkStream = new NetworkStream(toSock.socket, segment.alloc(), false);
            } catch (IOException ex) {
                throw new SocketException((InetSocketAddress) client.getLocalSocketAddress(), ex);
            }
            this.owner = owner;
        }

        @Override
        protected void freeManaged() {
            owner.getTracer().writeLine("%s client[%s->%s] close..", owner.getTimeString(),
                    Sockets.getId(networkStream.getSocket(), false), Sockets.getId(networkStream.getSocket(), true));
            owner.clients.remove(this);
            networkStream.close();

            owner.getTracer().writeLine("%s client[%s->%s] return..", owner.getTimeString(),
                    Sockets.getId(toSock.socket, false), Sockets.getId(toSock.socket, true));
            toNetworkStream.close();
            toSock.close();
        }
    }

    private static final int       DefaultBacklog           = 128;
    private static final int       DefaultConnectRetryCount = 4;
    private InetSocketAddress      directAddress;
    private final ServerSocket     server;
    private final List<ClientItem> clients;
    private volatile int           connectRetryCount;

    @Override
    public boolean isClosed() {
        return !(!super.isClosed() && !server.isClosed());
    }

    public InetSocketAddress getDirectAddress() {
        return directAddress;
    }

    public InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) server.getLocalSocketAddress();
    }

    public NQuery<Tuple<Socket, Socket>> getClients() {
        return NQuery.of(getClientsCopy()).select(p -> Tuple.of(p.networkStream.getSocket(), p.toSock.socket));
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
            server.setReuseAddress(true);
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
    protected void freeManaged() {
        try {
            for (ClientItem client : getClientsCopy()) {
                shutdown(client.networkStream.getSocket(), 1 | 2);
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
            try {
                int recv = client.networkStream.directTo(client.toNetworkStream, (p1, p2) -> {
                    getTracer().writeLine("%s sent %s bytes from %s to %s..", getTimeString(), p2,
                            Sockets.getId(client.networkStream.getSocket(), true),
                            Sockets.getId(client.toSock.socket, false));
                    return true;
                });
                Logger.debug("Socket[%s] close with %s", Sockets.getId(client.networkStream.getSocket(), true), recv);
            } finally {
                client.close();
            }
        }, String.format("%s[networkStream]", taskName));
        AsyncTask.TaskFactory.run(() -> {
            try {
                int recv = client.toNetworkStream.directTo(client.networkStream, (p1, p2) -> {
                    getTracer().writeLine("%s recv %s bytes from %s to %s..", getTimeString(), p2,
                            Sockets.getId(client.toSock.socket, false),
                            Sockets.getId(client.networkStream.getSocket(), true));
                    return true;
                });
                Logger.debug("Socket[%s] close with %s", Sockets.getId(client.toSock.socket, false), recv);
            } finally {
                client.close();
            }
        }, String.format("%s[toNetworkStream]", taskName));
    }
}
