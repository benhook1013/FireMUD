package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.account.v1.SettleOriginalStartSessionWorldParticipationRequest;
import net.firedevops.firemud.account.v1.SettleOriginalStartSessionWorldParticipationResponse;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository
    .ImmutableBindingConflictException;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository
    .RetainedEvidenceUnavailableException;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationSettlementGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationSettlementService;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationSettlementGrpcCodec;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationSettlementInput;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Receiver ordering and dispatch tests; synthetic peer context is not authenticated TLS proof. */
class AccountStartSessionWorldParticipationSettlementGrpcServiceTest {
  private static final String NAMESPACE = "world-runtime";
  private static final String WORLD_URI =
      "spiffe://firemud/ns/world-runtime/sa/world-management-service";

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesExactWorldPeerBeforeMalformedRequestParsing() {
    var owner = mock(AccountStartSessionWorldParticipationSettlementService.class);
    var receiver = new AccountStartSessionWorldParticipationSettlementGrpcService(owner, NAMESPACE);
    var malformed = SettleOriginalStartSessionWorldParticipationRequest.newBuilder().build();

    assertCode(Status.Code.UNAUTHENTICATED, invoke(receiver, null, malformed));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        invoke(
            receiver,
            identity("spiffe://firemud/ns/other-runtime/sa/world-management-service"),
            malformed));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        invoke(
            receiver, identity("spiffe://firemud/ns/world-runtime/sa/account-service"), malformed));
    SessionContext.setContext("authenticated-user", List.of(), Map.of());
    assertCode(Status.Code.PERMISSION_DENIED, invoke(receiver, identity(WORLD_URI), malformed));
    verifyNoInteractions(owner);
  }

  @Test
  void rejectsAmbientSqlAndClosedEnvelopeErrorsBeforeOwnerCall() {
    var owner = mock(AccountStartSessionWorldParticipationSettlementService.class);
    var receiver = new AccountStartSessionWorldParticipationSettlementGrpcService(owner, NAMESPACE);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertCode(Status.Code.FAILED_PRECONDITION, invoke(receiver, identity(WORLD_URI), null));
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    assertCode(Status.Code.FAILED_PRECONDITION, invoke(receiver, identity(WORLD_URI), null));
    TransactionSynchronizationManager.clearSynchronization();

    assertCode(Status.Code.INVALID_ARGUMENT, invoke(receiver, identity(WORLD_URI), null));
    var duplicate =
        SettleOriginalStartSessionWorldParticipationRequest.newBuilder()
            .addSchemaVersion(1)
            .addSchemaVersion(1)
            .build();
    assertCode(Status.Code.INVALID_ARGUMENT, invoke(receiver, identity(WORLD_URI), duplicate));
    var unknown =
        SettleOriginalStartSessionWorldParticipationRequest.newBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    assertCode(Status.Code.INVALID_ARGUMENT, invoke(receiver, identity(WORLD_URI), unknown));
    verifyNoInteractions(owner);
  }

  @Test
  void validClosedWorldRequestReachesOwnerAndUnresolvedFailureHasNoResponse() {
    var owner = mock(AccountStartSessionWorldParticipationSettlementService.class);
    UUID participationId = UUID.fromString("5bb41395-a183-4ce5-9343-cd0995ba3b48");
    when(owner.settle(participationId, 43L)).thenThrow(Status.UNAVAILABLE.asRuntimeException());
    var receiver = new AccountStartSessionWorldParticipationSettlementGrpcService(owner, NAMESPACE);
    var request =
        AccountStartSessionWorldParticipationSettlementGrpcCodec.toRequest(
            new AccountStartSessionWorldParticipationSettlementInput(
                NAMESPACE,
                UUID.fromString("10d5b75a-7c46-4890-834e-4c6547458d94"),
                participationId,
                43L));

    assertCode(Status.Code.UNAVAILABLE, invoke(receiver, identity(WORLD_URI), request));
    verify(owner).settle(participationId, 43L);
  }

  @Test
  void retainedEvidenceConflictAndUnavailableMapToFailedPrecondition() {
    UUID participationId = UUID.fromString("5bb41395-a183-4ce5-9343-cd0995ba3b48");
    var conflictOwner = mock(AccountStartSessionWorldParticipationSettlementService.class);
    when(conflictOwner.settle(participationId, 43L))
        .thenThrow(new ImmutableBindingConflictException("immutable binding conflict"));
    var unavailableOwner = mock(AccountStartSessionWorldParticipationSettlementService.class);
    when(unavailableOwner.settle(participationId, 43L))
        .thenThrow(new RetainedEvidenceUnavailableException("retained evidence unavailable"));
    var request = request(participationId, 43L);

    assertCode(
        Status.Code.FAILED_PRECONDITION,
        invoke(
            new AccountStartSessionWorldParticipationSettlementGrpcService(
                conflictOwner, NAMESPACE),
            identity(WORLD_URI),
            request));
    assertCode(
        Status.Code.FAILED_PRECONDITION,
        invoke(
            new AccountStartSessionWorldParticipationSettlementGrpcService(
                unavailableOwner, NAMESPACE),
            identity(WORLD_URI),
            request));
  }

  @Test
  void transactionContractFailureRemainsUnavailable() {
    UUID participationId = UUID.fromString("5bb41395-a183-4ce5-9343-cd0995ba3b48");
    var owner = mock(AccountStartSessionWorldParticipationSettlementService.class);
    when(owner.settle(participationId, 43L))
        .thenThrow(
            new IllegalStateException("Writable Account READ_COMMITTED transaction required"));

    assertCode(
        Status.Code.UNAVAILABLE,
        invoke(
            new AccountStartSessionWorldParticipationSettlementGrpcService(owner, NAMESPACE),
            identity(WORLD_URI),
            request(participationId, 43L)));
  }

  private static SettleOriginalStartSessionWorldParticipationRequest request(
      UUID participationId, long participationFence) {
    return AccountStartSessionWorldParticipationSettlementGrpcCodec.toRequest(
        new AccountStartSessionWorldParticipationSettlementInput(
            NAMESPACE,
            UUID.fromString("10d5b75a-7c46-4890-834e-4c6547458d94"),
            participationId,
            participationFence));
  }

  private static Invocation invoke(
      AccountStartSessionWorldParticipationSettlementGrpcService receiver,
      GrpcPeerIdentity peer,
      SettleOriginalStartSessionWorldParticipationRequest request) {
    var result = new Invocation();
    Context context = Context.current();
    if (peer != null) {
      context = context.withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    }
    Context previous = context.attach();
    try {
      receiver.settleOriginalStartSessionWorldParticipation(request, result);
    } finally {
      context.detach(previous);
    }
    return result;
  }

  private static GrpcPeerIdentity identity(String uri) {
    return GrpcPeerIdentity.parseUri(uri).orElseThrow();
  }

  private static void assertCode(Status.Code expected, Invocation invocation) {
    assertThat(invocation.error).isNotNull();
    assertThat(invocation.error.statusRuntimeException()).isTrue();
    assertThat(invocation.error.code()).isEqualTo(expected);
    assertThat(invocation.response).isNull();
    assertThat(invocation.completed).isFalse();
  }

  private record ObservedError(boolean statusRuntimeException, Status.Code code) {}

  private static final class Invocation
      implements StreamObserver<SettleOriginalStartSessionWorldParticipationResponse> {
    private SettleOriginalStartSessionWorldParticipationResponse response;
    private ObservedError error;
    private boolean completed;

    @Override
    public void onNext(SettleOriginalStartSessionWorldParticipationResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable failure) {
      error =
          new ObservedError(
              failure instanceof StatusRuntimeException, Status.fromThrowable(failure).getCode());
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
