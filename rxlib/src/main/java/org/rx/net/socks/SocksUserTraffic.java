package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

public final class SocksUserTraffic {
    public interface Recorder {
        void record(TrafficUser user, InetSocketAddress remoteAddress,
                    long readBytes, long writeBytes,
                    long readPackets, long writePackets);
    }

    public static final AttributeKey<TrafficUser> ATTR_USER = AttributeKey.valueOf("socksTrafficUser");
    public static final AttributeKey<TrafficLoginInfo> ATTR_LOGIN_INFO = AttributeKey.valueOf("socksTrafficLoginInfo");
    private static final AtomicReference<Recorder> RECORDER = new AtomicReference<>();

    private SocksUserTraffic() {
    }

    public static void registerRecorder(Recorder recorder) {
        RECORDER.set(recorder);
    }

    public static void clearRecorder() {
        RECORDER.set(null);
    }

    public static void bind(Channel channel, TrafficUser user, TrafficLoginInfo loginInfo) {
        if (channel == null) {
            return;
        }
        if (user != null) {
            channel.attr(ATTR_USER).set(user);
        }
        if (loginInfo != null) {
            channel.attr(ATTR_LOGIN_INFO).set(loginInfo);
        }
    }

    public static void attach(SocksContext context, TrafficUser user, TrafficLoginInfo loginInfo) {
        if (context == null) {
            return;
        }
        if (user != null) {
            context.setUser(user);
        }
        if (loginInfo != null) {
            context.setLoginInfo(loginInfo);
        }
    }

    public static void attachFromChannel(SocksContext context, Channel channel) {
        if (context == null || channel == null) {
            return;
        }
        attach(context, channel.attr(ATTR_USER).get(), channel.attr(ATTR_LOGIN_INFO).get());
    }

    public static void recordWrite(SocksContext context, Object msg) {
        record(context, 0L, readableBytes(msg), 0L, 1L);
    }

    public static void recordRead(SocksContext context, Object msg) {
        record(context, readableBytes(msg), 0L, 1L, 0L);
    }

    public static void recordWrite(SocksContext context, long bytes, long packets) {
        record(context, 0L, bytes, 0L, packets);
    }

    public static void recordRead(SocksContext context, long bytes, long packets) {
        record(context, bytes, 0L, packets, 0L);
    }

    public static void recordWrite(Channel channel, SocksContext context, long bytes, long packets) {
        if (context == null) {
            return;
        }
        if (context.getUser() == null || context.getUser().isAnonymous()) {
            attachFromChannel(context, channel);
        }
        recordWrite(context, bytes, packets);
    }

    public static void recordRead(Channel channel, SocksContext context, long bytes, long packets) {
        if (context == null) {
            return;
        }
        if (context.getUser() == null || context.getUser().isAnonymous()) {
            attachFromChannel(context, channel);
        }
        recordRead(context, bytes, packets);
    }

    private static void record(SocksContext context, long readBytes, long writeBytes, long readPackets, long writePackets) {
        if (context == null) {
            return;
        }
        TrafficUser user = context.getUser();
        TrafficLoginInfo loginInfo = context.getLoginInfo();
        // 仅在已绑定统计用户时记账，避免把纯入口流量误归到内部认证用户。
        if (user == null || user.isAnonymous() || loginInfo == null) {
            return;
        }

        if (readBytes != 0L) {
            loginInfo.getTotalReadBytes().addAndGet(readBytes);
        }
        if (writeBytes != 0L) {
            loginInfo.getTotalWriteBytes().addAndGet(writeBytes);
        }
        if (readPackets != 0L) {
            loginInfo.getTotalReadPackets().addAndGet(readPackets);
        }
        if (writePackets != 0L) {
            loginInfo.getTotalWritePackets().addAndGet(writePackets);
        }

        Recorder recorder = RECORDER.get();
        if (recorder != null) {
            recorder.record(user, context.getSource(), readBytes, writeBytes, readPackets, writePackets);
        }
    }

    private static long readableBytes(Object msg) {
        if (msg instanceof ByteBuf) {
            return ((ByteBuf) msg).readableBytes();
        }
        return 0L;
    }
}
