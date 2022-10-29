package org.rx.test;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.rx.bean.DateTime;
import org.rx.core.NtpClock;
import org.rx.core.TimeAdvice;

import java.util.Date;

public class AgentTester extends AbstractTester {
    int total = 10000;
    int time = 5;

    @Test
    public void originTime() {
        System.out.println(System.currentTimeMillis());
        for (int i = 0; i < time; i++) {
            invoke("origin", x -> System.currentTimeMillis(), total);
        }
    }

    @Test
    public void agentTime() {
        System.out.println(System.currentTimeMillis());
        for (int i = 0; i < time; i++) {
            invoke("agent", x -> System.currentTimeMillis(), total);
        }
    }

    @Test
    public void agentTime2() {
        TimeAdvice.transform();
        System.out.println(System.currentTimeMillis());
        for (int i = 0; i < time; i++) {
            invoke("agent2", x -> System.currentTimeMillis(), total);
        }
    }

    @SneakyThrows
    public static void main(String[] args) {
        long ts = System.currentTimeMillis();
        System.out.println(ts);
        System.out.println(new DateTime(ts));

        //inject
        TimeAdvice.transform();
        NtpClock.sync();
        ts = System.currentTimeMillis();
        System.out.println(ts);
        ts = NtpClock.UTC.millis();
        System.out.println(ts);
        System.out.println(new DateTime(ts));
    }
}
