package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
public class HttpPseudoHeaderDecoder extends ByteToMessageDecoder {
    private static final int MAX_HEADER_SIZE = 8192;
//    private static final Pattern CONTENT_LENGTH_PATTERN = Pattern.compile("Content-Length:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private boolean headerParsed = false;
    private int contentLength = -1;
    private int headerEndIndex = -1;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
//        throw new InvalidException("decode ex");

        if (!headerParsed) {
            int readableBytes = in.readableBytes();
            int maxScanLen = Math.min(readableBytes, MAX_HEADER_SIZE);
            log.debug("pseudo readableBytes {}", readableBytes);

            int idx = indexOfHeaderEnd(in, in.readerIndex(), in.readerIndex() + maxScanLen);
            if (idx == -1) {
                return; // 等待更多数据
            }

            int headerLen = idx - in.readerIndex() + 4;
//            byte[] headerBytes = new byte[headerLen];
//            in.getBytes(in.readerIndex(), headerBytes);
//            String headers = new String(headerBytes, StandardCharsets.US_ASCII);
            String headers = in.toString(in.readerIndex(), headerLen, StandardCharsets.US_ASCII);
            log.debug("pseudo decode {}", headers);

//            Matcher matcher = CONTENT_LENGTH_PATTERN.matcher(headers);
//            if (!matcher.find()) {
//                throw new IllegalArgumentException("Missing Content-Length header");
//            }
//            contentLength = Integer.parseInt(matcher.group(1));
            String cl = "Content-Length: ";
            idx = headers.lastIndexOf(cl);
            if (idx == -1) {
                throw new IllegalArgumentException("Missing Content-Length header");
            }
            contentLength = Integer.parseInt(headers.substring(idx + cl.length()).trim());

            headerEndIndex = in.readerIndex() + headerLen;
            headerParsed = true;
        }

        // 是否已经接收了全部正文数据
        if (in.readableBytes() < (headerEndIndex - in.readerIndex()) + contentLength) {
            return; // 等待更多数据
        }

        // 跳过 header
        in.skipBytes(headerEndIndex - in.readerIndex());

        // 提取 body
        ByteBuf payload = in.readBytes(contentLength);
        out.add(payload);

        // 重置状态
        headerParsed = false;
        contentLength = -1;
        headerEndIndex = -1;
    }

    private int indexOfHeaderEnd(ByteBuf buffer, int start, int maxIndex) {
        for (int i = start; i < maxIndex - 3; i++) {
            if (buffer.getByte(i) == '\r' && buffer.getByte(i + 1) == '\n'
                    && buffer.getByte(i + 2) == '\r' && buffer.getByte(i + 3) == '\n') {
                return i;
            }
        }
        return -1;
    }
}
