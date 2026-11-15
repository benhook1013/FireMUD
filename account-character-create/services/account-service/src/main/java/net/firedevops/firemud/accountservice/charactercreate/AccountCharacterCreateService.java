package net.firedevops.firemud.accountservice.charactercreate;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.accountservice.charactercreate.AccountCharacterCreateRepository.Protection;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl.PlayerBootstrapPrincipal;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorActionCodec;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Account-owned producer preparation and exact original-source reader. Preparation consumes an
 * Account-verified player-bootstrap capability and revalidates it before accessing owner state.
 * Deliberately has no controller, bean or routable ingress registration until genuine coupled owner
 * composition is proved.
 */
public final class AccountCharacterCreateService {
  private static final Set<String> CREATION_SOURCE_READ_OWNERS =
      Set.of("entity-management-service", "game-session-service", "world-management-service");
  private final AccountCharacterCreateRepository repository;
  private final AccountCharacterCreateOwnerClients owners;
  private final TransactionTemplate transaction;
  private final String namespace;
  private final Clock clock;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The injected repository is the shared Account owner for these transactions and is not a copyable value.")
  public AccountCharacterCreateService(
      AccountCharacterCreateRepository repository,
      AccountCharacterCreateOwnerClients owners,
      PlatformTransactionManager transactions,
      String namespace,
      Clock clock) {
    this.repository = Objects.requireNonNull(repository);
    this.owners = Objects.requireNonNull(owners);
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Fixed canonical Account workload namespace required");
    }
    this.namespace = namespace;
    this.clock = Objects.requireNonNull(clock);
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setReadOnly(false);
  }

  /**
   * Retains the exact resolved source first, then protects Account and delegates. A return only
   * means delegation was submitted; only Entity's genuine immutable terminal can prove commit or
   * abort. Missing peers fail unavailable, leaving any committed protection pending genuine
   * authenticated immutable Entity terminal evidence.
   */
  public void submit(
      PlayerBootstrapPrincipal principal, UUID createCharacterRequestId, String connectScopeId) {
    requireOutsideTransaction();
    if (principal == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Authenticated player-bootstrap principal required")
          .asRuntimeException();
    }
    UUID accountId = Objects.requireNonNull(principal.authenticateCurrentAccount());
    AccountCharacterCreateIntent.requireId(createCharacterRequestId);
    AccountCharacterCreateIntent.requireConnectScope(connectScopeId);

    AccountCharacterCreateIntent original =
        transaction.execute(
            status ->
                repository
                    .findOriginal(namespace, accountId, createCharacterRequestId)
                    .orElse(null));
    if (original == null) {
      var source =
          Objects.requireNonNull(
              owners.resolvePublicProduction(namespace, principal, connectScopeId),
              "Genuine resolved owner source required");
      var candidate =
          new AccountCharacterCreateIntent(
              UUID.randomUUID(),
              createCharacterRequestId,
              connectScopeId,
              accountId,
              source.actionBytes(),
              CharacterCreateOperatorActionCodec.mutationDigest(source.actionBytes()),
              source.originalSourceReadback());
      if (!namespace.equals(candidate.targetNamespace())) throw denied();
      original =
          Objects.requireNonNull(transaction.execute(status -> repository.retain(candidate)));
    }
    if (!accountId.equals(original.accountId())
        || !createCharacterRequestId.equals(original.createCharacterRequestId())
        || !connectScopeId.equals(original.connectScopeId())
        || !namespace.equals(original.targetNamespace())) throw denied();

    // A fresh transaction proves the full retained winner is committed before any owner hold.
    AccountCharacterCreateIntent exact = original;
    AccountCharacterCreateIntent readback =
        Objects.requireNonNull(
            transaction.execute(
                status -> repository.findOperation(namespace, exact.operationId()).orElseThrow()));
    if (!exact.sameBinding(readback)) throw denied();
    owners.requireExactOriginalSource(readback);
    requireUnexpired(readback);
    Protection acquired =
        Objects.requireNonNull(transaction.execute(status -> repository.acquire(readback)));
    Protection committed =
        Objects.requireNonNull(
            transaction.execute(status -> repository.findProtection(readback).orElseThrow()));
    if (!acquired.sameBinding(committed)) throw denied();
    requireUnexpired(readback);
    owners.delegateProtected(readback, committed);
  }

  /** Exact original intent read, qualified by a creation owner's actual same-namespace peer. */
  public AccountCharacterCreateIntent readOriginalForOwner(
      UUID operationId, String mutationDigest) {
    requireCreationOwnerPeer();
    requireOutsideTransaction();
    AccountCharacterCreateIntent.requireId(operationId);
    AccountCharacterCreateIntent retained =
        Objects.requireNonNull(
            transaction.execute(
                status -> repository.findOperation(namespace, operationId).orElseThrow()));
    if (!retained.mutationDigest().equals(mutationDigest)) throw denied();
    return retained;
  }

  /** Historical protection read for reconciliation; it makes no expiry or terminal inference. */
  public Protection readProtectionForOwner(
      UUID operationId, String mutationDigest, UUID protectionId, long protectionFence) {
    AccountCharacterCreateIntent retained = readOriginalForOwner(operationId, mutationDigest);
    Protection protection =
        Objects.requireNonNull(
            transaction.execute(status -> repository.findProtection(retained).orElseThrow()));
    if (!protection.protectionId().equals(protectionId)
        || protection.protectionFence() != protectionFence) throw denied();
    return protection;
  }

  private void requireUnexpired(AccountCharacterCreateIntent intent) {
    if (!clock.instant().isBefore(intent.action().expectedVersion().connectScopeExpiresAt())) {
      throw Status.FAILED_PRECONDITION
          .withDescription(
              "Original CHARACTER_CREATE authority expired; protection remains pending")
          .asRuntimeException();
    }
  }

  private void requireCreationOwnerPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null
        || !namespace.equals(peer.namespace())
        || !CREATION_SOURCE_READ_OWNERS.contains(peer.service())
        || SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()) throw denied();
  }

  private static void requireOutsideTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("CHARACTER_CREATE must own its Account transactions");
    }
  }

  private static RuntimeException denied() {
    return Status.PERMISSION_DENIED
        .withDescription("Exact authenticated CHARACTER_CREATE binding required")
        .asRuntimeException();
  }
}
