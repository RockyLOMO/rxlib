//package org.rx;
//
//import javassist.ClassPool;
//import javassist.CtClass;
//import lombok.Getter;
//import lombok.extern.slf4j.Slf4j;
//import org.rx.exception.InvalidException;
//
//import java.lang.instrument.ClassFileTransformer;
//import java.lang.instrument.Instrumentation;
//import java.security.ProtectionDomain;
//
//@Slf4j
//public abstract class AbstractTransformer implements ClassFileTransformer {
//    @Getter
//    final Instrumentation instrumentation;
//    @Getter
//    Class<?> targetClass;
//    ClassLoader targetClassLoader;
//
//    public AbstractTransformer(Instrumentation inst, String targetClassName) {
//        this.instrumentation = inst;
//        try {
//            targetClass = Class.forName(targetClassName);
//            targetClassLoader = targetClass.getClassLoader();
//            return;
//        } catch (Exception e) {
//            log.error("Class [{}] not found with Class.forName", targetClassName, e);
//        }
//
//        for (Class<?> clazz : inst.getAllLoadedClasses()) {
//            if (clazz.getName().equals(targetClassName)) {
//                targetClass = clazz;
//                targetClassLoader = targetClass.getClassLoader();
//                return;
//            }
//        }
//        throw new InvalidException("Failed to find class [{}]", targetClassName);
//    }
//
//    public void retransform() {
//        instrumentation.addTransformer(this, true);
//        try {
//            instrumentation.retransformClasses(targetClass);
//        } catch (Exception e) {
//            throw new InvalidException("Transform failed for: [{}]", targetClass.getName(), e);
//        }
//    }
//
//    @Override
//    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
//        String finalTargetClassName = targetClass.getName().replaceAll("\\.", "/");
//        if (!className.equals(finalTargetClassName) || !loader.equals(targetClassLoader)) {
//            return classfileBuffer;
//        }
//
//        log.info("[Agent] Transforming class {}", targetClass.getName());
//        try {
//            ClassPool cp = ClassPool.getDefault();
//            CtClass cc = cp.get(targetClass.getName());
//            return transformClass(cp, cc);
//        } catch (Throwable e) {
//            log.error("[Agent] Exception", e);
//        }
//        return classfileBuffer;
//    }
//
//    protected abstract byte[] transformClass(ClassPool cp, CtClass cc) throws Throwable;
//}
