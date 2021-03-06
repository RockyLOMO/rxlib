package org.rx.net.shadowsocks.encryption;

import io.netty.buffer.ByteBuf;
import lombok.SneakyThrows;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.modes.AEADCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.rx.io.Bytes;

import java.nio.ByteBuffer;
import java.util.Arrays;

//TODO unfinished
public abstract class CryptoAeadBase implements ICrypto {
    protected static int PAYLOAD_SIZE_MASK = 0x3FFF;
    private static final byte[] info = "ss-subkey".getBytes();
    private static final byte[] ZERO_NONCE = new byte[getNonceLength()];

    private static int getNonceLength() {
        return 12;
    }

    protected static int getTagLength() {
        return 16;
    }

    protected static void increment(byte[] nonce) {
        for (int i = 0; i < nonce.length; i++) {
            ++nonce[i];
            if (nonce[i] != 0) {
                break;
            }
        }
    }

    protected final String _name;
    protected final ShadowSocksKey _ssKey;
    protected final int _keyLength;
    private boolean forUdp;
    protected boolean _encryptSaltSet;
    protected boolean _decryptSaltSet;
    protected final Object encLock = new Object();
    protected final Object decLock = new Object();
    protected AEADCipher encCipher;
    protected AEADCipher decCipher;
    private byte[] encSubkey;
    private byte[] decSubkey;
    protected byte[] encNonce;
    protected byte[] decNonce;
    protected byte[] encBuffer = new byte[2 + getTagLength() + PAYLOAD_SIZE_MASK + getTagLength()];
    protected byte[] decBuffer = new byte[PAYLOAD_SIZE_MASK + getTagLength()];
    /**
     * last chunk payload len already read size
     */
    protected int payloadLenRead = 0;
    /**
     * last chunk payload already read size
     */
    protected int payloadRead = 0;

    public CryptoAeadBase(String name, String password) {
        _name = name.toLowerCase();
        _keyLength = getKeyLength();
        _ssKey = new ShadowSocksKey(password, _keyLength);
    }

    @Override
    public void setForUdp(boolean forUdp) {
        this.forUdp = forUdp;
        if (!forUdp) {
            if (encNonce == null && decNonce == null) {
                encNonce = new byte[getNonceLength()];
                decNonce = new byte[getNonceLength()];
            }
        }
    }

    @SneakyThrows
    @Override
    public void encrypt(byte[] data, ByteBuf stream) {
        synchronized (encLock) {
            stream.clear();
            if (!_encryptSaltSet || forUdp) {
                byte[] salt = Bytes.randomBytes(getSaltLength());
                stream.writeBytes(salt);
                encSubkey = genSubkey(salt);
                encCipher = getCipher(true);
                _encryptSaltSet = true;
            }
            if (!forUdp) {
                _tcpEncrypt(data, stream);
            } else {
                _udpEncrypt(data, stream);
            }
        }
    }

    @Override
    public void encrypt(byte[] data, int length, ByteBuf stream) {
//        logger.debug("{} encrypt {}", this.hashCode(),new String(data, Charset.forName("GBK")));//
        byte[] d = Arrays.copyOfRange(data, 0, length);
        encrypt(d, stream);
    }

    @Override
    public void decrypt(byte[] data, ByteBuf stream) {
        byte[] temp;
        synchronized (decLock) {
            stream.clear();
            ByteBuffer buffer = ByteBuffer.wrap(data);
            if (decCipher == null || forUdp) {
                _decryptSaltSet = true;
                byte[] salt = new byte[getSaltLength()];
                buffer.get(salt);
                decSubkey = genSubkey(salt);
                decCipher = getCipher(false);
                temp = new byte[buffer.remaining()];
                buffer.get(temp);
            } else {
                temp = data;
            }
            if (!forUdp) {
                _tcpDecrypt(temp, stream);
            } else {
                _udpDecrypt(temp, stream);
            }
        }
    }

    @Override
    public void decrypt(byte[] data, int length, ByteBuf stream) {
//        logger.debug("{} decrypt {}", this.hashCode(),Arrays.toString(data));
        byte[] d = Arrays.copyOfRange(data, 0, length);
        decrypt(d, stream);
    }

    private byte[] genSubkey(byte[] salt) {
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA1Digest());
        hkdf.init(new HKDFParameters(_ssKey.getEncoded(), salt, info));
        byte[] okm = new byte[getKeyLength()];
        hkdf.generateBytes(okm, 0, getKeyLength());
        return okm;
    }

    protected CipherParameters getCipherParameters(boolean forEncryption) {
//        logger.debug("getCipherParameters subkey:{}",Arrays.toString(forEncryption ? encSubkey : decSubkey));
        byte[] nonce;
        if (!forUdp) {
            nonce = forEncryption ? Arrays.copyOf(encNonce, getNonceLength()) : Arrays.copyOf(decNonce, getNonceLength());
        } else {
            nonce = ZERO_NONCE;
        }
        return new AEADParameters(new KeyParameter(forEncryption ? encSubkey : decSubkey), getTagLength() * 8, nonce);
    }

    protected abstract int getKeyLength();

    protected abstract int getSaltLength();

    protected abstract AEADCipher getCipher(boolean isEncrypted);

    protected abstract void _tcpEncrypt(byte[] data, ByteBuf stream);

    protected abstract void _tcpDecrypt(byte[] data, ByteBuf stream);

    protected abstract void _udpEncrypt(byte[] data, ByteBuf stream);

    protected abstract void _udpDecrypt(byte[] data, ByteBuf stream);
}
