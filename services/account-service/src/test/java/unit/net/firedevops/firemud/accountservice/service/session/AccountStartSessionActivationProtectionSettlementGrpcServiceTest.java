package net.firedevops.firemud.accountservice.service.session;

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
import java.util.UUID;
import net.firedevops.firemud.account.v1.SettleOriginalStartSessionActivationProtectionRequest;
import net.firedevops.firemud.account.v1.SettleOriginalStartSessionActivationProtectionResponse;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionSettlementGrpcCodec;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionSettlementInput;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Receiver ordering and dispatch tests; synthetic peer context is not authenticated TLS proof. */
class AccountStartSessionActivationProtectionSettlementGrpcServiceTest {
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
    var owner = mock(AccountStartSessionActivationProtectionSettlementService.class);
    var receiver =
        new AccountStartSessionActivationProtectionSettlementGrpcService(owner, NAMESPACE);
    var malformed = SettleOriginalStartSessionActivationProtectionRequest.newBuilder().build();

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
    SessionContext.setContext("authenticated-user", java.util.List.of(), java.util.Map.of());
    assertCode(Status.Code.PERMISSION_DENIED, invoke(receiver, identity(WORLD_URI), malformed));
    verifyNoInteractions(owner);
  }

  @Test
  void rejectsAmbientSqlAndClosedEnvelopeErrorsBeforeOwnerCall() {
    var owner = mock(AccountStartSessionActivationProtectionSettlementService.class);
    var receiver =
        new AccountStartSessionActivationProtectionSettlementGrpcService(owner, NAMESPACE);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertCode(Status.Code.FAILED_PRECONDITION, invoke(receiver, identity(WORLD_URI), null));
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    assertCode(Status.Code.FAILED_PRECONDITION, invoke(receiver, identity(WORLD_URI), null));
    TransactionSynchronizationManager.clearSynchronization();

    assertCode(Status.Code.INVALID_ARGUMENT, invoke(receiver, identity(WORLD_URI), null));
    var duplicate =
        SettleOriginalStartSessionActivationProtectionRequest.newBuilder()
            .addSchemaVersion(1)
            .addSchemaVersion(1)
            .build();
    assertCode(Status.Code.INVALID_ARGUMENT, invoke(receiver, identity(WORLD_URI), duplicate));
    var unknown =
        SettleOriginalStartSessionActivationProtectionRequest.newBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    assertCode(Status.Code.INVALID_ARGUMENT, invoke(receiver, identity(WORLD_URI), unknown));
    verifyNoInteractions(owner);
  }

  @Test
  void passesOnlyTheExactProtectionLookupIdentityToTheOwner() {
    var owner = mock(AccountStartSessionActivationProtectionSettlementService.class);
    UUID protectionId = UUID.fromString("61380051-04b0-48ef-95c4-31f6acb80884");
    when(owner.settle(protectionId, 37L)).thenThrow(Status.UNAVAILABLE.asRuntimeException());
    var receiver =
        new AccountStartSessionActivationProtectionSettlementGrpcService(owner, NAMESPACE);
    var request =
        AccountStartSessionActivationProtectionSettlementGrpcCodec.toRequest(
            new AccountStartSessionActivationProtectionSettlementInput(
                NAMESPACE,
                UUID.fromString("7bd3571a-e943-45e2-a266-4c99db2bd836"),
                protectionId,
                37L));

    var invocation = invoke(receiver, identity(WORLD_URI), request);
    assertCode(Status.Code.UNAVAILABLE, invocation);
    verify(owner).settle(protectionId, 37L);
  }

  private static Invocation invoke(
      AccountStartSessionActivationProtectionSettlementGrpcService receiver,
      GrpcPeerIdentity peer,
      SettleOriginalStartSessionActivationProtectionRequest request) {
    var result = new Invocation();
    Context context = Context.current();
    if (peer != null) {
      context = context.withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    }
    Context previous = context.attach();
    try {
      receiver.settleOriginalStartSessionActivationProtection(request, result);
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
      implements StreamObserver<SettleOriginalStartSessionActivationProtectionResponse> {
    private SettleOriginalStartSessionActivationProtectionResponse response;
    private ObservedError error;
    private boolean completed;

    @Override
    public void onNext(SettleOriginalStartSessionActivationProtectionResponse value) {
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
