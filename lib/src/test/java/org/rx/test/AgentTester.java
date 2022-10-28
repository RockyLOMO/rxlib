package org.rx.test;

import org.junit.jupiter.api.Test;
import org.rx.bean.DateTime;
import org.rx.core.NtpClock;

import java.util.Date;

public class AgentTester extends AbstractTester {
    int total = 1000;

    @Test
    public void originTime() {
        System.out.println(System.currentTimeMillis());
        invoke("origin", i -> System.currentTimeMillis(), total);
    }

    @Test
    public void agentTime() {
        System.out.println(System.currentTimeMillis());
        invoke("agent", i -> System.currentTimeMillis(), total);
    }

    public static void main(String[] args) {
//        Agent.main();
        System.out.println(System.currentTimeMillis());
        System.out.println(DateTime.now().toString());
        NtpClock.sync();
        System.out.println(NtpClock.UTC.millis());
        System.out.println(new DateTime(NtpClock.UTC.millis()).toString());
    }
}
