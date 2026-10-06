package net.firedevops.firemud.accountservice.authordraft;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Ordering;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Settlement;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.common.authoring.GameDesignDraftTerminalReadClient;
import net.firedevops.firemud.common.authoring.GameDesignDraftTerminalReadEvidence;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadClient;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered two-owner recovery consumer. Exact authenticated owner readbacks recover only the
 * original V57 settlement; this component creates no authorization, source mutation or activation.
 */
public final class AccountDraftTerminalReconciliationService {
  private final DraftAuthorizationFenceRepository repository;
  private final GameDesignDraftTerminalReadClient gameDesignClient;
  private final WorldDraftTerminalReadClient worldClient;
  private final String namespace;
  private final TransactionTemplate ownerTransaction;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The injected owner repository is intentionally shared, not a value object.")
  public AccountDraftTerminalReconciliationService(
      DraftAuthorizationFenceRepository repository,
      PlatformTransactionManager transactionManager,
      GameDesignDraftTerminalReadClient gameDesignClient,
      WorldDraftTerminalReadClient worldClient,
      String namespace) {
    this.repository = Objects.requireNonNull(repository);
    this.gameDesignClient = Objects.requireNonNull(gameDesignClient);
    this.worldClient = Objects.requireNonNull(worldClient);
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical owner workload namespace required");
    }
    this.namespace = namespace;
    ownerTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    ownerTransaction.setReadOnly(false);
  }

  /**
   * Recovers only missing exact participant readbacks. A missing or timed-out owner stays unknown;
   * the remote reads run between two short Account owner transactions.
   */
  public Optional<Settlement> reconcile(UUID operationId) {
    DraftAuthorizationFenceBinding.requireUuid(operationId);
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Draft terminal recovery cannot join an ambient transaction");
    }

    Optional<Snapshot> original =
        Objects.requireNonNull(
            ownerTransaction.execute(
                status -> repository.readOriginalBinding(operationId).map(this::snapshot)));
    if (original.isEmpty()) return Optional.empty();
    Snapshot before = original.orElseThrow();
    if (before.ordering() == Ordering.RESERVED || before.settlement() != Settlement.PENDING) {
      return Optional.of(before.settlement());
    }

    Optional<OwnerReadback> gameDesignReadback =
        before.gameDesignReadback().isEmpty() ? readGameDesign(before.binding()) : Optional.empty();
    Optional<OwnerReadback> worldReadback =
        before.worldReadback().isEmpty() ? readWorld(before.binding()) : Optional.empty();

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
              retainExactMissing(current, Owner.GAME_DESIGN, gameDesignReadback, after);
              retainExactMissing(current, Owner.WORLD, worldReadback, after);
              return repository.readSettlement(current);
            });
    return Optional.of(Objects.requireNonNull(settled));
  }

  private Optional<OwnerReadback> readGameDesign(DraftAuthorizationFenceBinding binding) {
    GameDesignDraftTerminalReadEvidence.Request request =
        GameDesignDraftTerminalReadEvidence.Request.create(namespace, binding.canonicalBytes());
    GameDesignDraftTerminalReadEvidence response =
        Objects.requireNonNull(gameDesignClient.read(request));
    if (!request.equals(Objects.requireNonNull(response.request()))) {
      throw new IllegalArgumentException(
          "Game Design terminal response changed the exact read request");
    }
    return Objects.requireNonNull(response.ownerReadback())
        .map(readback -> verifyOwnerReadback(binding, Owner.GAME_DESIGN, readback));
  }

  private Optional<OwnerReadback> readWorld(DraftAuthorizationFenceBinding binding) {
    WorldDraftTerminalReadEvidence.Request request =
        WorldDraftTerminalReadEvidence.Request.create(namespace, binding.canonicalBytes());
    WorldDraftTerminalReadEvidence response = Objects.requireNonNull(worldClient.read(request));
    if (!request.equals(Objects.requireNonNull(response.request()))) {
      throw new IllegalArgumentException("World terminal response changed the exact read request");
    }
    return Objects.requireNonNull(response.ownerReadback())
        .map(readback -> verifyOwnerReadback(binding, Owner.WORLD, readback));
  }

  private Snapshot snapshot(DraftAuthorizationFenceBinding binding) {
    Ordering ordering = repository.read(binding).ordering();
    Optional<OwnerReadback> gameDesign = readStoredOwnerResult(binding, Owner.GAME_DESIGN);
    Optional<OwnerReadback> world = readStoredOwnerResult(binding, Owner.WORLD);
    return new Snapshot(binding, ordering, repository.readSettlement(binding), gameDesign, world);
  }

  private Optional<OwnerReadback> readStoredOwnerResult(
      DraftAuthorizationFenceBinding binding, Owner owner) {
    return repository
        .readOwnerResult(binding, owner)
        .map(
            stored -> {
              OwnerReadback readback = OwnerReadback.fromStored(stored.readback());
              if (stored.outcome() != readback.outcome()) {
                throw new IllegalStateException("Stored Draft outcome differs from its readback");
              }
              return verifyOwnerReadback(binding, owner, readback);
            });
  }

  private static OwnerReadback verifyOwnerReadback(
      DraftAuthorizationFenceBinding binding, Owner expectedOwner, OwnerReadback readback) {
    Objects.requireNonNull(readback);
    if (readback.owner() != expectedOwner || readback.result().length == 0) {
      throw new IllegalArgumentException(
          "Owner terminal result is absent or belongs to another owner");
    }
    readback.requireBinding(binding);
    OwnerReadback decoded = OwnerReadback.fromStored(readback.canonicalBytes());
    decoded.requireBinding(binding);
    if (decoded.owner() != expectedOwner
        || decoded.outcome() != readback.outcome()
        || !Arrays.equals(decoded.result(), readback.result())) {
      throw new IllegalArgumentException(
          "Owner terminal result differs from its canonical readback");
    }
    return decoded;
  }

  private void retainExactMissing(
      DraftAuthorizationFenceBinding binding,
      Owner owner,
      Optional<OwnerReadback> candidate,
      Snapshot after) {
    if (candidate.isEmpty()) return;
    OwnerReadback verified = verifyOwnerReadback(binding, owner, candidate.orElseThrow());
    Optional<OwnerReadback> existing =
        owner == Owner.GAME_DESIGN ? after.gameDesignReadback() : after.worldReadback();
    if (existing.isPresent()) {
      if (!Arrays.equals(existing.orElseThrow().canonicalBytes(), verified.canonicalBytes())) {
        throw new IllegalArgumentException("Concurrent owner recovery changed immutable evidence");
      }
      return;
    }
    repository.recordOwnerReadback(binding, verified);
  }

  private record Snapshot(
      DraftAuthorizationFenceBinding binding,
      Ordering ordering,
      Settlement settlement,
      Optional<OwnerReadback> gameDesignReadback,
      Optional<OwnerReadback> worldReadback) {}
}
