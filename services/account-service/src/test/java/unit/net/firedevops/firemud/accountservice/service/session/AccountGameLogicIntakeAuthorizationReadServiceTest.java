package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

class AccountGameLogicIntakeAuthorizationReadServiceTest {
  private final AccountGameLogicIntakeAuthorizationRepository repository =
      mock(AccountGameLogicIntakeAuthorizationRepository.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final AccountGameLogicIntakeAuthorizationReadService service =
      new AccountGameLogicIntakeAuthorizationReadService(repository, transactions, "test");
  private final GameLogicIntakeAuthorizationBinding binding =
      mock(GameLogicIntakeAuthorizationBinding.class);
  private final GameLogicIntakeAuthorizationReadEvidence.Request request =
      mock(GameLogicIntakeAuthorizationReadEvidence.Request.class);

  @AfterEach
  void clearTransactionState() {
    org.springframework.transaction.support.TransactionSynchronizationManager.clear();
  }

  @Test
  void permanentOwnerRefusalsBecomeSanitizedFailedPrecondition() {
    when(request.targetNamespace()).thenReturn("test");
    when(request.binding()).thenReturn(binding);
    doThrow(new IllegalArgumentException("private-binding-mismatch"))
        .when(repository)
        .readHeld(binding, "test");
    var argumentFailure = asGameLogic(() -> service.requireHeld(request));
    assertSanitized(argumentFailure, Status.Code.FAILED_PRECONDITION, "private-binding-mismatch");

    doThrow(new IllegalStateException("private-owner-state"))
        .when(repository)
        .readHeld(binding, "test");
    var stateFailure = asGameLogic(() -> service.requireHeld(request));
    assertSanitized(stateFailure, Status.Code.FAILED_PRECONDITION, "private-owner-state");
  }

  @Test
  void databaseAndTransactionCreationFailuresRemainSanitizedUnavailable() {
    when(request.targetNamespace()).thenReturn("test");
    when(request.binding()).thenReturn(binding);
    doThrow(new DataAccessResourceFailureException("private-database-outage"))
        .when(repository)
        .readHeld(binding, "test");
    var databaseFailure = asGameLogic(() -> service.requireHeld(request));
    assertSanitized(databaseFailure, Status.Code.UNAVAILABLE, "private-database-outage");

    when(transactions.getTransaction(any(TransactionDefinition.class)))
        .thenThrow(new CannotCreateTransactionException("private-transaction-outage"));
    var transactionFailure = asGameLogic(() -> service.requireHeld(request));
    assertSanitized(transactionFailure, Status.Code.UNAVAILABLE, "private-transaction-outage");
  }

  @Test
  void preservesStatusRuntimeCodesWithSanitizedDescriptionAndNoCause() {
    when(request.targetNamespace()).thenReturn("test");
    when(request.binding()).thenReturn(binding);
    doThrow(
            Status.PERMISSION_DENIED
                .withDescription("private-owner-evidence")
                .withCause(new IllegalStateException("private-cause"))
                .asRuntimeException())
        .when(repository)
        .readHeld(binding, "test");
    var failure = asGameLogic(() -> service.requireHeld(request));
    assertSanitized(failure, Status.Code.PERMISSION_DENIED, "private-owner-evidence");
    assertThat(failure.toString()).doesNotContain("private-cause");
  }

  private static StatusRuntimeException asGameLogic(Runnable action) {
    var context =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/game-logic-service")
                    .orElseThrow());
    try {
      context.run(action);
      throw new AssertionError("Expected sanitized RPC status");
    } catch (StatusRuntimeException failure) {
      return failure;
    }
  }

  private static void assertSanitized(
      StatusRuntimeException failure, Status.Code code, String secret) {
    assertThat(failure.getStatus().getCode()).isEqualTo(code);
    assertThat(failure.getStatus().getDescription())
        .isEqualTo("Account intake authorization read denied or unavailable");
    assertThat(failure.toString()).doesNotContain(secret);
    assertThat(failure.getCause()).isNull();
  }
}
