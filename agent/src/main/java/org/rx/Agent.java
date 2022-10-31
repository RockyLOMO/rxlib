package org.rx;

import java.lang.instrument.Instrumentation;

public class Agent {
    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[Agent] In premain method");
        TimeAdvice.transform(inst);
    }
}
