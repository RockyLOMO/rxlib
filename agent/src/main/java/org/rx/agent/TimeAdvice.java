package org.rx.agent;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.StubMethod;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.time.Clock;

public class TimeAdvice {
    @Advice.OnMethodExit
    static void exit(@Advice.Return(readOnly = false) long x) throws Throwable {
        final String klsName = "org.rx.AgentHolder",
                clockName = "a";
        Class<?> cls = Thread.currentThread().getContextClassLoader().loadClass(klsName);
        Clock clock = (Clock) cls.getDeclaredField(clockName).get(null);
        x = clock.millis();
    }

    public static void transform(Instrumentation inst) {
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
