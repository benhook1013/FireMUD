package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceActivationGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceActivationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceActivationService;
import net.firedevops.firemud.worldmanagement.v1.ActivateCanonicalWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.ActivateCanonicalWorldInstanceResponse;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldCanonicalInstanceActivationGrpcServiceTest {
  private static final UUID ACTIVATION_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID OTHER_ACTIVATION_ID =
      UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

  @Test
  void rejectsMissingWrongOrWrongNamespacePeerBeforeDecodingOrOwnerAccess() {
    var activationService = mock(WorldCanonicalInstanceActivationService.class);
    var grpc = new WorldCanonicalInstanceActivationGrpcService(activationService, "test");
    var malformed = request("not-a-uuid", new byte[] {1});

    Collector missing = call(grpc, malformed, null);
    Collector wrongService = call(grpc, malformed, peer("account-service", "test"));
    Collector wrongPeerNamespace = call(grpc, malformed, peer("game-session-service", "other"));

    assertThat(missing.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(wrongService.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(wrongPeerNamespace.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(activationService);
  }

  @Test
  void rejectsEndUserContextBeforeDecodingOrOwnerAccess() {
    var activationService = mock(WorldCanonicalInstanceActivationService.class);
    var grpc = new WorldCanonicalInstanceActivationGrpcService(activationService, "test");
    SessionContext.setContext("11111111-1111-4111-8111-111111111111", List.of(), Map.of());
    Collector result;
    try {
      result =
          call(grpc, request("not-a-uuid", new byte[] {1}), peer("game-session-service", "test"));
    } finally {
      SessionContext.clear();
    }

    assertThat(result.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(activationService);
  }

  @Test
  void rejectsAmbientTransactionOrSynchronizationBeforeDecodingOrOwnerAccess() {
    var activationService = mock(WorldCanonicalInstanceActivationService.class);
    var grpc = new WorldCanonicalInstanceActivationGrpcService(activationService, "test");
    var malformed = request("not-a-uuid", new byte[] {1});

    TransactionSynchronizationManager.setActualTransactionActive(true);
    Collector transactionResult;
    try {
      transactionResult = call(grpc, malformed, peer("game-session-service", "test"));
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    TransactionSynchronizationManager.initSynchronization();
    Collector synchronizationResult;
    try {
      synchronizationResult = call(grpc, malformed, peer("game-session-service", "test"));
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    assertThat(transactionResult.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(synchronizationResult.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(activationService);
  }

  @Test
  void rejectsUnknownMalformedAndNoncanonicalRequestBeforeOwnerAccess() {
    var activationService = mock(WorldCanonicalInstanceActivationService.class);
    var grpc = new WorldCanonicalInstanceActivationGrpcService(activationService, "test");
    var unknown =
        request(ACTIVATION_ID.toString(), new byte[] {1}).toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    Collector unknownField = call(grpc, unknown, peer("game-session-service", "test"));
    Collector malformedEvidence =
        call(
            grpc,
            request(ACTIVATION_ID.toString(), "not-json".getBytes(StandardCharsets.UTF_8)),
            peer("game-session-service", "test"));
    Collector uppercaseId =
        call(
            grpc,
            request(ACTIVATION_ID.toString().toUpperCase(), new byte[] {1}),
            peer("game-session-service", "test"));

    assertThat(unknownField.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(malformedEvidence.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(uppercaseId.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(activationService);
  }

  @Test
  void rejectsEvidenceFromAnotherNamespaceBeforeOwnerAccess() {
    var activationService = mock(WorldCanonicalInstanceActivationService.class);
    var grpc = new WorldCanonicalInstanceActivationGrpcService(activationService, "test");
    Fixture otherNamespace = fixture("other", "11111111-1111-4111-8111-111111111111", "source-a");
    try (MockedStatic<WorldCanonicalInstanceLifecycleEvidence> parser =
        mockStatic(WorldCanonicalInstanceLifecycleEvidence.class)) {
      parser
          .when(() -> WorldCanonicalInstanceLifecycleEvidence.fromStored(otherNamespace.bytes))
          .thenReturn(otherNamespace.preparing);

      Collector result =
          call(
              grpc,
              request(ACTIVATION_ID.toString(), otherNamespace.bytes),
              peer("game-session-service", "test"));

      assertThat(result.error).isEqualTo(Status.Code.PERMISSION_DENIED);
      verifyNoInteractions(activationService);
    }
  }

  @Test
  void defaultVerifierDeniesNewOperationWithoutOwnerMutation() {
    var repository = mock(WorldCanonicalInstanceActivationRepository.class);
    var actualService = new WorldCanonicalInstanceActivationService(repository);
    var grpc = new WorldCanonicalInstanceActivationGrpcService(actualService, "test");
    Fixture preparing = fixture("test", "11111111-1111-4111-8111-111111111111", "source-a");
    when(repository.readResult(any())).thenReturn(Optional.empty());

    try (MockedStatic<WorldCanonicalInstanceLifecycleEvidence> parser =
        mockStatic(WorldCanonicalInstanceLifecycleEvidence.class)) {
      parser
          .when(() -> WorldCanonicalInstanceLifecycleEvidence.fromStored(preparing.bytes))
          .thenReturn(preparing.preparing);

      Collector result =
          call(
              grpc,
              request(ACTIVATION_ID.toString(), preparing.bytes),
              peer("game-session-service", "test"));

      assertThat(result.error).isEqualTo(Status.Code.PERMISSION_DENIED);
      ArgumentCaptor<WorldCanonicalInstanceActivation.Request> captor =
          ArgumentCaptor.forClass(WorldCanonicalInstanceActivation.Request.class);
      verify(repository).readResult(captor.capture());
      assertThat(captor.getValue().activationRequestId()).isEqualTo(ACTIVATION_ID);
      assertThat(captor.getValue().preparingEvidenceBytes()).containsExactly(preparing.bytes);
      verify(repository, never()).activate(any(), any());
    }
  }

  @Test
  void exactRetryWithNewReadCorrelationReturnsOriginalImmutableResultAndStableId() {
    var repository = mock(WorldCanonicalInstanceActivationRepository.class);
    Fixture original = fixture("test", "11111111-1111-4111-8111-111111111111", "source-a");
    Fixture retry = fixture("test", "22222222-2222-4222-8222-222222222222", "source-a");
    var originalRequest =
        new WorldCanonicalInstanceActivation.Request(ACTIVATION_ID, original.preparing);
    var retryRequest = new WorldCanonicalInstanceActivation.Request(ACTIVATION_ID, retry.preparing);
    var active = lifecycleEvidence(original.lifecycleRequest, "ACTIVE", 4L, 1L, "active-result");
    var originalResult =
        new WorldCanonicalInstanceActivation.Result(
            originalRequest, WorldCanonicalInstanceActivation.Outcome.COMMITTED, null, active);
    when(repository.readResult(any())).thenReturn(Optional.of(originalResult));
    var actualService = new WorldCanonicalInstanceActivationService(repository);
    var grpc = new WorldCanonicalInstanceActivationGrpcService(actualService, "test");

    try (MockedStatic<WorldCanonicalInstanceLifecycleEvidence> parser =
        mockStatic(WorldCanonicalInstanceLifecycleEvidence.class)) {
      parser
          .when(() -> WorldCanonicalInstanceLifecycleEvidence.fromStored(original.bytes))
          .thenReturn(original.preparing);
      parser
          .when(() -> WorldCanonicalInstanceLifecycleEvidence.fromStored(retry.bytes))
          .thenReturn(retry.preparing);

      Collector response =
          call(
              grpc,
              request(ACTIVATION_ID.toString(), retry.bytes),
              peer("game-session-service", "test"));

      assertThat(response.error).isNull();
      assertThat(response.completed).isTrue();
      assertThat(response.value.getActivationRequestId()).isEqualTo(ACTIVATION_ID.toString());
      assertThat(response.value.getCanonicalResultBytes().toByteArray())
          .containsExactly(originalResult.canonicalBytes());
      ArgumentCaptor<WorldCanonicalInstanceActivation.Request> captor =
          ArgumentCaptor.forClass(WorldCanonicalInstanceActivation.Request.class);
      verify(repository).readResult(captor.capture());
      assertThat(captor.getValue().canonicalRequestBytes())
          .containsExactly(retryRequest.canonicalRequestBytes());
      verify(repository, never()).activate(any(), any());
    }
  }

  @Test
  void rejectsReturnedResultForChangedOperationIdentity() {
    var activationService = mock(WorldCanonicalInstanceActivationService.class);
    Fixture requested = fixture("test", "11111111-1111-4111-8111-111111111111", "source-a");
    Fixture changed = fixture("test", "11111111-1111-4111-8111-111111111111", "source-b");
    var changedRequest =
        new WorldCanonicalInstanceActivation.Request(OTHER_ACTIVATION_ID, changed.preparing);
    var changedResult =
        new WorldCanonicalInstanceActivation.Result(
            changedRequest,
            WorldCanonicalInstanceActivation.Outcome.ABORTED,
            "PRECONDITION_FAILED",
            changed.preparing);
    when(activationService.activate(any())).thenReturn(changedResult);
    var grpc = new WorldCanonicalInstanceActivationGrpcService(activationService, "test");

    try (MockedStatic<WorldCanonicalInstanceLifecycleEvidence> parser =
        mockStatic(WorldCanonicalInstanceLifecycleEvidence.class)) {
      parser
          .when(() -> WorldCanonicalInstanceLifecycleEvidence.fromStored(requested.bytes))
          .thenReturn(requested.preparing);

      Collector response =
          call(
              grpc,
              request(ACTIVATION_ID.toString(), requested.bytes),
              peer("game-session-service", "test"));

      assertThat(response.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
      assertThat(response.value).isNull();
      verify(activationService).activate(any());
    }
  }

  @Test
  void mapsTransientStorageToUnavailableAndPermanentStorageToInternal() {
    Fixture preparing = fixture("test", "11111111-1111-4111-8111-111111111111", "source-a");
    var transientService = mock(WorldCanonicalInstanceActivationService.class);
    when(transientService.activate(any()))
        .thenThrow(new TransientDataAccessException("temporary store issue") {});
    var permanentService = mock(WorldCanonicalInstanceActivationService.class);
    when(permanentService.activate(any()))
        .thenThrow(
            new DataAccessException("permanent store issue", new SQLException("bad schema")) {});
    var transientGrpc = new WorldCanonicalInstanceActivationGrpcService(transientService, "test");
    var permanentGrpc = new WorldCanonicalInstanceActivationGrpcService(permanentService, "test");

    try (MockedStatic<WorldCanonicalInstanceLifecycleEvidence> parser =
        mockStatic(WorldCanonicalInstanceLifecycleEvidence.class)) {
      parser
          .when(() -> WorldCanonicalInstanceLifecycleEvidence.fromStored(preparing.bytes))
          .thenReturn(preparing.preparing);

      Collector transientResult =
          call(
              transientGrpc,
              request(ACTIVATION_ID.toString(), preparing.bytes),
              peer("game-session-service", "test"));
      Collector permanentResult =
          call(
              permanentGrpc,
              request(ACTIVATION_ID.toString(), preparing.bytes),
              peer("game-session-service", "test"));

      assertThat(transientResult.error).isEqualTo(Status.Code.UNAVAILABLE);
      assertThat(permanentResult.error).isEqualTo(Status.Code.INTERNAL);
    }
  }

  @Test
  void mapsChangedOperationIdentityToIdempotencyConflict() {
    Fixture preparing = fixture("test", "11111111-1111-4111-8111-111111111111", "source-a");
    var activationService = mock(WorldCanonicalInstanceActivationService.class);
    when(activationService.activate(any()))
        .thenThrow(
            new WorldCanonicalInstanceActivationRepository.ActivationConflictException(
                "request identity changed"));
    var grpc = new WorldCanonicalInstanceActivationGrpcService(activationService, "test");

    try (MockedStatic<WorldCanonicalInstanceLifecycleEvidence> parser =
        mockStatic(WorldCanonicalInstanceLifecycleEvidence.class)) {
      parser
          .when(() -> WorldCanonicalInstanceLifecycleEvidence.fromStored(preparing.bytes))
          .thenReturn(preparing.preparing);

      Collector response =
          call(
              grpc,
              request(ACTIVATION_ID.toString(), preparing.bytes),
              peer("game-session-service", "test"));

      assertThat(response.error).isEqualTo(Status.Code.ALREADY_EXISTS);
      verify(activationService).activate(any());
    }
  }

  private static Fixture fixture(String namespace, String readId, String binding) {
    byte[] bytes =
        ("{\"request\":{\"readRequestId\":\""
                + readId
                + "\",\"targetNamespace\":\""
                + namespace
                + "\",\"binding\":\""
                + binding
                + "\"}}")
            .getBytes(StandardCharsets.UTF_8);
    var lifecycleRequest = mock(WorldCanonicalInstanceLifecycleEvidence.Request.class);
    when(lifecycleRequest.targetNamespace()).thenReturn(namespace);
    var preparing = lifecycleEvidence(lifecycleRequest, "PREPARING", 3L, 0L, readId + binding);
    when(preparing.canonicalBytes()).thenReturn(bytes);
    return new Fixture(bytes, lifecycleRequest, preparing);
  }

  private static WorldCanonicalInstanceLifecycleEvidence lifecycleEvidence(
      WorldCanonicalInstanceLifecycleEvidence.Request request,
      String state,
      long epoch,
      long rowVersion,
      String representation) {
    var evidence = mock(WorldCanonicalInstanceLifecycleEvidence.class);
    when(evidence.request()).thenReturn(request);
    when(evidence.lifecycleStatus()).thenReturn(state);
    when(evidence.lifecycleEpoch()).thenReturn(epoch);
    when(evidence.rowVersion()).thenReturn(rowVersion);
    when(evidence.canonicalBytes())
        .thenReturn(
            ("{\"request\":{\"representation\":\"" + representation + "\"}}")
                .getBytes(StandardCharsets.UTF_8));
    return evidence;
  }

  private static ActivateCanonicalWorldInstanceRequest request(
      String activationId, byte[] evidence) {
    return ActivateCanonicalWorldInstanceRequest.newBuilder()
        .setActivationRequestId(activationId)
        .setPreparingLifecycleEvidenceBytes(ByteString.copyFrom(evidence))
        .build();
  }

  private static Collector call(
      WorldCanonicalInstanceActivationGrpcService service,
      ActivateCanonicalWorldInstanceRequest request,
      GrpcPeerIdentity peer) {
    Collector response = new Collector();
    Context context =
        peer == null
            ? Context.current()
            : Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      service.activateCanonicalWorldInstance(request, response);
    } finally {
      context.detach(previous);
    }
    return response;
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private record Fixture(
      byte[] bytes,
      WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest,
      WorldCanonicalInstanceLifecycleEvidence preparing) {}

  private static final class Collector
      implements StreamObserver<ActivateCanonicalWorldInstanceResponse> {
    private ActivateCanonicalWorldInstanceResponse value;
    private Status.Code error;
    private boolean completed;

    @Override
    public void onNext(ActivateCanonicalWorldInstanceResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      assertThat(value).isNull();
      assertThat(completed).isFalse();
      error = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
