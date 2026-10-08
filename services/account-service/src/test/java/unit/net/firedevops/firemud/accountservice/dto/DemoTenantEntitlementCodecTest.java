package unit.net.firedevops.firemud.accountservice.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementEventV1Codec;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class DemoTenantEntitlementCodecTest {
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID CREATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID OPERATION_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final String CREATION_DIGEST = "sha256:" + "a".repeat(64);
  private static final long TWO_TO_53 = 9_007_199_254_740_992L;
  private static final long TWO_TO_53_PLUS_ONE = 9_007_199_254_740_993L;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void requestDigestDistinguishesAdjacentValuesAboveIeeeIntegerPrecision() {
    String exactRequestValue = request(TWO_TO_53_PLUS_ONE, TWO_TO_53).requestDigest();
    String roundedRequestValue = request(TWO_TO_53, TWO_TO_53).requestDigest();
    String exactQuotaValue = request(TWO_TO_53, TWO_TO_53_PLUS_ONE).requestDigest();
    String roundedQuotaValue = request(TWO_TO_53, TWO_TO_53).requestDigest();

    assertThat(exactRequestValue).isNotEqualTo(roundedRequestValue);
    assertThat(exactQuotaValue).isNotEqualTo(roundedQuotaValue);
  }

  @Test
  void eventRoundTripPreservesLongMaxAndValuesAboveIeeeIntegerPrecisionAsDecimalStrings() {
    DemoTenantEntitlementRequest request =
        request(
            Long.MAX_VALUE,
            TWO_TO_53_PLUS_ONE,
            new DemoTenantEntitlementRequest.Quotas(
                Long.MAX_VALUE, TWO_TO_53_PLUS_ONE, Long.MAX_VALUE));
    FreshTenantCreationEvidence source = source(TWO_TO_53_PLUS_ONE);
    DemoTenantEntitlementEventV1Codec.Event sealed =
        DemoTenantEntitlementEventV1Codec.seal(
            request,
            source,
            Long.MAX_VALUE,
            TWO_TO_53_PLUS_ONE,
            Long.MAX_VALUE,
            TWO_TO_53_PLUS_ONE);

    DemoTenantEntitlementEventV1Codec.Event verified =
        DemoTenantEntitlementEventV1Codec.verify(sealed.payload());
    String payload = new String(sealed.payload(), StandardCharsets.UTF_8);

    assertThat(verified.entitlementVersion()).isEqualTo(Long.MAX_VALUE);
    assertThat(verified.tenantAuthorityGeneration()).isEqualTo(TWO_TO_53_PLUS_ONE);
    assertThat(verified.tenantAuthoritySourceVersion()).isEqualTo(Long.MAX_VALUE);
    assertThat(verified.tenantBillingSequence()).isEqualTo(TWO_TO_53_PLUS_ONE);
    assertThat(verified.sourceEvidence().sourceGameRowId()).isEqualTo(TWO_TO_53_PLUS_ONE);
    assertThat(verified.quotas())
        .isEqualTo(
            new DemoTenantEntitlementRequest.Quotas(
                Long.MAX_VALUE, TWO_TO_53_PLUS_ONE, Long.MAX_VALUE));
    assertThat(payload)
        .contains("\"entitlementVersion\":\"9223372036854775807\"")
        .contains("\"tenantAuthorityGeneration\":\"9007199254740993\"")
        .contains("\"tenantBillingSequence\":\"9007199254740993\"")
        .contains("\"sourceGameRowId\":\"9007199254740993\"")
        .contains("\"maxConcurrentGameInstances\":\"9007199254740993\"")
        .contains("\"schemaVersion\":1");
  }

  @Test
  void eventVerifierRejectsNoncanonicalLongRepresentations() throws Exception {
    byte[] validPayload = event().payload();

    assertInvalidCounter(validPayload, 1);
    assertInvalidCounter(validPayload, "01");
    assertInvalidCounter(validPayload, "1e2");
    assertInvalidCounter(validPayload, "1.0");
    assertInvalidCounter(validPayload, "9223372036854775808");
    assertInvalidQuota(validPayload, "01");
    assertInvalidQuota(validPayload, "-1");
  }

  @Test
  void eventVerifierRejectsDuplicateKeysAndTrailingInput() throws Exception {
    byte[] validPayload = event().payload();
    String canonical = new String(validPayload, StandardCharsets.UTF_8);
    String duplicateKey =
        canonical.replace(
            "\"eventType\":\"TENANT_ENTITLEMENT_CHANGED\"",
            "\"eventType\":\"TENANT_ENTITLEMENT_CHANGED\",\"eventType\":\"TENANT_ENTITLEMENT_CHANGED\"");

    assertThatThrownBy(
            () ->
                DemoTenantEntitlementEventV1Codec.verify(
                    duplicateKey.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                DemoTenantEntitlementEventV1Codec.verify(
                    (canonical + " {}").getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertInvalidCounter(byte[] validPayload, Object invalidValue)
      throws Exception {
    Map<String, Object> wire = readWire(validPayload);
    wire.put("tenantBillingSequence", invalidValue);
    assertThatThrownBy(() -> DemoTenantEntitlementEventV1Codec.verify(resign(wire)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertInvalidQuota(byte[] validPayload, Object invalidValue)
      throws Exception {
    Map<String, Object> wire = readWire(validPayload);
    nestedObject(wire, "quotas").put("maxStorageBytes", invalidValue);
    assertThatThrownBy(() -> DemoTenantEntitlementEventV1Codec.verify(resign(wire)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static DemoTenantEntitlementEventV1Codec.Event event() {
    return DemoTenantEntitlementEventV1Codec.seal(request(1L, 1L), source(91L), 1L, 1L, 1L, 1L);
  }

  private static DemoTenantEntitlementRequest request(long expectedVersion, long quotaValue) {
    return request(
        expectedVersion,
        expectedVersion,
        new DemoTenantEntitlementRequest.Quotas(quotaValue, quotaValue, quotaValue));
  }

  private static DemoTenantEntitlementRequest request(
      long expectedVersion,
      long expectedSourceVersion,
      DemoTenantEntitlementRequest.Quotas quotas) {
    return new DemoTenantEntitlementRequest(
        REQUEST_ID,
        TENANT_ID,
        CREATION_ID,
        CREATION_DIGEST,
        expectedVersion,
        expectedVersion,
        expectedSourceVersion,
        true,
        false,
        true,
        true,
        quotas);
  }

  private static FreshTenantCreationEvidence source(long sourceGameRowId) {
    String tenantKey = "demo-tenant-source-key";
    return new FreshTenantCreationEvidence(
        1,
        "account-service",
        CREATION_ID,
        OPERATION_ID,
        CREATION_DIGEST,
        TENANT_ID,
        sourceGameRowId,
        tenantKey,
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            "account-service",
            CREATION_ID,
            OPERATION_ID,
            CREATION_DIGEST,
            TENANT_ID,
            sourceGameRowId,
            tenantKey,
            "NEW_GAME_ROW"));
  }

  private static Map<String, Object> readWire(byte[] payload) throws IOException {
    return JSON.readValue(payload, new TypeReference<>() {});
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> nestedObject(Map<String, Object> wire, String field) {
    return (Map<String, Object>) wire.get(field);
  }

  private static byte[] resign(Map<String, Object> wire) throws Exception {
    Map<String, Object> preimage = new LinkedHashMap<>(wire);
    preimage.remove("eventDigest");
    byte[] canonicalPreimage =
        Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(preimage));
    String digest =
        "sha256:"
            + HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalPreimage));
    preimage.put("eventDigest", digest);
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(preimage));
  }
}
