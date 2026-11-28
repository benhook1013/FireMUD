package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence.Request;
import net.firedevops.firemud.gamesession.service.GameSessionWorldCanonicalLifecycleVerifier.Expected;
import org.junit.jupiter.api.Test;

class GameSessionWorldCanonicalLifecycleVerifierTest {
  private static final UUID CURRENT_PREPARING_READ =
      UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID ACTIVE_READ = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID CURRENT_ACTIVE_READ =
      UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID OTHER_ID = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
  private final GameSessionWorldCanonicalLifecycleVerifier verifier =
      new GameSessionWorldCanonicalLifecycleVerifier();

  @Test
  void acceptsSameCaptureWithExactRetriesAndAdvancingMutableRowVersions() throws Exception {
    var source = AuthoringFixtures.lifecycleEvidence("PREPARING", 41L);
    var preparing = withLifecycle(source, expected(source), "PREPARING", 41L, 60L);
    var verifiedPreparing = verifier.verifyPreparing(expected(source), preparing);
    assertThat(verifiedPreparing.startLocation()).isEqualTo(source.startLocation());
    assertThat(verifiedPreparing.runtimeRoomInstanceId()).isEqualTo(source.runtimeRoomInstanceId());
    assertThat(verifiedPreparing.lifecycleEpoch()).isEqualTo(41L);
    assertThat(verifiedPreparing.expected().launchBinding().descriptor().versionStateEpoch())
        .isNotEqualTo(verifiedPreparing.lifecycleEpoch());
    var exactPreparingRetry =
        verifier.verifyCurrentPreparing(verifiedPreparing, expected(source), preparing);
    assertThat(exactPreparingRetry.evidence()).isEqualTo(preparing);

    var preparingRead = nextRead(source, CURRENT_PREPARING_READ);
    var currentPreparing = withLifecycle(source, preparingRead, "PREPARING", 41L, 63L);
    var verifiedCurrentPreparing =
        verifier.verifyCurrentPreparing(verifiedPreparing, preparingRead, currentPreparing);
    assertThat(verifiedCurrentPreparing.rowVersion()).isEqualTo(63L);

    var activeRead = nextRead(source, ACTIVE_READ);
    var active = withLifecycle(source, activeRead, "ACTIVE", 42L, 67L);
    var verifiedActive =
        verifier.verifyActiveAfterPreparing(verifiedCurrentPreparing, activeRead, active);
    assertThat(verifiedActive.expected()).isEqualTo(activeRead);
    assertThat(verifiedActive.evidence()).isEqualTo(active);
    assertThat(verifiedActive.lifecycleEpoch()).isEqualTo(42L);
    assertThat(verifiedActive.rowVersion()).isEqualTo(67L);
    assertThat(verifiedActive.startLocation()).isEqualTo(source.startLocation());
    assertThat(verifiedActive.runtimeRoomInstanceId()).isEqualTo(source.runtimeRoomInstanceId());
    assertThat(verifier.verifyCurrentActive(verifiedActive, activeRead, active).rowVersion())
        .isEqualTo(67L);

    var currentActiveRead = nextRead(source, CURRENT_ACTIVE_READ);
    var currentActive = withLifecycle(source, currentActiveRead, "ACTIVE", 42L, 70L);
    assertThat(
            verifier
                .verifyCurrentActive(verifiedActive, currentActiveRead, currentActive)
                .rowVersion())
        .isEqualTo(70L);
  }

