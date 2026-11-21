package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.account.startsession.*;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
import net.firedevops.firemud.worldmanagement.tenant.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled owner-client composition only, not transport or physical restart proof. */
class WorldOriginalOperationSettlementRecoveryTest {
  private final WorldCanonicalInstancePreparationService preparation =
      mock(WorldCanonicalInstancePreparationService.class);
  private final AccountStartSessionWorldParticipationSettlementClient account =
      mock(AccountStartSessionWorldParticipationSettlementClient.class);
  private final WorldCanonicalInstanceExecutionIdentity original =
      mock(WorldCanonicalInstanceExecutionIdentity.class);
  private final UUID requestId = UUID.randomUUID();
  private final UUID participation = UUID.randomUUID();
  private final WorldOriginalOperationSettlementRecovery recovery =
      new WorldOriginalOperationSettlementRecovery(preparation, account);

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  private void identity() {
    when(original.targetNamespace()).thenReturn("test");
    when(original.accountWorldParticipationId()).thenReturn(participation);
    when(original.accountWorldParticipationFence()).thenReturn(7L);
  }

  private WorldStartSessionExecutionTerminal terminal(byte value) {
    var terminal = mock(WorldStartSessionExecutionTerminal.class);
    when(terminal.canonicalBytes()).thenReturn(new byte[] {value});
    return terminal;
  }

  @Test
  void unresolvedOriginalDoesNotAttemptSettlement() {
    identity();
    when(preparation.recoverExactTerminal(original)).thenReturn(Optional.empty());
    assertThat(recovery.recover(original, requestId)).isEmpty();
    verifyNoInteractions(account);
  }

  @Test
  void exactTerminalSettlesWithOriginalIdentityAndRetriesSameCorrelation() {
    identity();
    var terminal = terminal((byte) 1);
    var receipt =
        new AccountStartSessionWorldParticipationSettlementEvidence(
            requestId, terminal, Instant.parse("2026-11-19T00:00:00Z"));
    when(preparation.recoverExactTerminal(original)).thenReturn(Optional.of(terminal));
    var request =
        new AccountStartSessionWorldParticipationSettlementInput(
            "test", requestId, participation, 7L);
    when(account.settle(request)).thenReturn(receipt);
    assertThat(recovery.recover(original, requestId)).contains(receipt);
    assertThat(recovery.recover(original, requestId)).contains(receipt);
    verify(account, times(2)).settle(request);
    verify(preparation, times(2)).recoverExactTerminal(original);
    verifyNoMoreInteractions(preparation, account);
  }

  @Test
  void changedTerminalOrCorrelationCannotClaimRecoveredSettlement() {
    identity();
    var terminal = terminal((byte) 1);
    when(preparation.recoverExactTerminal(original)).thenReturn(Optional.of(terminal));
    var changedTerminal = terminal((byte) 2);
    when(account.settle(any()))
        .thenReturn(
            new AccountStartSessionWorldParticipationSettlementEvidence(
                requestId, changedTerminal, Instant.EPOCH));
    assertThatThrownBy(() -> recovery.recover(original, requestId))
        .isInstanceOf(IllegalStateException.class);
    when(account.settle(any()))
        .thenReturn(
            new AccountStartSessionWorldParticipationSettlementEvidence(
                UUID.randomUUID(), terminal, Instant.EPOCH));
    assertThatThrownBy(() -> recovery.recover(original, requestId))
        .isInstanceOf(IllegalStateException.class);
    when(account.settle(any())).thenReturn(null);
    assertThatThrownBy(() -> recovery.recover(original, requestId))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void ownerReadFailureNeverInvokesSettlement() {
    identity();
    when(preparation.recoverExactTerminal(original))
        .thenThrow(new IllegalStateException("unavailable"));
    assertThatThrownBy(() -> recovery.recover(original, requestId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("unavailable");
    verifyNoInteractions(account);
  }

  @Test
  void lostSettlementReplyPropagatesWithoutCreatingAnAlternateAttempt() {
    identity();
    var retained = terminal((byte) 1);
    when(preparation.recoverExactTerminal(original)).thenReturn(Optional.of(retained));
    when(account.settle(any())).thenThrow(new IllegalStateException("reply lost"));
    assertThatThrownBy(() -> recovery.recover(original, requestId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("reply lost");
    verify(preparation).recoverExactTerminal(original);
    verifyNoMoreInteractions(preparation);
  }

  @Test
  void ambientTransactionRefusesBeforeAnyOwnerRead() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> recovery.recover(original, requestId))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(original, preparation, account);
  }

  @Test
  void nilCorrelationRefusesBeforeOwnerRead() {
    identity();
    assertThatThrownBy(() -> recovery.recover(original, new UUID(0, 0)))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(preparation, account);
  }
}
