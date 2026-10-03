package net.firedevops.firemud.worldmanagement.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.worldmanagement.client.GameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.dto.InitialAdmissionBindHoldDto;
import net.firedevops.firemud.worldmanagement.dto.InitialAdmissionBindOwnerProof;
import net.firedevops.firemud.worldmanagement.dto.InitialAdmissionBindOwnerProof.Outcome;
import net.firedevops.firemud.worldmanagement.entity.InitialAdmissionBindHold;
import net.firedevops.firemud.worldmanagement.repository.InitialAdmissionBindHoldRepository;
import net.firedevops.firemud.worldmanagement.service.InitialAdmissionBindHoldService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class InitialAdmissionBindHoldReconcilerTest {
  private static final String OVERDUE_OBSERVATIONS_METRIC =
      "world_initial_admission_bind_overdue_observations_total";

  @Test
  void springContextSelectsAutowiredProductionConstructor() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(
          InitialAdmissionBindHoldRepository.class,
          () -> Mockito.mock(InitialAdmissionBindHoldRepository.class));
      context.registerBean(
          InitialAdmissionBindHoldService.class,
          () -> Mockito.mock(InitialAdmissionBindHoldService.class));
      context.registerBean(
          GameSessionInitialAdmissionBindProofClient.class,
          () -> Mockito.mock(GameSessionInitialAdmissionBindProofClient.class));
      context.registerBean(SimpleMeterRegistry.class);
      context.registerBean(InitialAdmissionBindHoldReconciler.class);

      context.refresh();

      assertNotNull(context.getBean(InitialAdmissionBindHoldReconciler.class));
    }
  }

  @Test
  void failedReconciliationRequiredCasDoesNotStopTheRemainingBatch() {
    InitialAdmissionBindHold first = hold(3L);
    InitialAdmissionBindHold second = hold(4L);
    InitialAdmissionBindHoldRepository repository =
        Mockito.mock(InitialAdmissionBindHoldRepository.class);
    InitialAdmissionBindHoldService service = Mockito.mock(InitialAdmissionBindHoldService.class);
    ObjectProvider<GameSessionInitialAdmissionBindProofClient> proofClientProvider = mockProvider();
    when(repository.findNonterminal(32)).thenReturn(List.of(first, second));
    when(proofClientProvider.getIfAvailable()).thenReturn(null);
    doThrow(new IllegalStateException("stale reconciliation CAS"))
        .when(service)
        .requireReconciliation(first.holdId(), "GS_OWNER_READ_UNAVAILABLE");

    InitialAdmissionBindHoldReconciler reconciler =
        new InitialAdmissionBindHoldReconciler(
            repository, service, proofClientProvider, new SimpleMeterRegistry());
    reconciler.reconcilePendingHolds();

    verify(service).requireReconciliation(first.holdId(), "GS_OWNER_READ_UNAVAILABLE");
    verify(service).requireReconciliation(second.holdId(), "GS_OWNER_READ_UNAVAILABLE");
  }

  @Test
  void overdueObservationCountsBoundedOwnerPendingCodeWithoutIdentityLabels() {
    InitialAdmissionBindHold hold = hold(5L, Instant.parse("2026-10-02T00:00:00Z"), null);
    InitialAdmissionBindHoldRepository repository = repositoryWith(hold);
    InitialAdmissionBindHoldService service = Mockito.mock(InitialAdmissionBindHoldService.class);
    GameSessionInitialAdmissionBindProofClient proofClient =
        Mockito.mock(GameSessionInitialAdmissionBindProofClient.class);
    ObjectProvider<GameSessionInitialAdmissionBindProofClient> proofClientProvider =
        providerWith(proofClient);
    InitialAdmissionBindOwnerProof ownerProof = ownerProof(Outcome.PENDING);
    when(proofClient.readOwnerProof(hold)).thenReturn(ownerProof);
    when(service.reconcileOwnerProof(hold.holdId(), ownerProof))
        .thenReturn(dto(hold, "RECONCILIATION_REQUIRED"));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    reconciler(repository, service, proofClientProvider, meterRegistry).reconcilePendingHolds();

    assertThat(counter(meterRegistry, "GS_OWNER_PENDING")).isEqualTo(1.0);
    assertThat(meterRegistry.getMeters())
        .allSatisfy(
            meter ->
                assertThat(meter.getId().getTags())
                    .containsExactly(Tag.of("error_code", "GS_OWNER_PENDING")));
    verify(service).reconcileOwnerProof(hold.holdId(), ownerProof);
  }

  @Test
  void holdBeforeDiagnosticExpiryDoesNotEmitAnOverdueObservation() {
    InitialAdmissionBindHold hold = hold(6L, Instant.parse("2026-10-02T00:10:00Z"), null);
    InitialAdmissionBindHoldRepository repository = repositoryWith(hold);
    InitialAdmissionBindHoldService service = Mockito.mock(InitialAdmissionBindHoldService.class);
    GameSessionInitialAdmissionBindProofClient proofClient =
        Mockito.mock(GameSessionInitialAdmissionBindProofClient.class);
    ObjectProvider<GameSessionInitialAdmissionBindProofClient> proofClientProvider =
        providerWith(proofClient);
    InitialAdmissionBindOwnerProof ownerProof = ownerProof(Outcome.PENDING);
    when(proofClient.readOwnerProof(hold)).thenReturn(ownerProof);
    when(service.reconcileOwnerProof(hold.holdId(), ownerProof))
        .thenReturn(dto(hold, "RECONCILIATION_REQUIRED"));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    reconciler(repository, service, proofClientProvider, meterRegistry).reconcilePendingHolds();

    assertThat(meterRegistry.find(OVERDUE_OBSERVATIONS_METRIC).counter()).isNull();
  }

  @Test
  void overdueObservationIsEmittedWhenOwnerProofClientIsUnavailable() {
    InitialAdmissionBindHold hold = hold(7L, Instant.parse("2026-10-02T00:00:00Z"), null);
    InitialAdmissionBindHoldRepository repository = repositoryWith(hold);
    InitialAdmissionBindHoldService service = Mockito.mock(InitialAdmissionBindHoldService.class);
    ObjectProvider<GameSessionInitialAdmissionBindProofClient> proofClientProvider =
        providerWith(null);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    reconciler(repository, service, proofClientProvider, meterRegistry).reconcilePendingHolds();

    assertThat(counter(meterRegistry, "GS_OWNER_READ_UNAVAILABLE")).isEqualTo(1.0);
    verify(service).requireReconciliation(hold.holdId(), "GS_OWNER_READ_UNAVAILABLE");
  }

  @Test
  void overdueObservationRecordsOwnerProofThatFinalizesTheHold() {
    InitialAdmissionBindHold hold = hold(8L, Instant.parse("2026-10-02T00:00:00Z"), null);
    InitialAdmissionBindHoldRepository repository = repositoryWith(hold);
    InitialAdmissionBindHoldService service = Mockito.mock(InitialAdmissionBindHoldService.class);
    GameSessionInitialAdmissionBindProofClient proofClient =
        Mockito.mock(GameSessionInitialAdmissionBindProofClient.class);
    ObjectProvider<GameSessionInitialAdmissionBindProofClient> proofClientProvider =
        providerWith(proofClient);
    InitialAdmissionBindOwnerProof ownerProof = ownerProof(Outcome.COMMITTED);
    when(proofClient.readOwnerProof(hold)).thenReturn(ownerProof);
    when(service.reconcileOwnerProof(hold.holdId(), ownerProof)).thenReturn(dto(hold, "COMMITTED"));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    reconciler(repository, service, proofClientProvider, meterRegistry).reconcilePendingHolds();

    assertThat(counter(meterRegistry, "none")).isEqualTo(1.0);
    verify(service).reconcileOwnerProof(hold.holdId(), ownerProof);
  }

  private static InitialAdmissionBindHoldReconciler reconciler(
      InitialAdmissionBindHoldRepository repository,
      InitialAdmissionBindHoldService service,
      ObjectProvider<GameSessionInitialAdmissionBindProofClient> proofClientProvider,
      SimpleMeterRegistry meterRegistry) {
    return new InitialAdmissionBindHoldReconciler(
        repository,
        service,
        proofClientProvider,
        java.time.Clock.fixed(Instant.parse("2026-10-02T00:05:00Z"), java.time.ZoneOffset.UTC),
        meterRegistry);
  }

  private static InitialAdmissionBindHoldRepository repositoryWith(InitialAdmissionBindHold hold) {
    InitialAdmissionBindHoldRepository repository =
        Mockito.mock(InitialAdmissionBindHoldRepository.class);
    when(repository.findNonterminal(32)).thenReturn(List.of(hold));
    return repository;
  }

  private static InitialAdmissionBindOwnerProof ownerProof(Outcome outcome) {
    return new InitialAdmissionBindOwnerProof(
        outcome,
        "00000000-0000-0000-0000-000000000001",
        "00000000-0000-0000-0000-000000000002",
        42L,
        "00000000-0000-0000-0000-000000000003",
        "00000000-0000-0000-0000-000000000004",
        "SHARED",
        101L,
        11L,
        7L,
        "initial-admission-1",
        "a".repeat(64),
        true,
        1L,
        "owner-proof-1",
        null,
        0L,
        null,
        false);
  }

  private static InitialAdmissionBindHoldDto dto(InitialAdmissionBindHold hold, String status) {
    return new InitialAdmissionBindHoldDto(
        hold.holdId(),
        hold.holdFence(),
        hold.tenantId(),
        hold.realmUuid(),
        hold.playableStateNamespaceUuid(),
        hold.playableStateScope(),
        hold.gameInstanceId(),
        hold.versionId(),
        hold.activeLifecycleEpoch(),
        hold.initialAdmissionRequestId(),
        hold.requestDigest(),
        hold.expectedNoPriorPointer(),
        hold.expectedCatalogRevision(),
        status,
        hold.diagnosticExpiresAt());
  }

  private static double counter(SimpleMeterRegistry meterRegistry, String errorCode) {
    return meterRegistry
        .get(OVERDUE_OBSERVATIONS_METRIC)
        .tag("error_code", errorCode)
        .counter()
        .count();
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<GameSessionInitialAdmissionBindProofClient> providerWith(
      GameSessionInitialAdmissionBindProofClient client) {
    ObjectProvider<GameSessionInitialAdmissionBindProofClient> provider =
        (ObjectProvider<GameSessionInitialAdmissionBindProofClient>)
            Mockito.mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(client);
    return provider;
  }

  private static InitialAdmissionBindHold hold(long suffix) {
    return hold(suffix, Instant.now().plusSeconds(300), null);
  }

  private static InitialAdmissionBindHold hold(
      long suffix, Instant diagnosticExpiresAt, String reconciliationError) {
    String uuidSuffix = String.format("%012d", suffix);
    Instant now = Instant.now();
    return new InitialAdmissionBindHold(
        "00000000-0000-0000-0000-" + uuidSuffix,
        "10000000-0000-0000-0000-" + uuidSuffix,
        42L,
        "20000000-0000-0000-0000-000000000001",
        "30000000-0000-0000-0000-000000000002",
        "SHARED",
        101L,
        11L,
        7L,
        "initial-admission-" + suffix,
        "a".repeat(64),
        true,
        1L,
        "PENDING",
        diagnosticExpiresAt,
        null,
        null,
        null,
        null,
        reconciliationError,
        now,
        now,
        null,
        0L);
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<GameSessionInitialAdmissionBindProofClient> mockProvider() {
    return (ObjectProvider<GameSessionInitialAdmissionBindProofClient>)
        Mockito.mock(ObjectProvider.class);
  }
}
