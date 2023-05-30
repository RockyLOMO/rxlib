package org.rx.core;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.dynamic.ClassFileLocator;
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
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
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
        Instrumentation install = ByteBuddyAgent.install();
        String jar = "D:\\projs\\rxlib\\rxlib\\target\\rxlib-2.19.5-SNAPSHOT.jar";
//        install.appendToBootstrapClassLoaderSearch(new JarFile(jar));
        install.appendToSystemClassLoaderSearch(new JarFile(jar));
//        ElementMatcher.Junction<NamedElement> fjp = ElementMatchers.named("java.util.concurrent.ForkJoinPool");
//        ElementMatcher.Junction<TypeDefinition> fjp = ElementMatchers.is(ForkJoinPool.class);
        ElementMatcher.Junction<NamedElement> externalPush = ElementMatchers.named("externalPush");

//        TypePool typePool = TypePool.Default.ofSystemLoader();
        new ByteBuddy()
                .redefine(ForkJoinPool.class)
//                .redefine(typePool.describe("java.util.concurrent.ForkJoinPool").resolve(), ClassFileLocator.ForClassLoader.ofSystemLoader())
//                .method(externalPush).intercept(MethodDelegation.to(TimingInterceptor.class))
                .visit(Advice.to(BootstrapAdvice.class).on(externalPush))
                .make()
                .load(ClassLoader.getSystemClassLoader(), ClassReloadingStrategy.fromInstalledAgent());

//        new AgentBuilder.Default()
////                .disableClassFormatChanges()
//                .with(AgentBuilder.Listener.StreamWriting.toSystemOut().withErrorsOnly())
//                .with(AgentBuilder.InstallationListener.StreamWriting.toSystemOut())
//                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
//                .with(AgentBuilder.RedefinitionStrategy.Listener.StreamWriting.toSystemOut())
////                .ignore(ElementMatchers.nameStartsWith("net.bytebuddy."))
//                .type(fjp)
//                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> builder
////                        .method(externalPush).intercept(MethodDelegation.to(TimingInterceptor.class))
////                                .method(externalPush).intercept(Advice.to(BootstrapAdvice.class))
//                                .visit(Advice.to(BootstrapAdvice.class).on(externalPush))
//                )
//                .installOn(instrumentation);

