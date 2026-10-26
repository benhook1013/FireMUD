package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalGameplayIssuerPhysicalFieldSetCodecTest {
  private static final int MAX_LOCAL_PHYSICAL_FIELD_COUNT =
      CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MAX_HASH_FIELDS
          + 2 * CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.MAX_MEMBER_OPERATIONS;

  @Test
  void canonicalFieldSetRoundTripsTheMaximumLocalPhysicalFieldCount() {
    Map<String, byte[]> fields = fields(MAX_LOCAL_PHYSICAL_FIELD_COUNT);

    byte[] encoded = CanonicalGameplayIssuerBootstrapCoverageRepository.encodeFieldSet(fields);
    Map<String, byte[]> decoded =
        CanonicalGameplayIssuerBootstrapCoverageRepository.decodeFieldSet(encoded);

    assertThat(decoded).hasSameSizeAs(fields);
    fields.forEach((field, value) -> assertThat(decoded.get(field)).containsExactly(value));
    assertThat(CanonicalGameplayIssuerBootstrapCoverageRepository.encodeFieldSet(decoded))
        .containsExactly(encoded);
  }

  @Test
  void fieldSetCodecRejectsOneMoreThanTheLocalPhysicalFieldBound() {
    Map<String, byte[]> overLimit = fields(MAX_LOCAL_PHYSICAL_FIELD_COUNT + 1);

    assertThatThrownBy(
            () -> CanonicalGameplayIssuerBootstrapCoverageRepository.encodeFieldSet(overLimit))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayIssuerBootstrapCoverageRepository.decodeFieldSet(
                    fieldCountHeader(MAX_LOCAL_PHYSICAL_FIELD_COUNT + 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Persisted issuer physical field count is outside its bound");
  }

  private static Map<String, byte[]> fields(int count) {
    Map<String, byte[]> fields = new LinkedHashMap<>();
    for (int index = count - 1; index >= 0; index--) {
      String field = String.format(Locale.ROOT, "field-%04d", index);
      fields.put(field, ("value-" + index).getBytes(StandardCharsets.UTF_8));
    }
    return fields;
  }

  private static byte[] fieldCountHeader(int count) {
    byte[] magic = "issuer-physical-field-set/v1".getBytes(StandardCharsets.US_ASCII);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      output.writeInt(magic.length);
      output.write(magic);
      output.writeInt(count);
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
    return bytes.toByteArray();
  }
}
