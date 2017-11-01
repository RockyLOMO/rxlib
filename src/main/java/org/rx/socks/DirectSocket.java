package org.rx.socks;

import org.rx.*;
import org.rx.bean.Const;
import org.rx.bean.Tuple;
import org.rx.cache.BufferSegment;
import org.rx.cache.BytesSegment;
import org.rx.util.AsyncTask;
import org.rx.util.MemoryStream;

import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.rx.$.$;
import static org.rx.Contract.isNull;
import static org.rx.Contract.require;

public class DirectSocket extends Traceable implements AutoCloseable {
    @FunctionalInterface
    public interface SocketSupplier {
        Tuple<AutoCloseable, Socket> get(MemoryStream pack);
    }

    private static class ClientItem {
        private final DirectSocket  owner;
        private final BufferSegment segment;
        public final NetworkStream  stream;
        public final AutoCloseable  toSock;
        public final NetworkStream  toStream;

        public ClientItem(Socket client, DirectSocket owner) {
            this.owner = owner;
            segment = new BufferSegment(Const.DefaultBufferSize, 2);
            try {
                stream = new NetworkStream(client, segment.alloc());
                if (owner.directAddress != null) {
                    SocketPool.PooledSocket pooledSocket = App.retry(p -> SocketPool.Pool.borrowSocket(p.directAddress),
                            owner, owner.connectRetryCount);
                    toSock = pooledSocket;
                    toStream = new NetworkStream(pooledSocket.socket, segment.alloc(), false);
                    return;
                }
                if (owner.directSupplier != null) {
                    MemoryStream firstPack = new MemoryStream(32, true);
                    BytesSegment buffer = stream.getSegment();
                    int read;
                    while ((read = stream.readSegment()) > 0) {
                        System.out.println("----:" + Bytes.toString(buffer.array, buffer.offset, read));
                        firstPack.write(buffer.array, buffer.offset, read);
                        Tuple<AutoCloseable, Socket> toSocks;
                        if ((toSocks = owner.directSupplier.get(firstPack)) != null) {
                            toSock = toSocks.left;
                            firstPack.writeTo(toStream = new NetworkStream(toSocks.right, segment.alloc(), false));
                            return;
                        }
                    }
                    Logger.info("DirectSocket ClientItem directSupplier read: %s\ncontent: %s", read,
                            Bytes.toString(firstPack.toArray(), 0, firstPack.getLength()));
                }
            } catch (IOException ex) {
                throw new SocketException((InetSocketAddress) client.getLocalSocketAddress(), ex);
            }
            throw new SocketException((InetSocketAddress) client.getLocalSocketAddress(),
                    "DirectSocket directSupplier error");
        }

        public void closeSocket() {
            owner.getTracer().writeLine("client close socket[%s->%s]..", Sockets.getId(stream.getSocket(), false),
                    Sockets.getId(stream.getSocket(), true));
            owner.clients.remove(this);
            stream.close();
        }

        public void closeToSocket(boolean pooling) {
            owner.getTracer().writeLine("client %s socket[%s->%s]..", pooling ? "pooling" : "close",
                    Sockets.getId(toStream.getSocket(), false), Sockets.getId(toStream.getSocket(), true));
            if (pooling) {
                try {
                    toSock.close();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            } else {
                Sockets.close(toStream.getSocket());
            }
        }
    }

    public static final SocketSupplier HttpSupplier             = pack -> {
                                                                    String line = Bytes.readLine(pack.getBuffer());
                                                                    if (line == null) {
                                                                        return null;
                                                                    }
                                                                    InetSocketAddress authority;
                                                                    try {
                                                                        authority = Sockets.parseAddress(
                                                                                new URL(line.split(" ")[1])
                                                                                        .getAuthority());
                                                                    } catch (MalformedURLException ex) {
                                                                        throw SystemException.wrap(ex);
                                                                    }
                                                                    SocketPool.PooledSocket pooledSocket = App.retry(
                                                                            p -> SocketPool.Pool.borrowSocket(p),
                                                                            authority, 2);
                                                                    return Tuple.of(pooledSocket, pooledSocket.socket);
                                                                };
    private static final int           DefaultBacklog           = 128;
    private static final int           DefaultConnectRetryCount = 4;
    private final ServerSocket         server;
    private final List<ClientItem>     clients;
    private volatile int               connectRetryCount;
    private InetSocketAddress          directAddress;
    private SocketSupplier             directSupplier;

    @Override
    public boolean isClosed() {
        return !(!super.isClosed() && !server.isClosed());
    }

    public InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) server.getLocalSocketAddress();
    }

    public NQuery<Tuple<Socket, Socket>> getClients() {
        return NQuery.of(clients).select(p -> Tuple.of(p.stream.getSocket(), p.toStream.getSocket()));
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
        this(new InetSocketAddress(Sockets.AnyAddress, listenPort), directAddr, null);
    }

    public DirectSocket(InetSocketAddress listenAddr, InetSocketAddress directAddr, SocketSupplier directSupplier) {
        require(listenAddr);
        require(this, directAddr != null || directSupplier != null);

        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(listenAddr, DefaultBacklog);
        } catch (IOException ex) {
            throw new SocketException(listenAddr, ex);
        }
        directAddress = directAddr;
        this.directSupplier = directSupplier;
        clients = Collections.synchronizedList(new ArrayList<>());
        connectRetryCount = DefaultConnectRetryCount;
        String taskName = String.format("DirectSocket[%s->%s]", listenAddr, isNull(directAddress, "autoAddress"));
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
                int recv = client.stream.directTo(client.toStream, (p1, p2) -> {
                    getTracer().writeLine("sent %s bytes from %s to %s..", p2,
                            Sockets.getId(client.stream.getSocket(), true),
                            Sockets.getId(client.toStream.getSocket(), false));
                    return true;
                });
                getTracer().writeLine("socket[%s->%s] closing with %s", Sockets.getId(client.stream.getSocket(), false),
                        Sockets.getId(client.stream.getSocket(), true), recv);
            } catch (SystemException ex) {
                $<java.net.SocketException> out = $();
                if (ex.tryGet(out, java.net.SocketException.class)) {
                    if (out.$.getMessage().contains("Socket closed")) {
                        //ignore
                        Logger.debug("DirectTo ignore socket closed");
                        return;
                    }
                }
                throw ex;
            } finally {
                client.closeSocket();
            }
        }, String.format("%s[networkStream]", taskName));
        AsyncTask.TaskFactory.run(() -> {
            int recv = NetworkStream.StreamEOF;
            try {
                recv = client.toStream.directTo(client.stream, (p1, p2) -> {
                    getTracer().writeLine("recv %s bytes from %s to %s..", p2,
                            Sockets.getId(client.toStream.getSocket(), false),
                            Sockets.getId(client.stream.getSocket(), true));
                    return true;
                });
                getTracer().writeLine("socket[%s->%s] closing with %s",
                        Sockets.getId(client.toStream.getSocket(), false),
                        Sockets.getId(client.toStream.getSocket(), true), recv);
            } catch (SystemException ex) {
                $<java.net.SocketException> out = $();
                if (ex.tryGet(out, java.net.SocketException.class)) {
                    if (out.$.getMessage().contains("Socket closed")) {
                        //ignore
                        Logger.debug("DirectTo ignore socket closed");
                        return;
                    }
                }
                throw ex;
            } finally {
                client.closeToSocket(recv == NetworkStream.CannotWrite);
            }
        }, String.format("%s[toNetworkStream]", taskName));
    }
}
