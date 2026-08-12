package com.parkable.push;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Voluntary Application Server Identification (VAPID, RFC 8292).
 *
 * Push services will not accept an anonymous message: each request carries a
 * short-lived JWT signed by our application server key, which is how a push
 * service attributes traffic (and how it contacts us if we misbehave). The
 * matching public key is what the browser pins at subscribe time, so the
 * keypair must stay stable - rotating it invalidates every existing
 * subscription.
 */
public final class VapidSigner {

    /**
     * RFC 8292 §2 caps this at 24 hours. Twelve keeps well clear of that
     * ceiling while tolerating clock skew between us and the push service.
     */
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(12);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HEADER_JSON = "{\"typ\":\"JWT\",\"alg\":\"ES256\"}";

    private final ECPrivateKey privateKey;
    private final String publicKeyBase64Url;
    private final String subject;
    private final Clock clock;

    public VapidSigner(String privateKeyBase64Url, String publicKeyBase64Url, String subject, Clock clock)
            throws GeneralSecurityException {
        this.privateKey = P256.privateKey(P256.decodeBase64Url(
                Objects.requireNonNull(privateKeyBase64Url, "privateKeyBase64Url")));
        this.publicKeyBase64Url = Objects.requireNonNull(publicKeyBase64Url, "publicKeyBase64Url");
        this.subject = Objects.requireNonNull(subject, "subject");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The {@code Authorization} header value for a push request to
     * {@code endpoint}. The audience is the endpoint's origin only - including
     * the path would make the token specific to one subscription and the push
     * service would reject it.
     */
    public String authorizationHeader(URI endpoint) throws GeneralSecurityException {
        String audience = endpoint.getScheme() + "://" + endpoint.getHost();
        return "vapid t=" + token(audience) + ", k=" + publicKeyBase64Url;
    }

    String token(String audience) throws GeneralSecurityException {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("aud", audience);
        claims.put("exp", clock.instant().plus(TOKEN_LIFETIME).getEpochSecond());
        claims.put("sub", subject);

        String signingInput = base64Url(HEADER_JSON.getBytes(StandardCharsets.UTF_8))
                + "." + base64Url(writeJson(claims));

        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(privateKey);
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));

        return signingInput + "." + base64Url(derToJose(signature.sign()));
    }

    /**
     * Java signs into ASN.1 DER; JWS ES256 wants the raw {@code r || s} pair,
     * 32 bytes each. Skipping this conversion produces a signature that is
     * perfectly valid to Java's own verifier and rejected by every push
     * service, which is a genuinely confusing way to fail.
     */
    static byte[] derToJose(byte[] der) {
        int offset = 0;
        if (der[offset++] != 0x30) {
            throw new IllegalArgumentException("Not a DER sequence");
        }
        // Lengths above 127 use the long form: 0x81 then one length octet.
        if ((der[offset] & 0xff) == 0x81) {
            offset++;
        }
        offset++; // sequence length itself

        BigInteger r = readDerInteger(der, offset);
        offset += 2 + (der[offset + 1] & 0xff);
        BigInteger s = readDerInteger(der, offset);

        byte[] jose = new byte[64];
        System.arraycopy(P256.toFixedLength(r), 0, jose, 0, 32);
        System.arraycopy(P256.toFixedLength(s), 0, jose, 32, 32);
        return jose;
    }

    private static BigInteger readDerInteger(byte[] der, int offset) {
        if (der[offset] != 0x02) {
            throw new IllegalArgumentException("Expected a DER INTEGER at offset " + offset);
        }
        int length = der[offset + 1] & 0xff;
        // BigInteger(byte[]) reads two's complement, which correctly consumes
        // the leading 0x00 DER adds when the high bit would imply a negative.
        return new BigInteger(Arrays.copyOfRange(der, offset + 2, offset + 2 + length));
    }

    private static byte[] writeJson(Map<String, Object> claims) {
        try {
            return MAPPER.writeValueAsBytes(claims);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise VAPID claims", e);
        }
    }

    private static String base64Url(byte[] value) {
        return P256.encodeBase64Url(value);
    }

    /** Generates a fresh VAPID keypair as (privateBase64Url, publicBase64Url). */
    public static String[] generateKeyPair() throws GeneralSecurityException {
        var keyPair = P256.generateKeyPair();
        return new String[]{
                P256.encodeBase64Url(P256.encode((ECPrivateKey) keyPair.getPrivate())),
                P256.encodeBase64Url(P256.encode((java.security.interfaces.ECPublicKey) keyPair.getPublic()))
        };
    }
}
