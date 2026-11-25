package org.rx.net.socks.encryption;

import io.netty.util.concurrent.FastThreadLocal;

import javax.crypto.AEADBadTagException;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/** Per-channel AEAD state. Server listeners share only the authenticated salt history and immutable master key. */
public abstract class AeadCrypto implements ICrypto {
    private static final String HKDF_ALGORITHM = "HmacSHA1";
    private static final byte[] INFO = "ss-subkey".getBytes(StandardCharsets.US_ASCII);
    private static final FastThreadLocal<HkdfScratch> HKDF = new FastThreadLocal<HkdfScratch>() {
        @Override
        protected HkdfScratch initialValue() {
            return new HkdfScratch();
        }
    };

    SaltHistory history;
    private final ShadowSocksKey masterKey;
    private byte[] firstSalt;

    protected AeadCrypto(ShadowSocksKey masterKey) {
        this.masterKey = masterKey;
    }

    protected final void deriveSubkey(byte[] salt, int saltLen, byte[] okm, int keyLen) throws GeneralSecurityException {
        HkdfScratch s = HKDF.get();
        ReusableHmacKey saltKey = saltLen == 16 ? s.salt16 : s.salt32;
        System.arraycopy(salt, 0, saltKey.bytes, 0, saltLen);
        try {
            s.mac.init(saltKey);
            byte[] raw = masterKey.keyBytes();
            s.mac.update(raw, 0, raw.length);
            s.mac.doFinal(s.prk, 0);

            s.mac.init(s.prkKey);
            int copied = 0;
            int counter = 1;
            int blockLen = 0;
            while (copied < keyLen) {
                if (blockLen > 0) {
                    s.mac.update(s.block, 0, blockLen);
                }
                s.mac.update(INFO);
                s.mac.update((byte) counter++);
                s.mac.doFinal(s.block, 0);
                blockLen = 20;
                int copy = Math.min(blockLen, keyLen - copied);
                System.arraycopy(s.block, 0, okm, copied, copy);
                copied += copy;
            }
            if (okm.length > keyLen) {
                Arrays.fill(okm, keyLen, okm.length, (byte) 0);
            }
        } finally {
            Arrays.fill(saltKey.bytes, (byte) 0);
            Arrays.fill(s.prk, (byte) 0);
            Arrays.fill(s.block, (byte) 0);
        }
    }

    protected final void receivedTcpSalt(byte[] salt, int length) {
        if (history != null) firstSalt = Arrays.copyOf(salt, length);
    }

    protected final void authenticatedTcpPayload() throws AEADBadTagException {
        if (firstSalt != null) {
            authenticatedSalt(firstSalt, firstSalt.length);
            firstSalt = null;
        }
    }

    protected final void authenticatedSalt(byte[] salt, int length) throws AEADBadTagException {
        if (history != null && !history.record(salt, length)) {
            throw new AEADBadTagException("Salt already used");
        }
    }

    protected final boolean reserveLocalSalt(byte[] salt, int length) {
        return history == null || history.record(salt, length);
    }

    public boolean hasIncompleteRecord() {
        return false;
    }

    private static final class HkdfScratch {
        final Mac mac;
        final byte[] prk = new byte[20];
        final byte[] block = new byte[20];
        final ReusableHmacKey salt16 = new ReusableHmacKey(new byte[16]);
        final ReusableHmacKey salt32 = new ReusableHmacKey(new byte[32]);
        final ReusableHmacKey prkKey = new ReusableHmacKey(prk);

        HkdfScratch() {
            try {
                mac = Mac.getInstance(HKDF_ALGORITHM);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final class ReusableHmacKey implements SecretKey {
        private static final long serialVersionUID = 1L;
        final byte[] bytes;

        ReusableHmacKey(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override public String getAlgorithm() { return HKDF_ALGORITHM; }
        @Override public String getFormat() { return "RAW"; }
        @Override public byte[] getEncoded() { return bytes; }
    }
}
