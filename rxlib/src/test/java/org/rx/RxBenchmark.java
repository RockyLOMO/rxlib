package org.rx;

import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import org.rx.io.Bytes;

import java.util.concurrent.TimeUnit;

@Threads(Threads.MAX)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class RxBenchmark {
    @Setup
    public void setup() {
        System.out.println("init");
    }

    @Benchmark
    @Test
    public void a() {
        System.out.println(Bytes.readableByteSize(16));
        Bytes.readableByteSize(1024);
        Bytes.readableByteSize(1024 * 1024);
    }

    @Benchmark
    public void b() {

    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
//                .include(RxBenchmark.class.getSimpleName())
                .warmupIterations(2)
                .warmupTime(TimeValue.seconds(5))
                // 正式计量测试标注@Benchmark的代码，
                .measurementIterations(2)
                .measurementTime(TimeValue.seconds(5))
                // forks(3)指做3轮测试，每轮都是先预热，再正式计量。
                .forks(2)
                .output("./benchmark.log")
                .build();
        new Runner(opt).run();
    }
}
