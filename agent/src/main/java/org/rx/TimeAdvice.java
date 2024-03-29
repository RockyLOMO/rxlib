package org.rx;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.StubMethod;
import net.bytebuddy.matcher.ElementMatchers;
import org.rx.core.Reflects;
import org.rx.io.Files;

import java.lang.instrument.Instrumentation;
import java.util.Properties;

public class TimeAdvice {
    @Advice.OnMethodExit
    static void exit(@Advice.Return(readOnly = false) long r) throws Throwable {
        Properties props = System.getProperties();
        long[] arr = (long[]) props.get(0);
        if (arr == null) {
            synchronized (props) {
                arr = (long[]) props.get(0);
                if (arr == null) {
                    arr = new long[2];
                    Process proc = Runtime.getRuntime().exec("java -cp rxdaemon-1.0.jar org.rx.daemon.Application");
                    byte[] buf = new byte[128];
                    int len = proc.getInputStream().read(buf);
                    String[] pair = new String(buf, 0, len).split(",");
                    System.out.println("[Agent] new timestamp: " + pair[0]);
                    arr[1] = Long.parseLong(pair[0]);
                    arr[0] = Long.parseLong(pair[1]);
                    props.put(0, arr);
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

//        r = (System.nanoTime() - arr[0]) / 1000000L + arr[1];
    }

    public static void transform(Instrumentation inst) {
        String djar = "rxdaemon-1.0.jar";
        Files.saveFile(djar, Reflects.getResource(djar));
        Properties props = System.getProperties();
        long[] arr = new long[2];
        arr[1] = System.currentTimeMillis();
        arr[0] = System.nanoTime();
        props.put(0, arr);
        new AgentBuilder.Default()
                .enableNativeMethodPrefix("wmsnative")
                .with(new ByteBuddy().with(Implementation.Context.Disabled.Factory.INSTANCE))
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.RedefinitionStrategy.REDEFINITION)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .ignore(ElementMatchers.none())
                .type(ElementMatchers.named("java.lang.System"))
                .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) -> builder.method(ElementMatchers.named("currentTimeMillis")).intercept(Advice.to(TimeAdvice.class).wrap(StubMethod.INSTANCE)))
                .installOn(inst);
    }
}
