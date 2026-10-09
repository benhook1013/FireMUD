package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind;
import org.junit.jupiter.api.Test;

class CanonicalCurrentOpenPointerSnapshotDigestTest {
  @Test
  void canonicalEncodingMatchesFixedVectorIncludingLongUtf8AndNullOriginVersion() {
    var projection = new Fixture().projection();

    assertThat(projection.initialAdmissionRequestId()).hasSize(120);
    assertThat(CanonicalCurrentOpenPointerSnapshotDigest.digest(projection))
        .isEqualTo("7bb3cb77966978f60e81ebd7189bae2ac40ead75fa6f496f4bf87d0366cf3cc2");
  }

  @Test
  void canonicalDecimalCounterEncodingSupportsMaximumPositiveLongValues() {
    Fixture fixture = new Fixture();
    fixture.catalogRevision = Long.MAX_VALUE;
    fixture.pointerVersion = Long.MAX_VALUE;
    fixture.activeWorldEpoch = Long.MAX_VALUE;
    fixture.auditEventId = Long.MAX_VALUE;
    fixture.originKind = OriginKind.EXPECT_CLOSED;
    fixture.expectedPriorPointerVersion = Long.MAX_VALUE;

    assertThat(CanonicalCurrentOpenPointerSnapshotDigest.digest(fixture.projection()))
        .isEqualTo("02703fae1556ed0b11d9d580e9d02b916f2e13cab623d5e3d73dad8cd9ead28f");
  }

  @Test
  void changesToCurrentPointerTupleOrTerminalOwnerEvidenceChangeDigest() {
    String base = digest(new Fixture());

    Fixture changedPointerVersion = new Fixture();
    changedPointerVersion.pointerVersion = 74L;
    assertThat(digest(changedPointerVersion)).isNotEqualTo(base);

    Fixture changedCatalogRevision = new Fixture();
    changedCatalogRevision.catalogRevision = 42L;
    assertThat(digest(changedCatalogRevision)).isNotEqualTo(base);

    Fixture changedRequestIdentity = new Fixture();
    changedRequestIdentity.initialAdmissionRequestId = "first-open-operation-18";
    assertThat(digest(changedRequestIdentity)).isNotEqualTo(base);

    Fixture changedRequestDigest = new Fixture();
    changedRequestDigest.initialAdmissionRequestDigest = "b".repeat(64);
    assertThat(digest(changedRequestDigest)).isNotEqualTo(base);

    Fixture changedHoldFence = new Fixture();
    changedHoldFence.holdFence = uuid("99999999-9999-4999-8999-999999999999");
    assertThat(digest(changedHoldFence)).isNotEqualTo(base);

    Fixture changedHoldDigest = new Fixture();
    changedHoldDigest.holdBindingDigest = "sha256:" + "d".repeat(64);
    assertThat(digest(changedHoldDigest)).isNotEqualTo(base);

    Fixture changedOwnerProof = new Fixture();
    changedOwnerProof.ownerProofDigest = "sha256:" + "e".repeat(64);
    assertThat(digest(changedOwnerProof)).isNotEqualTo(base);

    Fixture changedAudit = new Fixture();
    changedAudit.auditEventId = 28L;
    assertThat(digest(changedAudit)).isNotEqualTo(base);

    Fixture changedTerminalTime = new Fixture();
    changedTerminalTime.terminalAt = Instant.parse("2026-10-07T00:00:00.000001Z");
    assertThat(digest(changedTerminalTime)).isNotEqualTo(base);
  }

  @Test
  void rejectsNoncurrentFlagsInvalidSelectorsAndMalformedDigests() {
    Fixture hidden = new Fixture();
    hidden.visible = false;
    assertInvalid(hidden);

    Fixture privateRealm = new Fixture();
    privateRealm.publicProductionRealm = false;
    assertInvalid(privateRealm);

    Fixture isolatedRealm = new Fixture();
    isolatedRealm.playableStateScope = "ISOLATED";
    assertInvalid(isolatedRealm);

    Fixture closedPointer = new Fixture();
    closedPointer.admissionState = "CLOSED";
    assertInvalid(closedPointer);

    Fixture changedSelector = new Fixture();
    changedSelector.worldSlug = "not a canonical slug";
    assertInvalid(changedSelector);

    Fixture nonpositivePointer = new Fixture();
    nonpositivePointer.pointerVersion = 0L;
    assertInvalid(nonpositivePointer);

    Fixture nonpositiveCatalog = new Fixture();
    nonpositiveCatalog.catalogRevision = 0L;
    assertInvalid(nonpositiveCatalog);

    Fixture nonpositiveEpoch = new Fixture();
    nonpositiveEpoch.activeWorldEpoch = 0L;
    assertInvalid(nonpositiveEpoch);

    Fixture malformedRequestDigest = new Fixture();
    malformedRequestDigest.initialAdmissionRequestDigest = "A".repeat(64);
    assertInvalid(malformedRequestDigest);

    Fixture malformedHoldDigest = new Fixture();
    malformedHoldDigest.holdBindingDigest = "sha256:" + "G".repeat(64);
    assertInvalid(malformedHoldDigest);

    Fixture requiresCharacterSelection = new Fixture();
    requiresCharacterSelection.requiresCharacterSelection = true;
    assertInvalid(requiresCharacterSelection);

    Fixture preparedVersionUpgrade = new Fixture();
    preparedVersionUpgrade.preparedVersionUpgradeId = "prepared-upgrade";
    assertInvalid(preparedVersionUpgrade);

    Fixture invalidNullableOrigin = new Fixture();
    invalidNullableOrigin.expectedPriorPointerVersion = 1L;
    assertInvalid(invalidNullableOrigin);

    Fixture invalidUtf16 = new Fixture();
    invalidUtf16.worldDisplayName = "bad\uD800";
    assertInvalid(invalidUtf16);
  }

