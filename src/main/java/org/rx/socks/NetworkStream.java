package org.rx.socks;

import org.rx.Logger;
import org.rx.cache.BytesSegment;
import org.rx.util.IOStream;

import java.io.IOException;
import java.net.Socket;

import static org.rx.Contract.require;
import static org.rx.socks.Sockets.shutdown;

public final class NetworkStream extends IOStream {
    @FunctionalInterface
    public interface DirectPredicate {
        boolean test(BytesSegment buffer, int count);
    }

    public static final int    SocketEOF   = 0;
    public static final int    StreamEOF   = -1;
    public static final int    CannotWrite = -2;
    private final boolean      ownsSocket;
    private final Socket       socket;
    private final BytesSegment segment;

    public boolean isConnected() {
        return !isClosed() && !socket.isClosed() && socket.isConnected();
    }

    @Override
    public boolean canRead() {
        return super.canRead() && checkSocket(socket, false);
    }

    @Override
    public boolean canWrite() {
        return super.canWrite() && checkSocket(socket, true);
    }

    private static boolean checkSocket(Socket sock, boolean isWrite) {
        return !sock.isClosed() && sock.isConnected() && !(isWrite ? sock.isOutputShutdown() : sock.isInputShutdown());
    }

    public Socket getSocket() {
        return socket;
    }

    public BytesSegment getSegment() {
        return segment;
    }

    public NetworkStream(Socket socket, BytesSegment segment) throws IOException {
        this(socket, segment, true);
    }

    public NetworkStream(Socket socket, BytesSegment segment, boolean ownsSocket) throws IOException {
        super(socket.getInputStream(), socket.getOutputStream());

        this.ownsSocket = ownsSocket;
        this.socket = socket;
        this.segment = segment;
    }

    @Override
    protected void freeUnmanaged() {
        try {
            Logger.info("NetworkStream freeUnmanaged ownsSocket=%s socket[%s][closed=%s]", ownsSocket,
                    Sockets.getId(socket, false), socket.isClosed());
            if (ownsSocket) {
                //super.freeUnmanaged(); Ignore this!!
                Sockets.close(socket, 1);
            }
        } finally {
            segment.close();
        }
    }

    int readSegment() {
        return read(segment.array, segment.offset, segment.count);
    }

    void writeSegment(int count) {
        write(segment.array, segment.offset, count);
    }

    public int directTo(NetworkStream to, DirectPredicate onEach) {
        checkNotClosed();
        require(to);

        int recv = StreamEOF;
        while (canRead() && (recv = read(segment.array, segment.offset, segment.count)) >= -1) {
            if (recv <= 0) {
                if (ownsSocket) {
                    Logger.debug("DirectTo read %s flag and shutdown send", recv);
                    shutdown(socket, 1);
                }
                break;
            }

            if (!to.canWrite()) {
                Logger.debug("DirectTo read %s bytes and can't write", recv);
                recv = CannotWrite;
                break;
            }
            to.write(segment.array, segment.offset, recv);

            if (onEach != null && !onEach.test(segment, recv)) {
                recv = StreamEOF;
                break;
            }
        }
        if (to.canWrite()) {
            to.flush();
        }
        return recv;
    }
}
