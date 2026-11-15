package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftGraphApplicationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftGraphAppliedResult;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTerminalOutcome;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTerminalOutcomeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTerminalReadGrpcService;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldDraftTerminalOutcomeRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldDraftTerminalOutcomeResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldDraftTerminalReadStatus;
import org.junit.jupiter.api.Test;

class WorldDraftTerminalReadGrpcServiceTest {
  private final WorldDraftGraphApplicationRepository applications =
      mock(WorldDraftGraphApplicationRepository.class);

  @Test
  void rejectsMissingOrSubstitutedAccountPeerBeforeDecodingOrQuerying() {
    WorldDraftTerminalOutcomeRepository repository =
        mock(WorldDraftTerminalOutcomeRepository.class);
    var service = new WorldDraftTerminalReadGrpcService(repository, applications, "test");
    ReadWorldDraftTerminalOutcomeRequest malformed =
        ReadWorldDraftTerminalOutcomeRequest.newBuilder().setSchemaVersion(-1).build();

    Collector missingPeer = new Collector();
    service.readWorldDraftTerminalOutcome(malformed, missingPeer);
    assertThat(Status.fromThrowable(missingPeer.error).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);

    Collector wrongPeer = new Collector();
    Context context =
        Context.current()
            .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer("world-management-service", "test"));
    Context previous = context.attach();
    try {
      service.readWorldDraftTerminalOutcome(malformed, wrongPeer);
    } finally {
      context.detach(previous);
    }
    assertThat(Status.fromThrowable(wrongPeer.error).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository, applications);
  }

  @Test
  void authenticatesThenReturnsUnknownForMissingTerminalRowWithoutInventingAbort() {
    WorldDraftTerminalOutcomeRepository repository =
        mock(WorldDraftTerminalOutcomeRepository.class);
    var request = WorldDraftTerminalReadEvidence.Request.create("test", accountBinding());
    when(repository.readDefinitiveAbort(eq("test"), any(byte[].class)))
        .thenReturn(Optional.empty());
    var service = new WorldDraftTerminalReadGrpcService(repository, applications, "test");
    Collector response = new Collector();
    Context context =
        Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer("account-service", "test"));
    Context previous = context.attach();
    try {
      service.readWorldDraftTerminalOutcome(
          net.firedevops.firemud.common.authoring.WorldDraftTerminalReadGrpcCodec.toRequest(
              request),
          response);
    } finally {
      context.detach(previous);
    }

    verify(repository).readDefinitiveAbort(eq("test"), any(byte[].class));
    verify(applications).readCommitted(eq("test"), any(byte[].class));
    assertThat(response.error).isNull();
    assertThat(response.completed).isTrue();
    assertThat(response.value.getStatus())
        .isEqualTo(WorldDraftTerminalReadStatus.WORLD_DRAFT_TERMINAL_READ_STATUS_UNKNOWN);
    assertThat(response.value.getOwnerReadbackBytes()).isEmpty();
  }

  @Test
  void trustedAccountPeerGetsUnavailableWhenJooqTerminalReadFails() {
    WorldDraftTerminalOutcomeRepository repository =
        mock(WorldDraftTerminalOutcomeRepository.class);
    var request = WorldDraftTerminalReadEvidence.Request.create("test", accountBinding());
    when(repository.readDefinitiveAbort(eq("test"), any(byte[].class)))
        .thenThrow(new org.jooq.exception.DataAccessException("database unavailable"));
    var service = new WorldDraftTerminalReadGrpcService(repository, applications, "test");
    Collector response = new Collector();
    Context context =
        Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer("account-service", "test"));
    Context previous = context.attach();
    try {
      service.readWorldDraftTerminalOutcome(
          net.firedevops.firemud.common.authoring.WorldDraftTerminalReadGrpcCodec.toRequest(
              request),
          response);
    } finally {
      context.detach(previous);
    }

    verify(repository).readDefinitiveAbort(eq("test"), any(byte[].class));
    assertThat(Status.fromThrowable(response.error).getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(response.value).isNull();
    assertThat(response.completed).isFalse();
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  @Test
  void conflictingCommittedAndAbortReceiptsReturnFailedPreconditionInsteadOfChoosingOne() {
    var repository = mock(WorldDraftTerminalOutcomeRepository.class);
    var request = WorldDraftTerminalReadEvidence.Request.create("test", accountBinding());
    var committed = mock(WorldDraftGraphAppliedResult.class);
    when(repository.readDefinitiveAbort(eq("test"), any(byte[].class)))
        .thenReturn(Optional.of(mock(WorldDraftTerminalOutcome.class)));
    when(applications.readCommitted(eq("test"), any(byte[].class)))
        .thenReturn(Optional.of(committed));
    var service = new WorldDraftTerminalReadGrpcService(repository, applications, "test");
    Collector response = callAsAccount(service, request);
    assertThat(Status.fromThrowable(response.error).getCode())
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(response.value).isNull();
    verifyNoInteractions(committed);
  }

  @Test
  void substitutedCommittedOwnerReadbackFailsClosedAfterAuthenticatedOwnerReads() {
    var repository = mock(WorldDraftTerminalOutcomeRepository.class);
    var request = WorldDraftTerminalReadEvidence.Request.create("test", accountBinding());
    var committed = mock(WorldDraftGraphAppliedResult.class);
    var binding = request.accountBinding();
    when(repository.readDefinitiveAbort(eq("test"), any(byte[].class)))
        .thenReturn(Optional.empty());
    when(applications.readCommitted(eq("test"), any(byte[].class)))
        .thenReturn(Optional.of(committed));
    when(committed.ownerReadback())
        .thenReturn(
            new DraftAuthorizationFenceBinding.OwnerReadback(
                DraftAuthorizationFenceBinding.Owner.GAME_DESIGN,
                DraftAuthorizationFenceBinding.Outcome.COMMITTED,
                binding.operationId(),
                binding.commitId(),
                binding.fenceId(),
                binding.inputDigest(),
                binding.canonicalBytes(),
                new byte[] {1}));
    var response =
        callAsAccount(
            new WorldDraftTerminalReadGrpcService(repository, applications, "test"), request);
    assertThat(Status.fromThrowable(response.error).getCode())
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(response.value).isNull();
  }

  private static Collector callAsAccount(
      WorldDraftTerminalReadGrpcService service, WorldDraftTerminalReadEvidence.Request request) {
    Collector response = new Collector();
    var context =
        Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer("account-service", "test"));
    var previous = context.attach();
    try {
      service.readWorldDraftTerminalOutcome(
          net.firedevops.firemud.common.authoring.WorldDraftTerminalReadGrpcCodec.toRequest(
              request),
          response);
    } finally {
      context.detach(previous);
    }
    return response;
  }

  private static byte[] accountBinding() {
    UUID tenant = uuid("11111111-1111-4111-8111-111111111111");
    UUID version = uuid("22222222-2222-4222-8222-222222222222");
    UUID request = uuid("44444444-4444-4444-8444-444444444444");
    UUID commit = uuid("55555555-5555-4555-8555-555555555555");
    DraftCommitBinding draft =
        DraftCommitBinding.create(
            new TargetProof(tenant, version, 19L, "tenant-key", 42L, "tenant-key", "NEW_GAME_ROW"),
            request,
            commit,
            "base-1",
            List.of(
                new RevisionPayload(
                    "0",
                    uuid("66666666-6666-4666-8666-666666666666"),
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "{}")),
            List.of(
                new AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "WORLD_TEMPLATE",
                    "world-1",
                    "ROOM_SCOPE",
                    "room-1",
                    "0")));
    byte[] draftBytes = draft.canonicalBytes();
    return new DraftAuthorizationFenceBinding(
            uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            request,
            commit,
            uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            tenant,
            version,
            "base-1",
            "0",
            draftBytes,
            draftBytes,
            draft.digest(),
            List.of(
                new SourceEvidence(
                    SourceKind.GLOBAL_ROLES,
                    "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                    null,
                    "1",
                    null,
                    null,
                    new byte[] {1})))
        .canonicalBytes();
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static final class Collector
      implements StreamObserver<ReadWorldDraftTerminalOutcomeResponse> {
    private ReadWorldDraftTerminalOutcomeResponse value;
    private Throwable error;
    private boolean completed;

    @Override
    public void onNext(ReadWorldDraftTerminalOutcomeResponse response) {
      value = response;
    }

    @Override
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "The test recorder retains the original throwable for assertion.")
    public void onError(Throwable failure) {
      error = failure;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
