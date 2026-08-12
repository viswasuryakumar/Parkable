package com.parkable.push;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * P-256 (secp256r1) key plumbing shared by the Web Push cipher (RFC 8291)
 * and the VAPID signer (RFC 8292).
 *
 * Both specs move keys around as raw bytes rather than as the DER/X.509
 * encodings {@code KeyFactory} hands back by default: public keys travel as
 * the 65-byte uncompressed point {@code 0x04 || X || Y}, private keys as the
 * bare 32-byte scalar. Everything here is stock JDK crypto - deliberately no
 * BouncyCastle, which would add several MB to a jar whose cold-start time is
 * a stated success criterion.
 */
final class P256 {

    static final int FIELD_BYTES = 32;
    static final int UNCOMPRESSED_POINT_BYTES = 1 + (2 * FIELD_BYTES);

    private P256() {
    }

    static ECParameterSpec parameters() throws GeneralSecurityException {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        return parameters.getParameterSpec(ECParameterSpec.class);
    }

    static KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    /** Decodes the 65-byte uncompressed point a browser publishes as its {@code p256dh} key. */
    static ECPublicKey publicKey(byte[] uncompressedPoint) throws GeneralSecurityException {
        if (uncompressedPoint.length != UNCOMPRESSED_POINT_BYTES || uncompressedPoint[0] != 0x04) {
            throw new IllegalArgumentException(
                    "Expected a 65-byte uncompressed P-256 point starting with 0x04, got "
                            + uncompressedPoint.length + " bytes");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressedPoint, 1, 1 + FIELD_BYTES));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressedPoint, 1 + FIELD_BYTES, UNCOMPRESSED_POINT_BYTES));
        return (ECPublicKey) KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), parameters()));
    }

    /** Rebuilds a private key from the bare 32-byte scalar (how VAPID keys are stored). */
    static ECPrivateKey privateKey(byte[] scalar) throws GeneralSecurityException {
        return (ECPrivateKey) KeyFactory.getInstance("EC")
                .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), parameters()));
    }

    static byte[] encode(ECPublicKey key) {
        ECPoint point = key.getW();
        byte[] encoded = new byte[UNCOMPRESSED_POINT_BYTES];
        encoded[0] = 0x04;
        System.arraycopy(toFixedLength(point.getAffineX()), 0, encoded, 1, FIELD_BYTES);
        System.arraycopy(toFixedLength(point.getAffineY()), 0, encoded, 1 + FIELD_BYTES, FIELD_BYTES);
        return encoded;
    }

    static byte[] encode(ECPrivateKey key) {
        return toFixedLength(key.getS());
    }

    /**
     * BigInteger.toByteArray() is variable width - it prepends a zero byte
     * when the high bit is set and drops leading zeroes otherwise - so a
     * coordinate lands anywhere from 31 to 33 bytes. Both specs require
     * exactly 32, and getting this wrong produces keys that verify locally
     * but are rejected by the push service.
     */
    static byte[] toFixedLength(BigInteger value) {
        byte[] raw = value.toByteArray();
        if (raw.length == FIELD_BYTES) {
            return raw;
        }
        byte[] fixed = new byte[FIELD_BYTES];
        if (raw.length > FIELD_BYTES) {
            System.arraycopy(raw, raw.length - FIELD_BYTES, fixed, 0, FIELD_BYTES);
        } else {
            System.arraycopy(raw, 0, fixed, FIELD_BYTES - raw.length, raw.length);
        }
        return fixed;
    }

    // Web Push and VAPID use unpadded base64url throughout, including for the
    // subscription keys the browser hands the client.
    static byte[] decodeBase64Url(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    static String encodeBase64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
