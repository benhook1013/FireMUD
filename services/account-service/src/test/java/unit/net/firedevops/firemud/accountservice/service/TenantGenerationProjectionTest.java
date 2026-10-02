package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.service.AccountTenantAuthorityEventProducer.TenantAuthoritySnapshot;
import net.firedevops.firemud.accountservice.service.TenantGenerationProjection;
import net.firedevops.firemud.common.account.authority.TenantGenerationAuthorityEventV1Codec;
import org.junit.jupiter.api.Test;

class TenantGenerationProjectionTest {
  private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final String TENANT_TEXT = TENANT_ID.toString();
  private static final String STREAM_KEY = "account:auth-authority:v1:tenant/" + TENANT_TEXT;

  @Test
  void emitsExactSequenceZeroBaselineWithoutAnEventFromTypedCurrentSource() {
    TenantGenerationProjection projection =
        TenantGenerationProjection.fromSource(
            new TenantAuthoritySnapshot(TENANT_ID, 1L, 1L, STREAM_KEY, 0L, Optional.empty()));

    assertThat(projection.key()).isEqualTo("session:auth:generation:tenant:" + TENANT_TEXT);
    assertThat(projection.toJson())
        .isEqualTo(
            "{\"schemaVersion\":\"account-auth-tenant-generation-projection/v1\","
                + "\"tenantId\":\""
                + TENANT_TEXT
                + "\",\"tenantAuthorityGeneration\":\"1\",\"sourceVersion\":\"1\","
                + "\"outboxStreamKey\":\""
                + STREAM_KEY
                + "\",\"outboxSequence\":\"0\"}");
    assertThat(TenantGenerationProjection.parse(projection.toJson())).isEqualTo(projection);
  }

  @Test
  void storesTheExactLatestClosedTenantEventAndDigestAsOneJsonString() {
    String eventJson = tenantEvent("4", "4", "3", TENANT_TEXT);
    TenantGenerationAuthorityEventV1Codec.TenantGenerationAuthorityEvent event =
        TenantGenerationAuthorityEventV1Codec.verify(eventJson);
    TenantGenerationProjection projection =
        TenantGenerationProjection.fromSource(
            new TenantAuthoritySnapshot(TENANT_ID, 4L, 4L, STREAM_KEY, 3L, Optional.of(event)));
    TenantGenerationProjection parsed = TenantGenerationProjection.parse(projection.toJson());

    assertThat(parsed).isEqualTo(projection);
    assertThat(parsed.sourceEvent()).contains(eventJson);
    assertThat(parsed.toJson()).contains("\"sourceEvent\":\"{" + "\\\"eventDigest\\\"");
    assertThat(TenantGenerationAuthorityEventV1Codec.verify(eventJson).canonicalJson())
        .isEqualTo(eventJson);
  }

  @Test
  void acceptsLargeCountersWithArbitraryPrecisionAndExactProgression() {
    String generation = "922337203685477580812345678901234567890";
    String sequence = new BigInteger(generation).subtract(BigInteger.ONE).toString();
    TenantGenerationProjection projection =
        projection(
            generation,
            generation,
            sequence,
            Optional.of(tenantEvent(generation, generation, sequence, TENANT_TEXT)));

    assertThat(projection.generationValue()).isEqualTo(new BigInteger(generation));
    assertThat(projection.sourceVersionValue()).isEqualTo(new BigInteger(generation));
    assertThat(projection.outboxSequenceValue()).isEqualTo(new BigInteger(sequence));
    assertThat(TenantGenerationProjection.parse(projection.toJson())).isEqualTo(projection);
  }

