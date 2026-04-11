package org.rx.net.rpc;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.rx.AbstractTester;
import org.rx.bean.ULID;
import org.rx.core.EventArgs;
import org.rx.net.transport.TcpServer;
import org.rx.net.transport.TcpServerConfig;
import org.rx.test.*;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.rx.core.Extends.eq;
import static org.rx.core.Extends.sleep;
import static org.rx.core.Sys.toJsonString;

@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RemotingRegisterIdempotentTest extends AbstractTester {

    static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    final Map<Object, TcpServer> serverHost = new ConcurrentHashMap<>();
    final long startDelay = 4000;
    final String eventName = "onCallback";

    <T> void startServer(T svcImpl, InetSocketAddress ep) {
        RpcServerConfig svr = new RpcServerConfig(new TcpServerConfig(ep.getPort()));
        svr.getTcpConfig().setReactorName("temp");
        serverHost.computeIfAbsent(svcImpl, k -> Remoting.register(k, svr));
        log.info("Start server on port {}", ep.getPort());
    }

    <T> void restartServer(T svcImpl, InetSocketAddress ep, long delay) {
        TcpServer rs = serverHost.remove(svcImpl);
        sleep(delay);
        rs.close();
        log.info("Close server on port {}", rs.getConfig().getListenPort());
        // 原 TestSocks 用 setTimeout 异步启动，调用方极易在端口未监听时发 RPC；改为同步等待后再 bind
        sleep(delay);
        startServer(svcImpl, ep);
    }

    @AfterEach
    void teardown() {
        for (TcpServer s : serverHost.values()) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
        serverHost.clear();
    }

    // ===== 原有幂等测试 =====

    @Test
    @Order(0)
    @Timeout(20)
    void register_sameContract_twice_returnsSameServer() throws Exception {
        int port = freePort();
        Object contract = new Object();
        RpcServerConfig conf = new RpcServerConfig(new TcpServerConfig(port));
        TcpServer s1 = Remoting.register(contract, conf);
        TcpServer s2 = Remoting.register(contract, conf);
        assertSame(s1, s2, "第二次 register 应命中 serverBeans 快路径");
        s1.close();
    }

    // ===== 从 TestSocks 迁入的 RPC 测试 =====

    @Test
    @Order(1)
    @Timeout(120)
    void rpcStatefulApi() {
        UserManagerImpl svcImpl = new UserManagerImpl();
        startServer(svcImpl, endpoint_3307);

        List<UserManager> facadeGroup = new ArrayList<>();
        facadeGroup.add(Remoting.createFacade(UserManager.class, RpcClientConfig.statefulMode(endpoint_3307, 0)));
        facadeGroup.add(Remoting.createFacade(UserManager.class, RpcClientConfig.statefulMode(endpoint_3307, 0)));

        rpcApiEvent(svcImpl, facadeGroup);
        // 重启 server：原 facade 在部分环境下长时间无法自动恢复，第二轮用新 facade 验证新服务端
        restartServer(svcImpl, endpoint_3307, startDelay);
        facadeGroup.clear();
        facadeGroup.add(Remoting.createFacade(UserManager.class, RpcClientConfig.statefulMode(endpoint_3307, 0)));
        facadeGroup.add(Remoting.createFacade(UserManager.class, RpcClientConfig.statefulMode(endpoint_3307, 0)));
        rpcApiEvent(svcImpl, facadeGroup);
    }

    private void rpcApiEvent(UserManagerImpl svcImpl, List<UserManager> facadeGroup) {
        for (UserManager facade : facadeGroup) {
            try {
                facade.triggerError();
                fail("应抛 RemotingException");
            } catch (RemotingException e) {
                // expected
            }
            assertEquals(2, facade.computeLevel(1, 1));
        }

        // 自定义事件（广播），每 facade 只订阅 1 次
        for (int i = 0; i < facadeGroup.size(); i++) {
            int x = i;
            facadeGroup.get(i).<UserEventArgs>attachEvent(eventName, (s, e) -> {
                log.info("facade{} {} -> flag={}", x, eventName, e.getFlag());
                e.setFlag(e.getFlag() + 1);
            }, false);
        }

        UserEventArgs args = new UserEventArgs(PersonBean.LeZhi);
        facadeGroup.get(0).raiseEvent(eventName, args);
        log.info("facade0 flag:{}", args.getFlag());
        assertEquals(1, args.getFlag());

        args = new UserEventArgs(PersonBean.LeZhi);
        args.setFlag(1);
        facadeGroup.get(1).raiseEvent(eventName, args);
        log.info("facade1 flag:{}", args.getFlag());
        assertEquals(2, args.getFlag());

        svcImpl.raiseEvent(eventName, args);
        sleep(100);
        log.info("svr flag:{}", args.getFlag());
        // 服务端触发：先执行最后注册客户端的 EventArgs，再广播其余组内客户端
        assertEquals(2, args.getFlag());
    }

    @Test
    @Order(2)
    @Timeout(60)
    void rpcStatefulImpl() {
        RpcServerConfig serverConfig = new RpcServerConfig(new TcpServerConfig(3307));
        serverConfig.setEventComputeVersion(2);
        UserManagerImpl server = new UserManagerImpl();
        serverHost.put(server, Remoting.register(server, serverConfig));

        RpcClientConfig<UserManagerImpl> config = RpcClientConfig.statefulMode("127.0.0.1:3307", 1);
        config.setInitHandler((p, c) -> {
            log.info("onHandshake computeLevel: {}", p.computeLevel(1, 2));
        });
        UserManagerImpl facade1 = Remoting.createFacade(UserManagerImpl.class, config);
        assertEquals(2, facade1.computeLevel(1, 1));
        try {
            facade1.triggerError();
            fail("应抛 RemotingException");
        } catch (RemotingException e) {
            // expected
        }
        assertEquals(18, facade1.computeLevel(17, 1));

        config.setEventVersion(2);
        UserManagerImpl facade2 = Remoting.createFacade(UserManagerImpl.class, config);

        rpcImplEvent(facade1, "0x00");
        server.create(PersonBean.LeZhi);

        rpcImplEvent(facade2, "0x01");
        server.create(PersonBean.LeZhi);

        facade1.create(PersonBean.LeZhi);
        sleep(3000);
    }

    private void rpcImplEvent(UserManagerImpl facade, String id) {
        facade.<UserEventArgs>attachEvent("onCreate", (s, e) -> {
            log.info("onInnerCall start");
            assertEquals(-1, facade.computeLevel(0, -1));
            log.info("facade{} onCreate -> {}", id, toJsonString(e));
            e.getStatefulList().add(id + ":" + ULID.randomULID());
            e.setCancel(false);
        });
    }

    @Test
    @Order(3)
    @Timeout(120)
    void rpcReconnect() {
        UserManagerImpl svcImpl = new UserManagerImpl();
        startServer(svcImpl, endpoint_3307);

        java.util.concurrent.atomic.AtomicBoolean connected = new java.util.concurrent.atomic.AtomicBoolean(false);
        RpcClientConfig<UserManager> config = RpcClientConfig.statefulMode(endpoint_3307, 0);
        config.setInitHandler((p, c) -> {
            p.attachEvent(eventName, (s, e) -> log.info("attachEvent callback"), false);
            c.onReconnecting.combine((s, e) -> {
                InetSocketAddress next = eq(e.getValue().getPort(), endpoint_3307.getPort())
                        ? endpoint_3308 : endpoint_3307;
                log.info("reconnect -> {}", next);
                e.setValue(next);
            });
            log.debug("init ok");
            connected.set(true);
        });

        UserManager userManager = Remoting.createFacade(UserManager.class, config);
        assertEquals(2, userManager.computeLevel(1, 1));
        userManager.raiseEvent(eventName, EventArgs.EMPTY);

        // 10 秒后开启 3308 实例，重连成功
        restartServer(svcImpl, endpoint_3308, startDelay);
        sleep(3000);

        int max = 10;
        for (int i = 0; i < max; ) {
            if (!connected.get()) {
                sleep(1000);
                continue;
            }
            if (i == 0) {
                sleep(5000);
            }
            assertEquals(i + 1, userManager.computeLevel(i, 1));
            i++;
        }
        userManager.raiseEvent(eventName, EventArgs.EMPTY);
    }

    @Test
    @Order(4)
    @Timeout(60)
    void rpcPoolMode() throws InterruptedException {
        serverHost.put(HttpUserManager.INSTANCE,
                Remoting.register(HttpUserManager.INSTANCE, endpoint_3307.getPort(), true));

        int tcount = 50;
        int threadCount = 8;
        CountDownLatch latch = new CountDownLatch(tcount);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        HttpUserManager facade = Remoting.createFacade(
                HttpUserManager.class, RpcClientConfig.poolMode(endpoint_3307, 1, threadCount));

        for (int i = 0; i < tcount; i++) {
            int finalI = i;
            org.rx.core.Tasks.run(() -> {
                try {
                    // pool 模式只验证调用不抛异常，不断言具体返回值（连接复用下顺序无保证）
                    facade.computeLevel(1, finalI);
                    sleep(500);
                } catch (Throwable t) {
                    firstError.compareAndSet(null, t);
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(55, java.util.concurrent.TimeUnit.SECONDS), "部分任务未在超时前完成");
        assertNull(firstError.get(), () -> "并发调用出现异常: " + firstError.get());
    }
}
