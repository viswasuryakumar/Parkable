package com.parkable.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class VapidSignerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-12T09:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("produces a JWT whose ES256 signature verifies against the VAPID public key")
    void signatureVerifies() throws Exception {
        String[] keys = VapidSigner.generateKeyPair();
        VapidSigner signer = new VapidSigner(keys[0], keys[1], "mailto:ops@parkable.dev", FIXED);

        String jwt = signer.token("https://fcm.googleapis.com");
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);

        ECPublicKey publicKey = P256.publicKey(P256.decodeBase64Url(keys[1]));
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKey);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));

        // The signature travels as raw r||s, so it has to be repacked into DER
        // before Java's verifier will look at it - the mirror of what the
        // signer does on the way out.
        assertThat(verifier.verify(joseToDer(P256.decodeBase64Url(parts[2])))).isTrue();
    }

    @Test
    @DisplayName("claims carry the endpoint origin as audience, never the full path")
    void audienceIsOriginOnly() throws Exception {
        String[] keys = VapidSigner.generateKeyPair();
        VapidSigner signer = new VapidSigner(keys[0], keys[1], "mailto:ops@parkable.dev", FIXED);

        String header = signer.authorizationHeader(
                URI.create("https://fcm.googleapis.com/fcm/send/abc123:long-subscription-token"));

        assertThat(header).startsWith("vapid t=").contains(", k=" + keys[1]);
        JsonNode claims = decodeClaims(header.substring("vapid t=".length(), header.indexOf(", k=")));
        assertThat(claims.get("aud").asText()).isEqualTo("https://fcm.googleapis.com");
        assertThat(claims.get("sub").asText()).isEqualTo("mailto:ops@parkable.dev");
    }

    @Test
    @DisplayName("expiry stays inside the 24 hour ceiling RFC 8292 allows")
    void expiryWithinSpecCeiling() throws Exception {
        String[] keys = VapidSigner.generateKeyPair();
        VapidSigner signer = new VapidSigner(keys[0], keys[1], "mailto:ops@parkable.dev", FIXED);

        JsonNode claims = decodeClaims(signer.token("https://updates.push.services.mozilla.com"));
        long exp = claims.get("exp").asLong();
        long now = FIXED.instant().getEpochSecond();

        assertThat(exp).isGreaterThan(now);
        assertThat(exp - now).isLessThanOrEqualTo(24 * 60 * 60);
    }

    @Test
    @DisplayName("DER signatures convert to exactly 64 raw bytes regardless of leading zeroes")
    void derToJoseIsFixedWidth() throws Exception {
        String[] keys = VapidSigner.generateKeyPair();
        VapidSigner signer = new VapidSigner(keys[0], keys[1], "mailto:ops@parkable.dev", FIXED);

        // DER trims/pads r and s unpredictably, so sign repeatedly to hit both
        // the short and the sign-byte-prefixed encodings.
        for (int i = 0; i < 40; i++) {
            String jwt = signer.token("https://example.push/" + i);
            assertThat(P256.decodeBase64Url(jwt.split("\\.")[2])).hasSize(64);
        }
    }

    private static JsonNode decodeClaims(String jwt) throws Exception {
        return MAPPER.readTree(P256.decodeBase64Url(jwt.split("\\.")[1]));
    }

    private static byte[] joseToDer(byte[] jose) {
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(jose, 0, 32));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(jose, 32, 64));
        byte[] rb = r.toByteArray();
        byte[] sb = s.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x30);
        out.write(4 + rb.length + sb.length);
        out.write(0x02);
        out.write(rb.length);
        out.writeBytes(rb);
        out.write(0x02);
        out.write(sb.length);
        out.writeBytes(sb);
        return out.toByteArray();
    }
}
