package org.rx.net.transport.kcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * ByteBuf based KCP control block.
 *
 * <p>All methods are intended to run on one EventLoop. {@link #send(ByteBuf)}
 * retains slices of the supplied payload; the caller keeps ownership of the
 * original buffer. {@link Output#write(ByteBuf)} receives ownership of each
 * encoded KCP datagram.</p>
 */
public final class RxKcp {
    public interface Output {
        void write(ByteBuf datagram);
    }

    public static final int OVERHEAD = 24;

    static final int CMD_PUSH = 81;
    static final int CMD_ACK = 82;
    static final int CMD_WASK = 83;
    static final int CMD_WINS = 84;

    static final int ASK_SEND = 1;
    static final int ASK_TELL = 2;

    static final int RTO_NDL = 30;
    static final int RTO_MIN = 100;
    static final int RTO_DEF = 200;
    static final int RTO_MAX = 60 * 1000;
    static final int PROBE_INIT = 7000;
    static final int PROBE_LIMIT = 120 * 1000;
    static final int THRESH_INIT = 2;
    static final int THRESH_MIN = 2;
    static final int DEAD_LINK = 20;

    static final class MessageTracker {
        int remaining;

        MessageTracker(int remaining) {
            this.remaining = remaining;
        }
    }

    static final class Segment {
        ByteBuf data;
        MessageTracker tracker;
        int conv;
        int cmd;
        int frg;
        int wnd;
        int ts;
        int sn;
        int una;
        int resendTs;
        int rto;
        int fastAck;
        int xmit;

        Segment(ByteBuf data) {
            this.data = data;
        }
    }

    final int conv;
    final ByteBufAllocator allocator;
    final Output output;
    final ArrayDeque<Segment> sndQueue = new ArrayDeque<>();
    final ArrayDeque<Segment> rcvQueue = new ArrayDeque<>();
    final List<Segment> sndBuf = new ArrayList<>();
    final List<Segment> rcvBuf = new ArrayList<>();

    int mtu = 1200;
    int mss = mtu - OVERHEAD;
    int sndUna;
    int sndNxt;
    int rcvNxt;
    int rxRttval;
    int rxSrtt;
    int rxRto = RTO_DEF;
    int rxMinRto = RTO_MIN;
    int sndWnd = 32;
    int rcvWnd = 128;
    int rmtWnd = 128;
    int cwnd = 1;
    int probe;
    int current;
    int interval = 100;
    int tsFlush = 100;
    int tsProbe;
    int probeWait;
    int ssthresh = THRESH_INIT;
    int deadLink = DEAD_LINK;
    int incr;
    int fastResend;
    int noDelay;
    boolean noCongestionControl;
    boolean updated;
    boolean closed;

    int[] ackList = new int[16];
    int ackCount;
    long pendingBytes;
    int pendingMessages;

    public RxKcp(int conv, ByteBufAllocator allocator, Output output) {
        if (allocator == null || output == null) {
            throw new IllegalArgumentException("allocator/output must not be null");
        }
        this.conv = conv;
        this.allocator = allocator;
        this.output = output;
    }

    public int getConv() {
        return conv;
    }

    public long pendingBytes() {
        return pendingBytes;
    }

    public int pendingMessages() {
        return pendingMessages;
    }

    public boolean canSend(int payloadBytes, int maxPendingBytes, int maxPendingMessages) {
        if (payloadBytes < 0) {
            return false;
        }
        if (maxPendingBytes > 0 && pendingBytes + payloadBytes > maxPendingBytes) {
            return false;
        }
        return maxPendingMessages <= 0 || pendingMessages < maxPendingMessages;
    }

    public void setMtu(int mtu) {
        if (mtu < OVERHEAD + 1) {
            throw new IllegalArgumentException("kcp mtu too small " + mtu);
        }
        this.mtu = mtu;
        this.mss = mtu - OVERHEAD;
    }

    public void setWindowSize(int sendWindow, int receiveWindow) {
        if (sendWindow > 0) {
            sndWnd = sendWindow;
        }
        if (receiveWindow > 0) {
            rcvWnd = receiveWindow;
            rmtWnd = receiveWindow;
        }
    }

    public void setNoDelay(int noDelay, int intervalMillis, int fastResend, int noCongestionControl) {
        this.noDelay = noDelay > 0 ? 1 : 0;
        rxMinRto = this.noDelay != 0 ? RTO_NDL : RTO_MIN;
        interval = bound(10, intervalMillis, 5000);
        this.fastResend = Math.max(0, fastResend);
        this.noCongestionControl = noCongestionControl != 0;
    }

    public void send(ByteBuf payload) {
        if (closed) {
            throw new IllegalStateException("kcp is closed");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload is null");
        }
        int bytes = payload.readableBytes();
        int count = bytes <= mss ? 1 : (bytes + mss - 1) / mss;
        if (count > 255) {
            throw new IllegalArgumentException("kcp message has too many fragments " + count);
        }

        MessageTracker tracker = new MessageTracker(count);
        List<Segment> created = new ArrayList<>(count);
        int offset = 0;
        try {
            for (int i = 0; i < count; i++) {
                int len = Math.min(mss, bytes - offset);
                if (bytes == 0) {
                    len = 0;
                }
                Segment segment = new Segment(payload.retainedSlice(payload.readerIndex() + offset, len));
                segment.frg = count - i - 1;
                segment.tracker = tracker;
                created.add(segment);
                offset += len;
            }
            sndQueue.addAll(created);
            pendingBytes += bytes;
            pendingMessages++;
        } catch (Throwable e) {
            for (Segment segment : created) {
                releaseData(segment);
            }
            throw e;
        }
    }

    public int input(ByteBuf packet, long nowMillis) {
        if (closed || packet == null) {
            return -1;
        }
        current = timestamp(nowMillis);
        int previousUna = sndUna;
        while (packet.readableBytes() >= OVERHEAD) {
            int segmentConv = packet.readIntLE();
            int cmd = packet.readUnsignedByte();
            int frg = packet.readUnsignedByte();
            int wnd = packet.readUnsignedShortLE();
            int ts = packet.readIntLE();
            int sn = packet.readIntLE();
            int una = packet.readIntLE();
            int len = packet.readIntLE();
            if (segmentConv != conv || len < 0 || len > packet.readableBytes()) {
                return -2;
            }
            if (cmd != CMD_PUSH && cmd != CMD_ACK && cmd != CMD_WASK && cmd != CMD_WINS) {
                return -3;
            }

            rmtWnd = wnd;
            parseUna(una);
            shrinkBuf();
            if (cmd == CMD_ACK) {
                if (timediff(current, ts) >= 0) {
                    updateAck(timediff(current, ts));
                }
                parseAck(sn);
                shrinkBuf();
                parseFastAck(sn, ts);
            } else if (cmd == CMD_PUSH) {
                if (timediff(sn, rcvNxt + rcvWnd) < 0) {
                    ackPush(sn, ts);
                    if (timediff(sn, rcvNxt) >= 0) {
                        Segment segment = new Segment(packet.retainedSlice(packet.readerIndex(), len));
                        segment.conv = segmentConv;
                        segment.cmd = cmd;
                        segment.frg = frg;
                        segment.wnd = wnd;
                        segment.ts = ts;
                        segment.sn = sn;
                        segment.una = una;
                        parseData(segment);
                    }
                }
            } else if (cmd == CMD_WASK) {
                probe |= ASK_TELL;
            }
            packet.skipBytes(len);
        }
        if (packet.isReadable()) {
            return -4;
        }

        if (timediff(sndUna, previousUna) > 0 && !noCongestionControl) {
            if (cwnd < rmtWnd) {
                int mssValue = mss;
                if (cwnd < ssthresh) {
                    cwnd++;
                    incr += mssValue;
                } else {
                    if (incr < mssValue) {
                        incr = mssValue;
                    }
                    incr += (mssValue * mssValue) / incr + (mssValue / 16);
                    if ((cwnd + 1) * mssValue <= incr) {
                        cwnd++;
                    }
                }
                if (cwnd > rmtWnd) {
                    cwnd = rmtWnd;
                    incr = rmtWnd * mssValue;
                }
            }
        }
        return 0;
    }

    public ByteBuf receive() {
        if (rcvQueue.isEmpty()) {
            return null;
        }
        Segment first = rcvQueue.peekFirst();
        int count = first.frg + 1;
        if (count > rcvQueue.size()) {
            return null;
        }

        CompositeByteBuf payload = allocator.compositeBuffer(count);
        boolean success = false;
        try {
            for (int i = 0; i < count; i++) {
                Segment segment = rcvQueue.removeFirst();
                payload.addComponent(true, segment.data);
                segment.data = null;
            }
            moveReceiveBuffer();
            success = true;
            return payload;
        } finally {
            if (!success) {
                ReferenceCountUtil.safeRelease(payload);
            }
        }
    }

    public void update(long nowMillis) {
        if (closed) {
            return;
        }
        current = timestamp(nowMillis);
        if (!updated) {
            updated = true;
            tsFlush = current;
        }

        int slap = timediff(current, tsFlush);
        if (slap >= 10000 || slap < -10000) {
            tsFlush = current;
            slap = 0;
        }
        if (slap >= 0) {
            tsFlush += interval;
            if (timediff(current, tsFlush) >= 0) {
                tsFlush = current + interval;
            }
            flush();
        }
    }

    public void flushNow(long nowMillis) {
        if (closed) {
            return;
        }
        current = timestamp(nowMillis);
        if (!updated) {
            updated = true;
            tsFlush = current + interval;
        }
        flush();
    }

    public void release() {
        if (closed) {
            return;
        }
        closed = true;
        for (Segment segment : sndQueue) {
            releaseData(segment);
        }
        for (Segment segment : sndBuf) {
            releaseData(segment);
        }
        for (Segment segment : rcvQueue) {
            releaseData(segment);
        }
        for (Segment segment : rcvBuf) {
            releaseData(segment);
        }
        sndQueue.clear();
        sndBuf.clear();
        rcvQueue.clear();
        rcvBuf.clear();
        pendingBytes = 0;
        pendingMessages = 0;
        ackCount = 0;
    }

    void flush() {
        int unusedWindow = unusedWindow();
        for (int i = 0; i < ackCount; i++) {
            Segment ack = new Segment(null);
            ack.conv = conv;
            ack.cmd = CMD_ACK;
            ack.wnd = unusedWindow;
            ack.ts = ackList[i * 2 + 1];
            ack.sn = ackList[i * 2];
            ack.una = rcvNxt;
            emit(ack);
        }
        ackCount = 0;

        if (rmtWnd == 0) {
            if (probeWait == 0) {
                probeWait = PROBE_INIT;
                tsProbe = current + probeWait;
            } else if (timediff(current, tsProbe) >= 0) {
                probeWait = Math.min(PROBE_LIMIT, Math.max(PROBE_INIT, probeWait + probeWait / 2));
                tsProbe = current + probeWait;
                probe |= ASK_SEND;
            }
        } else {
            tsProbe = 0;
            probeWait = 0;
        }

        if ((probe & ASK_SEND) != 0) {
            emitControl(CMD_WASK, unusedWindow);
        }
        if ((probe & ASK_TELL) != 0) {
            emitControl(CMD_WINS, unusedWindow);
        }
        probe = 0;

        int usableWindow = Math.min(sndWnd, rmtWnd);
        if (!noCongestionControl) {
            usableWindow = Math.min(cwnd, usableWindow);
        }
        while (timediff(sndNxt, sndUna + usableWindow) < 0 && !sndQueue.isEmpty()) {
            Segment segment = sndQueue.removeFirst();
            segment.conv = conv;
            segment.cmd = CMD_PUSH;
            segment.wnd = unusedWindow;
            segment.ts = current;
            segment.sn = sndNxt++;
            segment.una = rcvNxt;
            segment.resendTs = current;
            segment.rto = rxRto;
            segment.fastAck = 0;
            segment.xmit = 0;
            sndBuf.add(segment);
        }

        int resent = fastResend > 0 ? fastResend : Integer.MAX_VALUE;
        int rtoMin = noDelay == 0 ? rxRto >>> 3 : 0;
        boolean lost = false;
        int change = 0;
        for (Segment segment : sndBuf) {
            boolean needsSend = false;
            if (segment.xmit == 0) {
                needsSend = true;
                segment.xmit++;
                segment.rto = rxRto;
                segment.resendTs = current + segment.rto + rtoMin;
            } else if (timediff(current, segment.resendTs) >= 0) {
                needsSend = true;
                segment.xmit++;
                segment.rto += noDelay == 0 ? rxRto : rxRto / 2;
                segment.resendTs = current + segment.rto;
                lost = true;
            } else if (segment.fastAck >= resent) {
                needsSend = true;
                segment.xmit++;
                segment.fastAck = 0;
                segment.resendTs = current + segment.rto;
                change++;
            }
            if (!needsSend) {
                continue;
            }
            segment.ts = current;
            segment.wnd = unusedWindow;
            segment.una = rcvNxt;
            emit(segment);
            if (segment.xmit >= deadLink) {
                lost = true;
            }
        }

        if (!noCongestionControl) {
            if (change > 0) {
                int inflight = sndNxt - sndUna;
                ssthresh = Math.max(inflight / 2, THRESH_MIN);
                cwnd = ssthresh + resent;
                incr = cwnd * mss;
            }
            if (lost) {
                ssthresh = Math.max(usableWindow / 2, THRESH_MIN);
                cwnd = 1;
                incr = mss;
            }
            if (cwnd < 1) {
                cwnd = 1;
                incr = mss;
            }
        }
    }

    void emitControl(int cmd, int wnd) {
        Segment segment = new Segment(null);
        segment.conv = conv;
        segment.cmd = cmd;
        segment.wnd = wnd;
        segment.una = rcvNxt;
        emit(segment);
    }

    void emit(Segment segment) {
        int payloadLength = segment.data == null ? 0 : segment.data.readableBytes();
        ByteBuf header = allocator.ioBuffer(OVERHEAD);
        ByteBuf datagram = null;
        try {
            header.writeIntLE(segment.conv);
            header.writeByte(segment.cmd);
            header.writeByte(segment.frg);
            header.writeShortLE(segment.wnd);
            header.writeIntLE(segment.ts);
            header.writeIntLE(segment.sn);
            header.writeIntLE(segment.una);
            header.writeIntLE(payloadLength);
            if (payloadLength == 0) {
                datagram = header;
                header = null;
            } else {
                CompositeByteBuf composite = allocator.compositeBuffer(2);
                datagram = composite;
                composite.addComponent(true, header);
                header = null;
                composite.addComponent(true, segment.data.retainedDuplicate());
            }
            output.write(datagram);
            datagram = null;
        } finally {
            ReferenceCountUtil.safeRelease(header);
            ReferenceCountUtil.safeRelease(datagram);
        }
    }

    void updateAck(int rtt) {
        if (rxSrtt == 0) {
            rxSrtt = rtt;
            rxRttval = rtt / 2;
        } else {
            int delta = Math.abs(rtt - rxSrtt);
            rxRttval = (3 * rxRttval + delta) / 4;
            rxSrtt = (7 * rxSrtt + rtt) / 8;
            if (rxSrtt < 1) {
                rxSrtt = 1;
            }
        }
        int rto = rxSrtt + Math.max(interval, 4 * rxRttval);
        rxRto = bound(rxMinRto, rto, RTO_MAX);
    }

    void parseAck(int sn) {
        if (timediff(sn, sndUna) < 0 || timediff(sn, sndNxt) >= 0) {
            return;
        }
        for (int i = 0; i < sndBuf.size(); i++) {
            Segment segment = sndBuf.get(i);
            if (sn == segment.sn) {
                sndBuf.remove(i);
                releaseSent(segment);
                break;
            }
            if (timediff(sn, segment.sn) < 0) {
                break;
            }
        }
    }

    void parseUna(int una) {
        while (!sndBuf.isEmpty()) {
            Segment segment = sndBuf.get(0);
            if (timediff(una, segment.sn) <= 0) {
                break;
            }
            sndBuf.remove(0);
            releaseSent(segment);
        }
    }

    void parseFastAck(int sn, int ts) {
        if (timediff(sn, sndUna) < 0 || timediff(sn, sndNxt) >= 0) {
            return;
        }
        for (Segment segment : sndBuf) {
            if (timediff(sn, segment.sn) < 0) {
                break;
            }
            if (sn != segment.sn && timediff(ts, segment.ts) >= 0) {
                segment.fastAck++;
            }
        }
    }

    void shrinkBuf() {
        sndUna = sndBuf.isEmpty() ? sndNxt : sndBuf.get(0).sn;
    }

    void ackPush(int sn, int ts) {
        int required = (ackCount + 1) * 2;
        if (required > ackList.length) {
            int[] grown = new int[Math.max(required, ackList.length * 2)];
            System.arraycopy(ackList, 0, grown, 0, ackCount * 2);
            ackList = grown;
        }
        ackList[ackCount * 2] = sn;
        ackList[ackCount * 2 + 1] = ts;
        ackCount++;
    }

    void parseData(Segment incoming) {
        int sn = incoming.sn;
        if (timediff(sn, rcvNxt + rcvWnd) >= 0 || timediff(sn, rcvNxt) < 0) {
            releaseData(incoming);
            return;
        }

        boolean repeated = false;
        int insertAt = rcvBuf.size();
        for (int i = rcvBuf.size() - 1; i >= 0; i--) {
            Segment segment = rcvBuf.get(i);
            if (segment.sn == sn) {
                repeated = true;
                break;
            }
            if (timediff(sn, segment.sn) > 0) {
                insertAt = i + 1;
                break;
            }
            insertAt = i;
        }
        if (repeated) {
            releaseData(incoming);
        } else {
            rcvBuf.add(insertAt, incoming);
        }
        moveReceiveBuffer();
    }

    void moveReceiveBuffer() {
        while (!rcvBuf.isEmpty()) {
            Segment segment = rcvBuf.get(0);
            if (segment.sn != rcvNxt || rcvQueue.size() >= rcvWnd) {
                break;
            }
            rcvBuf.remove(0);
            rcvQueue.addLast(segment);
            rcvNxt++;
        }
    }

    int unusedWindow() {
        return rcvQueue.size() < rcvWnd ? rcvWnd - rcvQueue.size() : 0;
    }

    void releaseSent(Segment segment) {
        int bytes = segment.data == null ? 0 : segment.data.readableBytes();
        releaseData(segment);
        pendingBytes = Math.max(0, pendingBytes - bytes);
        MessageTracker tracker = segment.tracker;
        segment.tracker = null;
        if (tracker != null && --tracker.remaining == 0) {
            pendingMessages = Math.max(0, pendingMessages - 1);
        }
    }

    void releaseData(Segment segment) {
        if (segment != null) {
            ReferenceCountUtil.safeRelease(segment.data);
            segment.data = null;
        }
    }

    static int timestamp(long nowMillis) {
        return (int) nowMillis;
    }

    static int timediff(int later, int earlier) {
        return later - earlier;
    }

    static int bound(int lower, int value, int upper) {
        return Math.min(Math.max(lower, value), upper);
    }
}