  @Test
  void requiresTheIndependentExactRequestAndCompleteLaunchForEachRead() throws Exception {
    var preparing = AuthoringFixtures.lifecycleEvidence("PREPARING", 41L);
    var verifiedPreparing = verifier.verifyPreparing(expected(preparing), preparing);
    var preparingRead = nextRead(preparing, CURRENT_PREPARING_READ);
    var activeRead = nextRead(preparing, ACTIVE_READ);
    var active = withLifecycle(preparing, activeRead, "ACTIVE", 42L, 4L);
    var verifiedActive = verifier.verifyActiveAfterPreparing(verifiedPreparing, activeRead, active);
    var currentActiveRead = nextRead(preparing, CURRENT_ACTIVE_READ);

    assertThatThrownBy(() -> verifier.verifyPreparing(preparingRead, preparing))
        .hasMessageContaining("exact read request");
    assertThatThrownBy(
            () -> verifier.verifyCurrentPreparing(verifiedPreparing, preparingRead, preparing))
        .hasMessageContaining("exact read request");
    assertThatThrownBy(
            () -> verifier.verifyActiveAfterPreparing(verifiedPreparing, currentActiveRead, active))
        .hasMessageContaining("exact read request");
    assertThatThrownBy(
            () -> verifier.verifyCurrentActive(verifiedActive, currentActiveRead, active))
        .hasMessageContaining("exact read request");

    var sourceRequest = preparing.request();
    for (var changedRead :
        List.of(
            expectedFor(
                sourceRequest,
                CURRENT_PREPARING_READ,
                OTHER_ID,
                sourceRequest.playableStateNamespaceId(),
                preparing.launchBinding()),
            expectedFor(
                sourceRequest,
                CURRENT_PREPARING_READ,
                sourceRequest.canonicalGameInstanceId(),
                OTHER_ID,
                preparing.launchBinding()))) {
      var changedTarget = withLifecycle(preparing, changedRead, "PREPARING", 41L, 1L);
      assertThatThrownBy(
              () -> verifier.verifyCurrentPreparing(verifiedPreparing, changedRead, changedTarget))
          .hasMessageContaining("canonical launch request binding");
    }

    var changedLaunch = withChangedRelease(preparing.launchBinding());
    var changedRead =
        expectedFor(
            sourceRequest,
            ACTIVE_READ,
            sourceRequest.canonicalGameInstanceId(),
            sourceRequest.playableStateNamespaceId(),
            changedLaunch);
    var changedActive = withLifecycle(preparing, changedRead, "ACTIVE", 42L, 4L);
    assertThatThrownBy(
            () ->
                verifier.verifyActiveAfterPreparing(verifiedPreparing, changedRead, changedActive))
        .hasMessageContaining("canonical launch request binding");
    var wrongExpectedLaunch = new Expected(activeRead.request(), changedLaunch);
    assertThatThrownBy(
            () ->
                verifier.verifyActiveAfterPreparing(verifiedPreparing, wrongExpectedLaunch, active))
        .hasMessageContaining("complete launch binding");
  }

  @Test
  void refusesCaptureDriftForCurrentPreparingTransitionAndCurrentActive() throws Exception {
    var source = AuthoringFixtures.lifecycleEvidence("PREPARING", 41L);
    var verifiedPreparing = verifier.verifyPreparing(expected(source), source);
    var activeRead = nextRead(source, ACTIVE_READ);
    var active = withLifecycle(source, activeRead, "ACTIVE", 42L, 4L);
    var verifiedActive = verifier.verifyActiveAfterPreparing(verifiedPreparing, activeRead, active);

    var changedCaptures =
        List.of(
            withCapture(
                source,
                OTHER_ID,
                source.preparationInputDigest(),
                source.runtimeRoomInstanceId(),
                source.operationalRegionAssignments()),
            withCapture(
                source,
                source.captureId(),
                "sha256:" + "0".repeat(64),
                source.runtimeRoomInstanceId(),
                source.operationalRegionAssignments()),
            withCapture(
                source,
                source.captureId(),
                source.preparationInputDigest(),
                source.runtimeRoomInstanceId() + 1L,
                source.operationalRegionAssignments()),
            withCapture(
                source,
                source.captureId(),
                source.preparationInputDigest(),
                source.runtimeRoomInstanceId(),
                Map.of(
                    source.operationalRegionAssignments().keySet().iterator().next(), OTHER_ID)));
    var preparingRead = nextRead(source, CURRENT_PREPARING_READ);
    var currentActiveRead = nextRead(source, CURRENT_ACTIVE_READ);
    for (var changedCapture : changedCaptures) {
      var changedPreparing = withLifecycle(changedCapture, preparingRead, "PREPARING", 41L, 1L);
      var changedActive = withLifecycle(changedCapture, activeRead, "ACTIVE", 42L, 4L);
      var changedCurrentActive =
          withLifecycle(changedCapture, currentActiveRead, "ACTIVE", 42L, 5L);
      assertThatThrownBy(
              () ->
                  verifier.verifyCurrentPreparing(
                      verifiedPreparing, preparingRead, changedPreparing))
          .hasMessageContaining("original preparation capture");
      assertThatThrownBy(
              () ->
                  verifier.verifyActiveAfterPreparing(verifiedPreparing, activeRead, changedActive))
          .hasMessageContaining("original preparation capture");
      assertThatThrownBy(
              () ->
                  verifier.verifyCurrentActive(
                      verifiedActive, currentActiveRead, changedCurrentActive))
          .hasMessageContaining("original preparation capture");
    }

    // The shared carrier rejects drift from the selected release before a read can be verified.
    var selector = source.startLocation();
    var wrongRoom = new RoomTemplateRef(selector.tenantId(), selector.versionId(), OTHER_ID);
    assertThatThrownBy(() -> withSelectedTopology(source, wrongRoom, source.graphSha256()))
        .hasMessageContaining("release selector");
    assertThatThrownBy(() -> withSelectedTopology(source, selector, "0".repeat(64)))
        .hasMessageContaining("release selector");
  }

