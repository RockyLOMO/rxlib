package org.rx.socket;

import com.google.common.base.Stopwatch;
import org.rx.common.Lazy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.rx.common.Contract.isNull;
import static org.rx.common.Contract.require;
import static org.rx.util.App.logError;

public final class PingClient {
    public class Result {
        private final List<Long> results;
        private Lazy<Double>     avg;
        private Lazy<Long>       min, max;

        public List<Long> getResults() {
            return results;
        }

        public int getLossCount() {
            return (int) results.stream().filter(p -> p == null).count();
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
            min = new Lazy<>(() -> results.stream().filter(p -> p != null).mapToLong(p -> p).min().orElse(-1));
            max = new Lazy<>(() -> results.stream().filter(p -> p != null).mapToLong(p -> p).max().orElse(-1));
        }
    }

    public static boolean test(String endpoint) {
        try {
            return new PingClient().ping(endpoint).getLossCount() == 0;
        } catch (Exception ex) {
            logError(ex, "PingClient.test()");
            return false;
        }
    }

    private Stopwatch watcher;

    public PingClient() {
        watcher = Stopwatch.createUnstarted();
    }

    public Result ping(String endpoint) {
        return ping(Sockets.parseAddress(endpoint), 4);
    }

    public Result ping(InetSocketAddress sockAddr, int times) {
        require(sockAddr);

        return new Result(Arrays.stream(new Long[times]).map(p -> {
            Socket sock = new Socket();
            try {
                watcher.start();
                sock.connect(sockAddr);
            } catch (IOException ex) {
                logError(ex, "ping");
                return null;
            } finally {
                watcher.stop();
                try {
                    sock.close();
                } catch (IOException ex) {
                    logError(ex, "PingClient.ping()");
                }
            }
            return watcher.elapsed(TimeUnit.MILLISECONDS);
        }).collect(Collectors.toList()));
    }
}