//        Arrays.parallelSort();
        ThreadPool.traceIdGenerator = () -> UUID.randomUUID().toString().replace("-", "");
        ThreadPool.onTraceIdChanged.combine((s, e) -> MDC.put("rx-traceId", e.getValue()));
        ForkJoinPool pool = ForkJoinPool.commonPool();
        ThreadPool.startTrace(null);
        pool.execute(() -> {
            log.info("async task");
        });
        Thread.sleep(5000);
    }

    public static void main(String[] args) throws Throwable {
//        ntp();

        fjp();
//        new AgentBuilder.Default()
////                .enableNativeMethodPrefix("wmsnative")
//                .with(new ByteBuddy().with(Implementation.Context.Disabled.Factory.INSTANCE))
////                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
////                .with(AgentBuilder.RedefinitionStrategy.REDEFINITION)
////                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
//                .ignore(ElementMatchers.none())
////////                .type(ElementMatchers.named("java.lang.System"))
////////                .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) -> builder.method(ElementMatchers.named("currentTimeMillis")).intercept(Advice.to(NtpClock.TimeAdvice.class).wrap(StubMethod.INSTANCE)))
//                .type(ElementMatchers.named("java.util.concurrent.ForkJoinPool"))
//                .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) -> builder.method(ElementMatchers.any()).intercept(MethodDelegation.to(TimingInterceptor.class)))
//                .installOn(ByteBuddyAgent.install());

//        install.appendToBootstrapClassLoaderSearch(new JarFile("D:\\projs\\rxlib\\rxlib\\target\\rxlib-2.19.5-SNAPSHOT.jar"));
//        new AgentBuilder.Default()
//                // by default, JVM classes are not instrumented
//                .ignore(ElementMatchers.none())
//                .disableClassFormatChanges()
//                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
//                .type(ElementMatchers.is(ForkJoinPool.class))
//                .transform((builder, type, loader, x, y) -> builder
//                        .visit(Advice
//                                .to(GeneralInterceptor.class)
//                                .on(ElementMatchers.named("externalPush"))))
//                .installOn(install);
    }

    private static void loadClass(Map<String, byte[]> loadedTypeMap,
                                  String className) throws Exception {
        byte[] enhancedInstanceClassFile;
        try {
            String classResourceName = className.replaceAll("\\.", "/") + ".class";
            InputStream resourceAsStream = TestAgent.class.getClassLoader().getResourceAsStream(classResourceName);

            if (resourceAsStream == null) {
                throw new RuntimeException("class " + className + " not found.");
            }

            ByteArrayOutputStream os = new ByteArrayOutputStream();

            byte[] buffer = new byte[1024];
            int len;

            // read bytes from the input stream and store them in buffer
            while ((len = resourceAsStream.read(buffer)) != -1) {
                // write bytes from the buffer into output stream
                os.write(buffer, 0, len);
            }

            enhancedInstanceClassFile = os.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }

        loadedTypeMap.put(className, enhancedInstanceClassFile);
    }

    private static final String SHADE_PACKAGE = "";//"com.yametech.yangjian.agent.thirdparty.";
    public static final String[] CLASSES = {
            SHADE_PACKAGE + "net.bytebuddy.asm.Advice",
            SHADE_PACKAGE + "net.bytebuddy.asm.AsmVisitorWrapper",
            SHADE_PACKAGE + "net.bytebuddy.asm.AsmVisitorWrapper$ForDeclaredMethods",
            SHADE_PACKAGE + "net.bytebuddy.asm.AsmVisitorWrapper$ForDeclaredMethods$MethodVisitorWrapper",
            SHADE_PACKAGE + "net.bytebuddy.asm.Advice$OnMethodEnter",
            SHADE_PACKAGE + "net.bytebuddy.asm.Advice$OnMethodExit",
            SHADE_PACKAGE + "net.bytebuddy.asm.Advice$This",
            SHADE_PACKAGE + "net.bytebuddy.asm.Advice$AllArguments",
            SHADE_PACKAGE + "net.bytebuddy.asm.Advice$Origin",
            SHADE_PACKAGE + "net.bytebuddy.implementation.bind.annotation.RuntimeType",
            SHADE_PACKAGE + "net.bytebuddy.implementation.bind.annotation.This",
            SHADE_PACKAGE + "net.bytebuddy.implementation.bind.annotation.AllArguments",
            SHADE_PACKAGE + "net.bytebuddy.implementation.bind.annotation.AllArguments$Assignment",
            SHADE_PACKAGE + "net.bytebuddy.implementation.bind.annotation.SuperCall",
            SHADE_PACKAGE + "net.bytebuddy.implementation.bind.annotation.Origin",
            SHADE_PACKAGE + "net.bytebuddy.implementation.bind.annotation.Morph",
    };

    public static class BootstrapAdvice {
        @Advice.OnMethodEnter
        public static void enter(
//                @Advice.This Object self,
//                @Advice.Origin Method method,
                @Advice.AllArguments(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object[] arguments
        ) throws Exception {
            System.out.println("Advice enter");

            Object task = arguments[0];

            String tn = "org.rx.core.ForkJoinPoolWrapper";
//            Class<?> t = Class.forName("org.rx.core.ForkJoinPoolWrapper");
//            Class<?> t = Thread.currentThread().getContextClassLoader().loadClass(tn);
            Class<?> t = ClassLoader.getSystemClassLoader().loadClass(tn);
            Method m = t.getDeclaredMethod("wrap", Object.class);

            Object[] newArgs = new Object[1];
            newArgs[0] = task;
//            Object r = m.invoke(null, newArgs);
            Object r = ForkJoinPoolWrapper.wrap(task);
            System.out.printf("Advice adapt %s(%s) -> %s%n", m, task, r);
            newArgs[0] = r;

            arguments = newArgs;
        }
    }

    public static class TimingInterceptor {
        @RuntimeType
        public static Object intercept(
                @Origin Method method
                , @AllArguments Object[] arguments
                , @SuperCall Callable<?> callable
        ) throws Exception {
            long start = System.currentTimeMillis();
            try {
                return callable.call();
            } finally {
                System.out.println(method + " took " + (System.currentTimeMillis() - start));
            }
        }
    }
}
