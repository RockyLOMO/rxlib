//package org.rx.net.shadowsocks.encryption.impl;
//
//import io.netty.buffer.ByteBuf;
//import lombok.SneakyThrows;
//import org.bouncycastle.crypto.modes.AEADCipher;
//import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
//import org.rx.net.shadowsocks.encryption.CryptoAeadBase;
//
//import java.nio.ByteBuffer;
//
//public class ChaCha20Poly1305Crypto extends CryptoAeadBase {
//    /**
//     * last chunk payload len already read size
//     */
//    protected int payloadLenRead = 0;
//    /**
//     * last chunk payload already read size
//     */
//    protected int payloadRead = 0;
//
//    public ChaCha20Poly1305Crypto(String name, String password) {
//        super(name, password);
//    }
//
//    @Override
//    protected int getKeyLength() {
//        return 32;
//    }
//
//    @Override
//    protected int getSaltLength() {
//        return 32;
//    }
//
//    @Override
//    protected AEADCipher getCipher(boolean isEncrypted) {
//        return new ChaCha20Poly1305();
//    }
//
//    @SneakyThrows
//    @Override
//    protected void _tcpEncrypt(byte[] data, int length, ByteBuf out) {
//        ByteBuffer buffer = ByteBuffer.wrap(data, 0, length);
//        while (buffer.hasRemaining()) {
//            int nr = Math.min(buffer.remaining(), PAYLOAD_SIZE_MASK);
//            ByteBuffer.wrap(encBuffer).putShort((short) nr);
//            encCipher.init(true, getCipherParameters(true));
//            encCipher.doFinal(
//                    encBuffer,
//                    encCipher.processBytes(encBuffer, 0, 2, encBuffer, 0)
//            );
//            out.writeBytes(encBuffer, 0, 2 + getTagLength());
//            increment(this.encNonce);
//
//            buffer.get(encBuffer, 2 + getTagLength(), nr);
//
//            encCipher.init(true, getCipherParameters(true));
//            encCipher.doFinal(
//                    encBuffer,
//                    2 + getTagLength() + encCipher.processBytes(encBuffer, 2 + getTagLength(), nr, encBuffer, 2 + getTagLength())
//            );
//            increment(this.encNonce);
//
//            out.writeBytes(encBuffer, 2 + getTagLength(), nr + getTagLength());
//        }
//    }
//
//    @SneakyThrows
//    @Override
//    protected void _tcpDecrypt(byte[] data, int offset, int length, ByteBuf out) {
//        ByteBuffer buffer = ByteBuffer.wrap(data, offset, length);
//        while (buffer.hasRemaining()) {
//            if (payloadRead == 0) {
//                int wantLen = 2 + getTagLength() - payloadLenRead;
//                int remaining = buffer.remaining();
//                if (wantLen <= remaining) {
//                    buffer.get(decBuffer, payloadLenRead, wantLen);
//                } else {
//                    buffer.get(decBuffer, payloadLenRead, remaining);
//                    payloadLenRead += remaining;
//                    return;
//                }
//                decCipher.init(false, getCipherParameters(false));
//                decCipher.doFinal(
//                        decBuffer,
//                        decCipher.processBytes(decBuffer, 0, 2 + getTagLength(), decBuffer, 0)
//                );
//                increment(decNonce);
//            }
//
//            int size = ByteBuffer.wrap(decBuffer, 0, 2).getShort();
//            if (size == 0) {
//                //TODO exists?
//                return;
//            } else {
//                int wantLen = getTagLength() + size - payloadRead;
//                int remaining = buffer.remaining();
//                if (wantLen <= remaining) {
//                    buffer.get(decBuffer, 2 + getTagLength() + payloadRead, wantLen);
//                } else {
//                    buffer.get(decBuffer, 2 + getTagLength() + payloadRead, remaining);
//                    payloadRead += remaining;
//                    return;
//                }
//            }
//
//            decCipher.init(false, getCipherParameters(false));
//            decCipher.doFinal(
//                    decBuffer,
//                    (2 + getTagLength()) + decCipher.processBytes(decBuffer, 2 + getTagLength(), size + getTagLength(), decBuffer, 2 + getTagLength())
//            );
//            increment(decNonce);
//
//            payloadLenRead = 0;
//            payloadRead = 0;
//
//            out.writeBytes(decBuffer, 2 + getTagLength(), size);
//        }
//        //        while (buffer.hasRemaining()) {
////            // 1. 读取并解密长度信息 (2字节长度 + 16字节Tag)
////            if (payloadRead == 0) {
////                int wantLen = lenTagSize - payloadLenRead;
////                int remaining = buffer.remaining();
////                int canRead = Math.min(wantLen, remaining);
////
////                buffer.get(decBuffer, payloadLenRead, canRead);
////                payloadLenRead += canRead;
////
////                if (payloadLenRead < 2 + getTagLength()) {
////                    return; // 长度信息还没读够，等下一波数据
////                }
////
////                // 解密长度字段
////                decCipher.init(false, getCipherParameters(false));
////                decCipher.doFinal(
////                        decBuffer,
////                        decCipher.processBytes(decBuffer, 0, 2 + getTagLength(), decBuffer, 0)
////                );
////                increment(decNonce);
////
////                // 标记：我们现在已经拿到了解密后的长度，准备读取 Payload
////                // 故意让 payloadRead = 0.0001 (或者使用 boolean flag) 来区分状态
////                payloadRead = -1;
////            }
////
////            // 2. 解析长度
////            int size = ((decBuffer[0] & 0xFF) << 8) | (decBuffer[1] & 0xFF);
////            if (size > PAYLOAD_SIZE_MASK) {
////                throw new DecoderException("Payload too large: " + size);
////            }
////
////            // 3. 读取并解密 Payload (size字节数据 + 16字节Tag)
////            int currentPayloadRead = (payloadRead == -1) ? 0 : payloadRead;
////            int wantLen = size + getTagLength() - currentPayloadRead;
////            int remaining = buffer.remaining();
////            int canRead = Math.min(wantLen, remaining);
////
////            // 写入偏移量从 2+Tag 开始，避免覆盖已经解密的长度信息（如果逻辑需要）
////            buffer.get(decBuffer, 2 + getTagLength() + currentPayloadRead, canRead);
////
////            if (payloadRead == -1) payloadRead = 0;
////            payloadRead += canRead;
////
////            if (payloadRead < size + getTagLength()) {
////                return; // Payload 还没读够
////            }
////
////            // 解密 Payload
////            decCipher.init(false, getCipherParameters(false));
////            decCipher.doFinal(
////                    decBuffer,
////                    (2 + getTagLength()) + decCipher.processBytes(decBuffer, 2 + getTagLength(), size + getTagLength(), decBuffer, 2 + getTagLength())
////            );
////            increment(decNonce);
////
////            // 写入结果并重置
////            out.writeBytes(decBuffer, 2 + getTagLength(), size);
////
////            payloadLenRead = 0;
////            payloadRead = 0;
////        }
//    }
//
//    @SneakyThrows
//    @Override
//    protected void _udpEncrypt(byte[] data, int length, ByteBuf stream) {
//        ByteBuffer buffer = ByteBuffer.wrap(data, 0, length);
//        int remaining = buffer.remaining();
//        buffer.get(encBuffer, 0, remaining);
//        encCipher.init(true, getCipherParameters(true));
//        encCipher.doFinal(
//                encBuffer,
//                encCipher.processBytes(encBuffer, 0, remaining, encBuffer, 0)
//        );
//        stream.writeBytes(encBuffer, 0, remaining + getTagLength());
//    }
//
//    @SneakyThrows
//    @Override
//    protected void _udpDecrypt(byte[] data, int offset, int length, ByteBuf stream) {
//        ByteBuffer buffer = ByteBuffer.wrap(data, offset, length);
//        int remaining = buffer.remaining();
//        buffer.get(decBuffer, 0, remaining);
//        decCipher.init(false, getCipherParameters(false));
//        decCipher.doFinal(
//                decBuffer,
//                decCipher.processBytes(decBuffer, 0, remaining, decBuffer, 0)
//        );
//        stream.writeBytes(decBuffer, 0, remaining - getTagLength());
//    }
//}
