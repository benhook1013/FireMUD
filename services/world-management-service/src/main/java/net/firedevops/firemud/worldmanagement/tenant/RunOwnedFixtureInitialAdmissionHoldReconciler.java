package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionCanonicalInitialAdmissionOwnerClient;
import net.firedevops.firemud.worldmanagement.data.RunOwnedCanonicalWorldLaunchEvidenceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Recovers the configured fixture's World hold from actual Game Session owner evidence. */
public final class RunOwnedFixtureInitialAdmissionHoldReconciler {
  private static final Logger LOG =
      LoggerFactory.getLogger(RunOwnedFixtureInitialAdmissionHoldReconciler.class);

  private final WorldCanonicalInitialAdmissionHoldRepository holds;
  private final RunOwnedCanonicalWorldLaunchEvidenceProvider sourceProvider;
  private final GrpcGameSessionCanonicalInitialAdmissionOwnerClient gameSession;

  public RunOwnedFixtureInitialAdmissionHoldReconciler(
      WorldCanonicalInitialAdmissionHoldRepository holds,
      RunOwnedCanonicalWorldLaunchEvidenceProvider sourceProvider,
      GrpcGameSessionCanonicalInitialAdmissionOwnerClient gameSession) {
    this.holds = Objects.requireNonNull(holds, "holds");
    this.sourceProvider = Objects.requireNonNull(sourceProvider, "sourceProvider");
    this.gameSession = Objects.requireNonNull(gameSession, "gameSession");
  }

  @Scheduled(fixedDelayString = "${world.initial-admission-bind.reconcile-delay-ms:5000}")
  public void reconcilePendingHold() {
    try {
      var receipt = sourceProvider.requireConfiguredFixtureReceipt();
      var source = receipt.sourceEvidence();
      String provenance = receipt.fixturePolicySeal().evidenceDigest();
      String requestId = "run-owned-fixture-" + receipt.fixtureOperationId();
      holds
          .readPendingFixtureHold(
              source.targetNamespace(), source.canonicalTenantId(), requestId, provenance)
          .ifPresent(
              identity ->
                  holds.settleFixture(
                      identity,
                      provenance,
                      gameSession.readFixture(
                          identity, provenance, receipt.gameSessionLeafSha256())));
    } catch (RuntimeException unavailable) {
      LOG.debug(
          "Run-owned fixture hold remains pending after {}",
          unavailable.getClass().getSimpleName());
    }
  }
}
