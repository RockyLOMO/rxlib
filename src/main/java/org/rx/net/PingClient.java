package org.rx.net;

import com.google.common.base.Stopwatch;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.util.function.BiAction;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.rx.core.Contract.require;

@Slf4j
public final class PingClient {
    @Getter
    public static class Result {
        private static final long nullVal = -1L;
        private final long[] values;
        private int lossCount;
        private double avg;
        private long min, max;

        private Result(long[] values) {
            this.values = values;
            lossCount = (int) Arrays.stream(values).filter(p -> p == nullVal).count();
            avg = Arrays.stream(values).mapToDouble(p -> p == nullVal ? 0L : p).average().getAsDouble();
            min = Arrays.stream(values).filter(p -> p != nullVal).min().orElse(nullVal);
            max = Arrays.stream(values).filter(p -> p != nullVal).max().orElse(nullVal);
        }
    }

    public static boolean test(String endpoint) {
        return test(endpoint, null);
    }

    @SneakyThrows
    public static boolean test(String endpoint, BiAction<Result> onOk) {
        require(endpoint);

        Result result = new PingClient().ping(endpoint);
        boolean ok = result.getLossCount() == 0;
        if (ok && onOk != null) {
            onOk.invoke(result);
        }
        return ok;
    }

    @Getter
    @Setter
    private int connectTimeoutSeconds = 8;
    private final Stopwatch watcher = Stopwatch.createUnstarted();

    public Result ping(String endpoint) {
        return ping(Sockets.parseEndpoint(endpoint), 4);
    }

    public Result ping(InetSocketAddress endpoint, int times) {
        require(endpoint);

        long[] value = new long[times];
        for (int i = 0; i < value.length; i++) {
            Socket sock = new Socket();
            try {
                watcher.start();
                sock.connect(endpoint, connectTimeoutSeconds * 1000);
            } catch (IOException ex) {
                log.info("Ping error {}", ex.getMessage());
                value[i] = Result.nullVal;
                continue;
            } finally {
                watcher.stop();
                Sockets.closeOnFlushed(sock);
            }
            value[i] = watcher.elapsed(TimeUnit.MILLISECONDS);
        }
        return new Result(value);
    }
}
