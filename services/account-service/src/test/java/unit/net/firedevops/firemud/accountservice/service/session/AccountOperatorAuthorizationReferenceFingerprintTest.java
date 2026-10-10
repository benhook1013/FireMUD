package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import net.firedevops.firemud.accountservice.service.session.AccountOperatorAuthorizationReferenceFingerprint.ReferenceKind;
import org.junit.jupiter.api.Test;

class AccountOperatorAuthorizationReferenceFingerprintTest {
  private static final byte[] KEY_BYTES =
      new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
  private static final String KEY_ID = "test-key-1";

  @Test
  void matchesIndependentKnownAnswerVectorsForKindsKeyIdsReferencesAndFraming() {
    var calculator = calculator(KEY_ID);
    byte[] opaqueReference = ascii("opaque-A");

    assertThat(calculator.fingerprint(ReferenceKind.HUMAN_OPERATOR, opaqueReference))
        .isEqualTo(
            expected(KEY_ID, "e573642ff16f83612ae0e4b085ef434cd3b5b951606571c8d86ca05266bb849e"));
    assertThat(calculator.fingerprint(ReferenceKind.AUTOMATION_OPERATOR, opaqueReference))
        .isEqualTo(
            expected(KEY_ID, "916405c9980e49c7b01ffc9338d5ad947d3b06317cb6b9ed750e96eebdcade7c"));
    assertThat(calculator("test-key-2").fingerprint(ReferenceKind.HUMAN_OPERATOR, opaqueReference))
        .isEqualTo(
            expected(
                "test-key-2", "a331e3fc9cdda1afc5999bd309cbf018610f05a4fed83269e48b026c74d3803c"));
    assertThat(calculator.fingerprint(ReferenceKind.HUMAN_OPERATOR, ascii("opaque-B")))
        .isEqualTo(
            expected(KEY_ID, "11117a68f5c3217bfcad0bc0f7642168aff2d6113469f51266bc5ff57aa06db3"));

    // A colon in the reference exercises unambiguous segment framing.
    assertThat(calculator("a").fingerprint(ReferenceKind.HUMAN_OPERATOR, ascii("b:xx")))
        .isEqualTo(
            expected("a", "0c368b96387f546857f1490dd1790ec0dfdb304c0eff6cdee80661b92cd4ba2a"));
  }

  @Test
  void preservesExactUnicodeUtf8BytesWithoutNormalization() {
    var calculator = calculator(KEY_ID);
    byte[] composed = "é".getBytes(StandardCharsets.UTF_8);
    byte[] decomposed = "e\u0301".getBytes(StandardCharsets.UTF_8);

    assertThat(calculator.fingerprint(ReferenceKind.HUMAN_OPERATOR, composed))
        .isEqualTo(
            expected(KEY_ID, "26f3a3ff950707bd17f7eca8674cb7cec001c171f6532932b6d57890e5b60af9"));
    assertThat(calculator.fingerprint(ReferenceKind.HUMAN_OPERATOR, decomposed))
        .isEqualTo(
            expected(KEY_ID, "3c9c69068a8c6b40a1e8af27d4cba657e2ce1a185aadc8d3ae268a2ccae6c75f"));
    assertThat(calculator.fingerprint(ReferenceKind.HUMAN_OPERATOR, composed))
        .isNotEqualTo(calculator.fingerprint(ReferenceKind.HUMAN_OPERATOR, decomposed));
  }

  @Test
  void fingerprintsOpaqueBytesWithoutDecodingThemAsText() {
    byte[] opaqueBytes = new byte[] {0, (byte) 0xff};

    assertThat(calculator(KEY_ID).fingerprint(ReferenceKind.HUMAN_OPERATOR, opaqueBytes))
        .isEqualTo(
            expected(KEY_ID, "45840d9641eecbbf42a666bbe8a93933b92adb4d7c66d525840043f354904bec"));
  }

  @Test
  void rejectsMissingOrUnsupportedKeyMaterialAndInvalidKeyIds() {
    assertThatThrownBy(() -> new AccountOperatorAuthorizationReferenceFingerprint(KEY_ID, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountOperatorAuthorizationReferenceFingerprint(
                    KEY_ID, new SecretKeySpec(KEY_BYTES, "AES")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> calculator("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> calculator("bad/key")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> calculator("k".repeat(65)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMissingEmptyAndExcessiveReferenceBytes() {
    var calculator = calculator(KEY_ID);

    assertThatThrownBy(() -> calculator.fingerprint(ReferenceKind.HUMAN_OPERATOR, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> calculator.fingerprint(ReferenceKind.HUMAN_OPERATOR, new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> calculator.fingerprint(ReferenceKind.HUMAN_OPERATOR, new byte[4097]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> calculator.fingerprint(null, ascii("opaque-A")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void doesNotModifyTheCallerReferenceArrayOrExposeKeyMaterialInToString() {
    var calculator = calculator(KEY_ID);
    byte[] reference = ascii("opaque-A");
    byte[] before = reference.clone();

    calculator.fingerprint(ReferenceKind.HUMAN_OPERATOR, reference);

    assertThat(reference).containsExactly(before);
    assertThat(calculator.toString()).doesNotContain(KEY_ID).doesNotContain("00010203");
  }

  private static AccountOperatorAuthorizationReferenceFingerprint calculator(String keyId) {
    return new AccountOperatorAuthorizationReferenceFingerprint(
        keyId, new SecretKeySpec(KEY_BYTES.clone(), "HmacSHA256"));
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static String expected(String keyId, String digest) {
    return "arfp/v1/" + keyId + "/" + digest;
  }
}