  @Test
  void closesProjectionFieldsAndRejectsDuplicateUnknownNullAndNoncanonicalJson() {
    String baseline = projection("1", "1", "0", Optional.empty()).toJson();
    String duplicateTenantId =
        baseline.replace(
            "\"tenantId\":\"" + TENANT_TEXT + "\"",
            "\"tenantId\":\"" + TENANT_TEXT + "\",\"tenantId\":\"" + TENANT_TEXT + "\"");
    String unknownField =
        baseline.substring(0, baseline.length() - 1) + ",\"issuanceFence\":\"1\"}";
    String nullEvent = baseline.substring(0, baseline.length() - 1) + ",\"sourceEvent\":null}";
    String noncanonicalSequence =
        baseline.replace("\"outboxSequence\":\"0\"", "\"outboxSequence\":\"00\"");
    String reordered =
        baseline.replace(
            "\"schemaVersion\":\"account-auth-tenant-generation-projection/v1\","
                + "\"tenantId\":\""
                + TENANT_TEXT
                + "\",",
            "\"tenantId\":\""
                + TENANT_TEXT
                + "\",\"schemaVersion\":\"account-auth-tenant-generation-projection/v1\",");
    String trailing = baseline + " {}";

    assertThatThrownBy(() -> TenantGenerationProjection.parse(duplicateTenantId))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TenantGenerationProjection.parse(unknownField))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TenantGenerationProjection.parse(nullEvent))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TenantGenerationProjection.parse(noncanonicalSequence))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TenantGenerationProjection.parse(reordered))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TenantGenerationProjection.parse(trailing))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresOriginalBaselineAndPositiveEventAtTheExactSourceCheckpoint() {
    assertThatThrownBy(() -> projection("2", "1", "0", Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sequence zero");
    assertThatThrownBy(() -> projection("2", "2", "0", Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sequence zero");
    assertThatThrownBy(() -> projection("2", "2", "1", Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sequence+1");
    assertThatThrownBy(
            () -> projection("3", "3", "1", Optional.of(tenantEvent("3", "3", "1", TENANT_TEXT))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sequence+1");
    assertThatThrownBy(
            () ->
                projection(
                    "2",
                    "2",
                    "1",
                    Optional.of(
                        tenantEvent("2", "2", "1", "10000000-0000-0000-0000-000000000002"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checkpoint");
  }

  @Test
  void rejectsChangedEventDigestAndUnknownEventProperties() {
    String event = tenantEvent("2", "2", "1", TENANT_TEXT);
    String changedEvent = event.replace("\"sourceVersion\":\"2\"", "\"sourceVersion\":\"3\"");
    String unknownFieldEvent = event.substring(0, event.length() - 1) + ",\"extra\":\"x\"}";

    assertThatThrownBy(() -> projection("2", "2", "1", Optional.of(changedEvent)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> projection("2", "2", "1", Optional.of(unknownFieldEvent)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static TenantGenerationProjection projection(
      String generation, String sourceVersion, String sequence, Optional<String> sourceEvent) {
    return new TenantGenerationProjection(
        TENANT_TEXT, generation, sourceVersion, STREAM_KEY, sequence, sourceEvent);
  }

  private static String tenantEvent(
      String generation, String sourceVersion, String sequence, String tenantId) {
    String requestId = "11111111-1111-4111-8111-111111111111";
    var evidence =
        TenantGenerationAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry("schemaVersion", TenantGenerationAuthorityEventV1Codec.SCHEMA_VERSION),
                Map.entry("eventType", TenantGenerationAuthorityEventV1Codec.EVENT_TYPE),
                Map.entry(
                    "eventId", TenantGenerationAuthorityEventV1Codec.EVENT_ID_PREFIX + requestId),
                Map.entry("requestId", requestId),
                Map.entry("tenantId", tenantId),
                Map.entry("sourceScope", "tenant/" + tenantId),
                Map.entry("outboxStreamKey", "account:auth-authority:v1:tenant/" + tenantId),
                Map.entry("outboxSequence", sequence),
                Map.entry("tenantAuthorityGeneration", generation),
                Map.entry("sourceVersion", sourceVersion)));
    return new String(evidence.canonicalJsonUtf8(), StandardCharsets.UTF_8);
  }
}
