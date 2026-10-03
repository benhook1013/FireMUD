package net.firedevops.firemud.worldmanagement.service.impl;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import net.firedevops.firemud.worldmanagement.client.GameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.dto.InitialAdmissionBindOwnerProof;
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
  private static final String OVERDUE_OBSERVATIONS_METRIC =
      "world_initial_admission_bind_overdue_observations_total";

  private final InitialAdmissionBindHoldRepository holdRepository;
  private final InitialAdmissionBindHoldService holdService;
  private final ObjectProvider<GameSessionInitialAdmissionBindProofClient> proofClientProvider;
  private final Clock clock;
  private final MeterRegistry meterRegistry;

  @Autowired
  public InitialAdmissionBindHoldReconciler(
      InitialAdmissionBindHoldRepository holdRepository,
      InitialAdmissionBindHoldService holdService,
      ObjectProvider<GameSessionInitialAdmissionBindProofClient> proofClientProvider,
      MeterRegistry meterRegistry) {
    this(holdRepository, holdService, proofClientProvider, Clock.systemUTC(), meterRegistry);
  }

  InitialAdmissionBindHoldReconciler(
      InitialAdmissionBindHoldRepository holdRepository,
      InitialAdmissionBindHoldService holdService,
      ObjectProvider<GameSessionInitialAdmissionBindProofClient> proofClientProvider,
      Clock clock,
      MeterRegistry meterRegistry) {
    this.holdRepository = holdRepository;
    this.holdService = holdService;
    this.proofClientProvider = proofClientProvider;
    this.clock = clock;
    this.meterRegistry = meterRegistry;
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
      boolean overdue = !hold.diagnosticExpiresAt().isAfter(clock.instant());
      String errorCode = boundedErrorCode(hold.reconciliationError());
      try {
        errorCode = reconcileHold(hold, proofClient);
      } catch (RuntimeException exception) {
        logger.warn(
            "Initial admission bind reconciliation failed holdId={} tenantId={} gameInstanceId={} errorType={}",
            hold.holdId(),
            hold.tenantId(),
            hold.gameInstanceId(),
            exception.getClass().getSimpleName());
        errorCode = "other";
      } finally {
        if (overdue) {
          String boundedCode = boundedErrorCode(errorCode);
          meterRegistry.counter(OVERDUE_OBSERVATIONS_METRIC, "error_code", boundedCode).increment();
          logger.warn(
              "Initial admission bind hold exceeded its diagnostic age holdId={} tenantId={} gameInstanceId={} errorCode={}",
              hold.holdId(),
              hold.tenantId(),
              hold.gameInstanceId(),
              boundedCode);
        }
      }
    }
  }

  private String reconcileHold(
      net.firedevops.firemud.worldmanagement.entity.InitialAdmissionBindHold hold,
      GameSessionInitialAdmissionBindProofClient proofClient) {
    if (proofClient == null) {
      holdService.requireReconciliation(hold.holdId(), "GS_OWNER_READ_UNAVAILABLE");
      return "GS_OWNER_READ_UNAVAILABLE";
    }

    InitialAdmissionBindOwnerProof ownerProof;
    try {
      ownerProof = proofClient.readOwnerProof(hold);
    } catch (RuntimeException exception) {
      logger.warn(
          "Initial admission bind owner readback failed holdId={} tenantId={} gameInstanceId={} errorType={}",
          hold.holdId(),
          hold.tenantId(),
          hold.gameInstanceId(),
          exception.getClass().getSimpleName());
      holdService.requireReconciliation(hold.holdId(), "GS_OWNER_READ_FAILED");
      return "GS_OWNER_READ_FAILED";
    }

    var reconciled = holdService.reconcileOwnerProof(hold.holdId(), ownerProof);
    if ("COMMITTED".equals(reconciled.status()) || "ABORTED".equals(reconciled.status())) {
      return "none";
    }
    return ownerProofErrorCode(ownerProof);
  }

  private static String ownerProofErrorCode(InitialAdmissionBindOwnerProof ownerProof) {
    if (ownerProof == null || ownerProof.outcome() == null) {
      return "GS_OWNER_PROOF_MISSING";
    }
    return switch (ownerProof.outcome()) {
      case NOT_FOUND -> "GS_OWNER_NOT_FOUND";
      case PENDING -> "GS_OWNER_PENDING";
      case UNAVAILABLE -> "GS_OWNER_UNAVAILABLE";
      case ERROR -> "GS_OWNER_ERROR";
      case COMMITTED, ABORTED -> "other";
    };
  }

  private static String boundedErrorCode(String errorCode) {
    if (errorCode == null || errorCode.isBlank()) {
      return "none";
    }
    return switch (errorCode) {
      case "none",
          "GS_OWNER_READ_UNAVAILABLE",
          "GS_OWNER_READ_FAILED",
          "GS_OWNER_PROOF_MISSING",
          "GS_OWNER_NOT_FOUND",
          "GS_OWNER_PENDING",
          "GS_OWNER_UNAVAILABLE",
          "GS_OWNER_ERROR",
          "GS_OWNER_PROOF_MISMATCH",
          "GS_OWNER_TERMINAL_PROOF_INCOMPLETE",
          "WORLD_LIFECYCLE_PROOF_MISMATCH" ->
          errorCode;
      default -> "other";
    };
  }
}
