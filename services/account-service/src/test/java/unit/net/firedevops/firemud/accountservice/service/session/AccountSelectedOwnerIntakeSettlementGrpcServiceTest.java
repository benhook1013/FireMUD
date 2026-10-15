package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.SelectedOwnerIntakeSettlementRequest;
import net.firedevops.firemud.account.v1.SelectedOwnerIntakeSettlementResponse;
import net.firedevops.firemud.common.account.sourceintake.AccountSelectedOwnerIntakeSettlementReceipt;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSettlementEvidence;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSettlementGrpcCodec;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Handler boundary tests only; mocks do not prove authenticated mTLS or physical settlement. */
class AccountSelectedOwnerIntakeSettlementGrpcServiceTest {
  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesSameNamespaceGameDesignAndRejectsEndUserBeforeDecode() {
    var owner = mock(AccountSelectedOwnerIntakeSettlementService.class);
    var service = new AccountSelectedOwnerIntakeSettlementGrpcService(owner, "test");
    var malformed = SelectedOwnerIntakeSettlementRequest.getDefaultInstance();

    assertThat(invoke(service, malformed).code()).isEqualTo(Status.Code.UNAUTHENTICATED);
    for (String workload :
        List.of(
            "account-service",
            "automation-scripting-service",
            "entity-management-service",
            "game-logic-service",
            "world-management-service")) {
      assertThat(peer("test", workload, () -> invoke(service, malformed)).code())
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    }
    assertThat(peer("other", "game-design-service", () -> invoke(service, malformed)).code())
        .isEqualTo(Status.Code.PERMISSION_DENIED);

    SessionContext.setContext("44", List.of(), java.util.Map.of());
    try {
      assertThat(peer("test", "game-design-service", () -> invoke(service, malformed)).code())
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    } finally {
      SessionContext.clear();
    }
    assertThat(peer("test", "game-design-service", () -> invoke(service, malformed)).code())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(owner);
  }

