package org.rx.agent;

import lombok.extern.slf4j.Slf4j;

import java.lang.instrument.Instrumentation;

@Slf4j
public class Agent {
    public static void premain(String agentArgs, Instrumentation inst) {
        log.info("[Agent] In premain method");
        TimeAdvice.transform(inst);
    }
}
