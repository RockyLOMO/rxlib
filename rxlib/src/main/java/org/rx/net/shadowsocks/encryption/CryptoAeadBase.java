package org.rx.net.shadowsocks.encryption;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.modes.AEADCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.rx.codec.CodecUtil;
import org.rx.io.Bytes;

import java.nio.ByteBuffer;
import java.util.Arrays;

public abstract class CryptoAeadBase implements ICrypto {
    protected static int PAYLOAD_SIZE_MASK = 0x3FFF;
    private static final int NONCE_LENGTH = 12;
    private static final byte[] INFO = "ss-subkey".getBytes();
    private static final byte[] ZERO_NONCE = new byte[NONCE_LENGTH];

    protected static int getTagLength() {
        return 16;
    }

    protected static void increment(byte[] nonce) {
        for (int i = 0; i < nonce.length; i++) {
            if (++nonce[i] != 0) {
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
    protected final byte[] encBuffer = new byte[2 + getTagLength() + PAYLOAD_SIZE_MASK + getTagLength()];
    protected final byte[] decBuffer = new byte[2 + getTagLength() + PAYLOAD_SIZE_MASK + getTagLength()];

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
                encNonce = new byte[NONCE_LENGTH];
                decNonce = new byte[NONCE_LENGTH];
            }
        }
    }

    @Override
    public ByteBuf encrypt(ByteBuf in) {
        ByteBuf out = Bytes.directBuffer();
        synchronized (encLock) {
            if (!_encryptSaltSet || forUdp) {
                byte[] salt = CodecUtil.secureRandomBytes(getSaltLength());
                out.writeBytes(salt);
                encSubkey = genSubkey(salt);
                encCipher = getCipher(true);
                _encryptSaltSet = true;
            }
            if (!forUdp) {
                _tcpEncrypt(in, out);
            } else {
                _udpEncrypt(in, out);
            }
        }
        return out;
    }

    @Override
    public ByteBuf decrypt(ByteBuf in) {
        ByteBuf out = Bytes.directBuffer();
        synchronized (decLock) {
            boolean newSession = decCipher == null;
            if (newSession || forUdp) {
                int length = in.readableBytes();
                int saltLen = getSaltLength();
                if (length < saltLen + getTagLength()) {
                    throw new DecoderException("Packet too short");
                }
                byte[] salt = new byte[saltLen];
                in.readBytes(salt);
                decSubkey = genSubkey(salt);
                decCipher = getCipher(false);
                _decryptSaltSet = true;

//                int payloadAndTagLen = length - saltLen;
                if (!forUdp) {
                    resetDecryptState();
                    // TCP 首次：处理 salt 后面的 chunk 数据
                    _tcpDecrypt(in, out);
                } else {
                    // UDP：直接解密 payload + tag
                    _udpDecrypt(in, out);
                }
            } else {
                if (!forUdp) {
                    _tcpDecrypt(in, out);
                } else {
                    _udpDecrypt(in, out);
                }
            }
        }
        return out;
    }

    protected void resetDecryptState() {
        // 子类实现，用于重置 TCP 流式状态
    }

    private byte[] genSubkey(byte[] salt) {
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA1Digest());
        hkdf.init(new HKDFParameters(_ssKey.getEncoded(), salt, INFO));
        byte[] okm = new byte[getKeyLength()];
        hkdf.generateBytes(okm, 0, getKeyLength());
        return okm;
    }

    protected CipherParameters getCipherParameters(boolean forEncryption) {
//        logger.debug("getCipherParameters subkey:{}",Arrays.toString(forEncryption ? encSubkey : decSubkey));
        byte[] nonce;
        if (!forUdp) {
            nonce = forEncryption ? Arrays.copyOf(encNonce, NONCE_LENGTH) : Arrays.copyOf(decNonce, NONCE_LENGTH);
//            nonce = forEncryption ? encNonce : decNonce;
        } else {
            nonce = ZERO_NONCE;
        }
        return new AEADParameters(new KeyParameter(forEncryption ? encSubkey : decSubkey), getTagLength() * 8, nonce);
    }

    protected abstract int getKeyLength();

    protected abstract int getSaltLength();

    protected abstract AEADCipher getCipher(boolean isEncrypted);

    protected abstract void _tcpEncrypt(ByteBuf in, ByteBuf out);

    protected abstract void _tcpDecrypt(ByteBuf in, ByteBuf out);

    protected abstract void _udpEncrypt(ByteBuf in, ByteBuf out);

    protected abstract void _udpDecrypt(ByteBuf in, ByteBuf out);
}