  @Test
  void rejectsAmbientSqlUnknownFieldsAndChangedNamespaceBeforeOwnerAccess() {
    var owner = mock(AccountSelectedOwnerIntakeSettlementService.class);
    var service = new AccountSelectedOwnerIntakeSettlementGrpcService(owner, "test");
    var binding =
        AccountSelectedOwnerIntakeAuthorizationReadServiceTest.binding(Owner.AUTOMATION_SCRIPTING);
    var valid =
        SelectedOwnerIntakeSettlementGrpcCodec.toRequest(
            SelectedOwnerIntakeSettlementEvidence.Request.create("test", binding));

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThat(peer("test", "game-design-service", () -> invoke(service, valid)).code())
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    assertThat(peer("test", "game-design-service", () -> invoke(service, valid)).code())
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
    TransactionSynchronizationManager.clearSynchronization();

    var unknown =
        valid.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    assertThat(peer("test", "game-design-service", () -> invoke(service, unknown)).code())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            peer(
                    "test",
                    "game-design-service",
                    () -> invoke(service, valid.toBuilder().setTargetNamespace("other").build()))
                .code())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(owner);
  }

  @Test
  void returnsFullStoredReceiptAndExactEchoForNewCorrelationRetry() {
    var owner = mock(AccountSelectedOwnerIntakeSettlementService.class);
    var service = new AccountSelectedOwnerIntakeSettlementGrpcService(owner, "test");
    var binding =
        AccountSelectedOwnerIntakeAuthorizationReadServiceTest.binding(Owner.AUTOMATION_SCRIPTING);
    var firstRequest = SelectedOwnerIntakeSettlementEvidence.Request.create("test", binding);
    var retryRequest = SelectedOwnerIntakeSettlementEvidence.Request.create("test", binding);
    var receiptBytes = new byte[8192];
    for (int index = 0; index < receiptBytes.length; index++) {
      receiptBytes[index] = (byte) (index * 17);
    }
    var receipt = receipt(binding, receiptBytes);
    when(owner.settle(any())).thenReturn(receipt);

    var firstWire = SelectedOwnerIntakeSettlementGrpcCodec.toRequest(firstRequest);
    var first = peer("test", "game-design-service", () -> invoke(service, firstWire));
    assertThat(first.error()).isNull();
    assertThat(first.response().getRequest()).isEqualTo(firstWire);
    assertThat(first.response().getSettlementReceipt().toByteArray()).containsExactly(receiptBytes);
    assertThat(first.response().getSettlementReceiptDigest())
        .isEqualTo(DraftAuthorizationFenceBinding.digest(receiptBytes));

    var retryWire = SelectedOwnerIntakeSettlementGrpcCodec.toRequest(retryRequest);
    var retry = peer("test", "game-design-service", () -> invoke(service, retryWire));
    assertThat(retry.error()).isNull();
    assertThat(retry.response().getRequest()).isEqualTo(retryWire);
    assertThat(retry.response().getRequest()).isNotEqualTo(first.response().getRequest());
    assertThat(retry.response().getSettlementReceipt())
        .isEqualTo(first.response().getSettlementReceipt());
    var bindings = ArgumentCaptor.forClass(SelectedOwnerIntakeAuthorizationBinding.class);
    verify(owner, times(2)).settle(bindings.capture());
    assertThat(bindings.getAllValues())
        .allSatisfy(
            retained ->
                assertThat(retained.canonicalBytes()).containsExactly(binding.canonicalBytes()));
  }

  @Test
  void sanitizesOwnerFailureAndRejectsReceiptSubstitution() {
    var owner = mock(AccountSelectedOwnerIntakeSettlementService.class);
    var service = new AccountSelectedOwnerIntakeSettlementGrpcService(owner, "test");
    var binding =
        AccountSelectedOwnerIntakeAuthorizationReadServiceTest.binding(Owner.AUTOMATION_SCRIPTING);
    var wire =
        SelectedOwnerIntakeSettlementGrpcCodec.toRequest(
            SelectedOwnerIntakeSettlementEvidence.Request.create("test", binding));

    doThrow(
            Status.FAILED_PRECONDITION
                .withDescription("private-owner-settlement-evidence")
                .asRuntimeException())
        .when(owner)
        .settle(any());
    var failure = peer("test", "game-design-service", () -> invoke(service, wire));
    assertThat(failure.code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(failure.error().toString()).doesNotContain("private-owner-settlement-evidence");

    var substituted =
        receipt(
            AccountSelectedOwnerIntakeAuthorizationReadServiceTest.binding(Owner.ENTITY_MANAGEMENT),
            new byte[] {1, 2, 3});
    doReturn(substituted).when(owner).settle(any());
    var mismatch = peer("test", "game-design-service", () -> invoke(service, wire));
    assertThat(mismatch.code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(mismatch.error().toString()).doesNotContain("authorization");
  }

  private static AccountSelectedOwnerIntakeSettlementReceipt receipt(
      SelectedOwnerIntakeAuthorizationBinding binding, byte[] bytes) {
    var receipt = mock(AccountSelectedOwnerIntakeSettlementReceipt.class);
    when(receipt.authorizationBinding()).thenReturn(binding);
    when(receipt.targetNamespace()).thenReturn(binding.targetNamespace());
    when(receipt.canonicalBytes()).thenReturn(bytes.clone());
    when(receipt.digest()).thenReturn(DraftAuthorizationFenceBinding.digest(bytes));
    return receipt;
  }

  private record Result(SelectedOwnerIntakeSettlementResponse response, Throwable error) {
    Status.Code code() {
      return Status.fromThrowable(error).getCode();
    }
  }

  private static Result invoke(
      AccountSelectedOwnerIntakeSettlementGrpcService service,
      SelectedOwnerIntakeSettlementRequest request) {
    var response = new AtomicReference<SelectedOwnerIntakeSettlementResponse>();
    var error = new AtomicReference<Throwable>();
    service.settleSelectedOwnerIntake(
        request,
        new StreamObserver<>() {
          @Override
          public void onNext(SelectedOwnerIntakeSettlementResponse value) {
            response.set(value);
          }

          @Override
          public void onError(Throwable value) {
            error.set(value);
          }

          @Override
          public void onCompleted() {}
        });
    return new Result(response.get(), error.get());
  }

  private static <T> T peer(
      String namespace, String workload, java.util.function.Supplier<T> action) {
    var context =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + workload)
                    .orElseThrow());
    var previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }
}
