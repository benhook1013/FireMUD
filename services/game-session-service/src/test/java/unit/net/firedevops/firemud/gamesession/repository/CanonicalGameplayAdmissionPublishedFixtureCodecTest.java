package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.OriginKind;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.OwnerProofOutcome;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRunOwnedFixtureSourceEvidence;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldRunOwnedFixtureLifecycleEvidence;
import net.firedevops.firemud.gamesession.data.RunOwnedInitialAdmissionLaunchEvidence;
import net.firedevops.firemud.gamesession.data.RunOwnedInitialAdmissionLaunchEvidenceTestFixture;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import org.junit.jupiter.api.Test;

/** Closed run-owned fixture variant of the Account published-realm owner-read wire contract. */
class CanonicalGameplayAdmissionPublishedFixtureCodecTest {
  private static final String NAMESPACE = "dev";
  private static final UUID TENANT = UUID.fromString("9db3ab6f-cc09-49d2-8f93-84484397a120");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("3f773701-e812-44f5-8ae0-7268d543beed");
  private static final UUID REALM = UUID.fromString("23d39978-9d44-4e1a-8659-998ff9239b01");
  private static final UUID PLAYABLE_NAMESPACE =
      UUID.fromString("ed1b4d88-81f8-4404-af7c-9a5dc91d3043");
  private static final UUID INSTANCE = UUID.fromString("7556e829-d531-4c61-b8d4-350c8514c4f1");
  private static final UUID VERSION = UUID.fromString("10000000-0000-4000-8000-000000000011");

