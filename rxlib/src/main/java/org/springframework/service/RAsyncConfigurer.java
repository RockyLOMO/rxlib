package org.springframework.service;

import lombok.extern.slf4j.Slf4j;
import org.rx.core.Tasks;
import org.rx.exception.TraceHandler;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.scheduling.annotation.AsyncConfigurer;

import java.util.concurrent.Executor;

//@Component
@Slf4j
public class RAsyncConfigurer implements AsyncConfigurer {
    @Override
    public Executor getAsyncExecutor() {
        return Tasks.executor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (e, m, a) -> log.error("Async error", e);
    }
}
