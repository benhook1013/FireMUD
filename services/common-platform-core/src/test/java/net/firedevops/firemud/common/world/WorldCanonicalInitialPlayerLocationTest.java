package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import org.junit.jupiter.api.Test;

class WorldCanonicalInitialPlayerLocationTest {
  private static final UUID OPERATION_ID = uuid("10000000-0000-4000-8000-000000000001");

  @Test
  void requestDigestBindsActorAssignmentAdmissionOutcomeAndFullLifecycleProof() {
    var first =
        request(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "proof-a",
            "b0000000-0000-4000-8000-000000000001");
    var exact =
        request(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "proof-a",
            "b0000000-0000-4000-8000-000000000002");
    var changedAssignment =
        request("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "proof-a");
    var changedLifecycle =
        request("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "proof-b");

    assertThat(first.canonicalRequestBytes()).containsExactly(exact.canonicalRequestBytes());
    assertThat(first.requestDigest()).isEqualTo(exact.requestDigest());
    assertThat(first.originalLifecycleEvidenceBytes())
        .isNotEqualTo(exact.originalLifecycleEvidenceBytes());
    assertThat(first.normalizedLifecycleEvidenceBytes())
        .containsExactly(exact.normalizedLifecycleEvidenceBytes());
    assertThat(first.requestDigest()).startsWith("sha256:");
    assertThat(changedAssignment.requestDigest()).isNotEqualTo(first.requestDigest());
    assertThat(changedLifecycle.requestDigest()).isNotEqualTo(first.requestDigest());
  }

  @Test
  void expectClosedOriginIsRetainedButDoesNotSupplyOwnerProof() {
    var request =
        new WorldCanonicalInitialPlayerLocation.Request(
            OPERATION_ID,
            uuid("20000000-0000-4000-8000-000000000001"),
            uuid("30000000-0000-4000-8000-000000000001"),
            "starter-world",
            uuid("40000000-0000-4000-8000-000000000001"),
            uuid("50000000-0000-4000-8000-000000000001"),
            "SHARED",
            uuid("60000000-0000-4000-8000-000000000001"),
            uuid("70000000-0000-4000-8000-000000000001"),
            uuid("80000000-0000-4000-8000-000000000001"),
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            uuid("90000000-0000-4000-8000-000000000001"),
            uuid("a0000000-0000-4000-8000-000000000001"),
            "initial-request",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            1,
            "owner-proof",
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            "pointer-audit",
            1,
            WorldCanonicalInitialPlayerLocation.InitialAdmissionOrigin.EXPECT_CLOSED,
            lifecycle("proof", "b0000000-0000-4000-8000-000000000001"));
    assertThat(request.initialAdmissionOrigin())
        .isEqualTo(WorldCanonicalInitialPlayerLocation.InitialAdmissionOrigin.EXPECT_CLOSED);
    assertThat(new String(request.canonicalRequestBytes(), java.nio.charset.StandardCharsets.UTF_8))
        .contains("\"initialAdmissionOrigin\":\"EXPECT_CLOSED\"");
  }

  @Test
  void resultRetainsExactRequestBytesAndUsesTypedRoomReference() {
    var request =
        request("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "proof-a");
    var room = request.activeLifecycleEvidence().startLocation();
    var result = WorldCanonicalInitialPlayerLocation.Result.applied(request, room, 23);
    var retry =
        WorldCanonicalInitialPlayerLocation.Result.fromStored(request, result.canonicalBytes());

    assertThat(retry.canonicalBytes()).containsExactly(result.canonicalBytes());
    assertThat(retry.requestDigest()).isEqualTo(result.requestDigest());
    assertThat(retry.startLocation()).isEqualTo(room);
    assertThat(retry.runtimeRoomInstanceId()).isEqualTo(23L);
    var freshRead =
        request(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "proof-a",
            "b0000000-0000-4000-8000-000000000002");
    var correlationRetry =
        WorldCanonicalInitialPlayerLocation.Result.fromStored(freshRead, result.canonicalBytes());
    assertThat(correlationRetry.canonicalBytes()).containsExactly(result.canonicalBytes());
    assertThat(correlationRetry.request().originalLifecycleEvidenceBytes())
        .isNotEqualTo(request.activeLifecycleEvidence().canonicalBytes());
    assertThatThrownBy(
            () ->
                WorldCanonicalInitialPlayerLocation.Result.applied(
                    request,
                    new net.firedevops.firemud.common.world.RoomTemplateRef(
                        uuid("21000000-0000-4000-8000-000000000001"),
                        room.versionId(),
                        room.roomTemplateId()),
                    23))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact V2 ROOM mapping");
    assertThatThrownBy(
            () ->
                WorldCanonicalInitialPlayerLocation.Result.applied(
                    request,
                    new net.firedevops.firemud.common.world.RoomTemplateRef(
                        room.tenantId(),
                        uuid("31000000-0000-4000-8000-000000000001"),
                        room.roomTemplateId()),
                    23))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact V2 ROOM mapping");
    assertThatThrownBy(
            () ->
                WorldCanonicalInitialPlayerLocation.Result.applied(
                    request,
                    new net.firedevops.firemud.common.world.RoomTemplateRef(
                        room.tenantId(),
                        room.versionId(),
                        uuid("41000000-0000-4000-8000-000000000001")),
                    23))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact V2 ROOM mapping");
    assertThatThrownBy(() -> WorldCanonicalInitialPlayerLocation.Result.applied(request, room, 24))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact V2 ROOM mapping");
    var tampered =
        new String(result.canonicalBytes(), java.nio.charset.StandardCharsets.UTF_8)
            .replace("\"runtimeRoomInstanceId\":\"23\"", "\"runtimeRoomInstanceId\":\"24\"")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertThatThrownBy(
            () -> WorldCanonicalInitialPlayerLocation.Result.fromStored(request, tampered))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact V2 ROOM mapping");
  }

  @Test
  void conflictCodeAcceptsOnlyBoundedUppercaseAsciiMachineCodes() {
    var request =
        request("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "proof-a");
    assertThat(
            WorldCanonicalInitialPlayerLocation.Result.conflict(request, "ACTOR_CONFLICT")
                .outcome())
        .isEqualTo(WorldCanonicalInitialPlayerLocation.Outcome.CONFLICT);

    for (String invalidCode :
        new String[] {"", "lowercase", "INVALID\nCODE", "CAFÉ", "A".repeat(65)}) {
      assertThatThrownBy(
              () -> WorldCanonicalInitialPlayerLocation.Result.conflict(request, invalidCode))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void storedRequestRoundTripsWithItsCompleteOriginalLifecycleEvidence() throws Exception {
    var lifecycle = genuineLifecycleEvidence();
    var request = requestWithLifecycle(lifecycle);

    var decoded =
        WorldCanonicalInitialPlayerLocation.Request.fromStored(
            request.canonicalRequestBytes(), request.originalLifecycleEvidenceBytes());

    assertThat(decoded.canonicalRequestBytes()).containsExactly(request.canonicalRequestBytes());
    assertThat(decoded.originalLifecycleEvidenceBytes())
        .containsExactly(request.originalLifecycleEvidenceBytes());

    var alternateRead =
        copyLifecycle(
            lifecycle,
            copyLifecycleRequest(lifecycle, uuid("ffffffff-ffff-4fff-8fff-ffffffffffff")),
            lifecycle.lifecycleEpoch());
    assertThat(
            WorldCanonicalInitialPlayerLocation.normalizedLifecycleEvidenceBytes(
                alternateRead.canonicalBytes()))
        .containsExactly(request.normalizedLifecycleEvidenceBytes());
    var sameRequestWithFreshRead =
        WorldCanonicalInitialPlayerLocation.Request.fromStored(
            request.canonicalRequestBytes(), alternateRead.canonicalBytes());
    assertThat(sameRequestWithFreshRead.originalLifecycleEvidenceBytes())
        .containsExactly(alternateRead.canonicalBytes());
  }

  @Test
  void storedRequestRejectsChangedEvidenceAndMalformedClosedRepresentations() throws Exception {
    var lifecycle = genuineLifecycleEvidence();
    var request = requestWithLifecycle(lifecycle);
    byte[] canonical = request.canonicalRequestBytes();
    String json = new String(canonical, java.nio.charset.StandardCharsets.UTF_8);
    var changedEvidence =
        copyLifecycle(lifecycle, lifecycle.request(), lifecycle.lifecycleEpoch() + 1);

    assertThatThrownBy(
            () ->
                WorldCanonicalInitialPlayerLocation.Request.fromStored(
                    canonical, changedEvidence.canonicalBytes()))
        .isInstanceOf(IllegalArgumentException.class);

    for (byte[] malformed :
        new byte[][] {
          (json.substring(0, json.length() - 1) + ",\"unknown\":true}")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8),
          json.replace("\"schema\":", "\"schema\":\"duplicate\",\"schema\":")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8),
          (json + "{}\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),
          json.replace(
                  request.initialAdmissionHoldFence().toString(),
                  request.initialAdmissionHoldFence().toString().toUpperCase())
              .getBytes(java.nio.charset.StandardCharsets.UTF_8),
          json.replace("\"catalogRevision\":\"1\"", "\"catalogRevision\":\"01\"")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8),
          java.util.Arrays.copyOf(canonical, canonical.length + 1)
        }) {
      if (malformed[malformed.length - 1] == 0) malformed[malformed.length - 1] = (byte) 0xc3;
      assertThatThrownBy(
              () ->
                  WorldCanonicalInitialPlayerLocation.Request.fromStored(
                      malformed, request.originalLifecycleEvidenceBytes()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static WorldCanonicalInitialPlayerLocation.Request request(
      String assignmentDigest, String lifecycleProof) {
    return request(assignmentDigest, lifecycleProof, "b0000000-0000-4000-8000-000000000001");
  }

  private static WorldCanonicalInitialPlayerLocation.Request request(
      String assignmentDigest, String lifecycleProof, String readRequestId) {
    return new WorldCanonicalInitialPlayerLocation.Request(
        OPERATION_ID,
        uuid("20000000-0000-4000-8000-000000000001"),
        uuid("30000000-0000-4000-8000-000000000001"),
        "starter-world",
        uuid("40000000-0000-4000-8000-000000000001"),
        uuid("50000000-0000-4000-8000-000000000001"),
        "SHARED",
        uuid("60000000-0000-4000-8000-000000000001"),
        uuid("70000000-0000-4000-8000-000000000001"),
        uuid("80000000-0000-4000-8000-000000000001"),
        assignmentDigest,
        uuid("90000000-0000-4000-8000-000000000001"),
        uuid("a0000000-0000-4000-8000-000000000001"),
        "initial-request",
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        1,
        "owner-proof",
        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        "pointer-audit",
        1,
        WorldCanonicalInitialPlayerLocation.InitialAdmissionOrigin.NO_PRIOR_POINTER,
        lifecycle(lifecycleProof, readRequestId));
  }

  private static WorldCanonicalInstanceLifecycleEvidence lifecycle(
      String proof, String readRequestId) {
    var request = mock(WorldCanonicalInstanceLifecycleEvidence.Request.class);
    when(request.canonicalTenantId()).thenReturn(uuid("20000000-0000-4000-8000-000000000001"));
    when(request.worldSlug()).thenReturn("starter-world");
    when(request.canonicalGameInstanceId())
        .thenReturn(uuid("40000000-0000-4000-8000-000000000001"));
    when(request.playableStateNamespaceId())
        .thenReturn(uuid("50000000-0000-4000-8000-000000000001"));
    when(request.playableStateScope()).thenReturn("SHARED");
    when(request.canonicalVersionId()).thenReturn(uuid("30000000-0000-4000-8000-000000000001"));
    var evidence = mock(WorldCanonicalInstanceLifecycleEvidence.class);
    when(evidence.lifecycleStatus()).thenReturn("ACTIVE");
    when(evidence.request()).thenReturn(request);
    var room =
        new net.firedevops.firemud.common.world.RoomTemplateRef(
            uuid("20000000-0000-4000-8000-000000000001"),
            uuid("30000000-0000-4000-8000-000000000001"),
            uuid("40000000-0000-4000-8000-000000000001"));
    when(evidence.startLocation()).thenReturn(room);
    when(evidence.runtimeRoomInstanceId()).thenReturn(23L);
    String canonicalEvidence =
        "{\"schema\":\"world-canonical-instance-lifecycle-evidence/v1\","
            + "\"lifecycleStatus\":\"ACTIVE\",\"lifecycleEpoch\":\"7\",\"request\":{"
            + "\"readRequestId\":\""
            + readRequestId
            + "\","
            + "\"canonicalTenantId\":\"20000000-0000-4000-8000-000000000001\","
            + "\"canonicalVersionId\":\"30000000-0000-4000-8000-000000000001\","
            + "\"worldSlug\":\"starter-world\","
            + "\"canonicalGameInstanceId\":\"40000000-0000-4000-8000-000000000001\","
            + "\"playableStateNamespaceId\":\"50000000-0000-4000-8000-000000000001\","
            + "\"playableStateScope\":\"SHARED\"},\"startLocation\":{"
            + "\"tenantId\":\"20000000-0000-4000-8000-000000000001\","
            + "\"versionId\":\"30000000-0000-4000-8000-000000000001\","
            + "\"roomTemplateId\":\"40000000-0000-4000-8000-000000000001\"},"
            + "\"runtimeRoomInstanceId\":\"23\",\"fixtureProof\":\""
            + proof
            + "\"}";
    when(evidence.canonicalBytes())
        .thenReturn(canonicalEvidence.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return evidence;
  }

  private static WorldCanonicalInitialPlayerLocation.Request requestWithLifecycle(
      WorldCanonicalInstanceLifecycleEvidence lifecycle) {
    var scope = lifecycle.request();
    return new WorldCanonicalInitialPlayerLocation.Request(
        OPERATION_ID,
        scope.canonicalTenantId(),
        uuid("30000000-0000-4000-8000-000000000001"),
        scope.worldSlug(),
        scope.canonicalGameInstanceId(),
        scope.playableStateNamespaceId(),
        scope.playableStateScope(),
        uuid("60000000-0000-4000-8000-000000000001"),
        uuid("70000000-0000-4000-8000-000000000001"),
        uuid("80000000-0000-4000-8000-000000000001"),
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        uuid("90000000-0000-4000-8000-000000000001"),
        uuid("a0000000-0000-4000-8000-000000000001"),
        "initial-request",
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        1,
        "owner-proof",
        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        "pointer-audit",
        1,
        WorldCanonicalInitialPlayerLocation.InitialAdmissionOrigin.NO_PRIOR_POINTER,
        lifecycle);
  }

  private static WorldCanonicalInstanceLifecycleEvidence genuineLifecycleEvidence()
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
    var descriptor =
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
                            owner),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "c".repeat(64) : null))
            .toList();
    var release =
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
    var binding = new CompleteLaunchBindingEvidence(descriptor, release);
    var selectorReceipt =
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
        "ACTIVE",
        3L,
        0L,
        uuid("33333333-3333-4333-8333-333333333333"),
        selectorReceipt.graphDigest().substring("sha256:".length()),
        "sha256:" + "e".repeat(64),
        java.util.Map.of(
            uuid("11111111-1111-4111-8111-111111111111"),
            uuid("22222222-2222-4222-8222-222222222222")));
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request copyLifecycleRequest(
      WorldCanonicalInstanceLifecycleEvidence source, UUID readRequestId) {
    var request = source.request();
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        request.schemaVersion(),
        readRequestId,
        request.targetNamespace(),
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
  }

  private static WorldCanonicalInstanceLifecycleEvidence copyLifecycle(
      WorldCanonicalInstanceLifecycleEvidence source,
      WorldCanonicalInstanceLifecycleEvidence.Request request,
      long lifecycleEpoch) {
    return new WorldCanonicalInstanceLifecycleEvidence(
        request,
        source.launchBinding(),
        source.startLocation(),
        source.runtimeRoomInstanceId(),
        source.lifecycleStatus(),
        lifecycleEpoch,
        source.rowVersion(),
        source.captureId(),
        source.graphSha256(),
        source.preparationInputDigest(),
        source.operationalRegionAssignments());
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
