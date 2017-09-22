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
import java.util.function.Function;

import static org.rx.$.$;
import static org.rx.Contract.require;

public class DirectSocket extends Traceable implements AutoCloseable {
    private static class ClientItem {
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

        public void closeSocket() {
            owner.getTracer().writeLine("client close socket[%s->%s]..",
                    Sockets.getId(networkStream.getSocket(), false), Sockets.getId(networkStream.getSocket(), true));
            owner.clients.remove(this);
            networkStream.close();
        }

        public void closeToSocket(boolean pooling) {
            owner.getTracer().writeLine("client %s socket[%s->%s]..", pooling ? "pooling" : "close",
                    Sockets.getId(toSock.socket, false), Sockets.getId(toSock.socket, true));
            if (pooling) {
                toSock.close();
            } else {
                Sockets.close(toSock.socket);
            }
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
        return NQuery.of(clients).select(p -> Tuple.of(p.networkStream.getSocket(), p.toSock.socket));
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
        Logger tracer = new Logger();
        tracer.setPrefix(taskName + " ");
        setTracer(tracer);
        AsyncTask.TaskFactory.run(() -> {
            getTracer().writeLine("start..");
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
    protected void freeUnmanaged() {
        try {
            for (ClientItem client : NQuery.of(clients)) {
                client.closeSocket();
            }
            clients.clear();
            server.close();
        } catch (IOException ex) {
            Logger.error(ex, "DirectSocket close");
        }
        getTracer().writeLine("stop..");
    }

    private void onReceive(ClientItem client, String taskName) {
        AsyncTask.TaskFactory.run(() -> {
            try {
                int recv = client.networkStream.directTo(client.toNetworkStream, (p1, p2) -> {
                    getTracer().writeLine("sent %s bytes from %s to %s..", p2,
                            Sockets.getId(client.networkStream.getSocket(), true),
                            Sockets.getId(client.toSock.socket, false));
                    return true;
                });
                getTracer().writeLine("socket[%s->%s] closing with %s",
                        Sockets.getId(client.networkStream.getSocket(), false),
                        Sockets.getId(client.networkStream.getSocket(), true), recv);
            } catch (SystemException e) {
                $<java.net.SocketException> out = $();
                if (e.tryGet(out, java.net.SocketException.class)) {
                    if (out.$.getMessage().contains("Socket closed")) {
                        //ignore
                        Logger.debug("DirectTo ignore socket closed");
                        return;
                    }
                }
                throw e;
            } finally {
                client.closeSocket();
            }
        }, String.format("%s[networkStream]", taskName));
        AsyncTask.TaskFactory.run(() -> {
            int recv = NetworkStream.StreamEOF;
            try {
                recv = client.toNetworkStream.directTo(client.networkStream, (p1, p2) -> {
                    getTracer().writeLine("recv %s bytes from %s to %s..", p2,
                            Sockets.getId(client.toSock.socket, false),
                            Sockets.getId(client.networkStream.getSocket(), true));
                    return true;
                });
                getTracer().writeLine("socket[%s->%s] closing with %s", Sockets.getId(client.toSock.socket, false),
                        Sockets.getId(client.toSock.socket, true), recv);
            } catch (SystemException e) {
                $<java.net.SocketException> out = $();
                if (e.tryGet(out, java.net.SocketException.class)) {
                    if (out.$.getMessage().contains("Socket closed")) {
                        //ignore
                        Logger.debug("DirectTo ignore socket closed");
                        return;
                    }
                }
                throw e;
            } finally {
                client.closeToSocket(recv == NetworkStream.CannotWrite);
            }
        }, String.format("%s[toNetworkStream]", taskName));
    }
}
