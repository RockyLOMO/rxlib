package org.rx.socks;

import lombok.extern.slf4j.Slf4j;
import org.rx.cache.BytesSegment;
import org.rx.io.IOStream;

import java.io.IOException;
import java.net.Socket;

import static org.rx.core.Contract.require;
import static org.rx.socks.Sockets.shutdown;

@Slf4j
public final class NetworkStream extends IOStream {
    @FunctionalInterface
    public interface DirectPredicate {
        boolean test(BytesSegment buffer, int count);
    }

    public static final int SocketEOF = 0;
    public static final int StreamEOF = -1;
    public static final int CannotWrite = -2;
    private final boolean ownsSocket;
    private final Socket socket;
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
    protected void freeObjects() {
        try {
            log.info("NetworkStream freeObjects ownsSocket={} socket[{}][closed={}]", ownsSocket,
                    Sockets.getId(socket, false), socket.isClosed());
            if (ownsSocket) {
                //super.freeObjects(); Ignore this!!
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
                    log.debug("DirectTo read {} flag and shutdown send", recv);
                    shutdown(socket, 1);
                }
                break;
            }

            if (!to.canWrite()) {
                log.debug("DirectTo read {} bytes and can't write", recv);
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
