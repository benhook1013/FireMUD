package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import org.junit.jupiter.api.Test;

class WorldCanonicalInitialAdmissionHoldStateTest {
  private static final UUID REALM = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID HOLD_ID = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID HOLD_FENCE = uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

  @Test
  void completePendingActiveStateRoundTripsExactNestedOwnerBytes() throws Exception {
    WorldCanonicalInstanceLifecycleEvidence lifecycle = lifecycle("ACTIVE", 3L);
    Request request = holdRequest(lifecycle);
    var identity = new HoldIdentity(request, HOLD_ID, HOLD_FENCE);
    var state =
        new WorldCanonicalInitialAdmissionHoldState(
            identity, WorldCanonicalInitialAdmissionHoldState.HoldStatus.PENDING, lifecycle);

    byte[] encoded = state.canonicalBytes();
    var decoded = WorldCanonicalInitialAdmissionHoldState.fromStored(encoded);

    assertThat(decoded).isEqualTo(state);
    assertThat(decoded.isPendingAtExpectedActiveEpoch()).isTrue();
    String encodedText = new String(encoded, StandardCharsets.UTF_8);
    assertThat(encodedText)
        .contains(java.util.Base64.getEncoder().encodeToString(identity.canonicalBytes()));
    assertThat(encodedText)
        .contains(java.util.Base64.getEncoder().encodeToString(lifecycle.canonicalBytes()));
  }

  @Test
  void nonterminalAndAdvancedLifecycleSamplesRemainHonestButDoNotQualify() throws Exception {
    WorldCanonicalInstanceLifecycleEvidence active = lifecycle("ACTIVE", 3L);
    Request request = holdRequest(active);
    HoldIdentity identity = new HoldIdentity(request, HOLD_ID, HOLD_FENCE);

    for (var status :
        List.of(
            WorldCanonicalInitialAdmissionHoldState.HoldStatus.RECONCILIATION_REQUIRED,
            WorldCanonicalInitialAdmissionHoldState.HoldStatus.COMMITTED,
            WorldCanonicalInitialAdmissionHoldState.HoldStatus.ABORTED)) {
      var state = new WorldCanonicalInitialAdmissionHoldState(identity, status, active);
      assertThat(state.isPendingAtExpectedActiveEpoch()).isFalse();
      assertThat(WorldCanonicalInitialAdmissionHoldState.fromStored(state.canonicalBytes()))
          .isEqualTo(state);
    }

    var advanced = lifecycle("TERMINATING", 4L);
    var observed =
        new WorldCanonicalInitialAdmissionHoldState(
            identity, WorldCanonicalInitialAdmissionHoldState.HoldStatus.PENDING, advanced);
    assertThat(observed.lifecycleEvidence().lifecycleStatus()).isEqualTo("TERMINATING");
    assertThat(observed.lifecycleEvidence().lifecycleEpoch()).isEqualTo(4L);
    assertThat(observed.isPendingAtExpectedActiveEpoch()).isFalse();
    assertThat(WorldCanonicalInitialAdmissionHoldState.fromStored(observed.canonicalBytes()))
        .isEqualTo(observed);
  }

  @Test
  void rejectsChangedTargetScopeWithoutEquatingIndependentRequestIds() throws Exception {
    WorldCanonicalInstanceLifecycleEvidence lifecycle = lifecycle("ACTIVE", 3L);
    Request original = holdRequest(lifecycle);
    Request mismatched =
        new Request(
            original.targetNamespace(),
            original.canonicalTenantId(),
            original.worldSlug(),
            REALM,
            uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            original.playableStateScope(),
            original.canonicalGameInstanceId(),
            original.canonicalVersionId(),
            original.activeLifecycleEpoch(),
            original.initialAdmissionRequestId(),
            original.initialAdmissionRequestDigest(),
            InitialAdmissionOrigin.NO_PRIOR_POINTER,
            1L,
            null);

    assertThatThrownBy(
            () ->
                new WorldCanonicalInitialAdmissionHoldState(
                    new HoldIdentity(mismatched, HOLD_ID, HOLD_FENCE),
                    WorldCanonicalInitialAdmissionHoldState.HoldStatus.PENDING,
                    lifecycle))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("target scope");

    Request independentRequestId =
        new Request(
            original.targetNamespace(),
            original.canonicalTenantId(),
            original.worldSlug(),
            original.realmId(),
            original.playableStateNamespaceId(),
            original.playableStateScope(),
            original.canonicalGameInstanceId(),
            original.canonicalVersionId(),
            original.activeLifecycleEpoch(),
            "a-distinct-initial-admission-request-id",
            original.initialAdmissionRequestDigest(),
            InitialAdmissionOrigin.NO_PRIOR_POINTER,
            1L,
            null);
    assertThat(
            new WorldCanonicalInitialAdmissionHoldState(
                    new HoldIdentity(independentRequestId, HOLD_ID, HOLD_FENCE),
                    WorldCanonicalInitialAdmissionHoldState.HoldStatus.PENDING,
                    lifecycle)
                .lifecycleEvidence()
                .request()
                .controlPlaneRequestId())
        .isEqualTo(lifecycle.request().controlPlaneRequestId());
    assertThat(independentRequestId.initialAdmissionRequestId())
        .isNotEqualTo(lifecycle.request().controlPlaneRequestId());
  }

