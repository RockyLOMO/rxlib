package org.rx.net.shadowsocks.encryption;

import lombok.Setter;
import lombok.SneakyThrows;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.StreamCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import javax.crypto.SecretKey;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Arrays;

public abstract class CryptoSteamBase implements ICrypto {
    protected final String _name;
    protected final SecretKey _key;
    protected final ShadowSocksKey _ssKey;
    protected final int _ivLength;
    protected final int _keyLength;
    @Setter
    protected boolean forUdp;
    protected boolean _encryptIVSet;
    protected boolean _decryptIVSet;
    protected byte[] _encryptIV;
    protected byte[] _decryptIV;
    protected final Object encLock = new Object();
    protected final Object decLock = new Object();
    protected StreamCipher encCipher;
    protected StreamCipher decCipher;

    public CryptoSteamBase(String name, String password) {
        _name = name.toLowerCase();
        _ivLength = getIVLength();
        _keyLength = getKeyLength();
        _ssKey = new ShadowSocksKey(password, _keyLength);
        _key = getKey();
    }

    protected void setIV(byte[] iv, boolean isEncrypt) {
        if (_ivLength == 0) {
            return;
        }

        CipherParameters cipherParameters;
        if (isEncrypt) {
            cipherParameters = getCipherParameters(iv);
            encCipher = getCipher(isEncrypt);
            encCipher.init(isEncrypt, cipherParameters);
        } else {
            _decryptIV = Arrays.copyOfRange(iv, 0, _ivLength);
            cipherParameters = getCipherParameters(iv);
            decCipher = getCipher(isEncrypt);
            decCipher.init(isEncrypt, cipherParameters);
        }
    }

    protected CipherParameters getCipherParameters(byte[] iv) {
        _decryptIV = Arrays.copyOfRange(iv, 0, _ivLength);
        return new ParametersWithIV(new KeyParameter(_key.getEncoded()), _decryptIV);
    }

    @SneakyThrows
    @Override
    public void encrypt(byte[] data, ByteArrayOutputStream stream) {
        synchronized (encLock) {
            stream.reset();
            if (!_encryptIVSet || forUdp) {
                _encryptIVSet = true;
                byte[] iv = randomBytes(_ivLength);
                setIV(iv, true);
                stream.write(iv);
            }
            _encrypt(data, stream);
        }
    }

    @Override
    public void encrypt(byte[] data, int length, ByteArrayOutputStream stream) {
        byte[] d = Arrays.copyOfRange(data, 0, length);
        encrypt(d, stream);
    }

    @Override
    public void decrypt(byte[] data, ByteArrayOutputStream stream) {
        byte[] temp;
        synchronized (decLock) {
            stream.reset();
            if (!_decryptIVSet || forUdp) {
                _decryptIVSet = true;
                setIV(data, false);
                temp = Arrays.copyOfRange(data, _ivLength, data.length);
            } else {
                temp = data;
            }
            _decrypt(temp, stream);
        }
    }

    @Override
    public void decrypt(byte[] data, int length, ByteArrayOutputStream stream) {
        byte[] d = Arrays.copyOfRange(data, 0, length);
        decrypt(d, stream);
    }

    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    protected abstract int getIVLength();

    protected abstract int getKeyLength();

    protected abstract SecretKey getKey();

    protected abstract StreamCipher getCipher(boolean isEncrypted);

    protected abstract void _encrypt(byte[] data, ByteArrayOutputStream stream);

    protected abstract void _decrypt(byte[] data, ByteArrayOutputStream stream);
}
