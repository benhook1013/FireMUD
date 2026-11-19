package net.firedevops.firemud.accountservice.service.session;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeEntry;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeState;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationPhase;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.common.prev1.PreV1GateWaivers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pre-v1 stand-in (ADR 0185) for the missing validator readiness orchestrator.
 *
 * <p>Account's signer promotion needs a durable readiness receipt from every expected validator
 * Pod, but no Pod-bound receiver client or orchestrator exists yet. Under the pre-v1 gate waiver
 * this driver plans the current generation, issues each probe through the real probe service, and
 * records a self-attested acceptance for each expected Pod from the protected inventory. Genuine
 * per-Pod verification is owed before v1 and listed in the player access tracker.
 */
public final class PreV1SelfAttestedReadinessDriver {
  private static final Logger LOG = LoggerFactory.getLogger(PreV1SelfAttestedReadinessDriver.class);

  private final PreV1GateWaivers waivers;
  private final AccountJwtSignerMaterializerTrustBinding materializerTrust;
  private final AccountJwtReadinessTrustBinding readinessTrust;
  private final AccountJwtSignerDesiredStateRepository desiredState;
  private final AccountJwtReadinessProbeRepository readinessRepository;
  private final AccountJwtReadinessProbeService probes;
  private final TransactionTemplate transaction;
  private final Clock clock;
  // The one-shot claim is durable; reuse it if issuing failed after it was persisted.
  private AccountJwtReadinessProbeRepository.DeliveryClaim claimed;

  public PreV1SelfAttestedReadinessDriver(
      PreV1GateWaivers waivers,
      AccountJwtSignerMaterializerTrustBinding materializerTrust,
      AccountJwtReadinessTrustBinding readinessTrust,
      AccountJwtSignerDesiredStateRepository desiredState,
      AccountJwtReadinessProbeRepository readinessRepository,
      AccountJwtReadinessProbeService probes,
      TransactionTemplate transaction,
      Clock clock) {
    if (!waivers.enabled()) {
      throw new IllegalStateException("Self-attested readiness exists only under the waiver");
    }
    this.waivers = waivers;
    this.materializerTrust = Objects.requireNonNull(materializerTrust);
    this.readinessTrust = Objects.requireNonNull(readinessTrust);
    this.desiredState = Objects.requireNonNull(desiredState);
    this.readinessRepository = Objects.requireNonNull(readinessRepository);
    this.probes = Objects.requireNonNull(probes);
    this.transaction = Objects.requireNonNull(transaction);
    this.clock = Objects.requireNonNull(clock);
  }

  /**
   * One bounded pass; safe to repeat. Returns true when every planned probe has a receipt. Never
   * throws, because a scheduled task that throws is silently never run again.
   */
  public boolean driveOnce() {
    if (!waivers.enabled()) {
      return false;
    }
    try {
      return drive();
    } catch (RuntimeException notYet) {
      LOG.info(
          "Pre-v1 self-attested readiness not complete yet: {}: {}",
          notYet.getClass().getName(),
          notYet.getMessage());
      return false;
    }
  }

  private boolean drive() {
    var materializer = materializerTrust.current().orElse(null);
    var readiness = readinessTrust.current().orElse(null);
    if (materializer == null || readiness == null) {
      return false;
    }
    var binding = materializer.accountBinding();
    TrustFence trust =
        new TrustFence(
            materializer.expectedClusterIncarnationUid(),
            materializer.expectedNamespaceUid(),
            materializer.bindingDigest(),
            materializer.configRevision());
    var request =
        transaction.execute(status -> desiredState.readCurrentGenerationRequest(binding, trust));
    if (request == null || request.phase() != GenerationPhase.GENERATION_RECORDED) {
      return false;
    }
    UUID operationId = request.operationId();
    AccountJwtReadinessProbeRepository.ReadinessProbePlan plan;
    try {
      plan = probes.readCurrentPlan(binding, trust, operationId);
    } catch (RuntimeException absent) {
      plan = probes.planCurrent(binding, trust);
    }
    if (clock.instant().getEpochSecond() < plan.notBeforeEpochSecond()) {
      return false;
    }
    var entries = plan.entries();
    if (entries.stream().allMatch(entry -> entry.state() == ProbeState.PLANNED)) {
      var current = plan;
      if (claimed == null || !claimed.rotationOperationId().equals(operationId)) {
        claimed =
            transaction.execute(
                status ->
                    readinessRepository.claimSingleDelivery(
                        binding,
                        trust,
                        current,
                        readiness,
                        new AccountJwtReadinessTrustBinding.PeerIdentity(
                            readiness.expectedPeerUri(), readiness.peerSpkiSha256Pins().get(0)),
                        Instant.ofEpochSecond(clock.instant().getEpochSecond())));
      }
      entries =
          probes.issueCurrentPlan(binding, trust, operationId, claimed, (metadata, jwt) -> {});
    }
    var issued = entries;
    boolean complete = true;
    for (ProbeEntry entry : issued) {
      if (entry.state() != ProbeState.ISSUED) {
        complete &= entry.verificationReceipt().isPresent();
        continue;
      }
      for (var pod : probes.readCurrentExpectedPods(binding, trust, entry)) {
        var target = pod.target();
        var acceptance =
            AccountJwtReadinessReceiverInvocationPort.authenticatedAcceptance(
                target.podUid(),
                target.exactPodEndpoint().orElseThrow().toString(),
                target.image(),
                target.verifierConfigSha256(),
                target.canonicalServiceUri().orElseThrow(),
                target.podLeafSpkiSha256().orElseThrow(),
                entry.jti(),
                entry.compactTokenSha256().orElseThrow(),
                entry.targetKid(),
                target.expectation(),
                clock.instant().getEpochSecond());
        probes.recordPodAcceptance(binding, trust, entry, pod, acceptance);
      }
    }
    return complete;
  }
}
