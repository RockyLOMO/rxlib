package org.rx.exception;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.matcher.ElementMatchers;
import org.rx.core.RxConfig;
import org.rx.core.Sys;

@Slf4j
public class LoggingAgent {
    public static class LoggerErrorAdvice {
        //Advice需public
        public static final FastThreadLocal<Boolean> idempotent = new FastThreadLocal<>();

        @Advice.OnMethodEnter
        public static void onEnter(
                @Advice.This Object self,
//                @Advice.Origin String method,
                @Advice.AllArguments Object[] args
        ) {
            int keepDays = RxConfig.INSTANCE.getTrace().getKeepDays();
            if (keepDays <= 0) {
                return;
            }
            if (Boolean.TRUE.equals(idempotent.get())) {
                return;
            }
            try {
                idempotent.set(Boolean.TRUE);
                String format = (String) args[0];
                Object lastArg = args[args.length - 1];
                if (lastArg instanceof Throwable) {
//                    System.out.println(format + ", " + lastArg);
                    TraceHandler.INSTANCE.saveExceptionTrace(Thread.currentThread(), format, (Throwable) lastArg);
                }
            } catch (Throwable e) {
                e.printStackTrace();
            } finally {
                idempotent.remove();
            }
        }
    }

    public static synchronized void transform() {
        final byte flag = 2;
        if ((Sys.transformedFlags & flag) == flag) {
            return;
        }
        Sys.transformedFlags |= flag;
        new AgentBuilder.Default()
//                .with(AgentBuilder.Listener.StreamWriting.toSystemOut())
                .with(new ByteBuddy().with(Implementation.Context.Disabled.Factory.INSTANCE))
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.RedefinitionStrategy.REDEFINITION)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .ignore(ElementMatchers.none())
                .type(ElementMatchers.hasSuperType(ElementMatchers.named("org.slf4j.Logger")))
                .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) -> builder.visit(
                        Advice.to(LoggerErrorAdvice.class)
                                .on(ElementMatchers.named("error"))
                ))
                .installOn(ByteBuddyAgent.install());
    }
}
