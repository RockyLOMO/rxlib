package org.rx.net.socks.encryption.impl;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import lombok.SneakyThrows;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.AEADCipher;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.rx.net.socks.encryption.CryptoAeadBase;

import java.security.InvalidAlgorithmParameterException;

public class AesGcmCrypto extends CryptoAeadBase {
    public final static String AEAD_AES_128_GCM = "aes-128-gcm";
    public final static String AEAD_AES_192_GCM = "aes-192-gcm";
    public final static String AEAD_AES_256_GCM = "aes-256-gcm";

    // TCP 流式解密状态机（跨多次 decrypt 调用保持）
    private boolean readingLengthPhase = true;
    private int currentPayloadLength = 0;
    private int phaseBytesRead = 0;

    public AesGcmCrypto(String name, String password) {
        super(name, password);
    }

    @Override
    protected void resetDecryptState() {
        readingLengthPhase = true;
        currentPayloadLength = 0;
        phaseBytesRead = 0;
    }

    @Override
    public int getKeyLength() {
        switch (_name) {
            case AEAD_AES_128_GCM:
                return 16;
            case AEAD_AES_192_GCM:
                return 24;
            case AEAD_AES_256_GCM:
                return 32;
        }
        return 0;
    }

    @Override
    public int getSaltLength() {
        switch (_name) {
            case AEAD_AES_128_GCM:
                return 16;
            case AEAD_AES_192_GCM:
                return 24;
            case AEAD_AES_256_GCM:
                return 32;
        }
        return 0;
    }

    @SneakyThrows
    @Override
    protected AEADCipher getCipher(boolean isEncrypted) {
        switch (_name) {
            case AEAD_AES_128_GCM:
            case AEAD_AES_192_GCM:
            case AEAD_AES_256_GCM:
                return GCMBlockCipher.newInstance(AESEngine.newInstance());
//                return new GCMBlockCipher(new AESEngine());
            default:
                throw new InvalidAlgorithmParameterException(_name);
        }
    }

    /**
     * TCP:[encrypted payload length][length tag][encrypted payload][payload tag]
     * UDP:[salt][encrypted payload][tag]
     */
    @SneakyThrows
    @Override
    protected void _tcpEncrypt(ByteBuf in, ByteBuf out) {
        int lenTagSize = 2 + TAG_LENGTH;

        while (in.isReadable()) {
            int chunkSize = Math.min(in.readableBytes(), PAYLOAD_SIZE_MASK);
            // 加密长度字段
            encBuffer[0] = (byte) (chunkSize >> 8);
            encBuffer[1] = (byte) chunkSize;
            encCipher.init(true, getCipherParameters(true));
            encCipher.doFinal(
                    encBuffer,
                    encCipher.processBytes(encBuffer, 0, 2, encBuffer, 0)
            );
            increment(this.encNonce);
            out.writeBytes(encBuffer, 0, lenTagSize);

            // 加密 payload
            in.readBytes(encBuffer, lenTagSize, chunkSize);
            encCipher.init(true, getCipherParameters(true));
            encCipher.doFinal(
                    encBuffer,
                    lenTagSize + encCipher.processBytes(encBuffer, lenTagSize, chunkSize, encBuffer, lenTagSize)
            );
            increment(this.encNonce);
            out.writeBytes(encBuffer, lenTagSize, chunkSize + TAG_LENGTH);
        }
    }

    @SneakyThrows
    @Override
    protected void _tcpDecrypt(ByteBuf in, ByteBuf out) {
        int lenTagSize = 2 + TAG_LENGTH;

        while (in.isReadable()) {
            if (readingLengthPhase) {
                // 读取并解密长度字段
                int toRead = lenTagSize - phaseBytesRead;
                int readNow = Math.min(toRead, in.readableBytes());
                in.readBytes(decBuffer, phaseBytesRead, readNow);
                phaseBytesRead += readNow;

                if (phaseBytesRead < lenTagSize) {
                    return; // 需要更多数据
                }

                decCipher.init(false, getCipherParameters(false));
                decCipher.doFinal(
                        decBuffer,
                        decCipher.processBytes(decBuffer, 0, lenTagSize, decBuffer, 0)
                );
                increment(decNonce);

                currentPayloadLength = ((decBuffer[0] & 0xFF) << 8) | (decBuffer[1] & 0xFF);
                if (currentPayloadLength > PAYLOAD_SIZE_MASK) {
                    throw new DecoderException("Payload too large: " + currentPayloadLength);
                }

                readingLengthPhase = false;
                phaseBytesRead = 0;
            } else {
                // 读取并解密 payload + tag
                int payloadTagSize = currentPayloadLength + TAG_LENGTH;
                int toRead = payloadTagSize - phaseBytesRead;
                int readNow = Math.min(toRead, in.readableBytes());
                in.readBytes(decBuffer, lenTagSize + phaseBytesRead, readNow);
                phaseBytesRead += readNow;

                if (phaseBytesRead < payloadTagSize) {
                    return; // 需要更多数据
                }

                decCipher.init(false, getCipherParameters(false));
                decCipher.doFinal(
                        decBuffer,
                        lenTagSize + decCipher.processBytes(decBuffer, lenTagSize, payloadTagSize, decBuffer, lenTagSize)
                );
                increment(decNonce);

                out.writeBytes(decBuffer, lenTagSize, currentPayloadLength);

                readingLengthPhase = true;
                phaseBytesRead = 0;
                currentPayloadLength = 0;
            }
        }
    }

    @SneakyThrows
    @Override
    protected void _udpEncrypt(ByteBuf in, ByteBuf out) {
        int length = in.readableBytes();
        in.readBytes(encBuffer, 0, length);
        encCipher.init(true, getCipherParameters(true));
        encCipher.doFinal(
                encBuffer,
                encCipher.processBytes(encBuffer, 0, length, encBuffer, 0)
        );
        out.writeBytes(encBuffer, 0, length + TAG_LENGTH);
    }

    @SneakyThrows
    @Override
    protected void _udpDecrypt(ByteBuf in, ByteBuf out) {
        int length = in.readableBytes();  // encrypted payload + tag
        byte[] encrypted = new byte[length];  // 独立输入缓冲区
        in.readBytes(encrypted, 0, length);
        decCipher.init(false, getCipherParameters(false));
        decCipher.doFinal(
                decBuffer,
                decCipher.processBytes(encrypted, 0, length, decBuffer, 0)
        );

//        int length = in.readableBytes();
//        in.readBytes(decBuffer, 0, length);
//        decCipher.init(false, getCipherParameters(false));
//        decCipher.doFinal(
//                decBuffer,
//                decCipher.processBytes(decBuffer, 0, length, decBuffer, 0)
//        );
        out.writeBytes(decBuffer, 0, length - TAG_LENGTH);
    }
}