  @Test
  void enforcesLifecycleStatusAndEpochAndRowVersionFences() throws Exception {
    var source = AuthoringFixtures.lifecycleEvidence("PREPARING", 41L);
    var preparing = withLifecycle(source, expected(source), "PREPARING", 41L, 60L);
    var verifiedPreparing = verifier.verifyPreparing(expected(source), preparing);
    var preparingRead = nextRead(source, CURRENT_PREPARING_READ);
    var activeRead = nextRead(source, ACTIVE_READ);
    var active = withLifecycle(source, activeRead, "ACTIVE", 42L, 64L);
    var verifiedActive = verifier.verifyActiveAfterPreparing(verifiedPreparing, activeRead, active);
    var currentActiveRead = nextRead(source, CURRENT_ACTIVE_READ);

    assertThatThrownBy(
            () ->
                verifier.verifyCurrentPreparing(
                    verifiedPreparing,
                    preparingRead,
                    withLifecycle(source, preparingRead, "PREPARING", 42L, 60L)))
        .hasMessageContaining("PREPARING lifecycle epoch changed");
    assertThatThrownBy(
            () ->
                verifier.verifyCurrentPreparing(
                    verifiedPreparing,
                    preparingRead,
                    withLifecycle(source, preparingRead, "PREPARING", 41L, 59L)))
        .hasMessageContaining("moved backwards");
    for (long wrongEpoch : List.of(41L, 43L)) {
      assertThatThrownBy(
              () ->
                  verifier.verifyActiveAfterPreparing(
                      verifiedPreparing,
                      activeRead,
                      withLifecycle(source, activeRead, "ACTIVE", wrongEpoch, 64L)))
          .hasMessageContaining("immediately follow PREPARING");
    }
    for (long staleRow : List.of(59L, 60L)) {
      assertThatThrownBy(
              () ->
                  verifier.verifyActiveAfterPreparing(
                      verifiedPreparing,
                      activeRead,
                      withLifecycle(source, activeRead, "ACTIVE", 42L, staleRow)))
          .hasMessageContaining("row version did not advance");
    }
    assertThatThrownBy(
            () ->
                verifier.verifyCurrentActive(
                    verifiedActive,
                    currentActiveRead,
                    withLifecycle(source, currentActiveRead, "ACTIVE", 43L, 65L)))
        .hasMessageContaining("ACTIVE lifecycle epoch changed");
    assertThatThrownBy(
            () ->
                verifier.verifyCurrentActive(
                    verifiedActive,
                    currentActiveRead,
                    withLifecycle(source, currentActiveRead, "ACTIVE", 42L, 63L)))
        .hasMessageContaining("moved backwards");

    var maxEpoch = withLifecycle(source, expected(source), "PREPARING", Long.MAX_VALUE, 60L);
    var verifiedMaxEpoch = verifier.verifyPreparing(expected(source), maxEpoch);
    assertThatThrownBy(
            () -> verifier.verifyActiveAfterPreparing(verifiedMaxEpoch, activeRead, active))
        .hasMessageContaining("cannot advance");
    assertThatThrownBy(() -> verifier.verifyPreparing(activeRead, active))
        .hasMessageContaining("not PREPARING");
    assertThatThrownBy(() -> verifier.verifyCurrentPreparing(verifiedPreparing, activeRead, active))
        .hasMessageContaining("not PREPARING");
    for (String status :
        List.of("PREPARING", "FAILED_PRE_ACTIVATION", "TERMINATING", "TERMINATED")) {
      var response = withLifecycle(source, currentActiveRead, status, 42L, 65L);
      assertThatThrownBy(
              () ->
                  verifier.verifyActiveAfterPreparing(
                      verifiedPreparing, currentActiveRead, response))
          .hasMessageContaining("not ACTIVE");
      assertThatThrownBy(
              () -> verifier.verifyCurrentActive(verifiedActive, currentActiveRead, response))
          .hasMessageContaining("not ACTIVE");
    }
  }

