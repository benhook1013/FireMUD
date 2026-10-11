package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalClosedAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalClosedAdmissionPointerRequest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import org.junit.jupiter.api.Test;

class GameSessionCanonicalAdmissionPointerRepositoryTest {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String TARGET_NAMESPACE = "canonical-closed-pointer-test";

  @Test
  void requestAndReceiptDigestsMatchFixedOrderUtf8GoldenVectors() {
    CreateCanonicalClosedAdmissionPointerRequest request = request("Café 🐉", "close 東京");
    CanonicalRealmCatalogSnapshot catalog = catalogSnapshot();
    String requestDigest =
        GameSessionCanonicalAdmissionPointerRepository.requestDigest(request, catalog);

    assertThat(requestDigest)
        .isEqualTo("sha256:0dc3926970e3d5be36349ff3fe259869fed779f3429b767e2e20ece407313d24");
    assertThat(
            GameSessionCanonicalAdmissionPointerRepository.requestDigest(
                request("Café 🐲", "close 東京"), catalog))
        .isNotEqualTo(requestDigest);
    assertThat(
            GameSessionCanonicalAdmissionPointerRepository.receiptDigest(
                request,
                catalog,
                requestDigest,
                123L,
                Instant.parse("2026-10-03T00:00:00.123456Z")))
        .isEqualTo("sha256:5d465f6eec3f08d0de06c5562ba60d2bd44d335c41f82355c4adc158d5806286");
    assertThat(
            GameSessionCanonicalAdmissionPointerRepository.receiptDigest(
                request,
                catalog,
                requestDigest,
                124L,
                Instant.parse("2026-10-03T00:00:00.123456Z")))
        .isNotEqualTo("sha256:5d465f6eec3f08d0de06c5562ba60d2bd44d335c41f82355c4adc158d5806286");
  }

