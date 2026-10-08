package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadGrpcCodec;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GameSessionWorldCanonicalLifecycleVerifierTest {
  private static final UUID OPERATION_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID REQUEST_ID = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID COMMIT_ID = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID INTAKE_REQUEST_ID = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID FENCE_ID = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID ACTOR_ID = uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID TENANT_ID = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION_ID = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID REGION_TEMPLATE_ID = uuid("88888888-8888-4888-8888-888888888888");
  private static final UUID ZONE_TEMPLATE_ID = uuid("99999999-9999-4999-8999-999999999999");
  private static final UUID ROOM_TEMPLATE_ID = uuid("77777777-7777-4777-8777-777777777777");
  private static final UUID REGION_REVISION_ID = uuid("12345678-1234-4234-8234-123456789001");
  private static final UUID ZONE_REVISION_ID = uuid("12345678-1234-4234-8234-123456789002");
  private static final UUID ROOM_REVISION_ID = uuid("12345678-1234-4234-8234-123456789003");
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void acceptsCompletePreparingAndNextActiveCaptureIncludingExactRetriesAndRowVersionAdvance()
      throws Exception {
    Fixture preparing =
        fixture("PREPARING", 41L, 60L, uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"));
    GameSessionWorldCanonicalLifecycleVerifier verifier =
        new GameSessionWorldCanonicalLifecycleVerifier();

    var verifiedPreparing = verifier.verifyPreparing(preparing.expected(), preparing.evidence());
    assertThat(verifiedPreparing.startLocation()).isEqualTo(preparing.evidence().startLocation());
    assertThat(verifiedPreparing.runtimeRoomInstanceId())
        .isEqualTo(preparing.evidence().runtimeRoomInstanceId());
    assertThat(verifiedPreparing.lifecycleEpoch()).isEqualTo(41L);
    assertThat(verifiedPreparing.expected().launchBinding().descriptor().versionStateEpoch())
        .isNotEqualTo(verifiedPreparing.lifecycleEpoch());

    var exactPreparingRetry =
        verifier.verifyCurrentPreparing(
            verifiedPreparing, preparing.expected(), preparing.evidence());
    assertThat(exactPreparingRetry.rowVersion()).isEqualTo(60L);

    Fixture currentPreparing =
        rebind(
            preparing,
            uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeef"),
            "PREPARING",
            41L,
            63L,
            null,
            null,
            null,
            null,
            null);
    var verifiedCurrentPreparing =
        verifier.verifyCurrentPreparing(
            verifiedPreparing, currentPreparing.expected(), currentPreparing.evidence());
    assertThat(verifiedCurrentPreparing.rowVersion()).isEqualTo(63L);

    Fixture active =
        rebind(
            preparing,
            uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeed"),
            "ACTIVE",
            42L,
            64L,
            null,
            null,
            null,
            null,
            null);
    var verifiedActive =
        verifier.verifyActiveAfterPreparing(
            verifiedCurrentPreparing, active.expected(), active.evidence());
    assertThat(verifiedActive.lifecycleEpoch()).isEqualTo(42L);
    assertThat(verifiedActive.rowVersion()).isEqualTo(64L);
    assertThat(verifiedActive.evidence().launchBinding())
        .isEqualTo(preparing.evidence().launchBinding());

    var exactActiveRetry =
        verifier.verifyCurrentActive(verifiedActive, active.expected(), active.evidence());
    assertThat(exactActiveRetry.rowVersion()).isEqualTo(64L);
    Fixture newerCurrentActive =
        rebind(
            active,
            uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeec"),
            "ACTIVE",
            42L,
            70L,
            null,
            null,
            null,
            null,
            null);
    assertThat(
            verifier
                .verifyCurrentActive(
                    verifiedActive, newerCurrentActive.expected(), newerCurrentActive.evidence())
                .rowVersion())
        .isEqualTo(70L);
  }

  @Test
  void rejectsChangedCanonicalLaunchScopeAndCompleteReleaseAcrossReads() throws Exception {
    Fixture preparing =
        fixture("PREPARING", 41L, 60L, uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"));
    GameSessionWorldCanonicalLifecycleVerifier verifier =
        new GameSessionWorldCanonicalLifecycleVerifier();
    var verified = verifier.verifyPreparing(preparing.expected(), preparing.evidence());
    var request = preparing.expected().request();
    var binding = preparing.expected().launchBinding();

    var changedTargetNamespaceRequest =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            request.schemaVersion(),
            uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeef"),
            "another-test",
            request.canonicalTenantId(),
            request.worldSlug(),
            request.canonicalGameInstanceId(),
            request.playableStateNamespaceId(),
            request.playableStateScope(),
            request.publicProduction(),
            request.controlPlaneRequestId(),
            request.canonicalVersionId(),
            request.expectedDescriptorRequestDigest(),
            request.expectedDescriptorResultDigest(),
            request.expectedReleaseAttestationDigest());
    assertThatThrownBy(
            () ->
                new GameSessionWorldCanonicalLifecycleVerifier.Expected(
                    changedTargetNamespaceRequest, binding))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                new GameSessionWorldCanonicalLifecycleVerifier.Expected(
                    copyRequest(
                        request,
                        null,
                        uuid("33333333-3333-4333-8333-333333333333"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                    binding))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GameSessionWorldCanonicalLifecycleVerifier.Expected(
                    copyRequest(
                        request,
                        null,
                        null,
                        "another-world",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                    binding))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GameSessionWorldCanonicalLifecycleVerifier.Expected(
                    copyRequest(
                        request,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        uuid("33333333-3333-4333-8333-333333333333"),
                        null,
                        null,
                        null),
                    binding))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GameSessionWorldCanonicalLifecycleVerifier.Expected(
                    copyRequest(
                        request,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "sha256:" + "0".repeat(64)),
                    binding))
        .isInstanceOf(IllegalArgumentException.class);

    var changedInstanceRequest =
        copyRequest(
            request,
            uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeef"),
            null,
            null,
            uuid("33333333-3333-4333-8333-333333333333"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    Fixture changedInstance = fixtureWith(preparing, changedInstanceRequest, preparing.evidence());
    assertThatThrownBy(
            () ->
                verifier.verifyCurrentPreparing(
                    verified, changedInstance.expected(), changedInstance.evidence()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical launch request binding");

    var changedNamespaceRequest =
        copyRequest(
            request,
            uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeef"),
            null,
            null,
            null,
            uuid("44444444-4444-4444-8444-444444444444"),
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    Fixture changedNamespace =
        fixtureWith(preparing, changedNamespaceRequest, preparing.evidence());
    assertThatThrownBy(
            () ->
                verifier.verifyCurrentPreparing(
                    verified, changedNamespace.expected(), changedNamespace.evidence()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical launch request binding");

    var alteredRelease = changedRelease(binding);
    var alteredReleaseRequest =
        copyRequest(
            request,
            uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeef"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            alteredRelease.releaseAttestation().evidenceDigest());
    var alteredReleaseEvidence =
        withBinding(preparing.evidence(), alteredReleaseRequest, alteredRelease);
    Fixture changedRelease =
        new Fixture(
            new GameSessionWorldCanonicalLifecycleVerifier.Expected(
                alteredReleaseRequest, alteredRelease),
            alteredReleaseEvidence);
    assertThatThrownBy(
            () ->
                verifier.verifyCurrentPreparing(
                    verified, changedRelease.expected(), changedRelease.evidence()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical launch request binding");

    assertThatThrownBy(
            () ->
                new GameSessionWorldCanonicalLifecycleVerifier.Expected(
                    copyRequest(
                        request,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "ISOLATED",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                    binding))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsChangedCaptureInputGraphSelectorRuntimeRoomAndStaleEpochOrRowVersion()
      throws Exception {
    Fixture preparing =
        fixture("PREPARING", 41L, 60L, uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"));
    GameSessionWorldCanonicalLifecycleVerifier verifier =
        new GameSessionWorldCanonicalLifecycleVerifier();
    var verifiedPreparing = verifier.verifyPreparing(preparing.expected(), preparing.evidence());
    Fixture active =
        rebind(
            preparing,
            uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeed"),
            "ACTIVE",
            42L,
            64L,
            null,
            null,
            null,
            null,
            null);
    var verifiedActive =
        verifier.verifyActiveAfterPreparing(
            verifiedPreparing, active.expected(), active.evidence());

    assertCurrentActiveRejected(
        verifier,
        verifiedActive,
        active,
        withEvidence(
            active.evidence(),
            active.expected().request(),
            "ACTIVE",
            42L,
            64L,
            uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            null,
            null,
            null,
            null));
    assertThatThrownBy(
            () ->
                withEvidence(
                    active.evidence(),
                    active.expected().request(),
                    "ACTIVE",
                    42L,
                    64L,
                    null,
                    "a".repeat(64),
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("release selector");
    assertCurrentActiveRejected(
        verifier,
        verifiedActive,
        active,
        withEvidence(
            active.evidence(),
            active.expected().request(),
            "ACTIVE",
            42L,
            64L,
            null,
            null,
            "sha256:" + "0".repeat(64),
            null,
            null));
    assertCurrentActiveRejected(
        verifier,
        verifiedActive,
        active,
        withEvidence(
            active.evidence(),
            active.expected().request(),
            "ACTIVE",
            42L,
            64L,
            null,
            null,
            null,
            null,
            active.evidence().runtimeRoomInstanceId() + 1L));

    assertThatThrownBy(
            () ->
                withEvidence(
                    active.evidence(),
                    active.expected().request(),
                    "ACTIVE",
                    42L,
                    64L,
                    null,
                    null,
                    null,
                    new RoomTemplateRef(
                        active.evidence().startLocation().tenantId(),
                        active.evidence().startLocation().versionId(),
                        uuid("66666666-6666-4666-8666-666666666666")),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("release selector");

    Fixture staleEpoch =
        rebind(
            preparing,
            uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeed"),
            "ACTIVE",
            43L,
            64L,
            null,
            null,
            null,
            null,
            null);
    assertThatThrownBy(
            () ->
                verifier.verifyActiveAfterPreparing(
                    verifiedPreparing, staleEpoch.expected(), staleEpoch.evidence()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("immediately follow PREPARING");

    for (long unchangedOrRegressingRowVersion : List.of(60L, 59L)) {
      Fixture staleTransitionRowVersion =
          rebind(
              preparing,
              uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeed"),
              "ACTIVE",
              42L,
              unchangedOrRegressingRowVersion,
              null,
              null,
              null,
              null,
              null);
      assertThatThrownBy(
              () ->
                  verifier.verifyActiveAfterPreparing(
                      verifiedPreparing,
                      staleTransitionRowVersion.expected(),
                      staleTransitionRowVersion.evidence()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("row version did not advance");
    }

    Fixture staleRowVersion =
        rebind(
            active,
            active.expected().request().readRequestId(),
            "ACTIVE",
            42L,
            63L,
            null,
            null,
            null,
            null,
            null);
    assertThatThrownBy(
            () ->
                verifier.verifyCurrentActive(
                    verifiedActive, staleRowVersion.expected(), staleRowVersion.evidence()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("moved backwards");

    Fixture changedActiveEpoch =
        rebind(
            active,
            active.expected().request().readRequestId(),
            "ACTIVE",
            43L,
            65L,
            null,
            null,
            null,
            null,
            null);
    assertThatThrownBy(
            () ->
                verifier.verifyCurrentActive(
                    verifiedActive, changedActiveEpoch.expected(), changedActiveEpoch.evidence()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lifecycle epoch changed");
  }

  @Test
  void rejectsNonAdmissibleStatusesAndPreparingEpochOverflow() throws Exception {
    Fixture preparing =
        fixture("PREPARING", Long.MAX_VALUE, 60L, uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"));
    GameSessionWorldCanonicalLifecycleVerifier verifier =
        new GameSessionWorldCanonicalLifecycleVerifier();
    var verifiedPreparing = verifier.verifyPreparing(preparing.expected(), preparing.evidence());

    assertThatThrownBy(
            () -> fixture("UNKNOWN", 42L, 61L, uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeed")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported World lifecycle status");
    assertThatThrownBy(() -> fixture(null, 42L, 61L, uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeed")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported World lifecycle status");

    Fixture overflowActive =
        rebind(
            preparing,
            uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeed"),
            "ACTIVE",
            1L,
            61L,
            null,
            null,
            null,
            null,
            null);
    assertThatThrownBy(
            () ->
                verifier.verifyActiveAfterPreparing(
                    verifiedPreparing, overflowActive.expected(), overflowActive.evidence()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot advance");

    Fixture active =
        rebind(
            preparing,
            uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeed"),
            "ACTIVE",
            Long.MAX_VALUE,
            61L,
            null,
            null,
            null,
            null,
            null);
    assertThatThrownBy(() -> verifier.verifyPreparing(active.expected(), active.evidence()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not PREPARING");

    for (String status : List.of("FAILED_PRE_ACTIVATION", "TERMINATING", "TERMINATED")) {
      Fixture unavailable =
          rebind(
              preparing,
              uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeed"),
              status,
              42L,
              61L,
              null,
              null,
              null,
              null,
              null);
      assertThatThrownBy(
              () ->
                  verifier.verifyActiveAfterPreparing(
                      verifiedPreparing, unavailable.expected(), unavailable.evidence()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not ACTIVE");
    }
  }

  private static void assertCurrentActiveRejected(
      GameSessionWorldCanonicalLifecycleVerifier verifier,
      GameSessionWorldCanonicalLifecycleVerifier.VerifiedActive previouslyVerified,
      Fixture expected,
      WorldCanonicalInstanceLifecycleEvidence observed) {
    assertThatThrownBy(
            () -> verifier.verifyCurrentActive(previouslyVerified, expected.expected(), observed))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Fixture fixture(String status, long epoch, long rowVersion, UUID readRequestId)
      throws Exception {
    WorldPublishedStartLocationEvidence selector = selectorEvidence();
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            new AuthoredWorldLaunchDescriptorEvidence.Request(
                "test",
                "world-lifecycle-control-request",
                selector.request().canonicalTenantId(),
                "synthetic-world",
                uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
                "sha256:" + "a".repeat(64),
                19L,
                false,
                null,
                false,
                null,
                false,
                null,
                false,
                null),
            "canonical-instance-launch-descriptor",
            42L,
            false,
            null,
            "{}",
            "generation-revision",
            9L,
            7L,
            "release-bundle",
            false,
            null);
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        selector.request().appliedCommitId(),
                        selector.request().contentDigest(),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "c".repeat(64) : null))
            .toList();
    AuthoredWorldReleaseAttestationEvidence release =
        AuthoredWorldReleaseAttestationEvidence.create(
            descriptor.targetNamespace(),
            descriptor.resultDigest(),
            descriptor.canonicalTenantId(),
            selector.request().canonicalVersionId(),
            descriptor.worldSlug(),
            descriptor.authoredWorldSourceOperationId(),
            descriptor.authoredWorldSourceEvidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            selector.request().publishWorkflowId(),
            selector.request().appliedCommitId(),
            participants,
            "sha256:" + "d".repeat(64),
            1,
            List.of(),
            List.of(),
            List.of(),
            descriptor.generationConfigRevision(),
            selector);
    CompleteLaunchBindingEvidence binding = new CompleteLaunchBindingEvidence(descriptor, release);
    RoomTemplateRef startLocation =
        WorldDraftStartLocationEvidence.fromStored(selector.selectorReceiptBytes()).startLocation();
    WorldCanonicalInstanceLifecycleEvidence.Request request =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
            readRequestId,
            descriptor.targetNamespace(),
            descriptor.canonicalTenantId(),
            descriptor.worldSlug(),
            uuid("11111111-1111-4111-8111-111111111111"),
            uuid("22222222-2222-4222-8222-222222222222"),
            "SHARED",
            true,
            descriptor.controlPlaneRequestId(),
            release.canonicalVersionId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest());
    var evidence =
        new WorldCanonicalInstanceLifecycleEvidence(
            request,
            binding,
            startLocation,
            9_007_199_254_740_993L,
            status,
            epoch,
            rowVersion,
            uuid("33333333-3333-4333-8333-333333333333"),
            WorldDraftStartLocationEvidence.fromStored(selector.selectorReceiptBytes())
                .graphDigest()
                .substring("sha256:".length()),
            "sha256:" + "e".repeat(64),
            java.util.Map.of(
                uuid("11111111-1111-4111-8111-111111111111"),
                uuid("22222222-2222-4222-8222-222222222222")));
    return new Fixture(
        new GameSessionWorldCanonicalLifecycleVerifier.Expected(request, binding), evidence);
  }

  private static Fixture rebind(
      Fixture original,
      UUID readRequestId,
      String status,
      long lifecycleEpoch,
      long rowVersion,
      UUID captureId,
      String graphSha256,
      String preparationInputDigest,
      RoomTemplateRef startLocation,
      Long runtimeRoomInstanceId) {
    var request = withReadRequestId(original.expected().request(), readRequestId);
    var evidence =
        withEvidence(
            original.evidence(),
            request,
            status,
            lifecycleEpoch,
            rowVersion,
            captureId,
            graphSha256,
            preparationInputDigest,
            startLocation,
            runtimeRoomInstanceId);
    return fixtureWith(original, request, evidence);
  }

  private static Fixture fixtureWith(
      Fixture original,
      WorldCanonicalInstanceLifecycleEvidence.Request request,
      WorldCanonicalInstanceLifecycleEvidence evidence) {
    return new Fixture(
        new GameSessionWorldCanonicalLifecycleVerifier.Expected(
            request, original.expected().launchBinding()),
        evidence);
  }

  private static WorldCanonicalInstanceLifecycleEvidence withBinding(
      WorldCanonicalInstanceLifecycleEvidence evidence,
      WorldCanonicalInstanceLifecycleEvidence.Request request,
      CompleteLaunchBindingEvidence binding) {
    return withEvidence(
        evidence,
        request,
        evidence.lifecycleStatus(),
        evidence.lifecycleEpoch(),
        evidence.rowVersion(),
        evidence.captureId(),
        evidence.graphSha256(),
        evidence.preparationInputDigest(),
        evidence.startLocation(),
        evidence.runtimeRoomInstanceId(),
        binding);
  }

  private static WorldCanonicalInstanceLifecycleEvidence withEvidence(
      WorldCanonicalInstanceLifecycleEvidence original,
      WorldCanonicalInstanceLifecycleEvidence.Request request,
      String status,
      long epoch,
      long rowVersion,
      UUID captureId,
      String graphSha256,
      String preparationInputDigest,
      RoomTemplateRef startLocation,
      Long runtimeRoomInstanceId) {
    return withEvidence(
        original,
        request,
        status,
        epoch,
        rowVersion,
        captureId,
        graphSha256,
        preparationInputDigest,
        startLocation,
        runtimeRoomInstanceId,
        original.launchBinding());
  }

  private static WorldCanonicalInstanceLifecycleEvidence withEvidence(
      WorldCanonicalInstanceLifecycleEvidence original,
      WorldCanonicalInstanceLifecycleEvidence.Request request,
      String status,
      long epoch,
      long rowVersion,
      UUID captureId,
      String graphSha256,
      String preparationInputDigest,
      RoomTemplateRef startLocation,
      Long runtimeRoomInstanceId,
      CompleteLaunchBindingEvidence binding) {
    return new WorldCanonicalInstanceLifecycleEvidence(
        request,
        binding,
        startLocation == null ? original.startLocation() : startLocation,
        runtimeRoomInstanceId == null ? original.runtimeRoomInstanceId() : runtimeRoomInstanceId,
        status,
        epoch,
        rowVersion,
        captureId == null ? original.captureId() : captureId,
        graphSha256 == null ? original.graphSha256() : graphSha256,
        preparationInputDigest == null ? original.preparationInputDigest() : preparationInputDigest,
        original.operationalRegionAssignments());
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request withReadRequestId(
      WorldCanonicalInstanceLifecycleEvidence.Request source, UUID readRequestId) {
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        source.schemaVersion(),
        readRequestId,
        source.targetNamespace(),
        source.canonicalTenantId(),
        source.worldSlug(),
        source.canonicalGameInstanceId(),
        source.playableStateNamespaceId(),
        source.playableStateScope(),
        source.publicProduction(),
        source.controlPlaneRequestId(),
        source.canonicalVersionId(),
        source.expectedDescriptorRequestDigest(),
        source.expectedDescriptorResultDigest(),
        source.expectedReleaseAttestationDigest());
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request copyRequest(
      WorldCanonicalInstanceLifecycleEvidence.Request source,
      UUID readRequestId,
      UUID canonicalTenantId,
      String worldSlug,
      UUID canonicalGameInstanceId,
      UUID playableStateNamespaceId,
      String playableStateScope,
      Boolean publicProduction,
      String controlPlaneRequestId,
      UUID canonicalVersionId,
      String expectedDescriptorRequestDigest,
      String expectedDescriptorResultDigest,
      String expectedReleaseAttestationDigest) {
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        source.schemaVersion(),
        readRequestId == null ? source.readRequestId() : readRequestId,
        source.targetNamespace(),
        canonicalTenantId == null ? source.canonicalTenantId() : canonicalTenantId,
        worldSlug == null ? source.worldSlug() : worldSlug,
        canonicalGameInstanceId == null
            ? source.canonicalGameInstanceId()
            : canonicalGameInstanceId,
        playableStateNamespaceId == null
            ? source.playableStateNamespaceId()
            : playableStateNamespaceId,
        playableStateScope == null ? source.playableStateScope() : playableStateScope,
        publicProduction == null ? source.publicProduction() : publicProduction,
        controlPlaneRequestId == null ? source.controlPlaneRequestId() : controlPlaneRequestId,
        canonicalVersionId == null ? source.canonicalVersionId() : canonicalVersionId,
        expectedDescriptorRequestDigest == null
            ? source.expectedDescriptorRequestDigest()
            : expectedDescriptorRequestDigest,
        expectedDescriptorResultDigest == null
            ? source.expectedDescriptorResultDigest()
            : expectedDescriptorResultDigest,
        expectedReleaseAttestationDigest == null
            ? source.expectedReleaseAttestationDigest()
            : expectedReleaseAttestationDigest);
  }

  private static CompleteLaunchBindingEvidence changedRelease(
      CompleteLaunchBindingEvidence binding) {
    AuthoredWorldReleaseAttestationEvidence old = binding.releaseAttestation();
    AuthoredWorldReleaseAttestationEvidence changed =
        AuthoredWorldReleaseAttestationEvidence.create(
            old.targetNamespace(),
            old.descriptorResultDigest(),
            old.canonicalTenantId(),
            old.canonicalVersionId(),
            old.worldSlug(),
            old.authoredWorldSourceOperationId(),
            old.authoredWorldSourceEvidenceDigest(),
            old.launchDescriptorId(),
            old.publishedReleaseBundleRef(),
            old.versionStateEpoch(),
            old.publishWorkflowId(),
            old.commitId(),
            old.participantDigests(),
            old.manifestHash(),
            old.manifestSchemaVersion(),
            old.requiredManifestAssetKeys(),
            old.artifactDigests(),
            List.of("LOOK", "SAY"),
            old.generationConfigRevision(),
            old.worldStartLocationEvidence());
    return new CompleteLaunchBindingEvidence(binding.descriptor(), changed);
  }

  private static WorldPublishedStartLocationEvidence selectorEvidence() throws Exception {
    DraftCommitBinding draft = freshGraphBinding();
    var terminalRequest =
        new WorldDraftTerminalReadEvidence.Request(
            WorldDraftTerminalReadEvidence.Request.SCHEMA_VERSION,
            "test",
            uuid("33333333-3333-4333-8333-333333333333"),
            accountBinding(draft));
    DraftAuthorizationFenceBinding.OwnerReadback committed =
        committedReadback(terminalRequest, draft);
    List<WorldPublishedStartLocationEvidence.OwnedAffectedTuple> tuples =
        draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                        unit.owner().name(),
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch()))
            .toList();
    var request =
        new WorldPublishedStartLocationEvidence.Request(
            "test",
            TENANT_ID,
            VERSION_ID,
            INTAKE_REQUEST_ID,
            uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            "publication-request",
            "a".repeat(64),
            5L,
            "publish-workflow",
            COMMIT_ID.toString(),
            "b".repeat(64),
            3,
            tuples);
    var result = JSON.readTree(committed.result());
    return new WorldPublishedStartLocationEvidence(
        request,
        Base64.getDecoder().decode(result.get("startLocationReceiptBase64").textValue()),
        committed.fullBinding(),
        committed.result());
  }

  private static DraftCommitBinding freshGraphBinding() throws Exception {
    String declaration =
        JSON.writeValueAsString(
            Map.of(
                "tenantId", TENANT_ID.toString(),
                "versionId", VERSION_ID.toString(),
                "startLocation",
                    Map.of(
                        "tenantId", TENANT_ID.toString(),
                        "versionId", VERSION_ID.toString(),
                        "roomTemplateId", ROOM_TEMPLATE_ID.toString()),
                "familyCounts",
                    List.of(
                        Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_REGION", "count", 1),
                        Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_ZONE", "count", 1),
                        Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_ROOM", "count", 1),
                        Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT", "count", 0),
                        Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE", "count", 0),
                        Map.of(
                            "family",
                            "WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING",
                            "count",
                            0))));
    return DraftCommitBinding.create(
        new TargetProof(
            TENANT_ID, VERSION_ID, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW"),
        REQUEST_ID,
        COMMIT_ID,
        "base-1",
        List.of(
            new RevisionPayload(
                "0",
                REGION_REVISION_ID,
                DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                worldRevisionPayload(
                    REGION_REVISION_ID,
                    "WORLD_DESIGN_AGGREGATE_TYPE_REGION",
                    REGION_TEMPLATE_ID,
                    declaration)),
            new RevisionPayload(
                "1",
                ZONE_REVISION_ID,
                DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                worldRevisionPayload(
                    ZONE_REVISION_ID, "WORLD_DESIGN_AGGREGATE_TYPE_ZONE", ZONE_TEMPLATE_ID, null)),
            new RevisionPayload(
                "2",
                ROOM_REVISION_ID,
                DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                worldRevisionPayload(
                    ROOM_REVISION_ID, "WORLD_DESIGN_AGGREGATE_TYPE_ROOM", ROOM_TEMPLATE_ID, null))),
        List.of(
                affected("REGION", REGION_TEMPLATE_ID, "REGION_SUBTREE", REGION_TEMPLATE_ID),
                affected("ZONE", ZONE_TEMPLATE_ID, "REGION_SUBTREE", REGION_TEMPLATE_ID),
                affected("ROOM", ROOM_TEMPLATE_ID, "ZONE_SUBTREE", ZONE_TEMPLATE_ID))
            .stream()
            .flatMap(List::stream)
            .toList());
  }

  private static String worldRevisionPayload(
      UUID revisionId, String family, UUID templateId, String declaration) throws Exception {
    var payload = new java.util.LinkedHashMap<String, Object>();
    payload.put("logicalRevisionId", revisionId.toString());
    payload.put("commitId", COMMIT_ID.toString());
    payload.put("aggregateType", family);
    payload.put("aggregateId", templateId.toString());
    if (declaration != null) payload.put("freshGraphDeclaration", JSON.readTree(declaration));
    return JSON.writeValueAsString(payload);
  }

  private static List<AffectedUnit> affected(
      String family, UUID templateId, String scopeType, UUID scopeId) {
    return List.of(
        new AffectedUnit(
            DraftCommitBinding.Owner.WORLD_MANAGEMENT,
            family,
            templateId.toString(),
            "AGGREGATE",
            templateId.toString(),
            "0"),
        new AffectedUnit(
            DraftCommitBinding.Owner.WORLD_MANAGEMENT,
            family,
            templateId.toString(),
            scopeType,
            scopeId.toString(),
            "0"));
  }

  private static byte[] accountBinding(DraftCommitBinding draft) {
    byte[] draftBytes = draft.canonicalBytes();
    return new DraftAuthorizationFenceBinding(
            OPERATION_ID,
            REQUEST_ID,
            COMMIT_ID,
            FENCE_ID,
            ACTOR_ID,
            TENANT_ID,
            VERSION_ID,
            "base-1",
            "0",
            draftBytes,
            draftBytes,
            draft.digest(),
            List.of(
                new SourceEvidence(
                    SourceKind.GLOBAL_ROLES,
                    uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd").toString(),
                    null,
                    "1",
                    null,
                    null,
                    new byte[] {4, 5})))
        .canonicalBytes();
  }

  private static DraftAuthorizationFenceBinding.OwnerReadback committedReadback(
      WorldDraftTerminalReadEvidence.Request request, DraftCommitBinding draft) throws Exception {
    var account = request.accountBinding();
    var operation = new ByteArrayOutputStream();
    var frames = new java.io.DataOutputStream(operation);
    java.util.function.Consumer<byte[]> frame =
        bytes -> {
          try {
            frames.writeInt(bytes.length);
            frames.write(bytes);
          } catch (java.io.IOException impossible) {
            throw new AssertionError(impossible);
          }
        };
    java.util.function.Consumer<String> text =
        value -> frame.accept(value.getBytes(StandardCharsets.UTF_8));
    text.accept("world-draft-terminal-operation/v1");
    for (UUID id :
        List.of(
            account.operationId(),
            account.requestId(),
            account.commitId(),
            account.fenceId(),
            account.tenantId(),
            account.versionId())) text.accept(id.toString());
    frame.accept(draft.canonicalBytes());
    for (String value :
        List.of(
            request.targetNamespace(),
            account.tenantId().toString(),
            account.versionId().toString(),
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            Long.toString(draft.target().gameDesignVersionRowId()),
            INTAKE_REQUEST_ID.toString(),
            "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            "a".repeat(64),
            "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
            "b".repeat(64),
            "c".repeat(64))) text.accept(value);
    text.accept(sha256(account.canonicalBytes()));
    frame.accept(account.canonicalBytes());
    byte[] graph = graphBytes(request, draft);
    String graphDigest = sha256(graph);
    var receipt = receipt(request, draft, graphDigest);
    byte[] receiptBytes = canonical(receipt);
    var result = new java.util.LinkedHashMap<String, Object>();
    result.put("schema", "world-draft-graph-applied/v2");
    result.put("status", "APPLIED");
    result.put("operationBytesBase64", Base64.getEncoder().encodeToString(operation.toByteArray()));
    result.put("graphBytesBase64", Base64.getEncoder().encodeToString(graph));
    result.put("graphDigest", graphDigest);
    result.put("startLocationReceiptBase64", Base64.getEncoder().encodeToString(receiptBytes));
    result.put("startLocationReceiptDigest", receipt.get("receiptDigest").textValue());
    result.put(
        "appliedEpochs",
        draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    Map.of(
                        "aggregateType",
                        unit.aggregateType(),
                        "aggregateId",
                        unit.aggregateId(),
                        "scopeType",
                        unit.scopeType(),
                        "scopeId",
                        unit.scopeId(),
                        "expectedEpoch",
                        unit.expectedEpoch(),
                        "resultingEpoch",
                        new java.math.BigInteger(unit.expectedEpoch())
                            .add(java.math.BigInteger.ONE)
                            .toString()))
            .toList());
    var ownerReadback =
        new DraftAuthorizationFenceBinding.OwnerReadback(
            Owner.WORLD,
            Outcome.COMMITTED,
            account.operationId(),
            account.commitId(),
            account.fenceId(),
            account.inputDigest(),
            account.canonicalBytes(),
            canonical(JSON.valueToTree(result)));
    return WorldDraftTerminalReadGrpcCodec.fromResponse(
            request,
            WorldDraftTerminalReadGrpcCodec.toResponse(request, Optional.of(ownerReadback)))
        .ownerReadback()
        .orElseThrow();
  }

  private static byte[] graphBytes(
      WorldDraftTerminalReadEvidence.Request request, DraftCommitBinding draft) throws Exception {
    var root = new java.util.LinkedHashMap<String, Object>();
    root.put("schemaVersion", "2");
    root.put("canonicalTenantId", draft.target().canonicalTenantId().toString());
    root.put("canonicalVersionId", draft.target().canonicalVersionId().toString());
    var rows = new java.util.ArrayList<Map<String, Object>>();
    int mappingId = 1;
    long privateRowKey = 101;
    for (RevisionPayload revision : draft.revisions()) {
      if (revision.owner() != DraftCommitBinding.Owner.WORLD_MANAGEMENT) continue;
      var mutation = JSON.readTree(revision.payload());
      String family =
          mutation.get("aggregateType").textValue().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
      var mapping = new java.util.LinkedHashMap<String, Object>();
      mapping.put("id", mappingId++);
      mapping.put("target_namespace", request.targetNamespace());
      mapping.put("canonical_tenant_id", draft.target().canonicalTenantId().toString());
      mapping.put("canonical_version_id", draft.target().canonicalVersionId().toString());
      mapping.put("family", family);
      mapping.put("template_id", mutation.get("aggregateId").textValue());
      mapping.put("private_row_key", privateRowKey++);
      mapping.put("tenant_id", 11);
      mapping.put("version_id", 19);
      mapping.put("version_identity_operation_id", OPERATION_ID.toString());
      mapping.put("request_id", draft.requestId().toString());
      mapping.put("commit_id", draft.commitId().toString());
      mapping.put("revision_id", revision.revisionId().toString());
      mapping.put("revision_order", revision.revisionOrder());
      rows.add(Map.of("mapping", mapping, "content", Map.of()));
    }
    root.put("rows", rows);
    return JSON.writeValueAsBytes(root);
  }

  private static tools.jackson.databind.node.ObjectNode receipt(
      WorldDraftTerminalReadEvidence.Request request, DraftCommitBinding draft, String graphDigest)
      throws Exception {
    var account = request.accountBinding();
    var selector =
        Map.of(
            "tenantId", TENANT_ID.toString(),
            "versionId", VERSION_ID.toString(),
            "roomTemplateId", ROOM_TEMPLATE_ID.toString());
    String accountBindingDigest = sha256(request.originalAccountBinding());
    String receiptDigest =
        startLocationReceiptDigest(
            request.targetNamespace(),
            account.operationId(),
            account.requestId(),
            account.commitId(),
            account.fenceId(),
            accountBindingDigest,
            draft.digest(),
            TENANT_ID,
            VERSION_ID,
            ROOM_TEMPLATE_ID,
            graphDigest);
    var value = new java.util.LinkedHashMap<String, Object>();
    value.put("schema", "world-draft-start-location-receipt/v1");
    value.put("targetNamespace", request.targetNamespace());
    value.put("operationId", account.operationId().toString());
    value.put("requestId", account.requestId().toString());
    value.put("commitId", account.commitId().toString());
    value.put("authorizationFenceId", account.fenceId().toString());
    value.put("accountBindingDigest", accountBindingDigest);
    value.put("bindingDigest", draft.digest());
    value.put("startLocation", selector);
    value.put("graphDigest", graphDigest);
    value.put("receiptDigest", receiptDigest);
    return (tools.jackson.databind.node.ObjectNode) JSON.valueToTree(value);
  }

  private static String startLocationReceiptDigest(
      String targetNamespace,
      UUID operationId,
      UUID requestId,
      UUID commitId,
      UUID fenceId,
      String accountBindingDigest,
      String bindingDigest,
      UUID tenantId,
      UUID versionId,
      UUID roomTemplateId,
      String graphDigest)
      throws Exception {
    var framed = new ByteArrayOutputStream();
    for (String value :
        List.of(
            "world-draft-start-location-receipt/v1",
            targetNamespace,
            operationId.toString(),
            requestId.toString(),
            commitId.toString(),
            fenceId.toString(),
            accountBindingDigest,
            bindingDigest,
            tenantId.toString(),
            versionId.toString(),
            roomTemplateId.toString(),
            graphDigest)) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      framed.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
      framed.writeBytes(bytes);
    }
    return sha256(framed.toByteArray());
  }

  private static byte[] canonical(Object value) throws Exception {
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }

  private static String sha256(byte[] bytes) throws Exception {
    return "sha256:"
        + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record Fixture(
      GameSessionWorldCanonicalLifecycleVerifier.Expected expected,
      WorldCanonicalInstanceLifecycleEvidence evidence) {}
}
