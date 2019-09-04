package org.rx.socks;

import lombok.extern.slf4j.Slf4j;
import org.rx.beans.$;
import org.rx.beans.Tuple;
import org.rx.util.BufferSegment;
import org.rx.util.BytesSegment;
import org.rx.core.*;
import org.rx.core.AsyncTask;
import org.rx.io.MemoryStream;

import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.rx.beans.$.$;
import static org.rx.core.Contract.isNull;
import static org.rx.core.Contract.require;

@Slf4j
public class DirectSocket extends Traceable implements AutoCloseable {
    @FunctionalInterface
    public interface SocketSupplier {
        Tuple<AutoCloseable, Socket> get(MemoryStream pack);
    }

    private static class ClientItem {
        private final DirectSocket owner;
        private final BufferSegment segment;
        public final NetworkStream stream;
        public final AutoCloseable toSock;
        public final NetworkStream toStream;

        public ClientItem(Socket client, DirectSocket owner) {
            this.owner = owner;
            segment = new BufferSegment(Contract.config.getDefaultBufferSize(), 2);
            try {
                stream = new NetworkStream(client, segment.alloc());
                if (owner.directAddress != null) {
                    SocketPool.PooledSocket pooledSocket = App.retry(owner.connectRetryCount,
                            p -> SocketPool.Pool.borrowSocket(p.directAddress), owner);
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
                    log.info("DirectSocket ClientState directSupplier read: {}\ncontent: {}", read,
                            Bytes.toString(firstPack.toArray(), 0, firstPack.getLength()));
                }
            } catch (IOException ex) {
                throw new SocketException((InetSocketAddress) client.getLocalSocketAddress(), ex);
            }
            throw new SocketException((InetSocketAddress) client.getLocalSocketAddress(),
                    "DirectSocket directSupplier error");
        }

        public void closeSocket() {
            owner.getTracer().info("client close socket[%s->%s]..", Sockets.getId(stream.getSocket(), false),
                    Sockets.getId(stream.getSocket(), true));
            owner.clients.remove(this);
            stream.close();
        }

        public void closeToSocket(boolean pooling) {
            owner.getTracer().info("client %s socket[%s->%s]..", pooling ? "pooling" : "close",
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

    public static final SocketSupplier HttpSupplier = pack -> {
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
        SocketPool.PooledSocket pooledSocket = App.retry(2,
                p -> SocketPool.Pool.borrowSocket(p),
                authority);
        return Tuple.of(pooledSocket, pooledSocket.socket);
    };
    private static final int DefaultBacklog = 128;
    private static final int DefaultConnectRetryCount = 4;
    private final ServerSocket server;
    private final List<ClientItem> clients;
    private volatile int connectRetryCount;
    private InetSocketAddress directAddress;
    private SocketSupplier directSupplier;

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
        LogWriter tracer = new LogWriter();
        tracer.setPrefix(taskName + " ");
        setTracer(tracer);
        AsyncTask.TaskFactory.run(() -> {
            getTracer().info("start..");
            while (!isClosed()) {
                try {
                    ClientItem client = new ClientItem(server.accept(), this);
                    clients.add(client);
                    onReceive(client, taskName);
                } catch (IOException ex) {
                    log.error(taskName, ex);
                }
            }
            close();
        }, taskName);
    }

    @Override
    protected void freeObjects() {
        try {
            for (ClientItem client : NQuery.of(clients)) {
                client.closeSocket();
            }
            clients.clear();
            server.close();
        } catch (IOException ex) {
            log.error("DirectSocket close", ex);
        }
        getTracer().info("stop..");
    }

    private void onReceive(ClientItem client, String taskName) {
        AsyncTask.TaskFactory.run(() -> {
            try {
                int recv = client.stream.directTo(client.toStream, (p1, p2) -> {
                    getTracer().info("sent %s bytes from %s to %s..", p2,
                            Sockets.getId(client.stream.getSocket(), true),
                            Sockets.getId(client.toStream.getSocket(), false));
                    return true;
                });
                getTracer().info("socket[%s->%s] closing with %s", Sockets.getId(client.stream.getSocket(), false),
                        Sockets.getId(client.stream.getSocket(), true), recv);
            } catch (SystemException ex) {
                $<java.net.SocketException> out = $();
                if (ex.tryGet(out, java.net.SocketException.class)) {
                    if (out.v.getMessage().contains("Socket closed")) {
                        //ignore
                        log.debug("DirectTo ignore socket closed");
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
                    getTracer().info("recv %s bytes from %s to %s..", p2,
                            Sockets.getId(client.toStream.getSocket(), false),
                            Sockets.getId(client.stream.getSocket(), true));
                    return true;
                });
                getTracer().info("socket[%s->%s] closing with %s", Sockets.getId(client.toStream.getSocket(), false),
                        Sockets.getId(client.toStream.getSocket(), true), recv);
            } catch (SystemException ex) {
                $<java.net.SocketException> out = $();
                if (ex.tryGet(out, java.net.SocketException.class)) {
                    if (out.v.getMessage().contains("Socket closed")) {
                        //ignore
                        log.debug("DirectTo ignore socket closed");
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
