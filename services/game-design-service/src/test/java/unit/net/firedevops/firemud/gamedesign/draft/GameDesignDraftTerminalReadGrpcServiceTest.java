package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
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
import net.firedevops.firemud.common.authoring.GameDesignDraftTerminalReadEvidence;
import net.firedevops.firemud.common.authoring.GameDesignDraftTerminalReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.v1.GameDesignDraftTerminalReadStatus;
import net.firedevops.firemud.gamedesign.v1.ReadGameDesignDraftTerminalOutcomeRequest;
import net.firedevops.firemud.gamedesign.v1.ReadGameDesignDraftTerminalOutcomeResponse;
import org.junit.jupiter.api.Test;

/** Receiver adapter proof with stipulated Account peer identity and mocked terminal storage. */
class GameDesignDraftTerminalReadGrpcServiceTest {
  private static final String NAMESPACE = "test";
  private static final DraftAuthorizationFenceBinding ACCOUNT_BINDING = accountBinding();
  private static final byte[] ACCOUNT_BINDING_BYTES = ACCOUNT_BINDING.canonicalBytes();

  @Test
  void rejectsMissingOrWrongAccountPeerBeforeDecodingOrReading() {
    GameDesignDraftTerminalOutcomeRepository repository =
        mock(GameDesignDraftTerminalOutcomeRepository.class);
    var service = new GameDesignDraftTerminalReadGrpcService(repository, NAMESPACE);
    ReadGameDesignDraftTerminalOutcomeRequest malformed =
        ReadGameDesignDraftTerminalOutcomeRequest.newBuilder().setSchemaVersion(-1).build();

    Collector noPeer = new Collector();
    service.readGameDesignDraftTerminalOutcome(malformed, noPeer);
    assertThat(noPeer.errorCode).isEqualTo(Status.Code.PERMISSION_DENIED);

    Collector wrongService = new Collector();
    invokeAs(service, malformed, wrongService, peer("world-management-service", NAMESPACE));
    assertThat(wrongService.errorCode).isEqualTo(Status.Code.PERMISSION_DENIED);

    Collector wrongNamespace = new Collector();
    invokeAs(service, malformed, wrongNamespace, peer("account-service", "other"));
    assertThat(wrongNamespace.errorCode).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void authenticatedAccountGetsUnknownForMissingOperationWithoutInventedOutcome() {
    GameDesignDraftTerminalOutcomeRepository repository =
        mock(GameDesignDraftTerminalOutcomeRepository.class);
    when(repository.readAccountBound(any(byte[].class))).thenReturn(Optional.empty());
    var service = new GameDesignDraftTerminalReadGrpcService(repository, NAMESPACE);
    var request =
        GameDesignDraftTerminalReadEvidence.Request.create(NAMESPACE, ACCOUNT_BINDING_BYTES);
    Collector response = new Collector();

    invokeAs(
        service,
        GameDesignDraftTerminalReadGrpcCodec.toRequest(request),
        response,
        peer("account-service", NAMESPACE));

    verify(repository).readAccountBound(eq(ACCOUNT_BINDING_BYTES));
    assertThat(response.errorCode).isNull();
    assertThat(response.completed).isTrue();
    assertThat(response.value.getStatus())
        .isEqualTo(
            GameDesignDraftTerminalReadStatus.GAME_DESIGN_DRAFT_TERMINAL_READ_STATUS_UNKNOWN);
    assertThat(response.value.getOwnerReadbackBytes()).isEmpty();
  }

  @Test
  void returnsOnlyPersistedCommittedOrDefinitiveAbortOwnerReadbacks() {
    for (GameDesignDraftTerminalOutcome.Result result :
        List.of(
            GameDesignDraftTerminalOutcome.Result.COMMITTED,
            GameDesignDraftTerminalOutcome.Result.DEFINITIVELY_ABORTED)) {
      GameDesignDraftTerminalOutcomeRepository repository =
          mock(GameDesignDraftTerminalOutcomeRepository.class);
      var outcome = terminalOutcome(result);
      when(repository.readAccountBound(any(byte[].class))).thenReturn(Optional.of(outcome));
      var service = new GameDesignDraftTerminalReadGrpcService(repository, NAMESPACE);
      var request =
          GameDesignDraftTerminalReadEvidence.Request.create(NAMESPACE, ACCOUNT_BINDING_BYTES);
      Collector response = new Collector();

      invokeAs(
          service,
          GameDesignDraftTerminalReadGrpcCodec.toRequest(request),
          response,
          peer("account-service", NAMESPACE));

      verify(repository).readAccountBound(eq(ACCOUNT_BINDING_BYTES));
      assertThat(response.errorCode).isNull();
      assertThat(response.completed).isTrue();
      var evidence = GameDesignDraftTerminalReadGrpcCodec.fromResponse(request, response.value);
      assertThat(evidence.ownerReadback()).isPresent();
      assertThat(evidence.ownerReadback().orElseThrow().canonicalBytes())
          .containsExactly(outcome.toOwnerReadback().canonicalBytes());
      assertThat(response.value.getStatus())
          .isEqualTo(
              result == GameDesignDraftTerminalOutcome.Result.COMMITTED
                  ? GameDesignDraftTerminalReadStatus
                      .GAME_DESIGN_DRAFT_TERMINAL_READ_STATUS_COMMITTED
                  : GameDesignDraftTerminalReadStatus
                      .GAME_DESIGN_DRAFT_TERMINAL_READ_STATUS_DEFINITIVELY_ABORTED);
    }
  }

  @Test
  void malformedAuthenticatedRequestAndCrossNamespaceRequestNeverReachRepository() {
    GameDesignDraftTerminalOutcomeRepository repository =
        mock(GameDesignDraftTerminalOutcomeRepository.class);
    var service = new GameDesignDraftTerminalReadGrpcService(repository, NAMESPACE);

    Collector malformed = new Collector();
    invokeAs(
        service,
        ReadGameDesignDraftTerminalOutcomeRequest.newBuilder().setSchemaVersion(-1).build(),
        malformed,
        peer("account-service", NAMESPACE));
    assertThat(malformed.errorCode).isEqualTo(Status.Code.INVALID_ARGUMENT);

    var otherNamespaceRequest =
        GameDesignDraftTerminalReadEvidence.Request.create("other", ACCOUNT_BINDING_BYTES);
    Collector wrongNamespace = new Collector();
    invokeAs(
        service,
        GameDesignDraftTerminalReadGrpcCodec.toRequest(otherNamespaceRequest),
        wrongNamespace,
        peer("account-service", NAMESPACE));
    assertThat(wrongNamespace.errorCode).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void ownerStorageFailureIsUnavailableWithoutPartialBytes() {
    GameDesignDraftTerminalOutcomeRepository repository =
        mock(GameDesignDraftTerminalOutcomeRepository.class);
    when(repository.readAccountBound(any(byte[].class)))
        .thenThrow(new org.jooq.exception.DataAccessException("owner store unavailable"));
    var service = new GameDesignDraftTerminalReadGrpcService(repository, NAMESPACE);
    var request =
        GameDesignDraftTerminalReadEvidence.Request.create(NAMESPACE, ACCOUNT_BINDING_BYTES);
    Collector response = new Collector();

    invokeAs(
        service,
        GameDesignDraftTerminalReadGrpcCodec.toRequest(request),
        response,
        peer("account-service", NAMESPACE));

    assertThat(response.errorCode).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(response.value).isNull();
    assertThat(response.completed).isFalse();
  }

  private static GameDesignDraftTerminalOutcome terminalOutcome(
      GameDesignDraftTerminalOutcome.Result result) {
    var operation = new GameDesignDraftTerminalOperation(ACCOUNT_BINDING);
    String owner = operation.gameDesignBinding().requiredOwners().get(0).name();
    String status =
        result == GameDesignDraftTerminalOutcome.Result.COMMITTED ? "APPLIED" : "REJECTED";
    String vector =
        "[{\"appliedEpochs\":[],\"bindingDigest\":\""
            + operation.gameDesignBinding().digest()
            + "\",\"commitId\":\""
            + operation.gameDesignBinding().commitId()
            + "\",\"owner\":\""
            + owner
            + "\",\"resultBytesBase64\":\"AQ==\",\"resultIdentity\":\"test-result\",\"status\":\""
            + status
            + "\"}]";
    byte[] evidence =
        result == GameDesignDraftTerminalOutcome.Result.COMMITTED
            ? vector.getBytes(StandardCharsets.UTF_8)
            : new byte[] {8, 9};
    return new GameDesignDraftTerminalOutcome(
        operation,
        result,
        vector,
        evidence,
        GameDesignDraftTerminalOperation.sha256(evidence),
        OffsetDateTime.now());
  }

  private static void invokeAs(
      GameDesignDraftTerminalReadGrpcService service,
      ReadGameDesignDraftTerminalOutcomeRequest request,
      Collector collector,
      GrpcPeerIdentity peer) {
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      service.readGameDesignDraftTerminalOutcome(request, collector);
    } finally {
      context.detach(previous);
    }
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static DraftAuthorizationFenceBinding accountBinding() {
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
                uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd").toString(),
                null,
                "1",
                null,
                null,
                new byte[] {4, 5})));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static final class Collector
      implements StreamObserver<ReadGameDesignDraftTerminalOutcomeResponse> {
    private ReadGameDesignDraftTerminalOutcomeResponse value;
    private Status.Code errorCode;
    private boolean completed;

    @Override
    public void onNext(ReadGameDesignDraftTerminalOutcomeResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      errorCode = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
