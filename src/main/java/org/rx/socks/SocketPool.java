package org.rx.socks;

import org.rx.NQuery;
import org.rx.bean.DateTime;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.rx.Logger;

import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.rx.Contract.require;

public final class SocketPool extends Traceable implements AutoCloseable {
    public static final class PooledSocket implements AutoCloseable {
        private final SocketPool owner;
        private DateTime         lastActive;
        public final Socket      socket;

        public boolean isConnected() {
            return !owner.isClosed() && !socket.isClosed() && socket.isConnected();
        }

        public DateTime getLastActive() {
            return lastActive;
        }

        public void setLastActive(DateTime lastActive) {
            this.lastActive = lastActive;
        }

        private PooledSocket(SocketPool owner, Socket socket) {
            this.owner = owner;
            this.socket = socket;
            lastActive = DateTime.utcNow();
        }

        @Override
        public void close() {
            owner.returnSocket(this);
        }
    }

    public static final SocketPool                                                          Pool                   = new SocketPool();
    private static final int                                                                DefaultConnectTimeout  = 30000;
    private static final int                                                                DefaultMaxIdleMillis   = 120000;
    private static final int                                                                DefaultMaxSocketsCount = 64;
    private final ConcurrentHashMap<InetSocketAddress, ConcurrentLinkedDeque<PooledSocket>> pool;
    private volatile int                                                                    connectTimeout;
    private volatile int                                                                    maxIdleMillis;
    private volatile int                                                                    maxSocketsCount;
    private final Timer                                                                     timer;
    private volatile boolean                                                                isTimerRun;

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public int getMaxIdleMillis() {
        return maxIdleMillis;
    }

    public void setMaxIdleMillis(int maxIdleMillis) {
        if (maxIdleMillis <= 0) {
            maxIdleMillis = DefaultMaxIdleMillis;
        }
        this.maxIdleMillis = maxIdleMillis;
    }

    public int getMaxSocketsCount() {
        return maxSocketsCount;
    }

    public void setMaxSocketsCount(int maxSocketsCount) {
        if (maxSocketsCount < 0) {
            maxSocketsCount = 0;
        }
        this.maxSocketsCount = maxSocketsCount;
    }

    private SocketPool() {
        pool = new ConcurrentHashMap<>();
        connectTimeout = DefaultConnectTimeout;
        maxIdleMillis = DefaultMaxIdleMillis;
        maxSocketsCount = DefaultMaxSocketsCount;
        String n = "SocketPool";
        timer = new Timer(n, true);
        Logger tracer = new Logger();
        tracer.setPrefix(n + " ");
        tracer.writeLine("started..");
        setTracer(tracer);
    }

    @Override
    protected void freeUnmanaged() {
        clear();
    }

    private void runTimer() {
        if (isTimerRun) {
            return;
        }
        synchronized (timer) {
            if (isTimerRun) {
                return;
            }

            long period = 90000;
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    clearIdleSockets();
                }
            }, period, period);
            isTimerRun = true;
        }
        getTracer().writeLine("runTimer..");
    }

    private void clearIdleSockets() {
        for (Map.Entry<InetSocketAddress, ConcurrentLinkedDeque<PooledSocket>> entry : NQuery.of(pool.entrySet())) {
            ConcurrentLinkedDeque<PooledSocket> sockets = entry.getValue();
            if (sockets == null) {
                continue;
            }

            for (PooledSocket socket : NQuery.of(sockets)) {
                if (!socket.isConnected()
                        || DateTime.utcNow().subtract(socket.getLastActive()).getTotalMilliseconds() >= maxIdleMillis) {
                    sockets.remove(socket);
                    getTracer().writeLine("clear idle socket[local=%s, remote=%s]..",
                            Sockets.getId(socket.socket, false), Sockets.getId(socket.socket, true));
                }
            }
            if (sockets.isEmpty()) {
                pool.remove(entry.getKey());
            }
        }
        if (pool.size() == 0) {
            stopTimer();
        }
    }

    private void stopTimer() {
        synchronized (timer) {
            timer.cancel();
            timer.purge();
            isTimerRun = false;
        }
        getTracer().writeLine("stopTimer..");
    }

    private ConcurrentLinkedDeque<PooledSocket> getSockets(InetSocketAddress remoteAddr) {
        ConcurrentLinkedDeque<PooledSocket> sockets = pool.get(remoteAddr);
        if (sockets == null) {
            pool.put(remoteAddr, sockets = new ConcurrentLinkedDeque<>());
            runTimer();
        }
        return sockets;
    }

    public PooledSocket borrowSocket(InetSocketAddress remoteAddr) {
        checkNotClosed();
        require(remoteAddr);

        boolean isExisted = true;
        ConcurrentLinkedDeque<PooledSocket> sockets = getSockets(remoteAddr);
        PooledSocket pooledSocket;
        if ((pooledSocket = sockets.pollFirst()) == null) {
            Socket sock = new Socket();
            try {
                sock.connect(remoteAddr, connectTimeout);
            } catch (IOException ex) {
                throw new SocketException(remoteAddr, ex);
            }
            pooledSocket = new PooledSocket(this, sock);
            isExisted = false;
        }
        if (!pooledSocket.isConnected()) {
            if (isExisted) {
                sockets.remove(pooledSocket);
            }
            return borrowSocket(remoteAddr);
        }
        Socket sock = pooledSocket.socket;
        getTracer().writeLine("borrow %s socket[local=%s, remote=%s]..", isExisted ? "existed" : "new",
                Sockets.getId(sock, false), Sockets.getId(sock, true));
        return pooledSocket;
    }

    public void returnSocket(PooledSocket pooledSocket) {
        checkNotClosed();
        require(pooledSocket);

        String action = "return";
        try {
            if (!pooledSocket.isConnected()) {
                action = "discard closed";
                return;
            }
            pooledSocket.setLastActive(DateTime.utcNow());
            ConcurrentLinkedDeque<PooledSocket> sockets = getSockets(
                    (InetSocketAddress) pooledSocket.socket.getRemoteSocketAddress());
            if (sockets.size() >= maxSocketsCount || sockets.contains(pooledSocket)) {
                action = "discard contains";
                return;
            }

            sockets.addFirst(pooledSocket);
        } finally {
            Socket sock = pooledSocket.socket;
            getTracer().writeLine("%s socket[local=%s, remote=%s]..", action, Sockets.getId(sock, false),
                    Sockets.getId(sock, true));
        }
    }

    public void clear() {
        checkNotClosed();

        for (Socket socket : NQuery.of(pool.values()).selectMany(p -> p).select(p -> p.socket)) {
            try {
                getTracer().writeLine("clear socket[local=%s, remote=%s]..", Sockets.getId(socket, false),
                        Sockets.getId(socket, true));
                Sockets.close(socket);
            } catch (Exception ex) {
                Logger.error(ex, "SocketPool clear");
            }
        }
        pool.clear();
    }
}
