package org.rx.core;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.ntp.NTPUDPClient;
import org.rx.net.ntp.TimeInfo;

import java.io.Serializable;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.rx.core.Extends.*;

@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(callSuper = false)
public class NtpClock extends Clock implements Serializable {
    private static final long serialVersionUID = -242102888494125L;
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
