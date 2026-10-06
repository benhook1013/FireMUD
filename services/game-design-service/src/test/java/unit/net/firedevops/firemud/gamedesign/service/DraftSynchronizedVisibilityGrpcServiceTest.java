package net.firedevops.firemud.gamedesign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.AppliedEpoch;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.CommitSnapshot;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerOutcome;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerState;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerStatus;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.VisibilityFence;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.WorkflowState;
import net.firedevops.firemud.gamedesign.v1.ReadDraftSynchronizedVisibilityResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DraftSynchronizedVisibilityGrpcServiceTest {
  private static final String NAMESPACE = "test";
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID READ_ID = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID REQUEST_ID = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID COMMIT_ID = uuid("55555555-5555-4555-8555-555555555555");
  private static final OffsetDateTime NOW =
      OffsetDateTime.of(2026, 10, 6, 0, 0, 0, 0, ZoneOffset.UTC);

  @Test
  void exactSameNamespaceWorldPeerReceivesDurableCompleteFenceAndOwnerVector() {
    DraftCommitCoordinatorRepository coordinator = mock(DraftCommitCoordinatorRepository.class);
    DraftCommitBinding binding = binding();
    CommitSnapshot snapshot = snapshot(binding, WorkflowState.SYNCHRONIZED, OwnerStatus.APPLIED);
    VisibilityFence fence = fence(binding, vector(binding));
    when(coordinator.readVisibilityFence(binding.target())).thenReturn(Optional.of(fence));
    when(coordinator.read(binding.target(), REQUEST_ID)).thenReturn(Optional.of(snapshot));
    var handler = new DraftSynchronizedVisibilityGrpcService(coordinator, NAMESPACE);
    var request = DraftSynchronizedVisibilityGrpcCodec.toRequest(request());
    TestObserver<ReadDraftSynchronizedVisibilityResponse> observer = new TestObserver<>();

    withPeer(peer(NAMESPACE), () -> handler.readDraftSynchronizedVisibility(request, observer));

    assertThat(observer.error).isNull();
    assertThat(observer.completed).isTrue();
    assertThat(
            DraftSynchronizedVisibilityGrpcCodec.fromResponse(request(), observer.value).binding())
        .isEqualTo(binding);
    verify(coordinator).readVisibilityFence(binding.target());
    verify(coordinator).read(binding.target(), REQUEST_ID);
  }

  @Test
  void rejectsPeerBeforeMalformedTargetAndReturnsUnavailableForAbsentOrPartialFence() {
    DraftCommitCoordinatorRepository coordinator = mock(DraftCommitCoordinatorRepository.class);
    var handler = new DraftSynchronizedVisibilityGrpcService(coordinator, NAMESPACE);
    var malformed =
        net.firedevops.firemud.gamedesign.v1.ReadDraftSynchronizedVisibilityRequest.newBuilder()
            .setSchemaVersion(99)
            .build();
    TestObserver<ReadDraftSynchronizedVisibilityResponse> absent = new TestObserver<>();
    handler.readDraftSynchronizedVisibility(malformed, absent);
    assertStatus(absent, Status.Code.PERMISSION_DENIED);

    TestObserver<ReadDraftSynchronizedVisibilityResponse> wrongService = new TestObserver<>();
    withPeer(
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/test/sa/game-session-service", "test", "game-session-service"),
        () -> handler.readDraftSynchronizedVisibility(malformed, wrongService));
    assertStatus(wrongService, Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(coordinator);

    var valid = DraftSynchronizedVisibilityGrpcCodec.toRequest(request());
    when(coordinator.readVisibilityFence(target())).thenReturn(Optional.empty());
    TestObserver<ReadDraftSynchronizedVisibilityResponse> noFence = new TestObserver<>();
    withPeer(peer(NAMESPACE), () -> handler.readDraftSynchronizedVisibility(valid, noFence));
    assertStatus(noFence, Status.Code.UNAVAILABLE);

    DraftCommitBinding binding = binding();
    when(coordinator.readVisibilityFence(target()))
        .thenReturn(Optional.of(fence(binding, vector(binding))));
    when(coordinator.read(target(), REQUEST_ID))
        .thenReturn(
            Optional.of(snapshot(binding, WorkflowState.APPLYING, OwnerStatus.IN_PROGRESS)));
    TestObserver<ReadDraftSynchronizedVisibilityResponse> partial = new TestObserver<>();
    withPeer(peer(NAMESPACE), () -> handler.readDraftSynchronizedVisibility(valid, partial));
    assertStatus(partial, Status.Code.UNAVAILABLE);
  }

  @Test
  void rejectsFenceResultVectorThatSubstitutesOwnerOutcome() {
    DraftCommitCoordinatorRepository coordinator = mock(DraftCommitCoordinatorRepository.class);
    DraftCommitBinding binding = binding();
    when(coordinator.readVisibilityFence(binding.target()))
        .thenReturn(
            Optional.of(fence(binding, vector(binding).replace("world-result", "other-result"))));
    when(coordinator.read(binding.target(), REQUEST_ID))
        .thenReturn(
            Optional.of(snapshot(binding, WorkflowState.SYNCHRONIZED, OwnerStatus.APPLIED)));
    var handler = new DraftSynchronizedVisibilityGrpcService(coordinator, NAMESPACE);
    TestObserver<ReadDraftSynchronizedVisibilityResponse> observer = new TestObserver<>();

    withPeer(
        peer(NAMESPACE),
        () ->
            handler.readDraftSynchronizedVisibility(
                DraftSynchronizedVisibilityGrpcCodec.toRequest(request()), observer));

    assertStatus(observer, Status.Code.UNAVAILABLE);
  }

  private static CommitSnapshot snapshot(
      DraftCommitBinding binding, WorkflowState workflowState, OwnerStatus status) {
    Map<Owner, OwnerState> owners = new EnumMap<>(Owner.class);
    for (Owner owner : binding.requiredOwners()) {
      OwnerOutcome outcome = outcome(binding, owner);
      OwnerStatus current =
          owner == Owner.ENTITY_MANAGEMENT && status == OwnerStatus.IN_PROGRESS
              ? OwnerStatus.IN_PROGRESS
              : status;
      Optional<OwnerOutcome> optional =
          current == OwnerStatus.APPLIED ? Optional.of(outcome) : Optional.empty();
      owners.put(owner, new OwnerState(owner, current, optional, NOW, NOW));
    }
    return new CommitSnapshot(binding, workflowState, NOW, NOW, owners);
  }

  private static OwnerOutcome outcome(DraftCommitBinding binding, Owner owner) {
    List<AppliedEpoch> epochs =
        binding.affectedUnits(owner).stream()
            .map(
                unit ->
                    new AppliedEpoch(
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch(),
                        new java.math.BigInteger(unit.expectedEpoch())
                            .add(java.math.BigInteger.ONE)
                            .toString()))
            .toList();
    return new OwnerOutcome(
        owner,
        OwnerStatus.APPLIED,
        COMMIT_ID,
        binding.digest(),
        owner == Owner.WORLD_MANAGEMENT ? "world-result" : "entity-result",
        "owner-result-bytes".getBytes(StandardCharsets.UTF_8),
        epochs);
  }

  private static VisibilityFence fence(DraftCommitBinding binding, String vector) {
    return new VisibilityFence(target(), REQUEST_ID, COMMIT_ID, binding.digest(), vector, NOW);
  }

  private static DraftSynchronizedVisibilityEvidence.Request request() {
    return new DraftSynchronizedVisibilityEvidence.Request(1, NAMESPACE, READ_ID, target());
  }

  private static TargetProof target() {
    return new TargetProof(TENANT, VERSION, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW");
  }

  private static DraftCommitBinding binding() {
    return DraftCommitBinding.create(
        target(),
        REQUEST_ID,
        COMMIT_ID,
        "base-source-1",
        List.of(
            new RevisionPayload(
                "0", uuid("66666666-6666-4666-8666-666666666666"), Owner.WORLD_MANAGEMENT, "{}"),
            new RevisionPayload(
                "1", uuid("77777777-7777-4777-8777-777777777777"), Owner.ENTITY_MANAGEMENT, "{}")),
        List.of(
            new AffectedUnit(
                Owner.WORLD_MANAGEMENT, "WORLD_TEMPLATE", "world-1", "ROOM_SCOPE", "room-1", "0"),
            new AffectedUnit(
                Owner.ENTITY_MANAGEMENT,
                "ENTITY_TEMPLATE",
                "entity-1",
                "ACTOR_SCOPE",
                "actor-1",
                "1")));
  }

  private static String vector(DraftCommitBinding binding) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Owner owner : binding.requiredOwners()) {
      OwnerOutcome outcome = outcome(binding, owner);
      List<Map<String, String>> epochs =
          outcome.appliedEpochs().stream()
              .map(
                  epoch -> {
                    Map<String, String> map = new LinkedHashMap<>();
                    map.put("aggregateType", epoch.aggregateType());
                    map.put("aggregateId", epoch.aggregateId());
                    map.put("scopeType", epoch.scopeType());
                    map.put("scopeId", epoch.scopeId());
                    map.put("expectedEpoch", epoch.expectedEpoch());
                    map.put("resultingEpoch", epoch.resultingEpoch());
                    return map;
                  })
              .toList();
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("owner", owner.name());
      item.put("status", "APPLIED");
      item.put("commitId", COMMIT_ID.toString());
      item.put("bindingDigest", binding.digest());
      item.put("resultIdentity", outcome.resultIdentity());
      item.put("resultBytesBase64", Base64.getEncoder().encodeToString(outcome.resultBytes()));
      item.put("appliedEpochs", epochs);
      result.add(item);
    }
    return canonical(new ObjectMapper().writeValueAsString(result));
  }

  private static String canonical(String json) {
    try {
      return new String(Rfc8785CanonicalJson.canonicalizeUtf8(json), StandardCharsets.UTF_8);
    } catch (java.io.IOException exception) {
      throw new AssertionError(exception);
    }
  }

  private static void assertStatus(TestObserver<?> observer, Status.Code expected) {
    assertThat(observer.error).isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(observer.error).getCode()).isEqualTo(expected);
  }

  private static void withPeer(GrpcPeerIdentity peer, Runnable action) {
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      action.run();
    } finally {
      context.detach(previous);
    }
  }

  private static GrpcPeerIdentity peer(String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/world-management-service",
        namespace,
        "world-management-service");
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static final class TestObserver<T> implements StreamObserver<T> {
    private T value;
    private Throwable error;
    private boolean completed;

    @Override
    public void onNext(T next) {
      value = next;
    }

    @Override
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification =
            "The test recorder retains the exact original throwable solely for assertion.")
    public void onError(Throwable failure) {
      error = failure;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
