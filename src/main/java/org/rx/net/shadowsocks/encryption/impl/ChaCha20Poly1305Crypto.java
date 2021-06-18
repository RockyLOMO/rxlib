package org.rx.net.shadowsocks.encryption.impl;

import io.netty.buffer.ByteBuf;
import lombok.SneakyThrows;
import org.bouncycastle.crypto.modes.AEADCipher;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.rx.net.shadowsocks.encryption.CryptoAeadBase;

import java.nio.ByteBuffer;

public class ChaCha20Poly1305Crypto extends CryptoAeadBase {
    public ChaCha20Poly1305Crypto(String name, String password) {
        super(name, password);
    }

    @Override
    protected int getKeyLength() {
        return 32;
    }

    @Override
    protected int getSaltLength() {
        return 32;
    }

    @Override
    protected AEADCipher getCipher(boolean isEncrypted) {
        return new ChaCha20Poly1305();
    }

    @SneakyThrows
    @Override
    protected void _tcpEncrypt(byte[] data, ByteBuf stream) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) {
            int nr = Math.min(buffer.remaining(), PAYLOAD_SIZE_MASK);
            ByteBuffer.wrap(encBuffer).putShort((short) nr);
            encCipher.init(true, getCipherParameters(true));
            encCipher.doFinal(
                    encBuffer,
                    encCipher.processBytes(encBuffer, 0, 2, encBuffer, 0)
            );
            stream.writeBytes(encBuffer, 0, 2 + getTagLength());
            increment(this.encNonce);

            buffer.get(encBuffer, 2 + getTagLength(), nr);

            encCipher.init(true, getCipherParameters(true));
            encCipher.doFinal(
                    encBuffer,
                    2 + getTagLength() + encCipher.processBytes(encBuffer, 2 + getTagLength(), nr, encBuffer, 2 + getTagLength())
            );
            increment(this.encNonce);

            stream.writeBytes(encBuffer, 2 + getTagLength(), nr + getTagLength());
        }
    }

    @SneakyThrows
    @Override
    protected void _tcpDecrypt(byte[] data, ByteBuf stream) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) {
            if (payloadRead == 0) {
                int wantLen = 2 + getTagLength() - payloadLenRead;
                int remaining = buffer.remaining();
                if (wantLen <= remaining) {
                    buffer.get(decBuffer, payloadLenRead, wantLen);
                } else {
                    buffer.get(decBuffer, payloadLenRead, remaining);
                    payloadLenRead += remaining;
                    return;
                }
                decCipher.init(false, getCipherParameters(false));
                decCipher.doFinal(
                        decBuffer,
                        decCipher.processBytes(decBuffer, 0, 2 + getTagLength(), decBuffer, 0)
                );
                increment(decNonce);
            }

            int size = ByteBuffer.wrap(decBuffer, 0, 2).getShort();
            if (size == 0) {
                //TODO exists?
                return;
            } else {
                int wantLen = getTagLength() + size - payloadRead;
                int remaining = buffer.remaining();
                if (wantLen <= remaining) {
                    buffer.get(decBuffer, 2 + getTagLength() + payloadRead, wantLen);
                } else {
                    buffer.get(decBuffer, 2 + getTagLength() + payloadRead, remaining);
                    payloadRead += remaining;
                    return;
                }
            }

            decCipher.init(false, getCipherParameters(false));
            decCipher.doFinal(
                    decBuffer,
                    (2 + getTagLength()) + decCipher.processBytes(decBuffer, 2 + getTagLength(), size + getTagLength(), decBuffer, 2 + getTagLength())
            );
            increment(decNonce);

            payloadLenRead = 0;
            payloadRead = 0;

            stream.writeBytes(decBuffer, 2 + getTagLength(), size);
        }
    }

    @SneakyThrows
    @Override
    protected void _udpEncrypt(byte[] data, ByteBuf stream) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
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
    protected void _udpDecrypt(byte[] data, ByteBuf stream) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
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
