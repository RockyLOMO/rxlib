package org.rx;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.StubMethod;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.util.Properties;

public class TimeAdvice {
    @Advice.OnMethodExit
    static void exit(@Advice.Return(readOnly = false) long x) throws Throwable {
        Properties props = System.getProperties();
        long[] arr = (long[]) props.get(-1);
        x = Math.floorDiv(System.nanoTime() - arr[0], 1000000L) + arr[1];
//        x = (System.nanoTime() - arr[0]) / 1000000L + arr[1];
    }

    public static void transform(Instrumentation inst) {
        Properties props = System.getProperties();
        long[] arr = new long[2];
        arr[1] = System.currentTimeMillis();
        arr[0] = System.nanoTime();
        props.put(-1, arr);
        new AgentBuilder.Default()
                .enableNativeMethodPrefix("wmsnative")
                .with(new ByteBuddy().with(Implementation.Context.Disabled.Factory.INSTANCE))
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.RedefinitionStrategy.REDEFINITION)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .ignore(ElementMatchers.none())
                .type(ElementMatchers.named("java.lang.System"))
                .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) -> builder
                        .method(ElementMatchers.named("currentTimeMillis"))
                        .intercept(Advice.to(TimeAdvice.class).wrap(StubMethod.INSTANCE))
                )
                .installOn(inst);
    }
}
