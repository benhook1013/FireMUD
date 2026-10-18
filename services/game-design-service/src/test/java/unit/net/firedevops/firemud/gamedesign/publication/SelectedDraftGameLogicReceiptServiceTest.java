package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeSettlementEvidence;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeSettlementReadClient;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeSettlementReadEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeOperation;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeTerminal;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeRetainClient;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeRetainEvidence;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SelectedDraftGameLogicReceiptServiceTest {
  @Test
  void ownerCallsOutsideSqlThenStoresExactAccountReceiptAndRecoversWithoutRpc() {
    var value = SelectedDraftGameLogicReceiptTest.fixture();
    var repository = mock(SelectedDraftGameLogicReceiptRepository.class);
    var transactions = mock(PlatformTransactionManager.class);
    var gl = mock(GameLogicIntakeRetainClient.class);
    var account = mock(AccountGameLogicIntakeSettlementReadClient.class);
    when(repository.read(any(), any())).thenReturn(Optional.empty());
    when(repository.retain(any())).thenReturn(value);
    when(gl.retain(any()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return new GameLogicIntakeRetainEvidence(
                  call.getArgument(0), value.receipt().terminal());
            });
    when(account.read(any()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return new AccountGameLogicIntakeSettlementReadEvidence(
                  call.getArgument(0), value.receipt());
            });
    var service =
        new SelectedDraftGameLogicReceiptService(
            repository,
            ignored -> Optional.of(value.selection()),
            transactions,
            gl,
            account,
            "test");
    assertThat(service.retain(value.selection(), value.authorization())).isSameAs(value);
    var order = inOrder(repository, gl, account, transactions);
    order.verify(repository).read(any(), any());
    order.verify(gl).retain(any());
    order.verify(account).read(any());
    order.verify(transactions).getTransaction(any());
    order.verify(repository).retain(any());
    clearInvocations(gl, account, transactions);
    when(repository.read(any(), any())).thenReturn(Optional.of(value));
    assertThat(service.retain(value.selection(), value.authorization())).isSameAs(value);
    verifyNoInteractions(gl, account, transactions);
  }

  @Test
  void missingSelectionAndWrongCompleteSelectionDenyBeforeRpcOrTransaction() {
    var value = SelectedDraftGameLogicReceiptTest.fixture();
    var repository = mock(SelectedDraftGameLogicReceiptRepository.class);
    var transactions = mock(PlatformTransactionManager.class);
    var gl = mock(GameLogicIntakeRetainClient.class);
    var account = mock(AccountGameLogicIntakeSettlementReadClient.class);
    var service =
        new SelectedDraftGameLogicReceiptService(
            repository, ignored -> Optional.empty(), transactions, gl, account, "test");
    assertThatThrownBy(() -> service.retain(value.selection(), value.authorization()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SELECTION_UNAVAILABLE");
    assertThatThrownBy(
            () ->
                service.retain(
                    value.selection(), SelectedDraftGameLogicReceiptTest.fixture().authorization()))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(repository, transactions, gl, account);
  }

  @Test
  void abortedOrMissingRemoteResultCannotStoreReceipt() {
    var value = SelectedDraftGameLogicReceiptTest.fixture();
    var repository = mock(SelectedDraftGameLogicReceiptRepository.class);
    when(repository.read(any(), any())).thenReturn(Optional.empty());
    var transactions = mock(PlatformTransactionManager.class);
    var gl = mock(GameLogicIntakeRetainClient.class);
    var account = mock(AccountGameLogicIntakeSettlementReadClient.class);
    var service =
        new SelectedDraftGameLogicReceiptService(
            repository,
            ignored -> Optional.of(value.selection()),
            transactions,
            gl,
            account,
            "test");
    assertThatThrownBy(() -> service.retain(value.selection(), value.authorization()))
        .isInstanceOf(IllegalStateException.class);
    when(gl.retain(any()))
        .thenAnswer(
            call ->
                new GameLogicIntakeRetainEvidence(
                    call.getArgument(0),
                    GameLogicGameplayRuleIntakeTerminal.aborted(
                        new GameLogicGameplayRuleIntakeOperation("test", value.authorization()))));
    when(account.read(any()))
        .thenAnswer(
            call ->
                new AccountGameLogicIntakeSettlementReadEvidence(
                    call.getArgument(0),
                    new AccountGameLogicIntakeSettlementEvidence(
                        GameLogicGameplayRuleIntakeTerminal.aborted(
                            new GameLogicGameplayRuleIntakeOperation(
                                "test", value.authorization())))));
    assertThatThrownBy(() -> service.retain(value.selection(), value.authorization()))
        .isInstanceOf(IllegalArgumentException.class);
    verify(account).read(any());
    verifyNoInteractions(transactions);
    verify(repository, never()).retain(any());
  }

  @Test
  void lostAccountSettlementAcknowledgementRecoversOriginalIntakeWithNewTransportCorrelation() {
    var value = SelectedDraftGameLogicReceiptTest.fixture();
    var repository = mock(SelectedDraftGameLogicReceiptRepository.class);
    when(repository.read(any(), any())).thenReturn(Optional.empty());
    when(repository.retain(any())).thenReturn(value);
    var transactions = mock(PlatformTransactionManager.class);
    var gl = mock(GameLogicIntakeRetainClient.class);
    var account = mock(AccountGameLogicIntakeSettlementReadClient.class);
    when(gl.retain(any()))
        .thenAnswer(
            call ->
                new GameLogicIntakeRetainEvidence(call.getArgument(0), value.receipt().terminal()));
    when(account.read(any()))
        .thenThrow(new IllegalStateException("lost settlement acknowledgement"));
    var service =
        new SelectedDraftGameLogicReceiptService(
            repository,
            ignored -> Optional.of(value.selection()),
            transactions,
            gl,
            account,
            "test");
    assertThatThrownBy(() -> service.retain(value.selection(), value.authorization()))
        .hasMessage("lost settlement acknowledgement");
    verify(repository, never()).retain(any());
    verifyNoInteractions(transactions);
    doAnswer(
            call ->
                new AccountGameLogicIntakeSettlementReadEvidence(
                    call.getArgument(0), value.receipt()))
        .when(account)
        .read(any());
    assertThat(service.retain(value.selection(), value.authorization())).isSameAs(value);
    var requests = org.mockito.ArgumentCaptor.forClass(GameLogicIntakeRetainEvidence.Request.class);
    verify(gl, times(2)).retain(requests.capture());
    assertThat(requests.getAllValues().get(0).correlationId())
        .isNotEqualTo(requests.getAllValues().get(1).correlationId());
    for (var request : requests.getAllValues()) {
      assertThat(request.originalAuthorizationBytes())
          .containsExactly(value.authorization().canonicalBytes());
    }
  }

  @Test
  void changedRetainedSelectionDeniesEvenWhenTenantVersionAndIntakeMatch() {
    var value = SelectedDraftGameLogicReceiptTest.fixture();
    var alteredJson =
        value.selection().canonicalJson().replace("\"notes\":\"\"", "\"notes\":\"changed\"");
    var changed =
        net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection.fromStored(
            alteredJson,
            net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.digest(
                alteredJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    var repository = mock(SelectedDraftGameLogicReceiptRepository.class);
    var transactions = mock(PlatformTransactionManager.class);
    var gl = mock(GameLogicIntakeRetainClient.class);
    var account = mock(AccountGameLogicIntakeSettlementReadClient.class);
    var service =
        new SelectedDraftGameLogicReceiptService(
            repository, ignored -> Optional.of(changed), transactions, gl, account, "test");
    assertThatThrownBy(() -> service.retain(value.selection(), value.authorization()))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(repository, transactions, gl, account);
  }
}
