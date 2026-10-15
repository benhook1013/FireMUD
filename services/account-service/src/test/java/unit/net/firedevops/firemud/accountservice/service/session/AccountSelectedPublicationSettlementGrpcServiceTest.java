package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.account.v1.SettleSelectedPublicationRequest;
import net.firedevops.firemud.account.v1.SettleSelectedPublicationResponse;
import net.firedevops.firemud.accountservice.service.session.AccountSelectedDraftPublicationSettlementService;
import net.firedevops.firemud.accountservice.service.session.AccountSelectedPublicationSettlementGrpcService;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Receiver identity/ordering units; synthetic peer context is not authenticated TLS proof. */
class AccountSelectedPublicationSettlementGrpcServiceTest {
  private static final String NAMESPACE = "publication-runtime";
  private static final String GAME_DESIGN_URI =
      "spiffe://firemud/ns/publication-runtime/sa/game-design-service";

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesExactGameDesignBeforeMalformedRequestOrOwnerAccess() {
    var owner = mock(AccountSelectedDraftPublicationSettlementService.class);
    var receiver = new AccountSelectedPublicationSettlementGrpcService(owner, NAMESPACE);
    var malformed = SettleSelectedPublicationRequest.getDefaultInstance();

    assertCode(Status.Code.UNAUTHENTICATED, invoke(receiver, null, malformed));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        invoke(
            receiver,
            identity("spiffe://firemud/ns/other-runtime/sa/game-design-service"),
            malformed));
    for (String workload :
        List.of("account-service", "world-management-service", "game-session-service")) {
      assertCode(
          Status.Code.PERMISSION_DENIED,
          invoke(
              receiver,
              identity("spiffe://firemud/ns/publication-runtime/sa/" + workload),
              malformed));
    }
    SessionContext.setContext("test-user", List.of(), Map.of());
    assertCode(
        Status.Code.PERMISSION_DENIED, invoke(receiver, identity(GAME_DESIGN_URI), malformed));
    verifyNoInteractions(owner);
  }

  @Test
  void deniesAmbientSqlAndClosedEnvelopeErrorsBeforeOwnerCall() {
    var owner = mock(AccountSelectedDraftPublicationSettlementService.class);
    var receiver = new AccountSelectedPublicationSettlementGrpcService(owner, NAMESPACE);
    var peer = identity(GAME_DESIGN_URI);

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertCode(Status.Code.FAILED_PRECONDITION, invoke(receiver, peer, null));
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    assertCode(Status.Code.FAILED_PRECONDITION, invoke(receiver, peer, null));
    TransactionSynchronizationManager.clearSynchronization();

    assertCode(Status.Code.INVALID_ARGUMENT, invoke(receiver, peer, null));
    var unknown =
        SettleSelectedPublicationRequest.newBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    assertCode(Status.Code.INVALID_ARGUMENT, invoke(receiver, peer, unknown));
    verifyNoInteractions(owner);
  }

  private static Invocation invoke(
      AccountSelectedPublicationSettlementGrpcService receiver,
      GrpcPeerIdentity peer,
      SettleSelectedPublicationRequest request) {
    var result = new Invocation();
    Context context = Context.current();
    if (peer != null) context = context.withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      receiver.settleSelectedPublication(request, result);
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
      implements StreamObserver<SettleSelectedPublicationResponse> {
    private SettleSelectedPublicationResponse response;
    private ObservedError error;
    private boolean completed;

    @Override
    public void onNext(SettleSelectedPublicationResponse value) {
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
