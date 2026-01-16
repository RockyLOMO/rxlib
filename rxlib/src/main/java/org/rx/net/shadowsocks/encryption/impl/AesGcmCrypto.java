package org.rx.net.shadowsocks.encryption.impl;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.AEADCipher;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.rx.io.Bytes;
import org.rx.net.shadowsocks.encryption.CryptoAeadBase;

import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;

@Slf4j
public class AesGcmCrypto extends CryptoAeadBase {
    public final static String AEAD_AES_128_GCM = "aes-128-gcm";
    public final static String AEAD_AES_192_GCM = "aes-192-gcm";
    public final static String AEAD_AES_256_GCM = "aes-256-gcm";

    public AesGcmCrypto(String name, String password) {
        super(name, password);
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
    protected void _tcpEncrypt(byte[] data, int length, ByteBuf out) {
        ByteBuffer buffer = ByteBuffer.wrap(data, 0, length);
        int lenTagSize = 2 + getTagLength();

        while (buffer.hasRemaining()) {
            int chunkSize = Math.min(buffer.remaining(), PAYLOAD_SIZE_MASK);
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
            buffer.get(encBuffer, lenTagSize, chunkSize);
            encCipher.init(true, getCipherParameters(true));
            encCipher.doFinal(
                    encBuffer,
                    lenTagSize + encCipher.processBytes(encBuffer, lenTagSize, chunkSize, encBuffer, lenTagSize)
            );
            increment(this.encNonce);
            out.writeBytes(encBuffer, lenTagSize, chunkSize + getTagLength());
        }
    }

    @SneakyThrows
    @Override
    protected void _tcpDecrypt(byte[] data, int offset, int length, ByteBuf stream) {
        ByteBuffer buffer = ByteBuffer.wrap(data, offset, length);
        while (buffer.hasRemaining()) {
            // 1. 读取并解密长度信息 (2字节长度 + 16字节Tag)
            if (payloadRead == 0) {
                int wantLen = 2 + getTagLength() - payloadLenRead;
                int remaining = buffer.remaining();
                int canRead = Math.min(wantLen, remaining);

                buffer.get(decBuffer, payloadLenRead, canRead);
                payloadLenRead += canRead;

                if (payloadLenRead < 2 + getTagLength()) {
                    return; // 长度信息还没读够，等下一波数据
                }

                // 解密长度字段
                decCipher.init(false, getCipherParameters(false));
                decCipher.doFinal(
                        decBuffer,
                        decCipher.processBytes(decBuffer, 0, 2 + getTagLength(), decBuffer, 0)
                );
                increment(decNonce);

                // 标记：我们现在已经拿到了解密后的长度，准备读取 Payload
                // 故意让 payloadRead = 0.0001 (或者使用 boolean flag) 来区分状态
                payloadRead = -1;
            }

            // 2. 解析长度
            int size = ((decBuffer[0] & 0xFF) << 8) | (decBuffer[1] & 0xFF);
            if (size > PAYLOAD_SIZE_MASK) {
                throw new DecoderException("Payload too large: " + size);
            }

            // 3. 读取并解密 Payload (size字节数据 + 16字节Tag)
            int currentPayloadRead = (payloadRead == -1) ? 0 : payloadRead;
            int wantLen = size + getTagLength() - currentPayloadRead;
            int remaining = buffer.remaining();
            int canRead = Math.min(wantLen, remaining);

            // 写入偏移量从 2+Tag 开始，避免覆盖已经解密的长度信息（如果逻辑需要）
            buffer.get(decBuffer, 2 + getTagLength() + currentPayloadRead, canRead);

            if (payloadRead == -1) payloadRead = 0;
            payloadRead += canRead;

            if (payloadRead < size + getTagLength()) {
                return; // Payload 还没读够
            }

            // 解密 Payload
            decCipher.init(false, getCipherParameters(false));
            decCipher.doFinal(
                    decBuffer,
                    (2 + getTagLength()) + decCipher.processBytes(decBuffer, 2 + getTagLength(), size + getTagLength(), decBuffer, 2 + getTagLength())
            );
            increment(decNonce);

            // 写入结果并重置
            stream.writeBytes(decBuffer, 2 + getTagLength(), size);

            payloadLenRead = 0;
            payloadRead = 0;
        }
    }

    @SneakyThrows
    @Override
    protected void _udpEncrypt(byte[] data, int length, ByteBuf stream) {
        ByteBuffer buffer = ByteBuffer.wrap(data, 0, length);
        int remaining = buffer.remaining();
        buffer.get(encBuffer, 0, remaining);
        encCipher.init(true, getCipherParameters(true));
        encCipher.doFinal(
                encBuffer,
                encCipher.processBytes(encBuffer, 0, remaining, encBuffer, 0)
        );
        stream.writeBytes(encBuffer, 0, remaining + getTagLength());
    }

    @SneakyThrows
    @Override
    protected void _udpDecrypt(byte[] data, int offset, int length, ByteBuf stream) {
        ByteBuffer buffer = ByteBuffer.wrap(data, offset, length);
        int remaining = buffer.remaining();
        buffer.get(decBuffer, 0, remaining);
        decCipher.init(false, getCipherParameters(false));
        decCipher.doFinal(
                decBuffer,
                decCipher.processBytes(decBuffer, 0, remaining, decBuffer, 0)
        );
        stream.writeBytes(decBuffer, 0, remaining - getTagLength());
    }
}
