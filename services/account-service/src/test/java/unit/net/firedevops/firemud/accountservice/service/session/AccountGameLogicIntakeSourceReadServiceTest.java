package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import io.grpc.StatusRuntimeException;
import java.time.Clock;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class AccountGameLogicIntakeSourceReadServiceTest {
  @Test
  void ownerAuthenticatesPeerBeforeInspectingEitherProofOrAccessingStorage() {
    var repository = mock(AccountGameLogicIntakeAuthorizationRepository.class);
    var registry = mock(AccountControlUiCoordination.class);
    var transactions = mock(PlatformTransactionManager.class);
    var service =
        new AccountGameLogicIntakeSourceReadService(
            repository, registry, transactions, Clock.systemUTC(), "test");
    assertThatThrownBy(() -> service.readSourceScope(null, null, null))
        .isInstanceOf(StatusRuntimeException.class);
    assertThatThrownBy(() -> service.readFinalizedIntake(null, null, null))
        .isInstanceOf(StatusRuntimeException.class);
    var wrongPeer =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/game-logic-service")
                    .orElseThrow());
    var previous = wrongPeer.attach();
    try {
      assertThatThrownBy(() -> service.readSourceScope(null, null, null))
          .isInstanceOf(StatusRuntimeException.class);
      assertThatThrownBy(() -> service.readFinalizedIntake(null, null, null))
          .isInstanceOf(StatusRuntimeException.class);
    } finally {
      wrongPeer.detach(previous);
    }
    verifyNoInteractions(repository, registry, transactions);
  }

  @Test
  void reservationFinalizationAndRecoveryCannotRunOutsideTheAccountOwnerTransaction() {
    var dsl = mock(DSLContext.class);
    var repository = new AccountGameLogicIntakeAuthorizationRepository(dsl);
    assertThatThrownBy(() -> repository.reserveSourceRead(null, null, null, null, () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account owner");
    assertThatThrownBy(() -> repository.finalizeSourceRead(null, null, null, () -> {}))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> repository.recoverSourceRead(null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> repository.abortSourceRead(null))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(dsl);
  }
}
