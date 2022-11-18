package org.rx.core;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.StubMethod;
import net.bytebuddy.matcher.ElementMatchers;
import org.rx.io.Files;
import org.rx.third.apache.ntp.NTPUDPClient;
import org.rx.third.apache.ntp.TimeInfo;

import java.io.Serializable;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Properties;

import static org.rx.core.Extends.*;

@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(callSuper = false)
public class NtpClock extends Clock implements Serializable {
    private static final long serialVersionUID = -242102888494125L;

    public static class TimeAdvice {
        //    static final int SHARE_KEY = 0;
        static final String SHARE_KEY = "";
        static boolean transformed;

        @Advice.OnMethodExit
        static void exit(@Advice.Return(readOnly = false) long r) throws Throwable {
//        final int k = 0;
            final String k = "";
            Properties props = System.getProperties();
            long[] arr = (long[]) props.get(k);
            if (arr == null) {
                synchronized (props) {
                    arr = (long[]) props.get(k);
                    if (arr == null) {
                        arr = new long[2];
                        Process proc = Runtime.getRuntime().exec("java -cp rxdaemon-1.0.jar org.rx.daemon.Application");
                        byte[] buf = new byte[128];
                        int len = proc.getInputStream().read(buf);
                        String[] pair = new String(buf, 0, len).split(",");
                        System.out.println("[Agent] new timestamp: " + pair[0]);
                        arr[1] = Long.parseLong(pair[0]);
                        arr[0] = Long.parseLong(pair[1]);
                        props.put(k, arr);
                    }
                }
            }
            long x = System.nanoTime() - arr[0];
            long y = 1000000L;
            if (x <= y) {
                r = arr[1];
                return;
            }
            r = x / y + arr[1];
        }

        public synchronized static void transform() {
            if (transformed) {
                return;
            }
            transformed = true;
            String djar = "rxdaemon-1.0.jar";
            Files.saveFile(djar, Reflects.getResource(djar));
            Properties props = System.getProperties();
            long[] arr = new long[2];
            arr[1] = System.currentTimeMillis();
            arr[0] = System.nanoTime();
            props.put(SHARE_KEY, arr);
            new AgentBuilder.Default()
                    .enableNativeMethodPrefix("wmsnative")
                    .with(new ByteBuddy().with(Implementation.Context.Disabled.Factory.INSTANCE))
                    .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                    .with(AgentBuilder.RedefinitionStrategy.REDEFINITION)
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .ignore(ElementMatchers.none())
                    .type(ElementMatchers.named("java.lang.System"))
                    .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) -> builder.method(ElementMatchers.named("currentTimeMillis")).intercept(Advice.to(TimeAdvice.class).wrap(StubMethod.INSTANCE)))
                    .installOn(ByteBuddyAgent.install());
        }
    }

    public static final NtpClock UTC = new NtpClock(ZoneOffset.UTC);
    static long offset;
    static boolean injected;

    public static void scheduleTask() {
        Tasks.timer.setTimeout(NtpClock::sync, d -> RxConfig.INSTANCE.net.ntp.syncPeriod, NtpClock.class, TimeoutFlag.SINGLE.flags(TimeoutFlag.PERIOD));
    }

    @SneakyThrows
    public static void sync() {
        NTPUDPClient client = new NTPUDPClient();
        RxConfig.NtpConfig conf = RxConfig.INSTANCE.net.ntp;
        client.setDefaultTimeout((int) conf.timeoutMillis);
        client.open();
        eachQuietly(conf.servers, p -> {
            final TimeInfo info = client.getTime(InetAddress.getByName(p));
            info.computeDetails();
            offset = ifNull(info.getOffset(), 0L);
            log.debug("ntp sync with {} -> {}", p, offset);
            long[] tsAgent = (long[]) System.getProperties().get(TimeAdvice.SHARE_KEY);
            if (injected = tsAgent != null) {
                tsAgent[1] += offset;
                log.debug("ntp inject offset {}", offset);
            }
            circuitContinue(false);
        });
        client.close();
    }

    @Getter
    final ZoneId zone;

    @Override
    public Clock withZone(ZoneId zone) {
        if (zone.equals(this.zone)) {
            return this;
        }
        return new NtpClock(zone);
    }

    @Override
    public long millis() {
        long b = System.currentTimeMillis();
        return injected ? b : b + offset;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis());
    }
}
