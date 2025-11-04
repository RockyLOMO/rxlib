package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Constants;
import org.rx.core.Strings;
import org.rx.exception.InvalidException;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
public class HttpPseudoHeaderDecoder extends ByteToMessageDecoder {
    private final int maxFrameLength;
    private final boolean failFast;
    private boolean discardingTooLongFrame;
    private long tooLongFrameLength;
    private long bytesToDiscard;
    private int frameLengthInt = -1;

    public HttpPseudoHeaderDecoder() {
        this(Constants.MAX_HEAP_BUF_SIZE, true);
    }

    public HttpPseudoHeaderDecoder(int maxFrameLength, boolean failFast) {
        this.maxFrameLength = maxFrameLength;
        this.failFast = failFast;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
//        throw new InvalidException("decode ex");

        if (frameLengthInt == -1) { // new frame
            if (discardingTooLongFrame) {
                discardingTooLongFrame(in);
            }
            int endOfHeaders = findEndOfHeaders(in);
            if (endOfHeaders == -1) {
                log.debug("Not enough data to find end of headers {} -> {}", in.readableBytes(), in.toString(StandardCharsets.US_ASCII));
                return;
            }

            int headerLen = endOfHeaders - in.readerIndex();
            //没有\r\n\r\n
            String headersStr = in.readCharSequence(headerLen, StandardCharsets.US_ASCII).toString();
            in.skipBytes(4);
            int frameLength = parseContentLength(headersStr);
            log.debug("pseudo decode {} -> {}", headersStr, frameLength);
            if (frameLength < 0) {
                int lengthFieldEndOffset = in.readableBytes();
                failOnNegativeLengthField(in, frameLength, lengthFieldEndOffset);
            }
            if (frameLength > maxFrameLength) {
                exceededFrameLength(in, frameLength);
                return;
            }
            // never overflows because it's less than maxFrameLength
            frameLengthInt = frameLength;
        }
        if (in.readableBytes() < frameLengthInt) { // frameLengthInt exist , just check buf
            log.debug("Not enough data for frame {}/{}", in.readableBytes(), frameLengthInt);
            return;
        }

        // extract frame
        int readerIndex = in.readerIndex();
        int actualFrameLength = frameLengthInt;
        ByteBuf frame = in.retainedSlice(readerIndex, actualFrameLength);
        log.debug("Extract frame offset[{}] + length[{}]", readerIndex, actualFrameLength);
        in.readerIndex(readerIndex + actualFrameLength);
        frameLengthInt = -1; // start processing the next frame
        out.add(frame);
    }

    private int parseContentLength(String headersStr) {
        String cl = "Content-Length: ";
        int idx = Strings.lastIndexOfIgnoreCase(headersStr, cl);
        if (idx == -1) {
            throw new InvalidException("Missing Content-Length from header {}", headersStr);
        }
        return Integer.parseInt(headersStr.substring(idx + cl.length()));
    }

    private int findEndOfHeaders(ByteBuf in) {
        int start = in.readerIndex();
        int end = in.writerIndex() - 3;
        for (int i = start; i <= end; i++) {
            if (in.getByte(i) == '\r' && in.getByte(i + 1) == '\n' &&
                    in.getByte(i + 2) == '\r' && in.getByte(i + 3) == '\n') {
                return i;
            }
        }
        return -1;
    }

    private void discardingTooLongFrame(ByteBuf in) {
        long bytesToDiscard = this.bytesToDiscard;
        int localBytesToDiscard = (int) Math.min(bytesToDiscard, in.readableBytes());
        in.skipBytes(localBytesToDiscard);
        bytesToDiscard -= localBytesToDiscard;
        this.bytesToDiscard = bytesToDiscard;

        failIfNecessary(false);
    }

    private void failIfNecessary(boolean firstDetectionOfTooLongFrame) {
        if (bytesToDiscard == 0) {
            // Reset to the initial state and tell the handlers that
            // the frame was too large.
            long tooLongFrameLength = this.tooLongFrameLength;
            this.tooLongFrameLength = 0;
            discardingTooLongFrame = false;
            if (!failFast || firstDetectionOfTooLongFrame) {
                fail(tooLongFrameLength);
            }
        } else {
            // Keep discarding and notify handlers if necessary.
            if (failFast && firstDetectionOfTooLongFrame) {
                fail(tooLongFrameLength);
            }
        }
    }

    private void fail(long frameLength) {
        if (frameLength > 0) {
            throw new TooLongFrameException(
                    "Adjusted frame length exceeds " + maxFrameLength +
                            ": " + frameLength + " - discarded");
        } else {
            throw new TooLongFrameException(
                    "Adjusted frame length exceeds " + maxFrameLength +
                            " - discarding");
        }
    }

    private static void failOnNegativeLengthField(ByteBuf in, long frameLength, int lengthFieldEndOffset) {
        in.skipBytes(lengthFieldEndOffset);
        throw new CorruptedFrameException(
                "negative pre-adjustment length field: " + frameLength);
    }

    private void exceededFrameLength(ByteBuf in, long frameLength) {
        long discard = frameLength - in.readableBytes();
        tooLongFrameLength = frameLength;

        if (discard < 0) {
            // buffer contains more bytes then the frameLength so we can discard all now
            in.skipBytes((int) frameLength);
        } else {
            // Enter the discard mode and discard everything received so far.
            discardingTooLongFrame = true;
            bytesToDiscard = discard;
            in.skipBytes(in.readableBytes());
        }
        failIfNecessary(true);
    }
}