  @Test
  void rejectsUnknownDuplicateNoncanonicalMalformedAndOversizedState() throws Exception {
    var state =
        new WorldCanonicalInitialAdmissionHoldState(
            new HoldIdentity(holdRequest(lifecycle("ACTIVE", 3L)), HOLD_ID, HOLD_FENCE),
            WorldCanonicalInitialAdmissionHoldState.HoldStatus.PENDING,
            lifecycle("ACTIVE", 3L));
    String canonical = new String(state.canonicalBytes(), StandardCharsets.UTF_8);

    assertInvalid(canonical.replace("\"schema\":", "\"extra\":true,\"schema\":"));
    String duplicateSchema =
        "\"schema\":\""
            + WorldCanonicalInitialAdmissionHoldState.SCHEMA
            + "\",\"schema\":\""
            + WorldCanonicalInitialAdmissionHoldState.SCHEMA
            + "\"";
    assertInvalid(
        canonical.replace(
            "\"schema\":\"" + WorldCanonicalInitialAdmissionHoldState.SCHEMA + "\"",
            duplicateSchema));
    assertInvalid(canonical.replace("PENDING", "UNKNOWN"));
    assertInvalid(canonical.replace(",\"holdStatus\":\"PENDING\"", ""));
    assertInvalid(
        canonical.replace(
            java.util.Base64.getEncoder().encodeToString(state.holdIdentity().canonicalBytes()),
            "!"));
    assertInvalid(canonical + " ");
    assertThatThrownBy(
            () ->
                WorldCanonicalInitialAdmissionHoldState.fromStored(new byte[] {(byte) 0xc3, 0x28}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("strict UTF-8");
    assertThatThrownBy(
            () ->
                WorldCanonicalInitialAdmissionHoldState.fromStored(
                    new byte[WorldCanonicalInitialAdmissionHoldState.MAX_CANONICAL_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("limit");
  }

  private static void assertInvalid(String value) {
    assertThatThrownBy(
            () ->
                WorldCanonicalInitialAdmissionHoldState.fromStored(
                    value.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Request holdRequest(WorldCanonicalInstanceLifecycleEvidence lifecycle) {
    var request = lifecycle.request();
    return new Request(
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.worldSlug(),
        REALM,
        request.playableStateNamespaceId(),
        request.playableStateScope(),
        request.canonicalGameInstanceId(),
        request.canonicalVersionId(),
        3L,
        "initial-admission-independent-from-preparation-request",
        "a".repeat(64),
        InitialAdmissionOrigin.NO_PRIOR_POINTER,
        1L,
        null);
  }

  private static WorldCanonicalInstanceLifecycleEvidence lifecycle(String status, long epoch)
      throws Exception {
    WorldPublishedStartLocationEvidence selector =
        WorldPublishedStartLocationGrpcCodecTest.evidence();
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            selector.request().targetNamespace(),
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
            null);
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
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
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
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
                            owner, AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION),
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
    WorldDraftStartLocationEvidence selectorReceipt =
        WorldDraftStartLocationEvidence.fromStored(selector.selectorReceiptBytes());
    var request =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
            uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
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
    return new WorldCanonicalInstanceLifecycleEvidence(
        request,
        binding,
        selectorReceipt.startLocation(),
        1042L,
        status,
        epoch,
        0L,
        uuid("33333333-3333-4333-8333-333333333333"),
        selectorReceipt.graphDigest().substring("sha256:".length()),
        "sha256:" + "e".repeat(64),
        Map.of(
            uuid("11111111-1111-4111-8111-111111111111"),
            uuid("22222222-2222-4222-8222-222222222222")));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
