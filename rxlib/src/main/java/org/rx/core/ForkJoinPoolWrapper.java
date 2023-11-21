package org.rx.core;

import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatchers;
import org.rx.exception.InvalidException;

import java.util.Properties;
import java.util.concurrent.*;
import java.util.function.Function;

@Slf4j
public class ForkJoinPoolWrapper {
    static class TaskAdvice {
        @Advice.OnMethodEnter
        public static void enter(@Advice.AllArguments(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object[] arguments) throws Throwable {
            final String sk = "";
            final int sl = 2, idx = 1;
            Properties props = System.getProperties();
            Object v = props.get(sk);
            Object[] share = null;
            Function<Object, Object> fn;
            if (!(v instanceof Object[]) || (share = (Object[]) v).length != sl
                    || (fn = (Function<Object, Object>) share[idx]) == null) {
                System.err.println("TaskAdvice empty fn");
                synchronized (props) {
                    v = props.get(sk);
                    if (!(v instanceof Object[]) || (share = (Object[]) v).length != sl
                            || (fn = (Function<Object, Object>) share[idx]) == null) {
                        try {
                            final String t = "org.rx.core.ForkJoinPoolWrapper";
                            final String f = "ADVICE_FN";
                            fn = (Function<Object, Object>) ClassLoader.getSystemClassLoader()
                                    .loadClass(t).getDeclaredField(f).get(null);

                            boolean changed = share == null;
                            if (changed) {
                                share = new Object[sl];
                            }
                            share[idx] = fn;
                            if (changed) {
                                props.put(sk, share);
                            }
                            System.out.println("TaskAdvice get fn");
                        } catch (Throwable e) {
                            e.printStackTrace();
                            return;
                        }
                    }
                }
            }

            Object task = arguments[0];
            Object[] newArgs = new Object[1];
            newArgs[0] = fn.apply(task);
            arguments = newArgs;
        }
    }

    public synchronized static void transform() {
        final byte flag = 2;
        if ((Sys.transformedFlags & flag) == flag) {
            return;
        }
        Sys.transformedFlags |= flag;
        Sys.checkAdviceShare(true);
        ByteBuddyAgent.install();
        new ByteBuddy()
                .redefine(ForkJoinPool.class)
                .visit(Advice.to(TaskAdvice.class).on(ElementMatchers.named("externalPush")))
                .make()
                .load(ClassLoader.getSystemClassLoader(), ClassReloadingStrategy.fromInstalledAgent());
    }

    public static final Function<Object, Object> ADVICE_FN = task -> {
        if (task instanceof ForkJoinTask) {
            return wrap((ForkJoinTask<?>) task);
        }
        if (task instanceof Runnable) {
            return wrap((Runnable) task);
        }
        if (task instanceof Callable) {
            return wrap((Callable<?>) task);
        }
        throw new InvalidException("Error task type {}", task);
    };

    static Runnable wrap(Runnable task) {
        String traceId = ThreadPool.CTX_TRACE_ID.get();
//        log.info("wrap Runnable {}", traceId);
        return () -> {
            ThreadPool.startTrace(traceId);
            try {
                task.run();
            } finally {
                ThreadPool.endTrace();
            }
        };
    }

    static <T> Callable<T> wrap(Callable<T> task) {
        String traceId = ThreadPool.CTX_TRACE_ID.get();
//        log.info("wrap Callable {}", traceId);
        return () -> {
            ThreadPool.startTrace(traceId);
            try {
                return task.call();
            } finally {
                ThreadPool.endTrace();
            }
        };
    }

    static <T> ForkJoinTask<T> wrap(ForkJoinTask<T> task) {
        String traceId = ThreadPool.CTX_TRACE_ID.get();
//        log.info("wrap ForkJoinTask {}", traceId);
        return ForkJoinTask.adapt(() -> {
            ThreadPool.startTrace(traceId);
//            String tid = ThreadPool.startTrace(traceId);
//            log.info("wrap ForkJoinTask fn {}", tid);
            try {
                //join() will hang
                return task.invoke();
            } finally {
                ThreadPool.endTrace();
            }
        });
    }
}
