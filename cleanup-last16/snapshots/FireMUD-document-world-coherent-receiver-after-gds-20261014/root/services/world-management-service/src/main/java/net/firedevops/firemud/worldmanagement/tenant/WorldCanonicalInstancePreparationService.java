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
 * <p>No fresh-execution verifier is supplied by default. An explicitly supplied integration must
 * authenticate the same-namespace Game Session peer, acquire Account's exact original
 * participation, and bind its retained first owner-attempt observation before the World transaction
 * begins. The original Account-authorized APPLIED result is immutable input evidence; preparation
 * must not reauthorize the creator or reuse Draft/publication permission. Account's pending
 * participation protects its exact source vector through terminal settlement; the unchanged Game
 * Session lease is checked at actual World admission and is not treated as a continuous remote
 * lock. This class is deliberately not a Spring component and adds no RPC or public activation
 * path. The separate explicitly supplied historical recovery verifier reads retained Account and
 * Game Session evidence; it supplies no held authority for another execution.
 */
public final class WorldCanonicalInstancePreparationService {
  private final WorldCanonicalInstancePreparationRepository repository;
  private final OriginalOperationCommitAuthorityVerifier originalOperationVerifier;
  private final OriginalOperationRecoveryVerifier recoveryVerifier;

  /** Constructs the production-safe default: all attempts fail closed. */
  public WorldCanonicalInstancePreparationService(
      WorldCanonicalInstancePreparationRepository repository) {
    this(repository, null, WorldCanonicalInstancePreparationService::denyRecoveryByDefault, true);
  }

  /** Retained legacy constructor; an untyped verifier cannot authorize a fresh execution. */
  public WorldCanonicalInstancePreparationService(
      WorldCanonicalInstancePreparationRepository repository, CommitAuthorityVerifier verifier) {
    this(repository, null, WorldCanonicalInstancePreparationService::denyRecoveryByDefault, true);
    Objects.requireNonNull(verifier, "verifier");
  }

  /** Constructor for an explicitly supplied verifier of the typed original owner operation. */
  public WorldCanonicalInstancePreparationService(
      WorldCanonicalInstancePreparationRepository repository,
      OriginalOperationCommitAuthorityVerifier verifier) {
    this(
        repository,
        Objects.requireNonNull(verifier, "verifier"),
        WorldCanonicalInstancePreparationService::denyRecoveryByDefault,
        true);
  }

  /**
   * Constructor for explicit fresh-execution and original-operation recovery verifiers. No
   * constructor registers or enables a runtime path.
   */
  public WorldCanonicalInstancePreparationService(
      WorldCanonicalInstancePreparationRepository repository,
      CommitAuthorityVerifier verifier,
      OriginalOperationRecoveryVerifier recoveryVerifier) {
    this(repository, null, recoveryVerifier, true);
    Objects.requireNonNull(verifier, "verifier");
  }

  /** Constructor for explicit fresh original-owner and recovery verifiers. */
  public WorldCanonicalInstancePreparationService(
      WorldCanonicalInstancePreparationRepository repository,
      OriginalOperationCommitAuthorityVerifier verifier,
      OriginalOperationRecoveryVerifier recoveryVerifier) {
    this(repository, Objects.requireNonNull(verifier, "verifier"), recoveryVerifier, true);
  }

  private WorldCanonicalInstancePreparationService(
      WorldCanonicalInstancePreparationRepository repository,
      OriginalOperationCommitAuthorityVerifier originalOperationVerifier,
      OriginalOperationRecoveryVerifier recoveryVerifier,
      boolean ownerTypedConstructor) {
    this.repository = Objects.requireNonNull(repository, "repository");
    if (!ownerTypedConstructor)
      throw new IllegalArgumentException("Constructor marker is required");
    this.originalOperationVerifier = originalOperationVerifier;
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
    throw new PreparationDeniedException(
        "Fresh canonical World preparation requires the exact original Game Session owner operation");
  }

  /**
   * Decodes the closed transport input before Account acquisition and delegates to the owner path.
   */
  public Result prepare(
      Input input,
      net.firedevops.firemud.common.world.CanonicalWorldInstancePreparationGrpcCodec
              .OriginalOperationInput
          wireOperationInput) {
    Objects.requireNonNull(wireOperationInput, "wireOperationInput");
    return prepare(
        input,
        new WorldCanonicalInstancePreparation.OriginalStartSessionOperationInput(
            wireOperationInput.originalPostAuthorizationTuple(),
            wireOperationInput.gameSessionOwnerMutationId(),
            wireOperationInput.gameSessionOwnerAttemptId(),
            wireOperationInput.gameSessionOwnerFence()));
  }

  /**
   * Acquires Account's original participation outside World SQL, then admits only the exact
   * owner-observation-bound World execution identity.
   */
  public Result prepare(
      Input input,
      WorldCanonicalInstancePreparation.OriginalStartSessionOperationInput operationInput) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(operationInput, "operationInput");
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Canonical World preparation must verify outside an ambient transaction");
    }
    WorldCanonicalInstancePreparation.requireGenerationFree(input.topologyPlan());

    if (originalOperationVerifier == null) {
      throw new PreparationDeniedException(
          "Canonical World preparation has no authenticated Account participation verifier");
    }
    operationInput.requireMatches(input, input.gameSessionReadEvidence().targetNamespace());
    HeldCommitAuthority held =
        Objects.requireNonNull(
            originalOperationVerifier.verifyAndHold(input, operationInput),
            "Canonical preparation verifier returned no held commit authority");
    try (held) {
      held.requireHeld();
      held.executionIdentity()
          .requireMatches(
              input, operationInput, WorldCanonicalInstancePreparationRepository.inputJson(input));
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

  private static void denyRecoveryByDefault(WorldCanonicalInstanceExecutionIdentity identity) {
    throw new PreparationDeniedException(
        "Canonical World execution recovery has no authenticated original-operation verifier");
  }

  /** Legacy untyped verifier contract; it is not used to admit fresh World materialization. */
  @FunctionalInterface
  public interface CommitAuthorityVerifier {
    HeldCommitAuthority verifyAndHold(Input input);
  }

  /** Authenticates the typed original tuple and owner attempt before Account acquisition. */
  @FunctionalInterface
  public interface OriginalOperationCommitAuthorityVerifier {
    HeldCommitAuthority verifyAndHold(
        Input input,
        WorldCanonicalInstancePreparation.OriginalStartSessionOperationInput operationInput);
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
   * A verifier-owned handle for the exact Account pending participation and owner observation. It
   * stays open through the World transaction and its commit. The Account participation is the
   * durable source-protection evidence; the Game Session observation is only an immutable sample,
   * and its unchanged lease is independently checked by World SQL at actual admission.
   * Remote/current-state checks happen before the World transaction; {@code requireHeld()} is a
   * local handle assertion and must never perform RPC while World holds database locks. It must not
   * reapply the original authorization or Game Session lease expiry after the V35 SQL admission
   * check: an uninterrupted admitted transaction may finish after those ingress bounds while the
   * Account participation remains protected through exact World COMMITTED or durable ABORTED
   * settlement. {@link #close()} may release only local resources; it must never release durable
   * Account participation merely because this call returned, timed out, or has an uncertain
   * outcome. That participation remains protected until an authenticated exact World COMMITTED
   * result or fenced durable ABORTED settlement is known. It is not a new Account creator
   * permission.
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
