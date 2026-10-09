package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceExecutionIdentity;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationRepository.ExecutionLookup;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationRepository.ExecutionOperation;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationRepository.ExecutionState;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldCanonicalInstancePreparationServiceTest {
  @AfterEach
  void clearAmbientTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void defaultConstructorsDenyRecoveryWithoutTouchingRepository() {
    var repository = mock(WorldCanonicalInstancePreparationRepository.class);
    var identity = mock(WorldCanonicalInstanceExecutionIdentity.class);

    assertThatThrownBy(
            () -> new WorldCanonicalInstancePreparationService(repository).recoverExact(identity))
        .isInstanceOf(WorldCanonicalInstancePreparationService.PreparationDeniedException.class)
        .hasMessageContaining("no authenticated original-operation verifier");

    var commitVerifier =
        mock(WorldCanonicalInstancePreparationService.CommitAuthorityVerifier.class);
    assertThatThrownBy(
            () ->
                new WorldCanonicalInstancePreparationService(repository, commitVerifier)
                    .recoverExact(identity))
        .isInstanceOf(WorldCanonicalInstancePreparationService.PreparationDeniedException.class)
        .hasMessageContaining("no authenticated original-operation verifier");

    verifyNoInteractions(repository, commitVerifier);
  }

  @Test
  void recoveryVerifierDenialPrecedesTheExactRepositoryRead() {
    var repository = mock(WorldCanonicalInstancePreparationRepository.class);
    var recoveryVerifier =
        mock(WorldCanonicalInstancePreparationService.OriginalOperationRecoveryVerifier.class);
    var identity = mock(WorldCanonicalInstanceExecutionIdentity.class);
    var commitVerifier =
        mock(WorldCanonicalInstancePreparationService.CommitAuthorityVerifier.class);
    doThrow(new WorldCanonicalInstancePreparationService.PreparationDeniedException("denied"))
        .when(recoveryVerifier)
        .verifyOriginalOperation(identity);
    var service =
        new WorldCanonicalInstancePreparationService(repository, commitVerifier, recoveryVerifier);

    assertThatThrownBy(() -> service.recoverExact(identity))
        .isInstanceOf(WorldCanonicalInstancePreparationService.PreparationDeniedException.class)
        .hasMessage("denied");

    verifyNoInteractions(repository, commitVerifier);
  }

  @Test
  void ambientTransactionIsDeniedBeforeRecoveryAuthenticationOrStorage() {
    var repository = mock(WorldCanonicalInstancePreparationRepository.class);
    var recoveryVerifier =
        mock(WorldCanonicalInstancePreparationService.OriginalOperationRecoveryVerifier.class);
    var identity = mock(WorldCanonicalInstanceExecutionIdentity.class);
    var service =
        new WorldCanonicalInstancePreparationService(
            repository,
            mock(WorldCanonicalInstancePreparationService.CommitAuthorityVerifier.class),
            recoveryVerifier);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> service.recoverExact(identity))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must authenticate outside an ambient transaction");

    verifyNoInteractions(repository, recoveryVerifier);
  }

  @Test
  void exactRecoveryAuthenticatesThenReturnsTheUnchangedReadOnlyLookup() {
    var repository = mock(WorldCanonicalInstancePreparationRepository.class);
    var recoveryVerifier =
        mock(WorldCanonicalInstancePreparationService.OriginalOperationRecoveryVerifier.class);
    var identity = mock(WorldCanonicalInstanceExecutionIdentity.class);
    var commitVerifier =
        mock(WorldCanonicalInstancePreparationService.CommitAuthorityVerifier.class);
    var operation = new ExecutionOperation(ExecutionState.COMMITTED, 73L, 991L);
    var lookup = new ExecutionLookup(Optional.of(operation), true);
    when(repository.readExactExecution(identity)).thenReturn(lookup);
    var service =
        new WorldCanonicalInstancePreparationService(repository, commitVerifier, recoveryVerifier);

    ExecutionLookup result = service.recoverExact(identity);

    assertThat(result).isSameAs(lookup);
    var order = inOrder(recoveryVerifier, repository);
    order.verify(recoveryVerifier).verifyOriginalOperation(identity);
    order.verify(repository).readExactExecution(identity);
    verifyNoMoreInteractions(repository);
    verifyNoInteractions(commitVerifier);
  }

  @Test
  void expiredOriginalOperationCanBeReadWithoutStartingFreshExecution() {
    var repository = mock(WorldCanonicalInstancePreparationRepository.class);
    var recoveryVerifier =
        mock(WorldCanonicalInstancePreparationService.OriginalOperationRecoveryVerifier.class);
    var identity = mock(WorldCanonicalInstanceExecutionIdentity.class);
    var commitVerifier =
        mock(WorldCanonicalInstancePreparationService.CommitAuthorityVerifier.class);
    var lookup =
        new ExecutionLookup(
            Optional.of(new ExecutionOperation(ExecutionState.COMMITTED, 74L, 992L)), false);
    when(repository.readExactExecution(identity)).thenReturn(lookup);
    var service =
        new WorldCanonicalInstancePreparationService(repository, commitVerifier, recoveryVerifier);

    assertThat(service.recoverExact(identity)).isSameAs(lookup);
    assertThat(lookup.originalAuthorizationValidAtRead()).isFalse();
    verify(recoveryVerifier).verifyOriginalOperation(identity);
    verify(repository).readExactExecution(identity);
    verifyNoMoreInteractions(repository);
    verifyNoInteractions(commitVerifier);
  }

  @Test
  void missingExpiredOperationRemainsUnresolvedAndIsNotAbortedOrAdmitted() {
    var repository = mock(WorldCanonicalInstancePreparationRepository.class);
    var recoveryVerifier =
        mock(WorldCanonicalInstancePreparationService.OriginalOperationRecoveryVerifier.class);
    var identity = mock(WorldCanonicalInstanceExecutionIdentity.class);
    var commitVerifier =
        mock(WorldCanonicalInstancePreparationService.CommitAuthorityVerifier.class);
    var lookup = new ExecutionLookup(Optional.empty(), false);
    when(repository.readExactExecution(identity)).thenReturn(lookup);
    var service =
        new WorldCanonicalInstancePreparationService(repository, commitVerifier, recoveryVerifier);

    ExecutionLookup result = service.recoverExact(identity);

    assertThat(result).isSameAs(lookup);
    assertThat(result.operation()).isEmpty();
    assertThat(result.originalAuthorizationValidAtRead()).isFalse();
    verify(recoveryVerifier).verifyOriginalOperation(identity);
    verify(repository).readExactExecution(identity);
    verifyNoMoreInteractions(repository);
    verifyNoInteractions(commitVerifier);
  }
}
