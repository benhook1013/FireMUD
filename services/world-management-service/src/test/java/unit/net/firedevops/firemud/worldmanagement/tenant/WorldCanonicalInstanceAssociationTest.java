package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadGrpcCodec;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidence;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidenceTestFixtures;
import org.junit.jupiter.api.Test;

class WorldCanonicalInstanceAssociationTest {
  @Test
  void runOwnedFixtureUsesDeepValueEqualityAndRetainsDefensiveResponseBytes() {
    FixtureCase fixtureCase = validFixtureCase();
    WorldCanonicalInstanceAssociation.RunOwnedFixture original = fixtureCase.fixture();
    byte[] constructorBytes = original.gameSessionAssociationResponseBytes();
    byte[] expectedBytes = constructorBytes.clone();

    WorldCanonicalInstanceAssociation.RunOwnedFixture reconstructed =
        copy(original, constructorBytes, original.preparationInputDigest());
    assertThat(reconstructed).isEqualTo(original);
    assertThat(reconstructed.hashCode()).isEqualTo(original.hashCode());
    assertThat(reconstructed.gameSessionAssociationResponseBytes()).containsExactly(expectedBytes);

    WorldCanonicalInstanceAssociation reconstructedAssociation =
        association(
            fixtureCase.identity(),
            fixtureCase.worldInstanceId(),
            fixtureCase.versionIdentity(),
            reconstructed);
    assertThat(reconstructedAssociation).isEqualTo(fixtureCase.association());
    assertThat(reconstructedAssociation.hashCode()).isEqualTo(fixtureCase.association().hashCode());

    constructorBytes[0] ^= 1;
    assertThat(reconstructed.gameSessionAssociationResponseBytes()).containsExactly(expectedBytes);
    assertThat(original.gameSessionAssociationResponseBytes()).containsExactly(expectedBytes);
    byte[] accessorBytes = reconstructed.gameSessionAssociationResponseBytes();
    accessorBytes[0] ^= 1;
    assertThat(reconstructed.gameSessionAssociationResponseBytes()).containsExactly(expectedBytes);

    WorldCanonicalInstanceAssociation.RunOwnedFixture changed =
        copy(original, original.gameSessionAssociationResponseBytes(), digest('d'));
    assertThat(changed).isNotEqualTo(original);
    assertThat(
            association(
                fixtureCase.identity(),
                fixtureCase.worldInstanceId(),
                fixtureCase.versionIdentity(),
                changed))
        .isNotEqualTo(fixtureCase.association());
  }

  private static FixtureCase validFixtureCase() {
    RunOwnedWorldLaunchEvidence evidence = RunOwnedWorldLaunchEvidenceTestFixtures.create();
    var source = evidence.sourceReceipt().sourceEvidence();
    UUID intakeRequestId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    UUID intakeOperationId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    long worldTenantKey = 9001L;
    String requestDigest =
        WorldAuthoredSourceIntakeDigest.requestDigest(
            source.targetNamespace(), intakeRequestId, source);
    WorldAuthoredSourceIntakeReceipt sourceReceipt =
        new WorldAuthoredSourceIntakeReceipt(
            1,
            source.targetNamespace(),
            intakeRequestId,
            intakeOperationId,
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            requestDigest,
            WorldAuthoredSourceIntakeDigest.receiptDigest(
                source.targetNamespace(), intakeOperationId, requestDigest, source, worldTenantKey),
            worldTenantKey,
            source);

    var versionSnapshot = evidence.sourceReceipt().launchReadEvidence().versionState();
    var versionRequest =
        new AuthoredWorldVersionStateEvidence.Request(
            1,
            source.targetNamespace(),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            versionSnapshot.getVersionId());
    var versionStateEvidence =
        AuthoredWorldVersionStateEvidence.create(
            versionRequest,
            source,
            evidence.gameSessionAssociation().canonicalVersionId(),
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
            versionSnapshot.getVersionStateEpoch());
    WorldAuthoredVersionIdentityReceipt versionIdentity =
        new WorldAuthoredVersionIdentityReceipt(
            1,
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            9003L,
            sourceReceipt,
            versionStateEvidence);

    var gameSession = evidence.gameSessionAssociation();
    var capability = evidence.capability();
    var identity =
        new WorldCanonicalInstanceAssociation.CanonicalIdentity(
            gameSession.gameInstanceUuid(),
            source.targetNamespace(),
            source.canonicalTenantId(),
            source.worldSlug(),
            gameSession.playableStateNamespaceId(),
            capability.stateScope(),
            capability.publicProductionRealm(),
            gameSession.canonicalRealmId(),
            gameSession.catalogRevision(),
            capability.operationId());
    long worldInstanceId = 9002L;
    var privateKeys =
        new WorldCanonicalInstanceAssociation.WorldPrivateKeys(
            worldTenantKey, worldInstanceId, versionIdentity.localVersionKey());
    var fixture =
        new WorldCanonicalInstanceAssociation.RunOwnedFixture(
            privateKeys,
            gameSession.gameSessionTenantId(),
            gameSession.gameInstanceId(),
            capability.gameTemplateId(),
            UUID.fromString(capability.operationId()),
            evidence.fixtureProvenanceDigest(),
            digest('a'),
            1L,
            CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toFixtureResponse(gameSession)
                .toByteArray(),
            gameSession,
            sourceReceipt);
    return new FixtureCase(
        identity,
        worldInstanceId,
        versionIdentity,
        fixture,
        association(identity, worldInstanceId, versionIdentity, fixture));
  }

  private static WorldCanonicalInstanceAssociation.RunOwnedFixture copy(
      WorldCanonicalInstanceAssociation.RunOwnedFixture fixture,
      byte[] responseBytes,
      String preparationInputDigest) {
    return new WorldCanonicalInstanceAssociation.RunOwnedFixture(
        fixture.worldPrivateKeys(),
        fixture.gameSessionTenantId(),
        fixture.gameSessionGameInstanceId(),
        fixture.gameTemplateId(),
        fixture.fixtureOperationId(),
        fixture.fixtureProvenanceDigest(),
        preparationInputDigest,
        fixture.preparingLifecycleEpoch(),
        responseBytes,
        fixture.gameSessionAssociation(),
        fixture.sourceIntakeReceipt());
  }

  private static WorldCanonicalInstanceAssociation association(
      WorldCanonicalInstanceAssociation.CanonicalIdentity identity,
      long worldInstanceId,
      WorldAuthoredVersionIdentityReceipt versionIdentity,
      WorldCanonicalInstanceAssociation.RunOwnedFixture fixture) {
    return new WorldCanonicalInstanceAssociation(
        identity, worldInstanceId, null, versionIdentity, null, fixture);
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }

  private record FixtureCase(
      WorldCanonicalInstanceAssociation.CanonicalIdentity identity,
      long worldInstanceId,
      WorldAuthoredVersionIdentityReceipt versionIdentity,
      WorldCanonicalInstanceAssociation.RunOwnedFixture fixture,
      WorldCanonicalInstanceAssociation association) {}
}
