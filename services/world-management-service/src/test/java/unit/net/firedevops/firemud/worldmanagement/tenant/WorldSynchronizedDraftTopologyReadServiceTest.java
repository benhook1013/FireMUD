package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityClient;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WorldSynchronizedDraftTopologyReadServiceTest {
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID READ_ID = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID REQUEST_ID = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID COMMIT_ID = uuid("55555555-5555-4555-8555-555555555555");

  @Test
  void propagatesWorldDenialWhenAppliedOutputCannotBeBoundToGraph() {
    DraftSynchronizedVisibilityClient client = mock(DraftSynchronizedVisibilityClient.class);
    WorldDraftTopologyCommitRepository repository = mock(WorldDraftTopologyCommitRepository.class);
    var request = request(READ_ID);
    var evidence = evidence(request);
    when(client.read(request)).thenReturn(evidence);
    when(repository.readSynchronized(evidence))
        .thenThrow(
            new WorldDesignPublicationFenceRepository.ConflictException(
                "World synchronized graph selection has no canonical World APPLIED-result carrier "
                    + "bound to the retained graph"));

    assertThatThrownBy(
            () -> new WorldSynchronizedDraftTopologyReadService(client, repository).read(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no canonical World APPLIED-result carrier");
    verify(repository).readSynchronized(evidence);
  }

  @Test
  void deniesChangedReadEchoAndUnavailableVisibilityBeforeLocalGraphLookup() {
    DraftSynchronizedVisibilityClient client = mock(DraftSynchronizedVisibilityClient.class);
    WorldDraftTopologyCommitRepository repository = mock(WorldDraftTopologyCommitRepository.class);
    var request = request(READ_ID);
    when(client.read(request))
        .thenReturn(evidence(request(uuid("88888888-8888-4888-8888-888888888888"))));

    assertThatThrownBy(
            () -> new WorldSynchronizedDraftTopologyReadService(client, repository).read(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("changed the exact read target");
    verifyNoInteractions(repository);

    when(client.read(request))
        .thenThrow(
            Status.UNAVAILABLE.withDescription("no synchronized fence").asRuntimeException());
    assertThatThrownBy(
            () -> new WorldSynchronizedDraftTopologyReadService(client, repository).read(request))
        .isInstanceOf(io.grpc.StatusRuntimeException.class);
    verifyNoInteractions(repository);
  }

  private static DraftSynchronizedVisibilityEvidence evidence(
      DraftSynchronizedVisibilityEvidence.Request request) {
    DraftCommitBinding binding =
        DraftCommitBinding.create(
            target(),
            REQUEST_ID,
            COMMIT_ID,
            "base-source-1",
            List.of(
                new RevisionPayload(
                    "0",
                    uuid("66666666-6666-4666-8666-666666666666"),
                    Owner.WORLD_MANAGEMENT,
                    "{}")),
            List.of(
                new AffectedUnit(
                    Owner.WORLD_MANAGEMENT,
                    "WORLD_TEMPLATE",
                    "world-1",
                    "ROOM_SCOPE",
                    "room-1",
                    "0")));
    Map<String, Object> epoch = new LinkedHashMap<>();
    epoch.put("aggregateType", "WORLD_TEMPLATE");
    epoch.put("aggregateId", "world-1");
    epoch.put("scopeType", "ROOM_SCOPE");
    epoch.put("scopeId", "room-1");
    epoch.put("expectedEpoch", "0");
    epoch.put("resultingEpoch", "1");
    Map<String, Object> owner = new LinkedHashMap<>();
    owner.put("owner", "WORLD_MANAGEMENT");
    owner.put("status", "APPLIED");
    owner.put("commitId", COMMIT_ID.toString());
    owner.put("bindingDigest", binding.digest());
    owner.put("resultIdentity", "world-result");
    owner.put(
        "resultBytesBase64",
        Base64.getEncoder().encodeToString("result".getBytes(StandardCharsets.UTF_8)));
    owner.put("appliedEpochs", List.of(epoch));
    String vector = canonical(new ObjectMapper().writeValueAsString(List.of(owner)));
    return new DraftSynchronizedVisibilityEvidence(
        request,
        binding,
        "SYNCHRONIZED",
        new DraftSynchronizedVisibilityEvidence.Fence(
            REQUEST_ID,
            COMMIT_ID,
            binding.digest(),
            vector,
            OffsetDateTime.parse("2026-10-06T00:00:00Z")));
  }

  private static DraftSynchronizedVisibilityEvidence.Request request(UUID readId) {
    return new DraftSynchronizedVisibilityEvidence.Request(1, "test", readId, target());
  }

  private static TargetProof target() {
    return new TargetProof(TENANT, VERSION, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW");
  }

  private static String canonical(String json) {
    try {
      return new String(Rfc8785CanonicalJson.canonicalizeUtf8(json), StandardCharsets.UTF_8);
    } catch (java.io.IOException exception) {
      throw new AssertionError(exception);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
