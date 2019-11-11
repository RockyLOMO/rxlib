package org.rx.socks;

import com.google.common.base.Stopwatch;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.NQuery;
import org.rx.core.WeakCache;
import org.rx.core.Lazy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.rx.core.Contract.as;
import static org.rx.core.Contract.isNull;
import static org.rx.core.Contract.require;

@Slf4j
public final class PingClient {
    public static class Result {
        @Getter
        private final List<Long> results;
        private Lazy<Double> avg;
        private Lazy<Long> min, max;

        public int getLossCount() {
            return (int) results.stream().filter(Objects::isNull).count();
        }

        public double getAvg() {
            return avg.getValue();
        }

        public long getMin() {
            return min.getValue();
        }

        public long getMax() {
            return max.getValue();
        }

        private Result(List<Long> results) {
            this.results = results;
            avg = new Lazy<>(() -> results.stream().mapToDouble(p -> isNull(p, 0L)).average().getAsDouble());
            min = new Lazy<>(() -> results.stream().filter(Objects::nonNull).mapToLong(p -> p).min().orElse(-1));
            max = new Lazy<>(() -> results.stream().filter(Objects::nonNull).mapToLong(p -> p).max().orElse(-1));
        }
    }

    public static boolean test(String endpoint) {
        return test(endpoint, null, false);
    }

    public static boolean test(String endpoint, Consumer<String> onOk, boolean cacheResult) {
        require(endpoint);

        Consumer<Boolean> consumer = null;
        if (cacheResult) {
            WeakCache<String, Object> cache = WeakCache.getInstance();
            String k = String.format("_PingClient%s", endpoint);
            Boolean result = as(cache.get(k), Boolean.class);
            if (result != null) {
                return result;
            }
            consumer = p -> cache.add(k, p);
        }
        boolean ok = new PingClient().ping(endpoint).getLossCount() == 0;
        if (consumer != null) {
            consumer.accept(ok);
        }
        if (ok && onOk != null) {
            onOk.accept(endpoint);
        }
        return ok;
    }

    @Getter
    @Setter
    private int connectTimeout = 10000;
    private final Stopwatch watcher = Stopwatch.createUnstarted();

    public Result ping(String endpoint) {
        return ping(Sockets.parseEndpoint(endpoint), 4);
    }

    public Result ping(InetSocketAddress endpoint, int times) {
        require(endpoint);

        return new Result(NQuery.of(new int[times]).select(p -> {
            Socket sock = new Socket();
            try {
                watcher.start();
                sock.connect(endpoint, connectTimeout);
            } catch (IOException ex) {
                log.info("Ping error {}", ex.getMessage());
                return null;
            } finally {
                watcher.stop();
                Sockets.closeOnFlushed(sock);
            }
            return watcher.elapsed(TimeUnit.MILLISECONDS);
        }).toList());
    }
}
