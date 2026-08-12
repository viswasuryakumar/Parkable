package com.parkable.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

/**
 * Message Encryption for Web Push (RFC 8291) inside the aes128gcm content
 * encoding (RFC 8188).
 *
 * The push service is an untrusted relay: it sees the ciphertext and routes
 * it, but only the browser that produced the subscription can decrypt it.
 * That's why the payload is encrypted to the subscription's own P-256 key
 * rather than merely sent over TLS.
 *
 * Hand-rolled against the two RFCs on stock JDK crypto instead of pulling in
 * a library plus BouncyCastle. Safe to do here precisely because RFC 8291 §5
 * publishes a complete worked example with fixed keys and a fixed salt -
 * {@code WebPushCipherTest} reproduces that vector byte for byte, so the
 * whole construction is pinned by the spec's own numbers rather than by
 * "it seemed to work when I tried it."
 */
public final class WebPushCipher {

    /** Single-record size advertised in the header; payloads here are far smaller. */
    static final int RECORD_SIZE = 4096;

    private static final byte[] KEY_INFO = asciiWithNul("WebPush: info");
    private static final byte[] CEK_INFO = asciiWithNul("Content-Encoding: aes128gcm");
    private static final byte[] NONCE_INFO = asciiWithNul("Content-Encoding: nonce");

    private static final int SALT_BYTES = 16;
    private static final int CEK_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    /**
     * RFC 8188 §2: the last record of a payload is delimited by 0x02. We always
     * emit exactly one record, so the delimiter is always the final-record one.
     */
    private static final byte LAST_RECORD_DELIMITER = 0x02;

    private static final SecureRandom RANDOM = new SecureRandom();

    private WebPushCipher() {
    }

    /**
     * Encrypts {@code plaintext} to a subscription, generating a fresh
     * ephemeral keypair and salt (both MUST be new per message - reusing
     * either across messages leaks the plaintext).
     */
    public static byte[] encrypt(byte[] plaintext, byte[] uaPublicKey, byte[] authSecret)
            throws GeneralSecurityException {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, uaPublicKey, authSecret, P256.generateKeyPair(), salt);
    }

    /**
     * Deterministic form, used by the RFC 8291 §5 test vector. Not public: a
     * caller that supplies its own keypair/salt can silently destroy the
     * security of the scheme by reusing them.
     */
    static byte[] encrypt(byte[] plaintext, byte[] uaPublicKey, byte[] authSecret,
                          KeyPair senderKeyPair, byte[] salt) throws GeneralSecurityException {
        if (salt.length != SALT_BYTES) {
            throw new IllegalArgumentException("Salt must be " + SALT_BYTES + " bytes, got " + salt.length);
        }
        byte[] senderPublic = P256.encode((ECPublicKey) senderKeyPair.getPublic());

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(senderKeyPair.getPrivate());
        agreement.doPhase(P256.publicKey(uaPublicKey), true);
        byte[] sharedSecret = agreement.generateSecret();

        // RFC 8291 §3.3 - the auth secret is the HKDF salt at this stage, and
        // binding both public keys into the info string is what stops a push
        // service from swapping in a key of its own.
        byte[] keyInfo = concat(KEY_INFO, uaPublicKey, senderPublic);
        byte[] ikm = hkdf(authSecret, sharedSecret, keyInfo, 32);

        // RFC 8188 §2.2 - now the random salt from the header takes over.
        byte[] contentEncryptionKey = hkdf(salt, ikm, CEK_INFO, CEK_BYTES);
        byte[] nonce = hkdf(salt, ikm, NONCE_INFO, NONCE_BYTES);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(contentEncryptionKey, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, nonce));
        byte[] ciphertext = cipher.doFinal(concat(plaintext, new byte[]{LAST_RECORD_DELIMITER}));

        // RFC 8188 §2.1 header: salt | record size | key id length | key id.
        return ByteBuffer.allocate(SALT_BYTES + 4 + 1 + senderPublic.length + ciphertext.length)
                .put(salt)
                .putInt(RECORD_SIZE)
                .put((byte) senderPublic.length)
                .put(senderPublic)
                .put(ciphertext)
                .array();
    }

    /**
     * HKDF (RFC 5869) over HMAC-SHA-256. Only the single-block case is
     * implemented - every output here is 32 bytes or fewer - and lengths
     * beyond that are rejected rather than silently truncated.
     */
    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        if (length > 32) {
            throw new IllegalArgumentException("Single-block HKDF cannot produce " + length + " bytes");
        }
        byte[] pseudoRandomKey = hmacSha256(salt, ikm);
        byte[] block = hmacSha256(pseudoRandomKey, concat(info, new byte[]{1}));
        return Arrays.copyOf(block, length);
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] asciiWithNul(String value) {
        return concat(value.getBytes(StandardCharsets.US_ASCII), new byte[]{0});
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
