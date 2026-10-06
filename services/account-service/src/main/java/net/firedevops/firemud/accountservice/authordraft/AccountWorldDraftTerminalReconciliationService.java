package net.firedevops.firemud.accountservice.authordraft;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Ordering;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Settlement;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadClient;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered recovery consumer for the original World definitive-abort readback only. This
 * component creates no authorization, Game Design outcome, source mutation or runtime activation.
 */
public final class AccountWorldDraftTerminalReconciliationService {
  private final DraftAuthorizationFenceRepository repository;
  private final WorldDraftTerminalReadClient client;
  private final String namespace;
  private final TransactionTemplate ownerTransaction;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The injected owner repository is intentionally shared, not a value object.")
  public AccountWorldDraftTerminalReconciliationService(
      DraftAuthorizationFenceRepository repository,
      PlatformTransactionManager transactionManager,
      WorldDraftTerminalReadClient client,
      String namespace) {
    this.repository = Objects.requireNonNull(repository);
    this.client = Objects.requireNonNull(client);
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical World workload namespace required");
    }
    this.namespace = namespace;
    ownerTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    ownerTransaction.setReadOnly(false);
  }

  /** Absent original operations remain UNKNOWN; one owner result cannot settle both owners. */
  public Optional<Settlement> reconcileWorld(UUID operationId) {
    DraftAuthorizationFenceBinding.requireUuid(operationId);
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("World terminal recovery cannot join an ambient transaction");
    }
    Optional<Snapshot> original =
        Objects.requireNonNull(
            ownerTransaction.execute(
                status -> repository.readOriginalBinding(operationId).map(this::snapshot)));
    if (original.isEmpty()) return Optional.empty();
    Snapshot before = original.orElseThrow();
    if (before.ordering() == Ordering.RESERVED
        || before.worldPresent()
        || before.settlement() != Settlement.PENDING) {
      return Optional.of(before.settlement());
    }

    var request =
        WorldDraftTerminalReadEvidence.Request.create(namespace, before.binding().canonicalBytes());
    WorldDraftTerminalReadEvidence response = Objects.requireNonNull(client.read(request));
    requireExactResponse(request, response);
    Optional<OwnerReadback> terminal = response.ownerReadback();
    terminal.ifPresent(
        readback -> {
          if (readback.owner() != Owner.WORLD
              || readback.outcome() != Outcome.DEFINITIVELY_ABORTED) {
            throw new IllegalArgumentException("Only World definitive abort readback is supported");
          }
          OwnerReadback.fromStored(readback.canonicalBytes()).requireBinding(before.binding());
          readback.requireBinding(before.binding());
        });

    Settlement settled =
        ownerTransaction.execute(
            status -> {
              DraftAuthorizationFenceBinding current =
                  repository
                      .readOriginalBinding(operationId)
                      .orElseThrow(
                          () -> new IllegalStateException("Original Draft operation disappeared"));
              if (!Arrays.equals(current.canonicalBytes(), before.binding().canonicalBytes())) {
                throw new IllegalStateException("Original Draft binding changed during recovery");
              }
              Snapshot after = snapshot(current);
              if (after.ordering() != before.ordering()) {
                throw new IllegalStateException("Original Draft ordering changed during recovery");
              }
              if (terminal.isEmpty()) return after.settlement();
              repository.recordOwnerReadback(current, terminal.orElseThrow());
              return repository.readSettlement(current);
            });
    return Optional.of(Objects.requireNonNull(settled));
  }

  private Snapshot snapshot(DraftAuthorizationFenceBinding binding) {
    Ordering ordering = repository.read(binding).ordering();
    boolean worldPresent = repository.readOwnerResult(binding, Owner.WORLD).isPresent();
    repository.readOwnerResult(binding, Owner.GAME_DESIGN);
    return new Snapshot(binding, ordering, repository.readSettlement(binding), worldPresent);
  }

  private static void requireExactResponse(
      WorldDraftTerminalReadEvidence.Request expected, WorldDraftTerminalReadEvidence response) {
    var actual = Objects.requireNonNull(response.request());
    if (actual.schemaVersion() != expected.schemaVersion()
        || !actual.targetNamespace().equals(expected.targetNamespace())
        || !actual.readRequestId().equals(expected.readRequestId())
        || !Arrays.equals(actual.originalAccountBinding(), expected.originalAccountBinding())) {
      throw new IllegalArgumentException(
          "World terminal response differs from original read request");
    }
    Objects.requireNonNull(response.ownerReadback());
  }

  private record Snapshot(
      DraftAuthorizationFenceBinding binding,
      Ordering ordering,
      Settlement settlement,
      boolean worldPresent) {}
}
