package org.rx.socket;

import org.rx.Logger;
import org.rx.SystemException;
import org.rx.cache.BytesSegment;
import org.rx.util.IOStream;

import java.io.IOException;
import java.net.Socket;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static org.rx.Contract.as;
import static org.rx.Contract.require;

public final class NetworkStream extends IOStream {
    private final Socket       socket;
    private final BytesSegment segment;

    public Socket getSocket() {
        return socket;
    }

    public BytesSegment getSegment() {
        return segment;
    }

    public NetworkStream(Socket socket, BytesSegment segment) throws IOException {
        super(socket.getInputStream(), socket.getOutputStream());

        this.socket = socket;
        this.segment = segment;
    }

    @Override
    public void close() {
        super.close();
        try {
            socket.close();
        } catch (IOException e) {
            throw new SystemException(e);
        }
        segment.close();
    }

    @Override
    public int read(byte[] buffer, int offset, int count) {
        try {
            return super.read(buffer, offset, count);
        } catch (Exception ex) {
            java.net.SocketException sockEx = as(ex, java.net.SocketException.class);
            if (sockEx != null) {
                String msg = sockEx.getMessage();
                if (msg != null && msg.toLowerCase().contains("connection reset")) {
                    Logger.error(sockEx, "NetworkStream onReceive read 0");
                    return 0;
                }
            }
            throw ex;
        }
    }

    public int directTo(NetworkStream to, Predicate<NetworkStream> isConnected,
                        BiPredicate<BytesSegment, Integer> onEach) {
        require(to);

        int recv = -1;
        while ((isConnected == null || isConnected.test(this))
                && (recv = super.read(segment.array, segment.offset, segment.count)) >= 0) {
            if (recv == 0) {
                break;
            }
            if (!(isConnected == null || isConnected.test(this))) {
                break;
            }
            to.write(segment.array, segment.offset, recv);
            if (onEach != null && !onEach.test(segment, recv)) {
                break;
            }
        }
        to.flush();
        return recv;
    }
}
