package unit.net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeGrpcCodec;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository.OriginalOutcomeBinding;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository.OriginalOutcomeSnapshot;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository.OutcomeStatus;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.service.impl.OriginalTenantCreationOutcomeReadGrpcService;
import net.firedevops.firemud.gamedesign.v1.OriginalTenantCreationOutcomeReadServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ResolveOriginalTenantCreationOutcomeRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveOriginalTenantCreationOutcomeResponse;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class OriginalTenantCreationOutcomeReadGrpcServiceTest {
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String GAME_SESSION_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String OTHER_ACCOUNT_URI = "spiffe://firemud/ns/other/sa/account-service";
  private static final UUID READ_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID REQUEST_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID ACCOUNT_OPERATION_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID ACCOUNT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID CREATION_OPERATION_ID =
      UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID TENANT_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final String CREATION_DIGEST = "sha256:" + "a".repeat(64);
  private static final String ACCOUNT_DIGEST = "sha256:" + "b".repeat(64);

  private final GameTenantCreationOriginalOutcomeRepository outcomeRepository =
      mock(GameTenantCreationOriginalOutcomeRepository.class);
  private final GameTenantCreationRepository creationRepository =
      mock(GameTenantCreationRepository.class);
  private final OriginalTenantCreationOutcomeReadGrpcService service =
      new OriginalTenantCreationOutcomeReadGrpcService(
          outcomeRepository, creationRepository, "test");

  @Test
  void serviceIsDistinctAndResolvesExactCreatedReceiptOnlyForAccountPeer() {
    assertThat(OriginalTenantCreationOutcomeReadServiceGrpc.getServiceDescriptor().getName())
        .isEqualTo("gamedesign.v1.OriginalTenantCreationOutcomeReadService");
    assertThat(
            TenantIdentityServiceGrpc.getServiceDescriptor().getMethods().stream()
                .map(method -> method.getFullMethodName())
                .toList())
        .doesNotContain("gamedesign.v1.TenantIdentityService/ResolveOriginalTenantCreationOutcome");
    FreshTenantCreationEvidence receipt = receipt();
    when(outcomeRepository.readCommitted(binding()))
        .thenReturn(Optional.of(snapshot(OutcomeStatus.CREATED)));
    when(creationRepository.read(REQUEST_ID, "test")).thenReturn(Optional.of(receipt));

    ReadObserver observer = call(request(), ACCOUNT_URI);

    assertThat(observer.failure).isNull();
    assertThat(observer.completed).isTrue();
    assertThat(
            GameTenantCreationOriginalOutcomeGrpcCodec.fromReadResponse(
                readRequest(), observer.response))
        .isEqualTo(
            new GameTenantCreationOriginalOutcomeEvidence(
                1,
                new GameTenantCreationOriginalOutcomeEvidence.Binding(
                    "test",
                    REQUEST_ID,
                    ACCOUNT_OPERATION_ID,
                    ACCOUNT_ID,
                    CREATION_DIGEST,
                    ACCOUNT_DIGEST),
                GameTenantCreationOriginalOutcomeEvidence.Outcome.CREATED,
                Optional.of(receipt)));
    verify(outcomeRepository).readCommitted(binding());
    verify(creationRepository).read(REQUEST_ID, "test");
    org.mockito.Mockito.verifyNoMoreInteractions(outcomeRepository, creationRepository);
  }

  @Test
  void returnsNotCreatedOnlyFromDurableTombstoneAndReturnsNotFoundForMissingRow() {
    when(outcomeRepository.readCommitted(binding()))
        .thenReturn(Optional.of(snapshot(OutcomeStatus.NOT_CREATED)))
        .thenReturn(Optional.empty());
    when(creationRepository.read(REQUEST_ID, "test")).thenReturn(Optional.empty());

    ReadObserver tombstone = call(request(), ACCOUNT_URI);
    ReadObserver missing = call(request(), ACCOUNT_URI);

    assertThat(tombstone.failure).isNull();
    assertThat(tombstone.response.getOutcomeValue()).isEqualTo(2);
    assertThat(tombstone.response.hasCreatedReceipt()).isFalse();
    assertThat(missing.failure).isEqualTo(Status.Code.NOT_FOUND);
    assertThat(missing.response).isNull();
    verify(outcomeRepository, org.mockito.Mockito.times(2)).readCommitted(binding());
    verify(creationRepository).read(REQUEST_ID, "test");
    org.mockito.Mockito.verifyNoMoreInteractions(outcomeRepository, creationRepository);
  }

  @Test
  void refusesPendingMismatchedAndInconsistentOutcomes() {
    OriginalOutcomeBinding changedBinding =
        new OriginalOutcomeBinding(
            "test",
            REQUEST_ID,
            ACCOUNT_OPERATION_ID,
            UUID.fromString("77777777-7777-4777-8777-777777777777"),
            CREATION_DIGEST,
            ACCOUNT_DIGEST);
    when(outcomeRepository.readCommitted(binding()))
        .thenReturn(
            Optional.of(new OriginalOutcomeSnapshot(binding(), OutcomeStatus.PENDING, null, null)))
        .thenReturn(
            Optional.of(
                new OriginalOutcomeSnapshot(changedBinding, OutcomeStatus.NOT_CREATED, null, null)))
        .thenThrow(new IllegalStateException("committed PENDING row"));

    for (int index = 0; index < 3; index++) {
      assertThat(call(request(), ACCOUNT_URI).failure).isEqualTo(Status.Code.FAILED_PRECONDITION);
    }
    verifyNoInteractions(creationRepository);
  }

  @Test
  void refusesCreatedOutcomeWhenTheActualV30ReceiptIsAbsentOrSubstituted() {
    when(outcomeRepository.readCommitted(binding()))
        .thenReturn(Optional.of(snapshot(OutcomeStatus.CREATED)))
        .thenReturn(Optional.of(snapshot(OutcomeStatus.CREATED)));
    FreshTenantCreationEvidence substituted =
        new FreshTenantCreationEvidence(
            1,
            "test",
            REQUEST_ID,
            UUID.fromString("77777777-7777-4777-8777-777777777777"),
            CREATION_DIGEST,
            TENANT_ID,
            91L,
            "fresh-tenant-01",
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                "test",
                REQUEST_ID,
                UUID.fromString("77777777-7777-4777-8777-777777777777"),
                CREATION_DIGEST,
                TENANT_ID,
                91L,
                "fresh-tenant-01",
                "NEW_GAME_ROW"));
    when(creationRepository.read(REQUEST_ID, "test"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(substituted));

    assertThat(call(request(), ACCOUNT_URI).failure).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(call(request(), ACCOUNT_URI).failure).isEqualTo(Status.Code.FAILED_PRECONDITION);
  }

  @Test
  void refusesNotCreatedTombstoneIfAContradictoryV30ReceiptIsPresent() {
    when(outcomeRepository.readCommitted(binding()))
        .thenReturn(Optional.of(snapshot(OutcomeStatus.NOT_CREATED)));
    when(creationRepository.read(REQUEST_ID, "test")).thenReturn(Optional.of(receipt()));

    ReadObserver observer = call(request(), ACCOUNT_URI);

    assertThat(observer.failure).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(observer.response).isNull();
  }

  @Test
  void rejectsMalformedRequestsBeforeStorageReads() {
    var valid = request();
    for (var malformed :
        new ResolveOriginalTenantCreationOutcomeRequest[] {
          valid.toBuilder().setSchemaVersion(2).build(),
          valid.toBuilder().setCreationRequestId("not-a-uuid").build(),
          valid.toBuilder().setAccountAuthorizationDigest("bad").build(),
          valid.toBuilder().setTargetNamespace("other").build(),
          valid.toBuilder().setUnknownFields(unknownField()).build()
        }) {
      assertThat(call(malformed, ACCOUNT_URI).failure).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
    verifyNoInteractions(outcomeRepository, creationRepository);
  }

  @Test
  void deniesWrongPeerCrossNamespaceAndAuthenticatedPlayerContextBeforeStorageReads() {
    for (String peer :
        new String[] {
          null,
          GAME_SESSION_URI,
          OTHER_ACCOUNT_URI,
          "spiffe://firemud/ns/test/sa/entity-management-service"
        }) {
      assertThat(call(request(), peer).failure).isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    SessionContext.setContext("account-uuid", List.of("player"), Map.of());
    try {
      assertThat(call(request(), ACCOUNT_URI).failure).isEqualTo(Status.Code.PERMISSION_DENIED);
    } finally {
      SessionContext.clear();
    }
    verifyNoInteractions(outcomeRepository, creationRepository);
  }

  @Test
  void classifiesStorageOutageAsUnavailableAndDoesNotReturnPartialEvidence() {
    when(outcomeRepository.readCommitted(binding()))
        .thenThrow(new DataAccessResourceFailureException("offline"))
        .thenThrow(
            new DataAccessException(
                "connection dropped", new java.sql.SQLException("offline", "08006")));

    for (int index = 0; index < 2; index++) {
      ReadObserver observer = call(request(), ACCOUNT_URI);
      assertThat(observer.failure).isEqualTo(Status.Code.UNAVAILABLE);
      assertThat(observer.response).isNull();
      assertThat(observer.completed).isFalse();
    }
  }

  private ReadObserver call(ResolveOriginalTenantCreationOutcomeRequest request, String peerUri) {
    ReadObserver observer = new ReadObserver();
    Runnable invocation = () -> service.resolveOriginalTenantCreationOutcome(request, observer);
    if (peerUri == null) {
      invocation.run();
    } else {
      GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(peerUri).orElseThrow();
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(invocation);
    }
    return observer;
  }

  private static ResolveOriginalTenantCreationOutcomeRequest request() {
    return GameTenantCreationOriginalOutcomeGrpcCodec.toReadRequest(readRequest());
  }

  private static GameTenantCreationOriginalOutcomeGrpcCodec.ReadRequest readRequest() {
    OriginalOutcomeBinding original = binding();
    return new GameTenantCreationOriginalOutcomeGrpcCodec.ReadRequest(
        READ_ID,
        new GameTenantCreationOriginalOutcomeEvidence.Binding(
            original.targetNamespace(),
            original.creationRequestId(),
            original.accountAuthorizationOperationId(),
            original.initiatingAccountId(),
            original.creationRequestDigest(),
            original.accountAuthorizationDigest()));
  }

  private static OriginalOutcomeBinding binding() {
    return new OriginalOutcomeBinding(
        "test", REQUEST_ID, ACCOUNT_OPERATION_ID, ACCOUNT_ID, CREATION_DIGEST, ACCOUNT_DIGEST);
  }

  private static OriginalOutcomeSnapshot snapshot(OutcomeStatus status) {
    return new OriginalOutcomeSnapshot(
        binding(),
        status,
        status == OutcomeStatus.CREATED ? CREATION_OPERATION_ID : null,
        status == OutcomeStatus.CREATED ? receipt().evidenceDigest() : null);
  }

  private static FreshTenantCreationEvidence receipt() {
    return new FreshTenantCreationEvidence(
        1,
        "test",
        REQUEST_ID,
        CREATION_OPERATION_ID,
        CREATION_DIGEST,
        TENANT_ID,
        91L,
        "fresh-tenant-01",
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            "test",
            REQUEST_ID,
            CREATION_OPERATION_ID,
            CREATION_DIGEST,
            TENANT_ID,
            91L,
            "fresh-tenant-01",
            "NEW_GAME_ROW"));
  }

  private static UnknownFieldSet unknownField() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }

  private static final class ReadObserver
      implements StreamObserver<ResolveOriginalTenantCreationOutcomeResponse> {
    private ResolveOriginalTenantCreationOutcomeResponse response;
    private Status.Code failure;
    private boolean completed;

    @Override
    public void onNext(ResolveOriginalTenantCreationOutcomeResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable throwable) {
      failure = Status.fromThrowable(throwable).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
