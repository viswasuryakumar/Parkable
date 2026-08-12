package com.parkable.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the encryption to the worked example published in RFC 8291 §5.
 *
 * This is the whole reason hand-rolling the cipher is defensible instead of
 * reckless: the spec fixes the receiver keys, the sender keys and the salt,
 * so the entire output is deterministic and can be compared byte for byte
 * against the RFC's own answer. A subtle mistake anywhere in the chain -
 * ECDH, either HKDF stage, the info strings, the record delimiter, the
 * header framing - changes these bytes.
 */
class WebPushCipherTest {

    // RFC 8291 §5, verbatim.
    private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
    private static final String UA_PUBLIC =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String AS_PUBLIC =
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String EXPECTED_CIPHERTEXT =
            "8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ";

    /** RFC 8188 header: 16-byte salt, 4-byte record size, 1-byte key id length, then the key id. */
    private static final int HEADER_BYTES = 16 + 4 + 1 + P256.UNCOMPRESSED_POINT_BYTES;

    @Test
    @DisplayName("reproduces the RFC 8291 section 5 worked example exactly")
    void matchesRfcTestVector() throws Exception {
        KeyPair senderKeyPair = new KeyPair(
                P256.publicKey(P256.decodeBase64Url(AS_PUBLIC)),
                P256.privateKey(P256.decodeBase64Url(AS_PRIVATE)));

        byte[] body = WebPushCipher.encrypt(
                PLAINTEXT.getBytes(StandardCharsets.UTF_8),
                P256.decodeBase64Url(UA_PUBLIC),
                P256.decodeBase64Url(AUTH_SECRET),
                senderKeyPair,
                P256.decodeBase64Url(SALT));

        // Compared in parts rather than as one base64 blob: the header is 86
        // bytes, so it ends mid-base64-group and shifts every character of the
        // ciphertext that follows. Splitting first keeps the assertion against
        // the RFC's own published value instead of a re-encoded derivative.
        byte[] header = Arrays.copyOfRange(body, 0, HEADER_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(body, HEADER_BYTES, body.length);

        assertThat(P256.encodeBase64Url(Arrays.copyOfRange(header, 0, 16))).isEqualTo(SALT);
        assertThat(P256.encodeBase64Url(Arrays.copyOfRange(header, 21, HEADER_BYTES))).isEqualTo(AS_PUBLIC);
        assertThat(P256.encodeBase64Url(ciphertext)).isEqualTo(EXPECTED_CIPHERTEXT);
    }

    @Test
    @DisplayName("emits the RFC 8188 header: salt, record size, key id length, key id")
    void buildsHeader() throws Exception {
        byte[] body = WebPushCipher.encrypt(
                PLAINTEXT.getBytes(StandardCharsets.UTF_8),
                P256.decodeBase64Url(UA_PUBLIC),
                P256.decodeBase64Url(AUTH_SECRET));

        byte[] salt = java.util.Arrays.copyOfRange(body, 0, 16);
        int recordSize = java.nio.ByteBuffer.wrap(body, 16, 4).getInt();
        int keyIdLength = body[20] & 0xff;

        assertThat(salt).hasSize(16);
        assertThat(recordSize).isEqualTo(WebPushCipher.RECORD_SIZE);
        assertThat(keyIdLength).isEqualTo(P256.UNCOMPRESSED_POINT_BYTES);
        // Uncompressed point marker, so the receiver can parse the key id.
        assertThat(body[21]).isEqualTo((byte) 0x04);
    }

    @Test
    @DisplayName("uses a fresh salt and ephemeral key per message")
    void neverRepeatsSaltOrEphemeralKey() throws Exception {
        byte[] plaintext = PLAINTEXT.getBytes(StandardCharsets.UTF_8);
        byte[] uaPublic = P256.decodeBase64Url(UA_PUBLIC);
        byte[] auth = P256.decodeBase64Url(AUTH_SECRET);

        byte[] first = WebPushCipher.encrypt(plaintext, uaPublic, auth);
        byte[] second = WebPushCipher.encrypt(plaintext, uaPublic, auth);

        // Reusing either across messages would leak the plaintext, so identical
        // input must still produce a completely different body.
        assertThat(java.util.Arrays.copyOfRange(first, 0, 16))
                .isNotEqualTo(java.util.Arrays.copyOfRange(second, 0, 16));
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("rejects a subscription key that is not an uncompressed P-256 point")
    void rejectsMalformedSubscriptionKey() {
        byte[] auth = P256.decodeBase64Url(AUTH_SECRET);
        assertThatThrownBy(() -> WebPushCipher.encrypt("x".getBytes(StandardCharsets.UTF_8), new byte[64], auth))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uncompressed P-256 point");
    }
}
