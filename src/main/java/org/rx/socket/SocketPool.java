package org.rx.socket;

import org.rx.common.DateTime;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.rx.common.Contract.require;
import static org.rx.util.App.logError;

public final class SocketPool extends Traceable implements AutoCloseable {
    public static final class PooledSocket implements AutoCloseable {
        private final SocketPool owner;
        private DateTime         lastActive;
        public final Socket      socket;

        public boolean isConnected() {
            return !owner.isClosed && socket.isConnected() && !socket.isClosed();
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
            lastActive = new DateTime();
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
    private volatile boolean                                                                isClosed;
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
        Tracer tracer = new Tracer();
        tracer.setPrefix(n + " ");
        setTracer(tracer);
    }

    @Override
    public synchronized void close() {
        if (isClosed) {
            return;
        }

        clear();
        isClosed = true;
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
        getTracer().writeLine("%s runTimer..", getTimeString());
    }

    private void clearIdleSockets() {
        for (InetSocketAddress socketAddress : pool.keySet()) {
            ConcurrentLinkedDeque<PooledSocket> sockets = pool.get(socketAddress);
            if (sockets == null) {
                continue;
            }

            for (PooledSocket socket : sockets) {
                if (!socket.isConnected()
                        || new DateTime().subtract(socket.getLastActive()).getTotalMilliseconds() >= maxIdleMillis) {
                    sockets.remove(socket);
                    getTracer().writeLine("%s clearIdleSocket[Local=%s, Remote=%s]..", getTimeString(),
                            socket.socket.getLocalSocketAddress(), socket.socket.getRemoteSocketAddress());
                }
            }
            if (sockets.size() == 0) {
                pool.remove(socketAddress);
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
        getTracer().writeLine("%s stopTimer..", getTimeString());
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
        require(this, !isClosed);

        ConcurrentLinkedDeque<PooledSocket> sockets = getSockets(remoteAddr);
        PooledSocket pooledSocket;
        if ((pooledSocket = sockets.peekFirst()) == null) {
            Socket sock = new Socket();
            try {
                sock.connect(remoteAddr, connectTimeout);
            } catch (IOException ex) {
                throw new SocketException(remoteAddr, ex);
            }
            pooledSocket = new PooledSocket(this, sock);
        }
        if (!pooledSocket.isConnected()) {
            return borrowSocket(remoteAddr);
        }
        Socket sock = pooledSocket.socket;
        getTracer().writeLine("%s borrowSocket[Local=%s, Remote=%s]..", getTimeString(), sock.getLocalSocketAddress(),
                sock.getRemoteSocketAddress());
        return pooledSocket;
    }

    public void returnSocket(PooledSocket pooledSocket) {
        require(this, !isClosed);
        if (!pooledSocket.isConnected()) {
            return;
        }

        pooledSocket.setLastActive(new DateTime());
        ConcurrentLinkedDeque<PooledSocket> sockets = getSockets(
                (InetSocketAddress) pooledSocket.socket.getRemoteSocketAddress());
        if (sockets.size() >= maxSocketsCount) {
            return;
        }
        sockets.addFirst(pooledSocket);
        Socket sock = pooledSocket.socket;
        getTracer().writeLine("%s returnSocket[Local=%s, Remote=%s]..", getTimeString(), sock.getLocalSocketAddress(),
                sock.getRemoteSocketAddress());
    }

    public void clear() {
        require(this, !isClosed);

        pool.values().stream().flatMap(ConcurrentLinkedDeque::stream).map(p -> p.socket).forEach(p -> {
            try {
                getTracer().writeLine("%s clearSocket[Local=%s, Remote=%s]..", getTimeString(),
                        p.getLocalSocketAddress(), p.getRemoteSocketAddress());
                p.close();
            } catch (IOException ex) {
                logError(ex, "SocketPool.close()");
            }
        });
        pool.clear();
    }
}
