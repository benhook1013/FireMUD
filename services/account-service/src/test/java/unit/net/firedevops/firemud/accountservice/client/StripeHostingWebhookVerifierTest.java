package net.firedevops.firemud.accountservice.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StripeHostingWebhookVerifierTest {
  private static final String SECRET = "whsec_local_test_secret";
  private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String PAYLOAD = eventPayload();

  @Test
  void verifiesSignatureAndReturnsParsedEventIdentity() throws Exception {
    long timestamp = NOW.getEpochSecond();
    String signature = signatureHeader(SECRET, PAYLOAD, timestamp);

    StripeHostingWebhookVerifier.VerifiedEvent event =
        new StripeHostingWebhookVerifier(SECRET, CLOCK).verify(PAYLOAD, signature);

    assertEquals("evt_local_signature_test", event.providerEventId());
    assertEquals("customer.subscription.updated", event.eventType());
    assertEquals(PAYLOAD, event.rawPayload());
    assertEquals(
        "sha256:"
            + HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(PAYLOAD.getBytes(StandardCharsets.UTF_8))),
        event.payloadSha256());
    assertEquals(NOW.getEpochSecond(), event.eventCreatedAtEpochSeconds());
    assertEquals("2025-02-24.acacia", event.providerApiVersion());
    assertEquals(false, event.livemode());
    assertEquals(NOW, event.receivedAt());
  }

  @ParameterizedTest
  @MethodSource("invalidSecretSignatures")
  void rejectsWrongSecretOrSignature(String verifierSecret, String signingSecret) throws Exception {
    String signature = signatureHeader(signingSecret, PAYLOAD, NOW.getEpochSecond());

    assertThrows(
        SignatureVerificationException.class,
        () -> new StripeHostingWebhookVerifier(verifierSecret, CLOCK).verify(PAYLOAD, signature));
  }

  @ParameterizedTest
  @MethodSource("tamperedPayloads")
  void rejectsWhitespaceAndUtf8PayloadTampering(String tamperedPayload) throws Exception {
    String signature = signatureHeader(SECRET, PAYLOAD, NOW.getEpochSecond());
    StripeHostingWebhookVerifier verifier = new StripeHostingWebhookVerifier(SECRET, CLOCK);

    assertThrows(
        SignatureVerificationException.class, () -> verifier.verify(tamperedPayload, signature));
  }

  @Test
  void rejectsMalformedUnicodeBeforeLossyUtf8Encoding() throws Exception {
    StripeHostingWebhookVerifier verifier = new StripeHostingWebhookVerifier(SECRET, CLOCK);
    for (char surrogate : new char[] {(char) 0xD800, (char) 0xDC00}) {
      String malformed = PAYLOAD.replace("hébergement", Character.toString(surrogate));
      String signature = signatureHeader(SECRET, malformed, NOW.getEpochSecond());
      assertThrows(IllegalArgumentException.class, () -> verifier.verify(malformed, signature));
    }
  }

  @Test
  void rejectsForgedNonJsonBeforeEventDeserialization() {
    StripeHostingWebhookVerifier verifier = new StripeHostingWebhookVerifier(SECRET, CLOCK);
    assertThrows(
        SignatureVerificationException.class,
        () -> verifier.verify("not-json", "t=" + NOW.getEpochSecond() + ",v1=forged"));
  }

  @Test
  void normalizesMalformedTimestampHeadersToSignatureRejection() {
    StripeHostingWebhookVerifier verifier = new StripeHostingWebhookVerifier(SECRET, CLOCK);
    for (String malformed : new String[] {"t=abc", "t", "t=", "t=9223372036854775808"}) {
      assertThrows(SignatureVerificationException.class, () -> verifier.verify(PAYLOAD, malformed));
    }
  }

  @Test
  void rejectsMalformedSignatureHeader() {
    StripeHostingWebhookVerifier verifier = new StripeHostingWebhookVerifier(SECRET, CLOCK);

    assertThrows(
        SignatureVerificationException.class,
        () -> verifier.verify(PAYLOAD, "t=" + NOW.getEpochSecond() + ",v1=not-a-signature"));
  }

  @Test
  void rejectsSignatureOutsideSdkDefaultTolerance() throws Exception {
    long staleTimestamp = NOW.getEpochSecond() - Webhook.DEFAULT_TOLERANCE - 1;
    String staleButCorrectlySigned = signatureHeader(SECRET, PAYLOAD, staleTimestamp);

    assertThrows(
        SignatureVerificationException.class,
        () ->
            new StripeHostingWebhookVerifier(SECRET, CLOCK)
                .verify(PAYLOAD, staleButCorrectlySigned));
  }

  @ParameterizedTest
  @MethodSource("invalidInputs")
  void rejectsNullAndBlankConstructionAndVerificationInputs(
      Class<? extends Throwable> expectedException, Executable invalidInput) {
    assertThrows(expectedException, invalidInput);
  }

  private static Stream<Arguments> invalidSecretSignatures() {
    return Stream.of(
        Arguments.of("whsec_other_secret", SECRET), Arguments.of(SECRET, "whsec_other_secret"));
  }

  private static Stream<Arguments> tamperedPayloads() {
    return Stream.of(
        Arguments.of(PAYLOAD.replace("\"object\":\"event\"", "\"object\": \"event\"")),
        Arguments.of(PAYLOAD.replace("hébergement", "hebergement")));
  }

  private static Stream<Arguments> invalidInputs() {
    StripeHostingWebhookVerifier verifier = new StripeHostingWebhookVerifier(SECRET, CLOCK);
    return Stream.of(
        Arguments.of(
            IllegalArgumentException.class,
            (Executable) () -> new StripeHostingWebhookVerifier(null, CLOCK)),
        Arguments.of(
            IllegalArgumentException.class,
            (Executable) () -> new StripeHostingWebhookVerifier(" ", CLOCK)),
        Arguments.of(
            NullPointerException.class,
            (Executable) () -> new StripeHostingWebhookVerifier(SECRET, null)),
        Arguments.of(
            IllegalArgumentException.class, (Executable) () -> verifier.verify(null, "signature")),
        Arguments.of(
            IllegalArgumentException.class, (Executable) () -> verifier.verify(" ", "signature")),
        Arguments.of(
            IllegalArgumentException.class, (Executable) () -> verifier.verify(PAYLOAD, null)),
        Arguments.of(
            IllegalArgumentException.class, (Executable) () -> verifier.verify(PAYLOAD, " ")));
  }

  private static String signatureHeader(String secret, String payload, long timestamp)
      throws GeneralSecurityException {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] signedPayload = (timestamp + "." + payload).getBytes(StandardCharsets.UTF_8);
    return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(signedPayload));
  }

  private static String eventPayload() {
    return """
        {"id":"evt_local_signature_test","object":"event","api_version":"2025-02-24.acacia","created":__CREATED_TIMESTAMP__,"data":{"object":{"id":"sub_local_signature_test","object":"subscription","status":"active","metadata":{"description":"hébergement 🏠"}}},"livemode":false,"pending_webhooks":1,"request":null,"type":"customer.subscription.updated"}
        """
        .replace("__CREATED_TIMESTAMP__", Long.toString(NOW.getEpochSecond()));
  }
}
