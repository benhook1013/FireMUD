package net.firedevops.firemud.worldmanagement.service.impl;

import java.time.Clock;
import java.util.List;
import net.firedevops.firemud.worldmanagement.client.GameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.repository.InitialAdmissionBindHoldRepository;
import net.firedevops.firemud.worldmanagement.service.InitialAdmissionBindHoldService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Automatically reconciles World holds and keeps them fenced when owner readback is unavailable.
 */
@Component
public class InitialAdmissionBindHoldReconciler {
  private static final Logger logger =
      LoggerFactory.getLogger(InitialAdmissionBindHoldReconciler.class);

  private final InitialAdmissionBindHoldRepository holdRepository;
  private final InitialAdmissionBindHoldService holdService;
  private final ObjectProvider<GameSessionInitialAdmissionBindProofClient> proofClientProvider;
  private final Clock clock;

  @Autowired
  public InitialAdmissionBindHoldReconciler(
      InitialAdmissionBindHoldRepository holdRepository,
      InitialAdmissionBindHoldService holdService,
      ObjectProvider<GameSessionInitialAdmissionBindProofClient> proofClientProvider) {
    this(holdRepository, holdService, proofClientProvider, Clock.systemUTC());
  }

  InitialAdmissionBindHoldReconciler(
      InitialAdmissionBindHoldRepository holdRepository,
      InitialAdmissionBindHoldService holdService,
      ObjectProvider<GameSessionInitialAdmissionBindProofClient> proofClientProvider,
      Clock clock) {
    this.holdRepository = holdRepository;
    this.holdService = holdService;
    this.proofClientProvider = proofClientProvider;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${world.initial-admission-bind.reconcile-delay-ms:5000}")
  public void reconcilePendingHolds() {
    List<net.firedevops.firemud.worldmanagement.entity.InitialAdmissionBindHold> holds =
        holdRepository.findNonterminal(32);
    if (holds.isEmpty()) {
      return;
    }
    GameSessionInitialAdmissionBindProofClient proofClient = proofClientProvider.getIfAvailable();
    for (var hold : holds) {
      try {
        reconcileHold(hold, proofClient);
      } catch (RuntimeException exception) {
        logger.warn(
            "Initial admission bind reconciliation failed holdId={}", hold.holdId(), exception);
      }
    }
  }

  private void reconcileHold(
      net.firedevops.firemud.worldmanagement.entity.InitialAdmissionBindHold hold,
      GameSessionInitialAdmissionBindProofClient proofClient) {
    if (proofClient == null) {
      holdService.requireReconciliation(hold.holdId(), "GS_OWNER_READ_UNAVAILABLE");
      return;
    }
    if (!hold.diagnosticExpiresAt().isAfter(clock.instant())) {
      logger.warn(
          "Initial admission bind hold exceeded its diagnostic age holdId={} tenant={} gameInstanceId={}",
          hold.holdId(),
          hold.tenantId(),
          hold.gameInstanceId());
    }
    try {
      holdService.reconcileOwnerProof(hold.holdId(), proofClient.readOwnerProof(hold));
    } catch (RuntimeException exception) {
      logger.warn(
          "Initial admission bind owner readback failed holdId={} errorType={}",
          hold.holdId(),
          exception.getClass().getSimpleName());
      holdService.requireReconciliation(hold.holdId(), "GS_OWNER_READ_FAILED");
    }
  }
}
