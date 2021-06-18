package org.rx.net.shadowsocks.encryption.impl;

import io.netty.buffer.ByteBuf;
import org.bouncycastle.crypto.StreamBlockCipher;
import org.bouncycastle.crypto.engines.BlowfishEngine;
import org.bouncycastle.crypto.modes.CFBBlockCipher;
import org.rx.net.shadowsocks.encryption.CryptoSteamBase;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public class BlowFishCrypto extends CryptoSteamBase {
    public BlowFishCrypto(String name, String password) {
        super(name, password);
    }

    @Override
    public int getIVLength() {
        return 8;
    }

    @Override
    public int getKeyLength() {
        return 16;
    }

    @Override
    protected SecretKey getKey() {
        return new SecretKeySpec(_ssKey.getEncoded(), "AES");
    }

    @Override
    protected StreamBlockCipher getCipher(boolean isEncrypted) {
        return new CFBBlockCipher(new BlowfishEngine(), getIVLength() * 8);
    }

    @Override
    protected void _encrypt(byte[] data, ByteBuf stream) {
        byte[] buffer = new byte[data.length];
        int noBytesProcessed = encCipher.processBytes(data, 0, data.length, buffer, 0);
        stream.writeBytes(buffer, 0, noBytesProcessed);
    }

    @Override
    protected void _decrypt(byte[] data, ByteBuf stream) {
        byte[] buffer = new byte[data.length];
        int noBytesProcessed = decCipher.processBytes(data, 0, data.length, buffer, 0);
        stream.writeBytes(buffer, 0, noBytesProcessed);
    }
}
