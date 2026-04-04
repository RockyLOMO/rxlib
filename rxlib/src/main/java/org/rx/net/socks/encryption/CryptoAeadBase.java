package org.rx.net.socks.encryption;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.util.concurrent.FastThreadLocal;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.modes.AEADCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.rx.codec.CodecUtil;
import org.rx.io.Bytes;

public abstract class CryptoAeadBase implements ICrypto {
    protected static int PAYLOAD_SIZE_MASK = 0x3FFF;
    protected static final int TAG_LENGTH = 16;
    private static final int NONCE_LENGTH = 12;
    private static final byte[] INFO = "ss-subkey".getBytes();
    private static final byte[] ZERO_NONCE = new byte[NONCE_LENGTH];

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
    protected boolean forUdp;
    //TCP fields
    protected AEADCipher encCipher;
    protected AEADCipher decCipher;
    protected byte[] encSubkey;
    protected byte[] decSubkey;
    protected byte[] encNonce;
    protected byte[] decNonce;
    protected final byte[] encBuffer = new byte[2 + TAG_LENGTH + PAYLOAD_SIZE_MASK + TAG_LENGTH];
    protected final byte[] decBuffer = new byte[2 + TAG_LENGTH + PAYLOAD_SIZE_MASK + TAG_LENGTH];

