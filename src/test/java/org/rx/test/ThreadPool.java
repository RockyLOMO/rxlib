//package org.rx.test;
//
//import lombok.SneakyThrows;
//import lombok.extern.slf4j.Slf4j;
//import org.rx.core.Tasks;
//import org.springframework.beans.factory.InitializingBean;
//
//import java.util.ArrayList;
//import java.util.List;
//import java.util.concurrent.*;
//import java.util.concurrent.atomic.AtomicInteger;
//
///**
// * Created by caichengwu on 2020/9/25.
// */
//@Slf4j
//public class ThreadPool implements InitializingBean {
//
//    private ThreadPoolExecutor pool;
//
//    public ExecutorService get() {
//        return pool;
//    }
//
//    public void setMinSize(int minSize) {
//        this.minSize = minSize;
//    }
//
//    public void setMaxSize(int maxSize) {
//        this.maxSize = maxSize;
//    }
//
//    public void setMaxQueueSize(int maxQueueSize) {
//        this.maxQueueSize = maxQueueSize;
//    }
//
//    private int minSize;
//    private int maxSize;
//    private int maxQueueSize;
//
//
//    @Override
//    public void afterPropertiesSet() throws Exception {
//        AtomicInteger count = new AtomicInteger(0);
//
////        new ArrayBlockingQueue<>(maxQueueSize, false);
//        pool = new ThreadPoolExecutor(minSize, maxSize, 1, TimeUnit.MINUTES,
//                new LinkedTransferQueue<>(), new ThreadFactory() {
//            @Override
//            public Thread newThread(Runnable r) {
//                Thread t = new Thread(r,
//                        "pool-test" + count.incrementAndGet());
//                t.setDaemon(true);
//                return t;
//            }
//        },
//                (r, e) -> {
//                    Thread td = new Thread(r);
//                    td.start();
//                });
//
//    }
//
//    public void enqueue(Runnable runnable) {
//        pool.execute(runnable);
//    }
//
//    public MultiTaskWrapper addTask(Runnable runnable) {
//        MultiTaskWrapper wrapper = new MultiTaskWrapper();
//        wrapper.addTask(runnable);
//        return wrapper;
//    }
//
//
//    public class MultiTaskWrapper {
//        private List<CompletableFuture> tasks = new CopyOnWriteArrayList<>();
//
//        @SneakyThrows
//        public MultiTaskWrapper addTask(Runnable runnable) {
//            CompletableFuture<Void> task = CompletableFuture.runAsync(runnable, pool);
//            tasks.add(task);
//            log.info("addtask");
////            task.get();
//            return this;
//        }
//
//        public CompletableFuture<Void> invokeAll() throws InterruptedException {
////          return   CompletableFuture.runAsync(()->{
////                for (Runnable runnable : tasks) {
////                    runnable.run();
////                }
////            }, pool);
////            try {
////                return CompletableFuture.allOf(tasks.toArray(new CompletableFuture[]{}));
////            } catch (Exception ex) {
////                log.info("xxxxxx");
////                tasks.forEach(task -> task.cancel(true));
////                throw ex;
////            }
//            return null;
//        }
//
//        @SneakyThrows
//        public void invokeAll2() {
////            tasks.stream().map(p->Comp)
////            for (CompletableFuture<Void> task : tasks) {
////                log.info("{} {}", tasks.size(), task.toString());
////                task.get();
//////                while (!task.isDone()){
//////                    Thread.sleep(1000);
//////                }
////            }
////            log.info("wait done");
//        }
//    }
//
//    public static void main(String[] args) throws Exception {
//        throw new InterruptedException();
////        List<CompletableFuture<Void>> taks = new ArrayList<>();
////        for (int i = 0; i <8; i++) {
////            CompletableFuture<Void> run = Tasks.run(ThreadPool::dummy);
////            taks.add(run);
////        }
////        for (CompletableFuture<Void> tak : taks) {
////            tak.get();
////        }
//
//
////        ThreadPool tp = new ThreadPool();
////        tp.setMaxQueueSize(100);
////        tp.setMinSize(12);
////        tp.setMaxSize(64);
////        tp.afterPropertiesSet();
////        MultiTaskWrapper wrapper = null;
////        for (int i = 0; i < 11; i++) {
////            Runnable runnable = () -> {
////                log.info("outer start");
////                try {
////                    Thread.sleep(10);
////                } catch (InterruptedException e) {
////                    e.printStackTrace();
////                }
////                try {
////                    dummyWrapper(tp);
////                } catch (InterruptedException | ExecutionException e) {
////                    e.printStackTrace();
////                }
////                log.info("outer done");
////            };
////            if (wrapper == null) {
////                wrapper = tp.addTask(runnable);
////            } else
////                wrapper.addTask(runnable);
////        }
////        CompletableFuture<Void> endFlag = wrapper.invokeAll();
////        endFlag.get();
////        log.info("done");
////        System.in.read();
//    }
//
//    static void dummyWrapper(ThreadPool pool) throws InterruptedException, ExecutionException {
//        MultiTaskWrapper wrapper;
//        Runnable runnable = ThreadPool::dummy;
//        wrapper = pool.addTask(runnable);
////        CompletableFuture<Void> endFlag = wrapper.invokeAll();
////        endFlag.get();
//        wrapper.invokeAll2();
//    }
//
//    static void dummy() {
//        try {
//            Thread.sleep(100);
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }
//        log.info("done inner");
//    }
//
//}