  @Test
  void roundTripsRunOwnedFixtureVariantAndRefusesHumanAccessors() {
    var launch = fixtureEvidence();
    var source = fixtureSource(launch, launch.fixturePolicySeal().evidenceDigest());
    assertThat(CanonicalGameplayRunOwnedFixtureSourceEvidence.fromStored(source.canonicalBytes()))
        .isEqualTo(source);

    var parsed =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(
            CanonicalGameplayAdmissionDecisionReadGrpcCodec.toPublishedRealmRequest(
                source.request()));
    var target = target(source);
    var lifecycle = lifecycle(source, "ACTIVE", 82L);
    var response =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.toPublishedRealmFixtureResponse(
            parsed, source, target, lifecycle);
    assertThat(response.getPublishedSourceCount()).isZero();
    assertThat(response.getRunOwnedFixtureSourceCount()).isOne();

    var evidence =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromPublishedRealmResponse(
            parsed, response);
    assertThat(evidence.isRunOwnedFixture()).isTrue();
    assertThat(evidence.selector()).isEqualTo(source.request());
    assertThat(evidence.currentTarget()).isEqualTo(target);
    assertThat(evidence.fixtureSource()).isEqualTo(source);
    assertThat(evidence.fixtureLifecycleEvidence()).isEqualTo(lifecycle);
    assertThatThrownBy(evidence::sourceEvidence).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(evidence::worldLifecycleEvidence).isInstanceOf(IllegalStateException.class);

    // Exactly one source variant: a fixture response may not also carry a Human source slot.
    var both =
        response.toBuilder()
            .addPublishedSource(
                net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterOwnerReadResponse
                    .getDefaultInstance())
            .build();
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromPublishedRealmResponse(
                    parsed, both))
        .isInstanceOf(IllegalArgumentException.class);
    var neither = response.toBuilder().clearRunOwnedFixtureSource().build();
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromPublishedRealmResponse(
                    parsed, neither))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesInactiveOrWrongEpochLifecycleAndForgedSeal() {
    var launch = fixtureEvidence();
    var source = fixtureSource(launch, launch.fixturePolicySeal().evidenceDigest());
    var parsed =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(
            CanonicalGameplayAdmissionDecisionReadGrpcCodec.toPublishedRealmRequest(
                source.request()));
    var target = target(source);
    for (var lifecycle :
        java.util.List.of(
            lifecycle(source, "TERMINATING", 82L), lifecycle(source, "ACTIVE", 83L))) {
      assertThatThrownBy(
              () ->
                  CanonicalGameplayAdmissionDecisionReadGrpcCodec.toPublishedRealmFixtureResponse(
                      parsed, source, target, lifecycle))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var tampered =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.toPublishedRealmFixtureResponse(
                parsed, source, target, lifecycle(source, "ACTIVE", 82L))
            .toBuilder()
            .setWorldLifecycleEvidence(
                0,
                ByteString.copyFrom(
                    new WorldRunOwnedFixtureLifecycleEvidence(
                            lifecycleRequest(source, "sha256:" + "9".repeat(64)),
                            "sha256:" + "9".repeat(64),
                            "sha256:" + "8".repeat(64),
                            "ACTIVE",
                            82L,
                            1L)
                        .canonicalBytes()))
            .build();
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromPublishedRealmResponse(
                    parsed, tampered))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> fixtureSource(launch, "sha256:" + "9".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static CanonicalGameplayRunOwnedFixtureSourceEvidence fixtureSource(
      RunOwnedInitialAdmissionLaunchEvidence launch, String sealEvidenceDigest) {
    var request =
        new CanonicalGameplayRosterOwnerReadEvidence.Request(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            NAMESPACE,
            TENANT,
            "demo",
            REALM,
            "production",
            PLAYABLE_NAMESPACE,
            "SHARED",
            INSTANCE,
            VERSION,
            1L,
            73L,
            82L);
    var holdRequest =
        new WorldCanonicalInitialAdmissionHold.Request(
            NAMESPACE,
            TENANT,
            "demo",
            REALM,
            PLAYABLE_NAMESPACE,
            "SHARED",
            INSTANCE,
            VERSION,
            82L,
            "first-open-request-1",
            "a".repeat(64),
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            1L,
            null);
    var proof =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            new WorldCanonicalInitialAdmissionHold.HoldIdentity(
                holdRequest,
                UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff")),
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
            73L,
            91L,
            "sha256:" + "b".repeat(64),
            false,
            Instant.parse("2026-10-08T03:04:05Z"));
    var seal = launch.fixturePolicySeal();
    return new CanonicalGameplayRunOwnedFixtureSourceEvidence(
        request,
        "1".repeat(64),
        proof,
        launch.fixturePolicyRequestBytes(),
        seal.operationId(),
        seal.requestDigest(),
        sealEvidenceDigest);
  }

  private static Target target(CanonicalGameplayRunOwnedFixtureSourceEvidence source) {
    var request = source.request();
    var proof = source.gameSessionOwnerProof();
    var hold = proof.holdIdentity();
    return new Target(
        request.targetNamespace(),
        "fixture-tenant",
        request.canonicalTenantUuid(),
        request.worldSlug(),
        "Demo World",
        request.realmUuid(),
        request.realmSlug(),
        "Live Realm",
        1L,
        71L,
        request.playableStateNamespaceUuid(),
        request.playableStateScope(),
        request.canonicalGameInstanceUuid(),
        request.canonicalVersionUuid(),
        source.fixturePolicy().launchReadEvidence().publishedReleaseBundle().getVersionId(),
        request.expectedCatalogRevision(),
        request.expectedPointerVersion(),
        source.admissionPointerSnapshotDigest(),
        request.expectedActiveWorldEpoch(),
        hold.request().initialAdmissionRequestId(),
        hold.request().initialAdmissionRequestDigest(),
        OriginKind.NO_PRIOR_POINTER,
        null,
        hold.holdId(),
        hold.holdFence(),
        hold.holdBindingDigest(),
        proof.auditEventId(),
        proof.proofDigest(),
        OwnerProofOutcome.COMMITTED,
        false,
        proof.terminalAt(),
        "PRESEEDED_ONLY");
  }

  private static WorldRunOwnedFixtureLifecycleEvidence lifecycle(
      CanonicalGameplayRunOwnedFixtureSourceEvidence source, String status, long epoch) {
    String provenance = source.fixturePolicySealEvidenceDigest();
    return new WorldRunOwnedFixtureLifecycleEvidence(
        lifecycleRequest(source, provenance),
        provenance,
        "sha256:" + "f".repeat(64),
        status,
        epoch,
        1L);
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest(
      CanonicalGameplayRunOwnedFixtureSourceEvidence source, String attestationDigest) {
    var request = source.request();
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        1,
        request.requestUuid(),
        request.targetNamespace(),
        request.canonicalTenantUuid(),
        request.worldSlug(),
        request.canonicalGameInstanceUuid(),
        request.playableStateNamespaceUuid(),
        request.playableStateScope(),
        true,
        RunOwnedInitialAdmissionLaunchEvidenceTestFixture.operationId(),
        request.canonicalVersionUuid(),
        "sha256:" + "a".repeat(64),
        "sha256:" + "b".repeat(64),
        attestationDigest);
  }

  private static RunOwnedInitialAdmissionLaunchEvidence fixtureEvidence() {
    String operationId = RunOwnedInitialAdmissionLaunchEvidenceTestFixture.operationId();
    StartSessionRequest start = new StartSessionRequest(1L, 17L, operationId, 27L);
    UUID requestId = UUID.fromString(operationId);
    String tenantSlug = "fixture-tenant";
    String worldSlug = "demo";
    String worldDisplayName = "Demo World";
    long sourceGameRowId = 29L;
    String sourceGameTenantKey = "1";
    String provenanceKind = "NEW_GAME_ROW";
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, requestId, TENANT, tenantSlug, worldSlug, worldDisplayName);
    AuthoredWorldSourceEvidence source =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            requestId,
            SOURCE_OPERATION_ID,
            requestDigest,
            TENANT,
            tenantSlug,
            worldSlug,
            worldDisplayName,
            sourceGameRowId,
            sourceGameTenantKey,
            provenanceKind,
            AuthoredWorldSourceDigest.evidenceDigest(
                NAMESPACE,
                requestId,
                SOURCE_OPERATION_ID,
                requestDigest,
                TENANT,
                tenantSlug,
                worldSlug,
                worldDisplayName,
                sourceGameRowId,
                sourceGameTenantKey,
                provenanceKind));
    return RunOwnedInitialAdmissionLaunchEvidenceTestFixture.create(start, source);
  }
}
