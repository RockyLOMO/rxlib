package org.rx.socket;

import org.rx.Logger;
import org.rx.SystemException;
import org.rx.cache.BytesSegment;
import org.rx.util.IOStream;

import java.io.IOException;
import java.net.Socket;
import java.util.function.BiPredicate;

import static org.rx.Contract.require;
import static org.rx.socket.Sockets.shutdown;

public final class NetworkStream extends IOStream {
    private final boolean      ownsSocket;
    private final Socket       socket;
    private final BytesSegment segment;

    public boolean isConnected() {
        return !isClosed() && !socket.isClosed() && socket.isConnected();
    }

    public boolean canRead() {
        return !isClosed() && checkSocket(socket, false);
    }

    public boolean canWrite() {
        return !isClosed() && checkSocket(socket, true);
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
    protected void freeManaged() {
        super.freeManaged();
        try {
            if (ownsSocket && !socket.isClosed()) {
                shutdown(socket, 1);
                socket.setSoLinger(true, 2);
                socket.close();
            }
        } catch (IOException e) {
            throw new SystemException(e);
        } finally {
            segment.close();
        }
    }

    public int directTo(NetworkStream to, BiPredicate<BytesSegment, Integer> onEach) {
        require(to);

        int recv = -1;
        while (canRead() && (recv = read(segment.array, segment.offset, segment.count)) >= 0) {
            if (recv == 0) {
                shutdown(socket, 1);
                break;
            }
            if (to.canWrite()) {
                to.write(segment.array, segment.offset, recv);
            } else {
                Logger.debug("DirectTo read %s bytes and can't write", recv);
            }
            if (onEach != null && !onEach.test(segment, recv)) {
                recv = -1;
                break;
            }
        }
        if (to.canWrite()) {
            to.flush();
        }
        if (recv == -1) {
            close();
        }
        return recv;
    }
}
