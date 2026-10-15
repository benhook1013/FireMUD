package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeSettlementEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeOperation;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeTerminal;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationClient;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationEvidence;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SelectedDraftGameLogicIntakeCommandServiceTest {
  private static final String CREDENTIAL = "original-creator-credential-never-persisted";

  @Test
  void verifiesImmutableSelectionThenAuthorizesAndRetainsOutsideSqlInOrder() {
    var value = SelectedDraftGameLogicReceiptTest.fixture();
    var request = request(value.selection());
    var authorization = authorizationFor(value, request);
    var expectedReceipt = receiptFor(value.selection(), authorization);
    var account = mock(GameLogicIntakeAuthorizationClient.class);
    var events = new ArrayList<String>();
    when(account.authorize(any(), anyString()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(call.getArgument(0, GameLogicIntakeAuthorizationEvidence.Request.class))
                  .isSameAs(request);
              assertThat(call.getArgument(1, String.class)).isEqualTo(CREDENTIAL);
              events.add("account.authorize");
              return GameLogicIntakeAuthorizationEvidence.Result.authorized(request, authorization);
            });
    var service =
        new SelectedDraftGameLogicIntakeCommandService(
            selected -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              events.add("selection.read");
              return Optional.of(value.selection());
            },
            account,
            (selected, retainedAuthorization) -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(selected.canonicalBytes())
                  .containsExactly(value.selection().canonicalBytes());
              assertThat(retainedAuthorization.canonicalBytes())
                  .containsExactly(authorization.canonicalBytes());
              events.add("receipt.retain");
              return expectedReceipt;
            },
            "test");

    var receipt = service.authorizeAndRetain(value.selection(), request, CREDENTIAL);

    assertThat(receipt).isSameAs(expectedReceipt);
    assertThat(events).containsExactly("selection.read", "account.authorize", "receipt.retain");
    verify(account, never()).recover(any(), anyString());
  }

  @Test
  void rejectsMalformedRequestOrChangedDurableSelectionBeforeAccountRpc() {
    var value = SelectedDraftGameLogicReceiptTest.fixture();
    var account = mock(GameLogicIntakeAuthorizationClient.class);
    var retainCalls = new AtomicInteger();
    var readerCalls = new AtomicInteger();
    var service =
        new SelectedDraftGameLogicIntakeCommandService(
            selected -> {
              readerCalls.incrementAndGet();
              return Optional.of(selected);
            },
            account,
            (selected, authorization) -> {
              retainCalls.incrementAndGet();
              return value;
            },
            "test");

    var wrongNamespace =
        new GameLogicIntakeAuthorizationEvidence.Request(
            1,
            "other",
            UUID.randomUUID(),
            UUID.randomUUID(),
            value.selection().selectedCommit().canonicalBytes());
    assertThatThrownBy(
            () -> service.authorizeAndRetain(value.selection(), wrongNamespace, CREDENTIAL))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("namespace");
    var wrongSelectedBinding = request(SelectedDraftGameLogicReceiptTest.fixture().selection());
    assertThatThrownBy(
            () -> service.authorizeAndRetain(value.selection(), wrongSelectedBinding, CREDENTIAL))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("selected Draft binding");
    assertThat(readerCalls).hasValue(0);

    String alteredJson =
        value.selection().canonicalJson().replace("\"notes\":\"\"", "\"notes\":\"changed\"");
    var changedStoredSelection =
        AuthoredDraftPublishSelection.fromStored(
            alteredJson,
            net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.digest(
                alteredJson.getBytes(StandardCharsets.UTF_8)));
    var changedSelectionService =
        new SelectedDraftGameLogicIntakeCommandService(
            ignored -> Optional.of(changedStoredSelection),
            account,
            (selected, authorization) -> {
              retainCalls.incrementAndGet();
              return value;
            },
            "test");
    assertThatThrownBy(
            () ->
                changedSelectionService.authorizeAndRetain(
                    value.selection(), request(value.selection()), CREDENTIAL))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("immutable selection");

    assertThat(retainCalls).hasValue(0);
    verifyNoInteractions(account);
  }

  @Test
  void rejectsChangedResponseTransportCorrelationBeforeReceiptRetention() {
    var value = SelectedDraftGameLogicReceiptTest.fixture();
    var request = request(value.selection());
    var changedResponseRequest =
        new GameLogicIntakeAuthorizationEvidence.Request(
            request.schemaVersion(),
            request.targetNamespace(),
            UUID.randomUUID(),
            request.intakeRequestId(),
            request.selectedDraftBinding());
    var account = mock(GameLogicIntakeAuthorizationClient.class);
    when(account.authorize(any(), anyString()))
        .thenReturn(
            GameLogicIntakeAuthorizationEvidence.Result.authorized(
                changedResponseRequest, authorizationFor(value, changedResponseRequest)));
    var retainCalls = new AtomicInteger();
    var service =
        new SelectedDraftGameLogicIntakeCommandService(
            ignored -> Optional.of(value.selection()),
            account,
            (selected, authorization) -> {
              retainCalls.incrementAndGet();
              return value;
            },
            "test");

    assertThatThrownBy(() -> service.authorizeAndRetain(value.selection(), request, CREDENTIAL))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("request readback conflict");

    assertThat(retainCalls).hasValue(0);
  }

  @Test
  void rejectsAuthorizationWhoseSourceDoesNotMatchExactRequest() {
    var value = SelectedDraftGameLogicReceiptTest.fixture();
    var other = SelectedDraftGameLogicReceiptTest.fixture();
    var request = request(value.selection());
    var changedAuthorization = authorizationFor(other, request);
    var response = mock(GameLogicIntakeAuthorizationEvidence.Result.class);
    when(response.request()).thenReturn(request);
    when(response.outcome()).thenReturn(GameLogicIntakeAuthorizationEvidence.Outcome.FINALIZED);
    when(response.authorization()).thenReturn(Optional.of(changedAuthorization));
    var account = mock(GameLogicIntakeAuthorizationClient.class);
    when(account.recover(any(), anyString())).thenReturn(response);
    var retainCalls = new AtomicInteger();
    var service =
        new SelectedDraftGameLogicIntakeCommandService(
            ignored -> Optional.of(value.selection()),
            account,
            (selected, authorization) -> {
              retainCalls.incrementAndGet();
              return value;
            },
            "test");

    assertThatThrownBy(() -> service.recoverAndRetain(value.selection(), request, CREDENTIAL))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("differs from exact request");

    assertThat(retainCalls).hasValue(0);
  }

  @Test
  void sanitizesProducerFailuresWithoutReflectingProtectedCredential() {
    var value = SelectedDraftGameLogicReceiptTest.fixture();
    var account = mock(GameLogicIntakeAuthorizationClient.class);
    when(account.authorize(any(), anyString())).thenThrow(new IllegalStateException(CREDENTIAL));
    var service =
        new SelectedDraftGameLogicIntakeCommandService(
            ignored -> Optional.of(value.selection()),
            account,
            (selected, authorization) -> value,
            "test");

    assertThatThrownBy(
            () ->
                service.authorizeAndRetain(
                    value.selection(), request(value.selection()), CREDENTIAL))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account intake authorization denied or unavailable")
        .hasMessageNotContaining(CREDENTIAL);
  }

  @Test
  void reservedAndAbortedRecoveryNeverCreatesReceipt() {
    var value = SelectedDraftGameLogicReceiptTest.fixture();
    var request = request(value.selection());
    var account = mock(GameLogicIntakeAuthorizationClient.class);
    var reserved = response(request, GameLogicIntakeAuthorizationEvidence.Outcome.RESERVED);
    var aborted = response(request, GameLogicIntakeAuthorizationEvidence.Outcome.ABORTED);
    when(account.recover(any(), anyString())).thenReturn(reserved, aborted);
    var retainCalls = new AtomicInteger();
    var service =
        new SelectedDraftGameLogicIntakeCommandService(
            ignored -> Optional.of(value.selection()),
            account,
            (selected, authorization) -> {
              retainCalls.incrementAndGet();
              return value;
            },
            "test");

    for (int i = 0; i < 2; i++) {
      assertThatThrownBy(() -> service.recoverAndRetain(value.selection(), request, CREDENTIAL))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not finalized");
    }

    assertThat(retainCalls).hasValue(0);
  }

  @Test
  void exactRecoveryUsesCallerIntakeIdentityWithoutIssuingAnotherAuthorization() {
    var value = SelectedDraftGameLogicReceiptTest.fixture();
    var request = request(value.selection());
    var authorization = authorizationFor(value, request);
    var expectedReceipt = receiptFor(value.selection(), authorization);
    var account = mock(GameLogicIntakeAuthorizationClient.class);
    when(account.recover(any(), anyString()))
        .thenAnswer(
            call -> {
              assertThat(call.getArgument(0, GameLogicIntakeAuthorizationEvidence.Request.class))
                  .isSameAs(request);
              assertThat(call.getArgument(1, String.class)).isEqualTo(CREDENTIAL);
              return GameLogicIntakeAuthorizationEvidence.Result.authorized(request, authorization);
            });
    var service =
        new SelectedDraftGameLogicIntakeCommandService(
            ignored -> Optional.of(value.selection()),
            account,
            (selected, retainedAuthorization) -> expectedReceipt,
            "test");

    assertThat(service.recoverAndRetain(value.selection(), request, CREDENTIAL))
        .isSameAs(expectedReceipt);
    assertThat(request.intakeRequestId()).isEqualTo(authorization.intakeRequestId());
    verify(account, never()).authorize(any(), anyString());
  }

  private static GameLogicIntakeAuthorizationEvidence.Result response(
      GameLogicIntakeAuthorizationEvidence.Request request,
      GameLogicIntakeAuthorizationEvidence.Outcome outcome) {
    var result = mock(GameLogicIntakeAuthorizationEvidence.Result.class);
    when(result.request()).thenReturn(request);
    when(result.outcome()).thenReturn(outcome);
    when(result.authorization()).thenReturn(Optional.empty());
    return result;
  }

  private static GameLogicIntakeAuthorizationEvidence.Request request(
      AuthoredDraftPublishSelection selection) {
    return GameLogicIntakeAuthorizationEvidence.Request.create(
        "test", UUID.randomUUID(), selection.selectedCommit());
  }

  private static GameLogicIntakeAuthorizationBinding authorizationFor(
      SelectedDraftGameLogicReceipt fixture, GameLogicIntakeAuthorizationEvidence.Request request) {
    var original = fixture.authorization();
    return new GameLogicIntakeAuthorizationBinding(
        original.operationId(),
        original.fenceId(),
        request.intakeRequestId(),
        original.actorAccountId(),
        original.source(),
        original.sources());
  }

  private static SelectedDraftGameLogicReceipt receiptFor(
      AuthoredDraftPublishSelection selection, GameLogicIntakeAuthorizationBinding authorization) {
    var terminal =
        GameLogicGameplayRuleIntakeTerminal.retained(
            new GameLogicGameplayRuleIntakeOperation("test", authorization),
            authorization.source().canonicalBytes(),
            authorization.source().manifest().canonicalJson().getBytes(StandardCharsets.UTF_8));
    return new SelectedDraftGameLogicReceipt(
        selection, authorization, new AccountGameLogicIntakeSettlementEvidence(terminal));
  }
}