  @Test
  void nullablePriorVersionHasAClosedOriginRepresentation() {
    Fixture noPrior = new Fixture();
    assertThat(noPrior.expectedPriorPointerVersion).isNull();
    String noPriorDigest = digest(noPrior);

    Fixture expectedClosed = new Fixture();
    expectedClosed.originKind = OriginKind.EXPECT_CLOSED;
    expectedClosed.expectedPriorPointerVersion = 1L;
    assertThat(digest(expectedClosed)).isNotEqualTo(noPriorDigest);

    expectedClosed.expectedPriorPointerVersion = null;
    assertInvalid(expectedClosed);
  }

  private static String digest(Fixture fixture) {
    return CanonicalCurrentOpenPointerSnapshotDigest.digest(fixture.projection());
  }

  private static void assertInvalid(Fixture fixture) {
    assertThatThrownBy(() -> fixture.projection()).isInstanceOf(IllegalArgumentException.class);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static final class Fixture {
    int representationVersion = 3;
    String targetNamespace = "gameplay";
    UUID canonicalTenantId = uuid("11111111-1111-4111-8111-111111111111");
    String worldSlug = "demo-world";
    String worldDisplayName = "Demo Ω World";
    UUID realmId = uuid("22222222-2222-4222-8222-222222222222");
    String realmSlug = "main";
    String realmDisplayName = "Production — Main";
    UUID playableStateNamespaceId = uuid("33333333-3333-4333-8333-333333333333");
    String playableStateScope = "SHARED";
    long catalogRevision = 41L;
    long pointerVersion = 73L;
    String admissionState = "OPEN";
    boolean visible = true;
    boolean publicProductionRealm = true;
    Boolean requiresCharacterSelection;
    String characterCreationPolicy = "ALLOW_NEW";
    UUID canonicalGameInstanceId = uuid("44444444-4444-4444-8444-444444444444");
    UUID canonicalVersionId = uuid("55555555-5555-4555-8555-555555555555");
    String initialAdmissionRequestId = "request-" + "x".repeat(112);
    String initialAdmissionRequestDigest = "a".repeat(64);
    OriginKind originKind = OriginKind.NO_PRIOR_POINTER;
    Long expectedPriorPointerVersion;
    long activeWorldEpoch = 82L;
    UUID holdId = uuid("77777777-7777-4777-8777-777777777777");
    UUID holdFence = uuid("88888888-8888-4888-8888-888888888888");
    String holdBindingDigest = "sha256:" + "c".repeat(64);
    String lastUpdatedBy = "game-session-canonical-initial-admission";
    String lastUpdateReason = "World-held initial admission";
    String preparedVersionUpgradeId;
    String ownerProofDigest = "sha256:" + "f".repeat(64);
    long auditEventId = 27L;
    Instant terminalAt = Instant.parse("2026-10-07T00:00:00Z");

    CanonicalCurrentOpenPointerSnapshotDigest.Projection projection() {
      return new CanonicalCurrentOpenPointerSnapshotDigest.Projection(
          representationVersion,
          targetNamespace,
          canonicalTenantId,
          worldSlug,
          worldDisplayName,
          realmId,
          realmSlug,
          realmDisplayName,
          playableStateNamespaceId,
          playableStateScope,
          catalogRevision,
          pointerVersion,
          admissionState,
          visible,
          publicProductionRealm,
          requiresCharacterSelection,
          characterCreationPolicy,
          canonicalGameInstanceId,
          canonicalVersionId,
          initialAdmissionRequestId,
          initialAdmissionRequestDigest,
          originKind,
          expectedPriorPointerVersion,
          activeWorldEpoch,
          holdId,
          holdFence,
          holdBindingDigest,
          lastUpdatedBy,
          lastUpdateReason,
          preparedVersionUpgradeId,
          ownerProofDigest,
          auditEventId,
          terminalAt);
    }
  }
}