  private static Expected expected(WorldCanonicalInstanceLifecycleEvidence source) {
    return new Expected(source.request(), source.launchBinding());
  }

  private static Expected nextRead(WorldCanonicalInstanceLifecycleEvidence source, UUID readId) {
    var request = source.request();
    return expectedFor(
        request,
        readId,
        request.canonicalGameInstanceId(),
        request.playableStateNamespaceId(),
        source.launchBinding());
  }

  private static Expected expectedFor(
      Request source,
      UUID readId,
      UUID instanceId,
      UUID playableStateNamespaceId,
      CompleteLaunchBindingEvidence launch) {
    return new Expected(
        new Request(
            source.schemaVersion(),
            readId,
            source.targetNamespace(),
            source.canonicalTenantId(),
            source.worldSlug(),
            instanceId,
            playableStateNamespaceId,
            source.playableStateScope(),
            source.publicProduction(),
            source.controlPlaneRequestId(),
            source.canonicalVersionId(),
            source.expectedDescriptorRequestDigest(),
            source.expectedDescriptorResultDigest(),
            launch.releaseAttestation().evidenceDigest()),
        launch);
  }

  private static WorldCanonicalInstanceLifecycleEvidence withLifecycle(
      WorldCanonicalInstanceLifecycleEvidence source,
      Expected expected,
      String status,
      long epoch,
      long rowVersion) {
    return new WorldCanonicalInstanceLifecycleEvidence(
        expected.request(),
        expected.launchBinding(),
        source.startLocation(),
        source.runtimeRoomInstanceId(),
        status,
        epoch,
        rowVersion,
        source.captureId(),
        source.graphSha256(),
        source.preparationInputDigest(),
        source.operationalRegionAssignments());
  }

  private static WorldCanonicalInstanceLifecycleEvidence withCapture(
      WorldCanonicalInstanceLifecycleEvidence source,
      UUID captureId,
      String inputDigest,
      long runtimeRoomId,
      Map<UUID, UUID> assignments) {
    return new WorldCanonicalInstanceLifecycleEvidence(
        source.request(),
        source.launchBinding(),
        source.startLocation(),
        runtimeRoomId,
        source.lifecycleStatus(),
        source.lifecycleEpoch(),
        source.rowVersion(),
        captureId,
        source.graphSha256(),
        inputDigest,
        assignments);
  }

  private static WorldCanonicalInstanceLifecycleEvidence withSelectedTopology(
      WorldCanonicalInstanceLifecycleEvidence source,
      RoomTemplateRef selector,
      String graphDigest) {
    return new WorldCanonicalInstanceLifecycleEvidence(
        source.request(),
        source.launchBinding(),
        selector,
        source.runtimeRoomInstanceId(),
        source.lifecycleStatus(),
        source.lifecycleEpoch(),
        source.rowVersion(),
        source.captureId(),
        graphDigest,
        source.preparationInputDigest(),
        source.operationalRegionAssignments());
  }

  private static CompleteLaunchBindingEvidence withChangedRelease(
      CompleteLaunchBindingEvidence binding) {
    var original = binding.releaseAttestation();
    var changed =
        AuthoredWorldReleaseAttestationEvidence.create(
            original.targetNamespace(),
            original.descriptorResultDigest(),
            original.canonicalTenantId(),
            original.canonicalVersionId(),
            original.worldSlug(),
            original.authoredWorldSourceOperationId(),
            original.authoredWorldSourceEvidenceDigest(),
            original.launchDescriptorId(),
            original.publishedReleaseBundleRef(),
            original.versionStateEpoch(),
            original.publishWorkflowId(),
            original.commitId(),
            original.participantDigests(),
            original.manifestHash(),
            original.manifestSchemaVersion(),
            original.requiredManifestAssetKeys(),
            original.artifactDigests(),
            List.of("LOOK", "SAY"),
            original.generationConfigRevision(),
            original.worldStartLocationEvidence());
    return new CompleteLaunchBindingEvidence(binding.descriptor(), changed);
  }
}
