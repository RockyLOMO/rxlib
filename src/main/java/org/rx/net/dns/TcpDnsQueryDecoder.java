package org.rx.net.dns;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.dns.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
//@ChannelHandler.Sharable
public class TcpDnsQueryDecoder extends LengthFieldBasedFrameDecoder {
    private final DnsRecordDecoder decoder = DnsRecordDecoder.DEFAULT;

    public TcpDnsQueryDecoder() {
        super(Short.MAX_VALUE, 0, 2, 0, 2);
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        ByteBuf frame = (ByteBuf) super.decode(ctx, in);
        if (frame == null) {
            return null;
        }

        ByteBuf buf = frame.slice();
        DnsQuery query = newQuery(buf);
        boolean success = false;
        try {
            int questionCount = buf.readUnsignedShort();
            int answerCount = buf.readUnsignedShort();
            int authorityRecordCount = buf.readUnsignedShort();
            int additionalRecordCount = buf.readUnsignedShort();
            this.decodeQuestions(query, buf, questionCount);
            this.decodeRecords(query, DnsSection.ANSWER, buf, answerCount);
            this.decodeRecords(query, DnsSection.AUTHORITY, buf, authorityRecordCount);
            this.decodeRecords(query, DnsSection.ADDITIONAL, buf, additionalRecordCount);
            log.debug("dnsQuery:{}", query);
            success = true;
            return query;
        } finally {
            if (!success) {
                query.release();
            }
        }
    }

    @Override
    protected ByteBuf extractFrame(ChannelHandlerContext ctx, ByteBuf buffer, int index, int length) {
        return buffer.copy(index, length);
    }

    private static DnsQuery newQuery(ByteBuf buf) {
        int id = buf.readUnsignedShort();
        int flags = buf.readUnsignedShort();
        if (flags >> 15 == 1) {
            throw new CorruptedFrameException("not a query");
        }

        DnsQuery query = new DefaultDnsQuery(id, DnsOpCode.valueOf((byte) (flags >> 11 & 0xf)));
        query.setRecursionDesired((flags >> 8 & 1) == 1);
        query.setZ(flags >> 4 & 0x7);
        return query;
    }

    private void decodeQuestions(DnsQuery query, ByteBuf buf, int questionCount) throws Exception {
        for (int i = questionCount; i > 0; --i) {
            query.addRecord(DnsSection.QUESTION, this.decoder.decodeQuestion(buf));
        }
    }

    private void decodeRecords(DnsQuery query, DnsSection section, ByteBuf buf, int count) throws Exception {
        for (int i = count; i > 0; --i) {
            DnsRecord r = this.decoder.decodeRecord(buf);
            if (r == null) {
                break;
            }
            query.addRecord(section, r);
        }
    }
}
