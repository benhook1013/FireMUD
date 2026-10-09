package unit.net.firedevops.firemud.accountservice.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementEventV1Codec;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.dto.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.junit.jupiter.api.Test;

class TenantAuthorityEventV1CodecTest {
  @Test
  void authorityEventKeepsItsCanonicalCheckpointSeparateFromBillingReceipt() {
    UUID tenantId = UUID.fromString("44444444-4444-4444-8444-444444444444");
    FreshTenantCreationEvidence source = source(tenantId);
    DemoTenantEntitlementRequest request =
        new DemoTenantEntitlementRequest(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            tenantId,
            source.creationRequestId(),
            source.requestDigest(),
            null,
            null,
            null,
            true,
            false,
            false,
            true,
            new DemoTenantEntitlementRequest.Quotas(3L, 2L, 4096L));
    DemoTenantEntitlementEventV1Codec.Event billingEvent =
        DemoTenantEntitlementEventV1Codec.seal(request, source, 1L, 2L, 2L, 1L);
    TenantAuthorityEventV1Codec.Event authorityEvent =
        TenantAuthorityEventV1Codec.seal(request, source, 2L, 2L, 1L, billingEvent);

    TenantAuthorityEventV1Codec.Event verified =
        TenantAuthorityEventV1Codec.verify(authorityEvent.payload());
    assertThat(verified.outboxStreamKey())
        .isEqualTo("account:auth-authority:v1:tenant/" + tenantId);
    assertThat(verified.outboxSequence()).isEqualTo(1L);
    assertThat(verified.tenantBillingStreamKey())
        .isEqualTo("account:tenant-entitlement:v1:tenant/" + tenantId);
    assertThat(verified.tenantBillingSequence()).isEqualTo(1L);
    assertThat(verified.eventId())
        .isEqualTo(TenantAuthorityEventV1Codec.eventId(request.requestId()));
    assertThat(verified.tenantBillingEventId()).isEqualTo(billingEvent.eventId());
    assertThat(verified.tenantBillingEventDigest()).isEqualTo(billingEvent.eventDigest());
    assertThat(verified.sourceEvidence()).isEqualTo(source);
    assertThat(new String(verified.payload(), StandardCharsets.UTF_8))
        .contains("\"tenantAuthorityGeneration\":\"2\"")
        .contains("\"tenantBillingSequence\":\"1\"");

    String alteredJson =
        new String(authorityEvent.payload(), StandardCharsets.UTF_8)
            .replace("\"tenantAuthorityGeneration\":\"2\"", "\"tenantAuthorityGeneration\":\"3\"");
    assertThat(alteredJson)
        .isNotEqualTo(new String(authorityEvent.payload(), StandardCharsets.UTF_8));
    assertThatThrownBy(
            () -> TenantAuthorityEventV1Codec.verify(alteredJson.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digest or canonical bytes differ");
  }

  private static FreshTenantCreationEvidence source(UUID tenantId) {
    UUID creationRequestId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    UUID operationId = UUID.fromString("33333333-3333-4333-8333-333333333333");
    String requestDigest = "sha256:" + "a".repeat(64);
    String evidenceDigest =
        GameTenantCreationDigest.evidenceDigest(
            "account-service",
            creationRequestId,
            operationId,
            requestDigest,
            tenantId,
            801L,
            "tenant-801",
            "NEW_GAME_ROW");
    return new FreshTenantCreationEvidence(
        1,
        "account-service",
        creationRequestId,
        operationId,
        requestDigest,
        tenantId,
        801L,
        "tenant-801",
        "NEW_GAME_ROW",
        evidenceDigest);
  }
}
