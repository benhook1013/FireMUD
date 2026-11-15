package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionClient;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionInput;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceActivationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceActivationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldOriginalStartSessionProtectedActivationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Mock-only ordering and retry tests; no authenticated Account or committed World DB is implied.
 */
class WorldOriginalStartSessionProtectedActivationServiceTest {
  private final WorldCanonicalInstanceActivationRepository repository =
      mock(WorldCanonicalInstanceActivationRepository.class);
  private final WorldCanonicalInstanceActivationService activation =
      mock(WorldCanonicalInstanceActivationService.class);
  private final AccountStartSessionActivationProtectionAcquisitionClient account =
      mock(AccountStartSessionActivationProtectionAcquisitionClient.class);
  private final WorldOriginalStartSessionProtectedActivationService service =
      new WorldOriginalStartSessionProtectedActivationService(
          repository, activation, account, "world-runtime");

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactHistoricalTerminalReturnsFirstReceiptWithoutFreshAccountAcquisition() {
    var input = mock(AccountStartSessionActivationProtectionAcquisitionInput.class);
    var terminal = mock(WorldOriginalStartSessionActivationTerminalEvidence.class);
    doNothing().when(input).requireTargetNamespace("world-runtime");
    when(repository.readProtectedActivationByAcquisitionInput(input))
        .thenReturn(
            Optional.of(
                new WorldCanonicalInstanceActivationRepository.ProtectedActivationReadback(
                    false, terminal)));

    assertThat(service.activate(input)).isSameAs(terminal);

    verifyNoInteractions(account, activation);
  }

  @Test
  void existingPendingOperationNeverReacquiresOrRestarts() {
    var input = mock(AccountStartSessionActivationProtectionAcquisitionInput.class);
    doNothing().when(input).requireTargetNamespace("world-runtime");
    when(repository.readProtectedActivationByAcquisitionInput(input))
        .thenReturn(
            Optional.of(
                new WorldCanonicalInstanceActivationRepository.ProtectedActivationReadback(
                    true, null)));

    assertThatThrownBy(() -> service.activate(input))
        .isInstanceOf(
            WorldOriginalStartSessionProtectedActivationService.PendingActivationException.class)
        .hasMessageContaining("cannot reacquire authority or restart");

    verifyNoInteractions(account, activation);
  }

  @Test
  void changedCallerIdentityConflictIsTerminalFirstAndDoesNotAcquireAccount() {
    var input = mock(AccountStartSessionActivationProtectionAcquisitionInput.class);
    doNothing().when(input).requireTargetNamespace("world-runtime");
    when(repository.readProtectedActivationByAcquisitionInput(input))
        .thenThrow(
            new WorldCanonicalInstanceActivationRepository.ActivationConflictException(
                "changed exact activation identity"));

    assertThatThrownBy(() -> service.activate(input))
        .isInstanceOf(WorldCanonicalInstanceActivationRepository.ActivationConflictException.class);

    verifyNoInteractions(account, activation);
  }

  @Test
  void ambientTransactionIsDeniedBeforeTerminalReadOrAccountAcquisition() {
    var input = mock(AccountStartSessionActivationProtectionAcquisitionInput.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> service.activate(input))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outside an ambient owner transaction");

    verifyNoInteractions(repository, account, activation);
  }

  @Test
  void absentOperationAcquiresAndComparesCompleteCallerBindingBeforeProtectedCas() {
    var input = matchingInput();
    var protection = matchingEvidence(input);
    var terminal = mock(WorldOriginalStartSessionActivationTerminalEvidence.class);
    when(repository.readProtectedActivationByAcquisitionInput(input)).thenReturn(Optional.empty());
    when(account.acquire(input)).thenReturn(protection);
    when(activation.activateProtected(eq(protection), any()))
        .thenAnswer(
            call -> {
              WorldCanonicalInstanceActivationService.HeldActivationAuthority held =
                  call.getArgument(1);
              held.requireHeld();
              held.close();
              assertThatThrownBy(held::requireHeld)
                  .isInstanceOf(
                      WorldCanonicalInstanceActivationService.ActivationDeniedException.class);
              return terminal;
            });

    assertThat(service.activate(input)).isSameAs(terminal);

    verify(account).acquire(input);
    verify(activation).activateProtected(eq(protection), any());
    verify(repository).readProtectedActivationByAcquisitionInput(input);
  }

  @Test
  void accountReceiptChangingOriginalTupleIsDeniedBeforeWorldWrite() {
    var input = matchingInput();
    var protection = matchingEvidence(input);
    when(repository.readProtectedActivationByAcquisitionInput(input)).thenReturn(Optional.empty());
    when(account.acquire(input)).thenReturn(protection);
    when(protection.request().originalPostAuthorizationTupleBytes()).thenReturn(new byte[] {99});

    assertThatThrownBy(() -> service.activate(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("changed the exact original activation acquisition binding");

    verify(activation, never()).activateProtected(any(), any());
  }

  private static AccountStartSessionActivationProtectionAcquisitionInput matchingInput() {
    var input = mock(AccountStartSessionActivationProtectionAcquisitionInput.class);
    var request = mock(WorldCanonicalInstanceActivation.Request.class);
    doNothing().when(input).requireTargetNamespace("world-runtime");
    when(input.originalPostAuthorizationTuple()).thenReturn(new byte[] {1, 2, 3});
    when(input.gameSessionOwnerMutationId())
        .thenReturn(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    when(input.gameSessionOwnerAttemptId())
        .thenReturn(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    when(input.gameSessionOwnerFence()).thenReturn(3L);
    when(input.worldActivationRequest()).thenReturn(request);
    when(request.activationRequestId())
        .thenReturn(UUID.fromString("33333333-3333-4333-8333-333333333333"));
    when(request.canonicalRequestBytes()).thenReturn(new byte[] {4, 5, 6});
    return input;
  }

  private static AccountStartSessionActivationProtectionEvidence matchingEvidence(
      AccountStartSessionActivationProtectionAcquisitionInput input) {
    var originalTuple = input.originalPostAuthorizationTuple();
    var ownerMutationId = input.gameSessionOwnerMutationId();
    var ownerAttemptId = input.gameSessionOwnerAttemptId();
    var ownerFence = input.gameSessionOwnerFence();
    var activationRequest = input.worldActivationRequest();
    var activationRequestId = activationRequest.activationRequestId();
    var activationRequestBytes = activationRequest.canonicalRequestBytes();
    var evidence =
        mock(
            AccountStartSessionActivationProtectionEvidence.class,
            org.mockito.Mockito.withSettings()
                .defaultAnswer(org.mockito.Mockito.RETURNS_DEEP_STUBS));
    when(evidence.request().originalPostAuthorizationTupleBytes()).thenReturn(originalTuple);
    when(evidence.request().gameSessionOwnerMutationId()).thenReturn(ownerMutationId);
    when(evidence.request().gameSessionOwnerAttemptId()).thenReturn(ownerAttemptId);
    when(evidence.request().gameSessionOwnerFence()).thenReturn(ownerFence);
    when(evidence
            .request()
            .originalTuple()
            .preAuthorizationTuple()
            .action()
            .scope()
            .targetNamespace())
        .thenReturn("world-runtime");
    when(evidence.request().worldActivationRequest().activationRequestId())
        .thenReturn(activationRequestId);
    when(evidence.request().worldActivationRequest().canonicalRequestBytes())
        .thenReturn(activationRequestBytes);
    when(evidence.canonicalBytes()).thenReturn(new byte[] {7, 8, 9});
    return evidence;
  }
}
