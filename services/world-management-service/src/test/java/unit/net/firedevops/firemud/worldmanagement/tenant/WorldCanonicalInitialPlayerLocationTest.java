package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInitialPlayerLocation;
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
  void unsupportedExpectClosedOriginIsRejectedWithoutInventingPointerEvidence() {
    assertThatThrownBy(
            () ->
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
                    lifecycle("proof", "b0000000-0000-4000-8000-000000000001")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("EXPECT_CLOSED");
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

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
