package org.rx.net;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Constants;

import java.io.IOException;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class PingClient {
    @Getter
    public static class Result implements Serializable {
        private static final long serialVersionUID = 2269812764611557352L;
        private final long[] values;
        private final int lossCount;
        private final double avg;
        private final long min;
        private final long max;

        private Result(long[] values) {
            this.values = values;
            int nullVal = Constants.IO_EOF;
            lossCount = (int) Arrays.stream(values).filter(p -> p == nullVal).count();
            avg = Arrays.stream(values).mapToDouble(p -> p == nullVal ? 0L : p).average().getAsDouble();
            min = Arrays.stream(values).filter(p -> p != nullVal).min().orElse(nullVal);
            max = Arrays.stream(values).filter(p -> p != nullVal).max().orElse(nullVal);
        }
    }

    @Getter
    @Setter
    private int times = 4;
    @Getter
    @Setter
    private int timeoutSeconds = 5;

    public Result ping(String endpoint) {
        return ping(Sockets.parseEndpoint(endpoint));
    }

    public Result ping(InetSocketAddress endpoint) {
        return ping(endpoint, times);
    }

    public Result ping(@NonNull InetSocketAddress endpoint, int times) {
        long[] value = new long[times];
        for (int i = 0; i < value.length; i++) {
            long startTime;
            Socket sock = new Socket();
            try {
                startTime = System.nanoTime();
                sock.connect(endpoint, timeoutSeconds * 1000);
            } catch (IOException e) {
                log.info("Ping error {}", e.toString());
                value[i] = Constants.IO_EOF;
                continue;
            } finally {
                Sockets.closeOnFlushed(sock);
            }
            value[i] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
        }
        return new Result(value);
    }

    public boolean isReachable(String host) {
        try {
            return isReachable(InetAddress.getByName(host));
        } catch (UnknownHostException e) {
            log.warn("isReachable {}", e.getMessage());
            return false;
        }
    }

    @SneakyThrows
    public boolean isReachable(InetAddress address) {
        return address.isReachable(timeoutSeconds * 1000);
    }
}
