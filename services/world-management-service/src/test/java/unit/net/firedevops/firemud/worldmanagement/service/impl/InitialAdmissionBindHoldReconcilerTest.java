package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.worldmanagement.client.GameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.entity.InitialAdmissionBindHold;
import net.firedevops.firemud.worldmanagement.repository.InitialAdmissionBindHoldRepository;
import net.firedevops.firemud.worldmanagement.service.InitialAdmissionBindHoldService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class InitialAdmissionBindHoldReconcilerTest {
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
        new InitialAdmissionBindHoldReconciler(repository, service, proofClientProvider);
    reconciler.reconcilePendingHolds();

    verify(service).requireReconciliation(first.holdId(), "GS_OWNER_READ_UNAVAILABLE");
    verify(service).requireReconciliation(second.holdId(), "GS_OWNER_READ_UNAVAILABLE");
  }

  private static InitialAdmissionBindHold hold(long suffix) {
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
        now.plusSeconds(300),
        null,
        null,
        null,
        null,
        null,
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
