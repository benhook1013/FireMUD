package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparation.Input;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparation.Result;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered, default-denied owner operation for a canonical generation-free world instance.
 *
 * <p>No production verifier is supplied in this slice. A future authenticated integration must
 * verify the same-namespace Game Session producer and exact current Game Design release/terminal
 * source before the World transaction begins. The original Account-authorized APPLIED result is
 * immutable input evidence; preparation must not reauthorize the creator or reuse Draft/publication
 * permission. Its held producer evidence must remain valid through commit. This class is
 * deliberately not a Spring component and adds no RPC or public activation path.
 */
public final class WorldCanonicalInstancePreparationService {
  private final WorldCanonicalInstancePreparationRepository repository;
  private final CommitAuthorityVerifier verifier;
  private final OriginalOperationRecoveryVerifier recoveryVerifier;

  /** Constructs the production-safe default: all attempts fail closed. */
  public WorldCanonicalInstancePreparationService(
      WorldCanonicalInstancePreparationRepository repository) {
    this(
        repository,
        WorldCanonicalInstancePreparationService::denyByDefault,
        WorldCanonicalInstancePreparationService::denyRecoveryByDefault);
  }

  /** Constructor for an explicitly supplied owner verifier, including labeled synthetic tests. */
  public WorldCanonicalInstancePreparationService(
      WorldCanonicalInstancePreparationRepository repository, CommitAuthorityVerifier verifier) {
    this(repository, verifier, WorldCanonicalInstancePreparationService::denyRecoveryByDefault);
  }