    //UDP fields (FastThreadLocal for shared ICrypto instance)
    private static final FastThreadLocal<HKDFBytesGenerator> HKDF_HOLDER = new FastThreadLocal<>();
    private final FastThreadLocal<byte[]> udpEncBuffer = new FastThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[2 + TAG_LENGTH + PAYLOAD_SIZE_MASK + TAG_LENGTH];
        }
    };
    private final FastThreadLocal<byte[]> udpDecBuffer = new FastThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[2 + TAG_LENGTH + PAYLOAD_SIZE_MASK + TAG_LENGTH];
        }
    };
    private final FastThreadLocal<AEADCipher> udpEncCipher = new FastThreadLocal<>();
    private final FastThreadLocal<AEADCipher> udpDecCipher = new FastThreadLocal<>();
    private final FastThreadLocal<byte[]> udpEncSubkey = new FastThreadLocal<>();
    private final FastThreadLocal<byte[]> udpDecSubkey = new FastThreadLocal<>();

    protected byte[] encBuffer() {
        return forUdp ? udpEncBuffer.get() : encBuffer;
    }

    protected byte[] decBuffer() {
        return forUdp ? udpDecBuffer.get() : decBuffer;
    }

    protected AEADCipher getEncCipher() {
        if (forUdp) {
            AEADCipher c = udpEncCipher.get();
            if (c == null) {
                udpEncCipher.set(c = getCipher(true));
            }
            return c;
        }
        return encCipher;
    }

    protected void setEncCipher(AEADCipher c) {
        if (forUdp) {
            udpEncCipher.set(c);
        } else {
            encCipher = c;
        }
    }

    protected AEADCipher getDecCipher() {
        if (forUdp) {
            AEADCipher c = udpDecCipher.get();
            if (c == null) {
                udpDecCipher.set(c = getCipher(false));
            }
            return c;
        }
        return decCipher;
    }

    protected void setDecCipher(AEADCipher c) {
        if (forUdp) {
            udpDecCipher.set(c);
        } else {
            decCipher = c;
        }
    }

    protected byte[] getEncSubkey() {
        return forUdp ? udpEncSubkey.get() : encSubkey;
    }

    protected void setEncSubkey(byte[] s) {
        if (forUdp) {
            udpEncSubkey.set(s);
        } else {
            encSubkey = s;
        }
    }

    protected byte[] getDecSubkey() {
        return forUdp ? udpDecSubkey.get() : decSubkey;
    }

    protected void setDecSubkey(byte[] s) {
        if (forUdp) {
            udpDecSubkey.set(s);
        } else {
            decSubkey = s;
        }
    }

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
        int estimatedSize = forUdp
                ? getSaltLength() + in.readableBytes() + TAG_LENGTH
                : (encCipher == null ? getSaltLength() : 0) + in.readableBytes() + ((in.readableBytes() / PAYLOAD_SIZE_MASK) + 1) * (2 + TAG_LENGTH * 2);
        ByteBuf out = Bytes.directBuffer(estimatedSize);
        try {
            if (forUdp) {
                byte[] salt = CodecUtil.secureRandomBytes(getSaltLength());
                out.writeBytes(salt);
                setEncSubkey(genSubkey(salt));
                // getEncCipher() will auto-init if null
                _udpEncrypt(in, out);
            } else {
                boolean newSession = encCipher == null;
                if (newSession) {
                    byte[] salt = CodecUtil.secureRandomBytes(getSaltLength());
                    out.writeBytes(salt);
                    encSubkey = genSubkey(salt);
                    encCipher = getCipher(true);
                }
                _tcpEncrypt(in, out);
            }
            return out;
        } catch (Exception e) {
//            out.release();
            throw e;
        }
    }

    @Override
    public ByteBuf decrypt(ByteBuf in) {
        int estimatedSize;
        if (forUdp) {
            estimatedSize = Math.max(0, in.readableBytes() - getSaltLength() - TAG_LENGTH);
        } else {
            if (decCipher == null) {
                estimatedSize = Math.max(0, in.readableBytes() - getSaltLength());
            } else {
                estimatedSize = in.readableBytes();
            }
        }
        ByteBuf out = Bytes.directBuffer(estimatedSize);
        try {
            if (forUdp) {
                int length = in.readableBytes();
                int saltLen = getSaltLength();
                if (length < saltLen + TAG_LENGTH) {
                    throw new DecoderException("Packet too short");
                }
                byte[] salt = new byte[saltLen];
                in.readBytes(salt);
                setDecSubkey(genSubkey(salt));
                // getDecCipher() will auto-init if null
                _udpDecrypt(in, out);
            } else {
                boolean newSession = decCipher == null;
                if (newSession) {
                    int length = in.readableBytes();
                    int saltLen = getSaltLength();
                    if (length < saltLen + TAG_LENGTH) {
                        throw new DecoderException("Packet too short");
                    }
                    byte[] salt = new byte[saltLen];
                    in.readBytes(salt);
                    decSubkey = genSubkey(salt);
                    decCipher = getCipher(false);
                    // TCP 首次：处理 salt 后面的 chunk 数据 int payloadAndTagLen = length - saltLen;
                    resetDecryptState();
                }
                _tcpDecrypt(in, out);
            }
            return out;
        } catch (Exception e) {
//            out.release();
            throw e;
        }
    }

    protected void resetDecryptState() {
        // 子类实现，用于重置 TCP 流式状态
    }

    private byte[] genSubkey(byte[] salt) {
        HKDFBytesGenerator hkdf = HKDF_HOLDER.get();
        if (hkdf == null) {
            HKDF_HOLDER.set(hkdf = new HKDFBytesGenerator(new SHA1Digest()));
        }
        hkdf.init(new HKDFParameters(_ssKey.getEncoded(), salt, INFO));
        byte[] okm = new byte[getKeyLength()];
        hkdf.generateBytes(okm, 0, getKeyLength());
        return okm;
    }

    protected CipherParameters getCipherParameters(boolean forEncryption) {
        byte[] nonce;
        if (!forUdp) {
            nonce = forEncryption ? encNonce : decNonce;
        } else {
            nonce = ZERO_NONCE;
        }
        //has Arrays.clone(nonce)
        byte[] subkey = forEncryption ? getEncSubkey() : getDecSubkey();
        return new AEADParameters(new KeyParameter(subkey), TAG_LENGTH * 8, nonce);
    }

    protected abstract int getKeyLength();

    protected abstract int getSaltLength();

    protected abstract AEADCipher getCipher(boolean isEncrypted);

    protected abstract void _tcpEncrypt(ByteBuf in, ByteBuf out);

    protected abstract void _tcpDecrypt(ByteBuf in, ByteBuf out);

    protected abstract void _udpEncrypt(ByteBuf in, ByteBuf out);

    protected abstract void _udpDecrypt(ByteBuf in, ByteBuf out);
}
