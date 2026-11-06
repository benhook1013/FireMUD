package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationService.HeldCommitAuthority;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationService.OriginalOperationCommitAuthorityVerifier;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationService.OriginalOperationRecoveryVerifier;
import org.junit.jupiter.api.Test;

/** Mock-only early-refusal and historical-replay ordering; no installed authority is implied. */
class WorldCanonicalInstancePreparationPreflightTest {
  private final WorldCanonicalInstancePreparationRepository repository =
      mock(WorldCanonicalInstancePreparationRepository.class);
  private final WorldRuntimeInstallationRegistry registry =
      mock(WorldRuntimeInstallationRegistry.class);
  private final OriginalOperationCommitAuthorityVerifier verifier =
      mock(OriginalOperationCommitAuthorityVerifier.class);
  private final WorldCanonicalInstancePreparation.Input input =
      mock(WorldCanonicalInstancePreparation.Input.class);
  private final WorldCanonicalInstancePreparation.OriginalStartSessionOperationInput operation =
      mock(WorldCanonicalInstancePreparation.OriginalStartSessionOperationInput.class);
  private final UUID instanceId = UUID.fromString("88888888-8888-4888-8888-888888888888");

  @Test
  void unavailableInstallationRefusesBeforeAccountParticipationAcquisition() {
    var service = service();
    doThrow(new WorldRuntimeInstallationRegistry.RegistryUnavailableException("unavailable source"))
        .when(registry)
        .preflightLaunchOwners(instanceId);

    assertThatThrownBy(() -> service.prepare(input, operation))
        .isInstanceOf(WorldRuntimeInstallationRegistry.RegistryUnavailableException.class)
        .hasMessage("unavailable source");

    var order = inOrder(repository, registry);
    order.verify(repository).hasRetainedCommittedCandidate(input, operation);
    order.verify(registry).preflightLaunchOwners(instanceId);
    verifyNoInteractions(verifier);
  }

  @Test
  void committedCandidateStillRequiresAuthenticationBeforeAnyHistoricalReturn() {
    var service = service();
    when(repository.hasRetainedCommittedCandidate(input, operation)).thenReturn(true);
    when(verifier.verifyAndHold(input, operation)).thenThrow(new SecurityException("denied"));

    assertThatThrownBy(() -> service.prepare(input, operation))
        .isInstanceOf(SecurityException.class)
        .hasMessage("denied");

    verifyNoInteractions(registry);
    verify(repository, never()).readRetainedCommittedResult(eq(input), any());
  }

  @Test
  void exactCommittedReplayKeepsHeldQualificationAndBypassesCurrentInstallation() {
    var service = service();
    var held = mock(HeldCommitAuthority.class);
    var identity = mock(WorldCanonicalInstanceExecutionIdentity.class);
    var result = mock(WorldCanonicalInstancePreparation.Result.class);
    when(repository.hasRetainedCommittedCandidate(input, operation)).thenReturn(true);
    when(verifier.verifyAndHold(input, operation)).thenReturn(held);
    when(held.executionIdentity()).thenReturn(identity);
    when(repository.readRetainedCommittedResult(input, held)).thenReturn(Optional.of(result));

    try (var inputEncoding = mockStatic(WorldCanonicalInstancePreparationRepository.class)) {
      inputEncoding
          .when(() -> WorldCanonicalInstancePreparationRepository.inputJson(input))
          .thenReturn("{}");
      assertThat(service.prepare(input, operation)).isSameAs(result);
    }

    var order = inOrder(repository, verifier, held, identity);
    order.verify(repository).hasRetainedCommittedCandidate(input, operation);
    order.verify(verifier).verifyAndHold(input, operation);
    order.verify(held).requireHeld();
    order.verify(held).executionIdentity();
    order.verify(identity).requireMatches(input, operation, "{}");
    order.verify(repository).readRetainedCommittedResult(input, held);
    order.verify(held).close();
    verifyNoInteractions(registry);
  }

  @Test
  void exactCommittedReplayWithoutRegistryStillRequiresHeldAuthentication() {
    var service = service(false);
    var held = mock(HeldCommitAuthority.class);
    var identity = mock(WorldCanonicalInstanceExecutionIdentity.class);
    var result = mock(WorldCanonicalInstancePreparation.Result.class);
    when(repository.hasRetainedCommittedCandidate(input, operation)).thenReturn(true);
    when(verifier.verifyAndHold(input, operation)).thenReturn(held);
    when(held.executionIdentity()).thenReturn(identity);
    when(repository.readRetainedCommittedResult(input, held)).thenReturn(Optional.of(result));

    try (var inputEncoding = mockStatic(WorldCanonicalInstancePreparationRepository.class)) {
      inputEncoding
          .when(() -> WorldCanonicalInstancePreparationRepository.inputJson(input))
          .thenReturn("{}");
      assertThat(service.prepare(input, operation)).isSameAs(result);
    }

    var order = inOrder(repository, verifier, held, identity);
    order.verify(repository).hasRetainedCommittedCandidate(input, operation);
    order.verify(verifier).verifyAndHold(input, operation);
    order.verify(held).requireHeld();
    order.verify(held).executionIdentity();
    order.verify(identity).requireMatches(input, operation, "{}");
    order.verify(repository).readRetainedCommittedResult(input, held);
    order.verify(held).close();
    verifyNoInteractions(registry);
    verify(repository, never()).materialize(input, held);
  }

