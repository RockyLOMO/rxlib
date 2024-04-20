package org.rx.crawler;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RPriorityBlockingQueue;
import org.redisson.api.RSetCache;
import org.redisson.api.RTopic;
import org.rx.core.Constants;
import org.rx.core.ResetEventWait;
import org.rx.exception.TraceHandler;
import org.rx.redis.RedisCache;
import org.rx.util.function.TripleAction;
import org.rx.util.function.TripleFunc;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.rx.core.Sys.toJsonString;

@RequiredArgsConstructor
@Component
@ConditionalOnProperty(name = org.rx.spring.BeanRegister.REDIS_PROP_NAME)
@Slf4j
public class BrowserAsyncTopic {
    @RequiredArgsConstructor
    private class AsyncFuture<T> implements Future<T> {
        private final UUID asyncId;
        private final Object callback;
        private final ResetEventWait waiter = new ResetEventWait();
        @Getter
        private volatile boolean done;
        private volatile Throwable exception;
        private T result;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return callbacks.remove(asyncId) != null;
        }

        @Override
        public boolean isCancelled() {
            return !callbacks.containsKey(asyncId);
        }

        @Override
        public T get() throws ExecutionException {
            try {
                return get(Constants.TIMEOUT_INFINITE, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.warn("ignore", e);
            }
            return null;
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws ExecutionException, TimeoutException {
            if (!waiter.waitOne(TimeUnit.MILLISECONDS.convert(timeout, unit))) {
                throw new TimeoutException();
            }
            if (exception != null) {
                throw new ExecutionException(exception);
            }
            return result;
        }
    }

    //避免topic多次listen
    public static final String QUEUE_NAME = "BAsyncQueue", TOPIC_NAME = "BAsyncTopic", IN_PUBLISH_NAME = "BAsyncPublish";
    private final RedisCache<?, ?> redisCache;
    private RPriorityBlockingQueue<BrowserAsyncRequest> queue;
    private RTopic topic;
    private RSetCache<Integer> publishSet;
    private final ConcurrentHashMap<UUID, AsyncFuture> callbacks = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        queue = redisCache.getClient().getPriorityBlockingQueue(QUEUE_NAME);
        topic = redisCache.getClient().getTopic(TOPIC_NAME);
        publishSet = redisCache.getClient().getSetCache(IN_PUBLISH_NAME);
//        require(queue, queue.trySetComparator(Comparator.comparingInt(BrowserAsyncRequest::getPriority)));
        topic.addListener(BrowserAsyncResponse.class, (channel, asyncResponse) -> {
            log.info("Async consume response {}", toJsonString(asyncResponse));
            try {
                AsyncFuture future = callbacks.get(asyncResponse.getRequest().getAsyncId());
                if (future == null || future.isCancelled() || future.isDone()) {
                    return;
                }
                try (RemoteBrowser browser = RemoteBrowser.wrap(asyncResponse.getEndpoint())) {
                    if (future.isCancelled()) {
                        return;
                    }
                    if (future.callback instanceof TripleFunc) {
                        future.result = ((TripleFunc<RemoteBrowser, String, Object>) future.callback).invoke(browser, asyncResponse.getRequest().getUrl());
                        return;
                    }
                    ((TripleAction<RemoteBrowser, String>) future.callback).invoke(browser, asyncResponse.getRequest().getUrl());
                } catch (Throwable e) {
                    TraceHandler.INSTANCE.log("Async {} error", future.asyncId, e);
                    future.exception = e;
                } finally {
                    callbacks.remove(future.asyncId);
                    future.done = true;
                    future.waiter.set();
                }
            } finally {
                publishSet.remove(asyncResponse.getEndpoint().getPort());
            }
        });
        log.info("register BrowserAsyncTopic ok");
    }

    //region Consume
    public void add(@NonNull BrowserAsyncRequest request) {
        queue.add(request);
    }

    public Future listen(UUID asyncId, TripleAction<RemoteBrowser, String> callback) {
        AsyncFuture future = new AsyncFuture(asyncId, callback);
        callbacks.put(asyncId, future);
        return future;
    }

    public <T> Future<T> listen(UUID asyncId, TripleFunc<RemoteBrowser, String, T> callback) {
        AsyncFuture<T> future = new AsyncFuture<>(asyncId, callback);
        callbacks.put(asyncId, future);
        return future;
    }
    //endregion

    //region produce
    public List<BrowserAsyncRequest> poll(int takeCount) {
        return queue.poll(takeCount);
    }

    public BrowserAsyncRequest poll() {
        return queue.poll();
    }

    public boolean isPublishing(int nextIdleId) {
        return publishSet.contains(nextIdleId);
    }

    public void publish(BrowserAsyncResponse response) {
        if (response == null || response.getRequest() == null || response.getRequest().getAsyncId() == null || response.getEndpoint() == null) {
            log.warn("Async publish invalid response {}", toJsonString(response));
            return;
        }

        publishSet.add(response.getEndpoint().getPort(), 6, TimeUnit.SECONDS);
        topic.publish(response);
    }
    //endregion
}