  /**
   * Constructor for explicit fresh-execution and original-operation recovery verifiers. No
   * production verifier is supplied in this slice.
   */
  public WorldCanonicalInstancePreparationService(
      WorldCanonicalInstancePreparationRepository repository,
      CommitAuthorityVerifier verifier,
      OriginalOperationRecoveryVerifier recoveryVerifier) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.verifier = Objects.requireNonNull(verifier, "verifier");
    this.recoveryVerifier = Objects.requireNonNull(recoveryVerifier, "recoveryVerifier");
  }

  /**
   * Verifies before opening the owner transaction and returns only committed exact readback.
   * Current lifecycle state is never rewritten by a historical preparation retry.
   */
  public Result prepare(Input input) {
    Objects.requireNonNull(input, "input");
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Canonical World preparation must verify outside an ambient transaction");
    }
    WorldCanonicalInstancePreparation.requireGenerationFree(input.topologyPlan());

    HeldCommitAuthority held =
        Objects.requireNonNull(
            verifier.verifyAndHold(input),
            "Canonical preparation verifier returned no held commit authority");
    try (held) {
      held.requireHeld();
      return repository.materialize(input, held);
    }
  }

  /**
   * Authenticates and reads only the exact retained World operation. This path deliberately does
   * not obtain a held commit authority, reassemble external sources, materialize, or abort. It is
   * available after original authorization expiry without granting a mutation retry. An absent or
   * PENDING lookup is unresolved evidence, not an abort, settlement, or admission. The separate
   * serialized durable-abort boundary remains distinct from this read-only lookup.
   */
  public WorldCanonicalInstancePreparationRepository.ExecutionLookup recoverExact(
      WorldCanonicalInstanceExecutionIdentity originalIdentity) {
    Objects.requireNonNull(originalIdentity, "original World execution identity is required");
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Exact World execution recovery must authenticate outside an ambient transaction");
    }

    recoveryVerifier.verifyOriginalOperation(originalIdentity);
    return repository.readExactExecution(originalIdentity);
  }

  /**
   * Reads the exact durable COMMITTED or ABORTED World terminal through authenticated recovery.
   * Missing and PENDING operations remain unresolved and therefore have no terminal receipt.
   */
  public Optional<WorldStartSessionExecutionTerminal> recoverExactTerminal(
      WorldCanonicalInstanceExecutionIdentity originalIdentity) {
    return recoverExact(originalIdentity)
        .operation()
        .filter(
            operation ->
                operation.state()
                        == WorldCanonicalInstancePreparationRepository.ExecutionState.COMMITTED
                    || operation.state()
                        == WorldCanonicalInstancePreparationRepository.ExecutionState.ABORTED)
        .map(
            operation ->
                new WorldStartSessionExecutionTerminal(
                    originalIdentity.originalPostAuthorizationTuple(),
                    originalIdentity.accountWorldParticipationId(),
                    originalIdentity.accountWorldParticipationFence(),
                    originalIdentity.gameSessionOwnerAttemptId(),
                    originalIdentity.gameSessionOwnerFence(),
                    originalIdentity.targetNamespace(),
                    originalIdentity.canonicalTenantId(),
                    originalIdentity.controlPlaneRequestId(),
                    originalIdentity.canonicalGameInstanceId(),
                    originalIdentity.preparationInputDigest(),
                    originalIdentity.preparationInputJson(),
                    operation.worldExecutionFence(),
                    operation.state()
                            == WorldCanonicalInstancePreparationRepository.ExecutionState.COMMITTED
                        ? WorldStartSessionExecutionTerminal.Outcome.COMMITTED
                        : WorldStartSessionExecutionTerminal.Outcome.ABORTED));
  }

  private static HeldCommitAuthority denyByDefault(Input input) {
    throw new PreparationDeniedException(
        "Canonical World preparation has no authenticated source/release terminal verifier");
  }

  private static void denyRecoveryByDefault(WorldCanonicalInstanceExecutionIdentity identity) {
    throw new PreparationDeniedException(
        "Canonical World execution recovery has no authenticated original-operation verifier");
  }

  /** Performs authenticated producer checks before returning a continuously held local fence. */
  @FunctionalInterface
  public interface CommitAuthorityVerifier {
    HeldCommitAuthority verifyAndHold(Input input);
  }

  /**
   * Authenticates the exact configured same-namespace Game Session producer and verifies the
   * retained original Account participation, actual Game Session attempt/fence, and immutable
   * descriptor, release, source association, and canonical World target in {@code
   * originalIdentity}. The typed identity is integrity input, not permission. Implementations must
   * not reacquire, refresh, or renew participation or authorization, and must perform all remote
   * checks before the exact read-only repository lookup.
   */
  @FunctionalInterface
  public interface OriginalOperationRecoveryVerifier {
    void verifyOriginalOperation(WorldCanonicalInstanceExecutionIdentity originalIdentity);
  }

  /**
   * A verifier-owned, continuously held producer-evidence handle. It stays open throughout the
   * World transaction and its commit; implementations must fail closed from {@link #requireHeld()}
   * if the source participation/fence they verified is no longer continuously held.
   * Remote/current-state checks happen before the World transaction; {@code requireHeld()} is a
   * local fence/effective-state assertion and must never perform RPC while World holds database
   * locks. It must not reapply the original ingress-authority expiry after the V35 SQL admission
   * check: an uninterrupted admitted transaction may finish after that bound while the source
   * participation remains protected through exact World COMMITTED or durable ABORTED settlement.
   * {@link #close()} may release only local resources; it must never release durable Account
   * participation merely because this call returned, timed out, or has an uncertain outcome. That
   * participation remains protected until an authenticated exact World COMMITTED result or fenced
   * durable ABORTED settlement is known. It is not a new Account creator permission.
   */
  public interface HeldCommitAuthority extends AutoCloseable {
    void requireHeld();

    /**
     * Returns the exact typed identity this continuously held verifier authenticated. There is no
     * default identity: old generic no-op handles cannot authorize the new World SQL path.
     */
    default WorldCanonicalInstanceExecutionIdentity executionIdentity() {
      throw new PreparationDeniedException(
          "Canonical World preparation requires a complete typed StartSession execution identity");
    }

    @Override
    void close();
  }

  public static final class PreparationDeniedException extends IllegalStateException {
    public PreparationDeniedException(String message) {
      super(message);
    }
  }
}