  @Test
  void falseCommittedCandidateWithoutRegistryDeniesAfterHeldQualificationAndExactRead() {
    var service = service(false);
    var held = mock(HeldCommitAuthority.class);
    var identity = mock(WorldCanonicalInstanceExecutionIdentity.class);
    when(repository.hasRetainedCommittedCandidate(input, operation)).thenReturn(true);
    when(verifier.verifyAndHold(input, operation)).thenReturn(held);
    when(held.executionIdentity()).thenReturn(identity);
    when(repository.readRetainedCommittedResult(input, held)).thenReturn(Optional.empty());

    try (var inputEncoding = mockStatic(WorldCanonicalInstancePreparationRepository.class)) {
      inputEncoding
          .when(() -> WorldCanonicalInstancePreparationRepository.inputJson(input))
          .thenReturn("{}");
      assertThatThrownBy(() -> service.prepare(input, operation))
          .isInstanceOf(WorldCanonicalInstancePreparationService.PreparationDeniedException.class)
          .hasMessage("Protected current-installation registry is unavailable");
    }

    var order = inOrder(repository, verifier, held, identity);
    order.verify(repository).hasRetainedCommittedCandidate(input, operation);
    order.verify(verifier).verifyAndHold(input, operation);
    order.verify(held).requireHeld();
    order.verify(held).executionIdentity();
    order.verify(identity).requireMatches(input, operation, "{}");
    order.verify(repository).readRetainedCommittedResult(input, held);
    order.verify(held).close();
    verifyNoInteractions(registry);
    verify(repository, never()).materialize(input, held);
  }

  @Test
  void candidateWithoutExactRetainedResultStillRequiresCommitTimeInstallationGuard() {
    var service = service();
    var held = mock(HeldCommitAuthority.class);
    var identity = mock(WorldCanonicalInstanceExecutionIdentity.class);
    when(repository.hasRetainedCommittedCandidate(input, operation)).thenReturn(true);
    when(verifier.verifyAndHold(input, operation)).thenReturn(held);
    when(held.executionIdentity()).thenReturn(identity);
    when(repository.readRetainedCommittedResult(input, held)).thenReturn(Optional.empty());
    when(registry.withLaunchOwners(eq(instanceId), any()))
        .thenThrow(
            new WorldRuntimeInstallationRegistry.RegistryUnavailableException(
                "changed installation"));

    try (var inputEncoding = mockStatic(WorldCanonicalInstancePreparationRepository.class)) {
      inputEncoding
          .when(() -> WorldCanonicalInstancePreparationRepository.inputJson(input))
          .thenReturn("{}");
      assertThatThrownBy(() -> service.prepare(input, operation))
          .isInstanceOf(WorldRuntimeInstallationRegistry.RegistryUnavailableException.class)
          .hasMessage("changed installation");
    }

    verify(registry, never()).preflightLaunchOwners(instanceId);
    verify(registry).withLaunchOwners(eq(instanceId), any());
    verify(held).close();
    verify(repository, never()).materialize(input, held);
  }

  private WorldCanonicalInstancePreparationService service() {
    return service(true);
  }

  private WorldCanonicalInstancePreparationService service(boolean withRegistry) {
    var evidence = mock(WorldCanonicalInstanceAssociation.GameSessionReadEvidence.class);
    var topology = mock(WorldCanonicalInstanceTopologyPlan.class);
    when(input.gameSessionReadEvidence()).thenReturn(evidence);
    when(evidence.targetNamespace()).thenReturn("firemud");
    when(input.canonicalGameInstanceId()).thenReturn(instanceId);
    when(input.topologyPlan()).thenReturn(topology);
    when(topology.generationRules()).thenReturn(List.of());
    when(topology.spawnBindings()).thenReturn(List.of());
    var recoveryVerifier = mock(OriginalOperationRecoveryVerifier.class);
    return withRegistry
        ? new WorldCanonicalInstancePreparationService(
            repository, verifier, recoveryVerifier, registry)
        : new WorldCanonicalInstancePreparationService(repository, verifier, recoveryVerifier);
  }
}
