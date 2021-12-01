package org.rx.io;

import org.rx.core.Constants;
import org.rx.core.Tasks;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

final class BufferedWriter {
    ByteBuffer byteBuffer = ByteBuffer.allocateDirect(Constants.SIZE_4K);
    // 申请 64KB 的二级缓存
    ByteBuffer flushBuffer = ByteBuffer.allocateDirect(Constants.KB * 64);

    public void write(FileChannel channel, byte[] bytesLine) {
        int remain = byteBuffer.remaining();
        if (bytesLine.length > remain) {
            flush(byteBuffer, flushBuffer, channel);
        }
        byteBuffer.put(bytesLine);
    }

    /**
     * @param byteBuffer  一级缓存
     * @param flushBuffer 二级刷盘缓存
     * @param channel     管道
     * @description 缓冲区容量不足，写入二级缓存，若二级缓存即将慢，进行刷盘
     */
    public void flush(ByteBuffer byteBuffer, ByteBuffer flushBuffer, FileChannel channel) {
        // 反转一级缓存为读Mode
        byteBuffer.flip();
        // 剩余可读缓存
        int remain = byteBuffer.remaining();
        byte[] records = new byte[remain];
        byteBuffer.get(records);
        byteBuffer.clear();
        // 其他线程异步刷盘
        Tasks.run(() -> {
            // 检查二级缓存是否足够
            if (flushBuffer.remaining() < records.length) {
                // 读出二级缓存中的内容
                flushBuffer.flip();
                // 写入管道
                channel.write(flushBuffer);
                flushBuffer.clear();
            }
            flushBuffer.put(records);
        });
    }
}