  @Test
  void requestRejectsNilIdentityAndNonCanonicalTargetNamespace() {
    assertThatThrownBy(
            () ->
                new CreateCanonicalClosedAdmissionPointerRequest(
                    NIL_UUID, TARGET_NAMESPACE, uuid(2), uuid(3), uuid(4), 1L, "actor", "reason"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-nil UUID");
    assertThatThrownBy(
            () ->
                new CreateCanonicalClosedAdmissionPointerRequest(
                    uuid(1), "Canonical_Closed", uuid(2), uuid(3), uuid(4), 1L, "actor", "reason"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical DNS label");
    assertThatThrownBy(
            () ->
                new CreateCanonicalClosedAdmissionPointerRequest(
                    uuid(1), TARGET_NAMESPACE, uuid(2), uuid(3), uuid(4), 0L, "actor", "reason"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("catalogRevision must be positive");
  }

  @Test
  void requestRejectsMalformedOrUnboundedAuditText() {
    assertThatThrownBy(
            () ->
                new CreateCanonicalClosedAdmissionPointerRequest(
                    uuid(1),
                    TARGET_NAMESPACE,
                    uuid(2),
                    uuid(3),
                    uuid(4),
                    1L,
                    "bad" + (char) 0xD800,
                    "reason"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("well-formed UTF-8");
    assertThatThrownBy(
            () ->
                new CreateCanonicalClosedAdmissionPointerRequest(
                    uuid(1), TARGET_NAMESPACE, uuid(2), uuid(3), uuid(4), 1L, "actor", "  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bounded and nonblank");
  }

  @Test
  void expectedClosedVersionCheckRequiresTheExactInitialPointerAndCatalogTuple() {
    CreateCanonicalClosedAdmissionPointerRequest request = request("origin actor", "origin reason");
    CanonicalRealmCatalogSnapshot catalog = catalogSnapshot();
    String requestDigest =
        GameSessionCanonicalAdmissionPointerRepository.requestDigest(request, catalog);
    Instant updatedAt = Instant.parse("2026-10-03T00:00:00.123456Z");
    CanonicalClosedAdmissionPointerSnapshot origin =
        new CanonicalClosedAdmissionPointerSnapshot(
            catalog.targetNamespace(),
            catalog.tenantId(),
            catalog.realmId(),
            catalog.worldSlug(),
            catalog.realmSlug(),
            1L,
            catalog.catalogRevision(),
            "CLOSED",
            null,
            request.requestId(),
            requestDigest,
            GameSessionCanonicalAdmissionPointerRepository.receiptDigest(
                request, catalog, requestDigest, 123L, updatedAt),
            request.actorPrincipal(),
            request.reason(),
            123L,
            updatedAt,
            catalog);

    assertThatCode(
            () ->
                GameSessionCanonicalAdmissionPointerRepository.requireExpectedVersions(
                    origin, 1L, 1L))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                GameSessionCanonicalAdmissionPointerRepository.requireExpectedVersions(
                    origin, 2L, 1L))
        .isInstanceOf(
            GameSessionCanonicalAdmissionPointerRepository
                .InvalidCanonicalClosedPointerEvidenceException.class)
        .hasMessageContaining("versions do not match");
    assertThatThrownBy(
            () ->
                GameSessionCanonicalAdmissionPointerRepository.requireExpectedVersions(
                    origin, 1L, 2L))
        .isInstanceOf(
            GameSessionCanonicalAdmissionPointerRepository
                .InvalidCanonicalClosedPointerEvidenceException.class)
        .hasMessageContaining("versions do not match");
  }

  private static CreateCanonicalClosedAdmissionPointerRequest request(
      String actorPrincipal, String reason) {
    return new CreateCanonicalClosedAdmissionPointerRequest(
        uuid(1), TARGET_NAMESPACE, uuid(2), uuid(3), uuid(4), 1L, actorPrincipal, reason);
  }

  private static CanonicalRealmCatalogSnapshot catalogSnapshot() {
    UUID tenantId = uuid(2);
    UUID registrationRequestId = uuid(10);
    UUID sourceOperationId = uuid(11);
    String tenantSlug = "unit-tenant";
    String worldSlug = "authored-world";
    String worldDisplayName = "Authored World";
    String sourceGameTenantKey = "gds-unit-tenant";
    String sourceRequestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            TARGET_NAMESPACE,
            registrationRequestId,
            tenantId,
            tenantSlug,
            worldSlug,
            worldDisplayName);
    String sourceEvidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            TARGET_NAMESPACE,
            registrationRequestId,
            sourceOperationId,
            sourceRequestDigest,
            tenantId,
            tenantSlug,
            worldSlug,
            worldDisplayName,
            42L,
            sourceGameTenantKey,
            "NEW_GAME_ROW");
    AuthoredWorldSourceEvidence source =
        new AuthoredWorldSourceEvidence(
            1,
            TARGET_NAMESPACE,
            registrationRequestId,
            sourceOperationId,
            sourceRequestDigest,
            tenantId,
            tenantSlug,
            worldSlug,
            worldDisplayName,
            42L,
            sourceGameTenantKey,
            "NEW_GAME_ROW",
            sourceEvidenceDigest);
    UUID intakeRequestId = uuid(12);
    String intakeRequestDigest =
        GameSessionAuthoredWorldIntakeDigest.requestDigest(intakeRequestId, source);
    IntakeReceipt sourceReceipt =
        new IntakeReceipt(
            uuid(13),
            intakeRequestId,
            intakeRequestDigest,
            source,
            GameSessionAuthoredWorldIntakeDigest.receiptDigest(
                uuid(13), intakeRequestDigest, source.evidenceDigest()));
    return new CanonicalRealmCatalogSnapshot(
        TARGET_NAMESPACE,
        tenantId,
        tenantSlug,
        worldSlug,
        uuid(3),
        "Arcology",
        "Public Realm",
        true,
        true,
        "SHARED",
        uuid(14),
        "owner-policy-v1",
        1L,
        uuid(4),
        "sha256:" + "a".repeat(64),
        "sha256:" + "b".repeat(64),
        sourceReceipt);
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }
}
