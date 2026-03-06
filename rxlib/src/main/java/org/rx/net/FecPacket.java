package org.rx.net;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * FEC 数据包
 * 
 * <pre>
 * +-------+--------+----------+----------+----------+----------+
 * | Magic | SeqNo  | GroupId  | GroupIdx | IsParity | Payload  |
 * | 2B    | 4B     | 4B       | 1B       | 1B       | Variable |
 * +-------+--------+----------+----------+----------+----------+
 * </pre>
 */
@Getter
@RequiredArgsConstructor
@ToString(exclude = "payload")
public class FecPacket {
    public static final short MAGIC = (short) 0xFEC0;
    public static final int HEADER_SIZE = 2 + 4 + 4 + 1 + 1; // 12 bytes

    private final int seqNo;
    private final int groupId;
    private final byte groupIdx;
    private final boolean parity;
    private final byte[] payload;

    /**
     * 编码到 ByteBuf
     */
    public void encode(ByteBuf buf) {
        buf.writeShort(MAGIC);
        buf.writeInt(seqNo);
        buf.writeInt(groupId);
        buf.writeByte(groupIdx);
        buf.writeBoolean(parity);
        buf.writeBytes(payload);
    }

    /**
     * 从 ByteBuf 解码
     */
    public static FecPacket decode(ByteBuf buf) {
        short magic = buf.readShort();
        if (magic != MAGIC) {
            return null;
        }
        int seqNo = buf.readInt();
        int groupId = buf.readInt();
        byte groupIdx = buf.readByte();
        boolean isParity = buf.readBoolean();
        byte[] payload = new byte[buf.readableBytes()];
        buf.readBytes(payload);
        return new FecPacket(seqNo, groupId, groupIdx, isParity, payload);
    }

    /**
     * 计算编码后的总长度
     */
    public int encodedLength() {
        return HEADER_SIZE + payload.length;
    }
}
