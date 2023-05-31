package org.rx.core;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.*;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.Test;
import org.rx.AbstractTester;
import org.rx.bean.DateTime;
import org.slf4j.MDC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.function.Function;
import java.util.jar.JarFile;

@Slf4j
public class TestAgent extends AbstractTester {
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
        NtpClock.TimeAdvice.transform();
        System.out.println(System.currentTimeMillis());
        for (int i = 0; i < time; i++) {
            invoke("agent2", x -> System.currentTimeMillis(), total);
        }
    }

    static void ntp() {
        long ts = System.currentTimeMillis();
        System.out.println(ts);
        System.out.println(new DateTime(ts));

        //inject
        NtpClock.TimeAdvice.transform();
        NtpClock.sync();
        ts = System.currentTimeMillis();
        System.out.println(ts);
        ts = NtpClock.UTC.millis();
        System.out.println(ts);
        System.out.println(new DateTime(ts));
    }

    static void fjp() throws Throwable {
        ThreadPool.onTraceIdChanged.combine((s, e) -> MDC.put("rx-traceId", e.getValue()));

        ForkJoinPoolWrapper.TaskAdvice.transform();
        ForkJoinPool pool = ForkJoinPool.commonPool();
        ThreadPool.startTrace(null);
        pool.execute(() -> {
            log.info("async task");
        });

        Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9).parallelStream().forEach(p -> log.info("async {}", p));
        Thread.sleep(4000);
    }

    public static void main(String[] args) throws Throwable {
        ntp();
        fjp();

//        Instrumentation instrumentation = ByteBuddyAgent.install();
//        String jar = "D:\\projs\\rxlib\\rxlib\\target\\rxlib-2.19.5-SNAPSHOT.jar";
////        install.appendToBootstrapClassLoaderSearch(new JarFile(jar));
////        install.appendToSystemClassLoaderSearch(new JarFile(jar));
////        ElementMatcher.Junction<NamedElement> fjp = ElementMatchers.named("java.util.concurrent.ForkJoinPool");
////        ElementMatcher.Junction<TypeDefinition> fjp = ElementMatchers.is(ForkJoinPool.class);
//        ElementMatcher.Junction<NamedElement> externalPush = ElementMatchers.named("externalPush");
//
////        TypePool typePool = TypePool.Default.ofSystemLoader();
//        new ByteBuddy()
//                .redefine(ForkJoinPool.class)
////                .redefine(typePool.describe("java.util.concurrent.ForkJoinPool").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader())
////                .method(externalPush).intercept(MethodDelegation.to(TimingInterceptor.class))
//                .visit(Advice.to(BootstrapAdvice.class).on(externalPush))
//                .make()
//                .load(ClassLoader.getSystemClassLoader(), ClassReloadingStrategy.fromInstalledAgent());
//
////        new AgentBuilder.Default()
//////                .disableClassFormatChanges()
////                .with(AgentBuilder.Listener.StreamWriting.toSystemOut().withErrorsOnly())
////                .with(AgentBuilder.InstallationListener.StreamWriting.toSystemOut())
////                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
////                .with(AgentBuilder.RedefinitionStrategy.Listener.StreamWriting.toSystemOut())
//////                .ignore(ElementMatchers.nameStartsWith("net.bytebuddy."))
////                .type(fjp)
////                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> builder
//////                        .method(externalPush).intercept(MethodDelegation.to(TimingInterceptor.class))
//////                                .method(externalPush).intercept(Advice.to(BootstrapAdvice.class))
////                                .visit(Advice.to(BootstrapAdvice.class).on(externalPush))
////                )
////                .installOn(instrumentation);
//
////        Arrays.parallelSort();
    }

    public static class BootstrapAdvice {
        @Advice.OnMethodEnter
        public static void enter(
//                @Advice.This Object self,
//                @Advice.Origin Method method,
                @Advice.AllArguments(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object[] arguments
        ) throws Exception {
            System.out.println("Advice enter");
            Object task = arguments[0];

//            String tn = "org.rx.core.ForkJoinPoolWrapper";
////            Class<?> t = Class.forName("org.rx.core.ForkJoinPoolWrapper");
////            Class<?> t = Thread.currentThread().getContextClassLoader().loadClass(tn);
//            Class<?> t = ClassLoader.getSystemClassLoader().loadClass(tn);
//            Method m = t.getDeclaredMethod("wrap", Object.class);
            Function<Object, Object> fn = (Function<Object, Object>) System.getProperties().get("_x");
            if (fn == null) {
                System.err.println("Advice empty fn");
                return;
            }

            Object[] newArgs = new Object[1];
//            newArgs[0] = task;
//            Object r = m.invoke(null, newArgs);
//            System.out.printf("Advice adapt %s(%s) -> %s%n", m, task, r);
            Object r = fn.apply(task);
            System.out.printf("Advice adapt %s(%s) -> %s%n", fn, task, r);

            newArgs[0] = r;
            arguments = newArgs;
        }
    }
}
