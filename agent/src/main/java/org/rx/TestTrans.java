//package org.rx;
//
//import javassist.ClassPool;
//import javassist.CtClass;
//import javassist.CtMethod;
//import org.rx.bean.ULID;
//
//import java.lang.instrument.Instrumentation;
//
//public class TestTrans extends AbstractTransformer {
//    public TestTrans(Instrumentation inst) {
//        super(inst, ULID.class.getName());
//    }
//
//    @Override
//    protected byte[] transformClass(ClassPool cp, CtClass cc) throws Throwable {
//        CtMethod m = cc.getDeclaredMethod("randomULID");
//        m.insertBefore("System.out.println(\"rxagent..\");");
//        cc.detach();
//        return cc.toBytecode();
//    }
//}
