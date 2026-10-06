package net.firedevops.firemud.accountservice.authorpublication;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.accountservice.authorpublication.PublicationAuthorizationFenceRepository.Owner;
import net.firedevops.firemud.accountservice.authorpublication.PublicationAuthorizationFenceRepository.OwnerResultSnapshot;
import net.firedevops.firemud.accountservice.authorpublication.PublicationAuthorizationFenceRepository.Settlement;
import net.firedevops.firemud.accountservice.authorpublication.PublicationAuthorizationFenceRepository.TerminalSnapshot;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalClient;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.world.WorldPublicationTerminalClient;
import net.firedevops.firemud.common.world.WorldPublicationTerminalReadEvidence;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Unregistered recovery component; it neither authenticates a creator nor enables publication. */
public final class AccountPublicationTerminalReconciliationService {
  private final PublicationAuthorizationFenceRepository repository;
  private final GameDesignPublicationTerminalClient gameDesignClient;
  private final WorldPublicationTerminalClient worldClient;
  private final String namespace;
  private final TransactionTemplate ownerTransaction;

  public AccountPublicationTerminalReconciliationService(
      PublicationAuthorizationFenceRepository repository,
      PlatformTransactionManager transactionManager,
      GameDesignPublicationTerminalClient gameDesignClient,
      WorldPublicationTerminalClient worldClient,
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
   * Recovers only exact terminal readbacks for this complete allocated operation. Owner RPCs run
   * between short Account transactions; unavailable or UNKNOWN evidence leaves the order pending.
   */
  public Optional<Settlement> reconcile(byte[] operationBytes) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Publication terminal recovery cannot join an ambient transaction");
    }
    GameDesignPublicationOperationBinding operation =
        GameDesignPublicationOperationBinding.fromStored(operationBytes);
    byte[] canonicalOperation = operation.canonicalBytes();
    if (!Arrays.equals(operationBytes, canonicalOperation)) {
      throw new IllegalArgumentException("Noncanonical original Game Design publication operation");
    }

    Optional<TerminalSnapshot> initial =
        Objects.requireNonNull(
            ownerTransaction.execute(status -> repository.readOriginalOperation(operation)));
    if (initial.isEmpty()) {
      return Optional.empty();
    }
    TerminalSnapshot before = initial.orElseThrow();
    if (before.ordering() == PublicationAuthorizationFenceRepository.Ordering.RESERVED
        || before.settlement() != Settlement.PENDING) {
      return Optional.of(before.settlement());
    }

    Optional<GameDesignPublicationTerminalEvidence> gameDesignEvidence =
        before.gameDesignResult().map(AccountPublicationTerminalReconciliationService::terminal);
    if (gameDesignEvidence.isEmpty()) {
      gameDesignEvidence = readGameDesign(operation);
    }

    Optional<GameDesignPublicationTerminalEvidence> worldEvidence =
        before.worldResult().map(AccountPublicationTerminalReconciliationService::terminal);
    if (gameDesignEvidence.isPresent()) {
      GameDesignPublicationTerminalEvidence design = gameDesignEvidence.orElseThrow();
      if (!Arrays.equals(design.operationBytes(), canonicalOperation)) {
        throw new IllegalArgumentException(
            "Game Design terminal result changed the original operation");
      }
      if (worldEvidence.isPresent()) {
        requireIdenticalTerminal(design, worldEvidence.orElseThrow());
      } else {
        worldEvidence = readWorld(operation, design);
      }
    }

    Optional<GameDesignPublicationTerminalEvidence> finalGameDesign = gameDesignEvidence;
    Optional<GameDesignPublicationTerminalEvidence> finalWorld = worldEvidence;
    return Optional.ofNullable(
        ownerTransaction.execute(
            status -> {
              TerminalSnapshot current =
                  repository
                      .readOriginalOperation(operation)
                      .orElseThrow(
                          () ->
                              new IllegalStateException(
                                  "Original publication operation disappeared"));
              if (current.ordering() != before.ordering()) {
                throw new IllegalStateException(
                    "Original publication ordering changed during recovery");
              }
              retainExactMissing(operation, Owner.GAME_DESIGN, finalGameDesign, current);
              current =
                  repository
                      .readOriginalOperation(operation)
                      .orElseThrow(
                          () ->
                              new IllegalStateException(
                                  "Original publication operation disappeared"));
              if (current.ordering() != before.ordering()) {
                throw new IllegalStateException(
                    "Original publication ordering changed during recovery");
              }
              retainExactMissing(operation, Owner.WORLD, finalWorld, current);
              return repository.readSettlement(operation);
            }));
  }

  private Optional<GameDesignPublicationTerminalEvidence> readGameDesign(
      GameDesignPublicationOperationBinding operation) {
    try {
      GameDesignPublicationTerminalReadEvidence.ReadRequest request =
          GameDesignPublicationTerminalReadEvidence.ReadRequest.create(
              namespace, operation.canonicalBytes());
      GameDesignPublicationTerminalReadEvidence.ReadResult response =
          Objects.requireNonNull(gameDesignClient.read(request));
      if (!Arrays.equals(request.canonicalBytes(), response.request().canonicalBytes())) {
        throw new IllegalArgumentException("Game Design changed the exact terminal read request");
      }
      if (response.status() == GameDesignPublicationTerminalReadEvidence.Status.UNKNOWN) {
        return Optional.empty();
      }
      GameDesignPublicationTerminalEvidence terminal = response.terminalEvidence().orElseThrow();
      if (!Arrays.equals(terminal.operationBytes(), operation.canonicalBytes())
          || !terminal.outcome().name().equals(response.status().name())) {
        throw new IllegalArgumentException(
            "Game Design returned a changed publication terminal result");
      }
      return Optional.of(terminal);
    } catch (RuntimeException unavailableOrInvalid) {
      return Optional.empty();
    }
  }

  private Optional<GameDesignPublicationTerminalEvidence> readWorld(
      GameDesignPublicationOperationBinding operation,
      GameDesignPublicationTerminalEvidence gameDesignEvidence) {
    try {
      WorldPublicationTerminalReadEvidence.Request request =
          WorldPublicationTerminalReadEvidence.Request.create(
              namespace, gameDesignEvidence.canonicalBytes());
      WorldPublicationTerminalReadEvidence.ReadResult response =
          Objects.requireNonNull(worldClient.read(request));
      if (!Arrays.equals(request.canonicalBytes(), response.request().canonicalBytes())) {
        throw new IllegalArgumentException(
            "World changed the exact publication terminal read request");
      }
      WorldPublicationTerminalReadEvidence.Status expectedStatus =
          gameDesignEvidence.outcome() == GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
              ? WorldPublicationTerminalReadEvidence.Status.PUBLISHED
              : WorldPublicationTerminalReadEvidence.Status.ABORTED;
      if (response.status() == WorldPublicationTerminalReadEvidence.Status.UNKNOWN) {
        return Optional.empty();
      }
      GameDesignPublicationTerminalEvidence terminal = response.terminalEvidence().orElseThrow();
      if (response.status() != expectedStatus
          || !Arrays.equals(terminal.canonicalBytes(), gameDesignEvidence.canonicalBytes())
          || !Arrays.equals(terminal.operationBytes(), operation.canonicalBytes())) {
        throw new IllegalArgumentException(
            "World returned changed or contradictory terminal evidence");
      }
      return Optional.of(terminal);
    } catch (RuntimeException unavailableOrInvalid) {
      return Optional.empty();
    }
  }

  private void retainExactMissing(
      GameDesignPublicationOperationBinding operation,
      Owner owner,
      Optional<GameDesignPublicationTerminalEvidence> candidate,
      TerminalSnapshot snapshot) {
    if (candidate.isEmpty()) {
      return;
    }
    Optional<OwnerResultSnapshot> existing =
        owner == Owner.GAME_DESIGN ? snapshot.gameDesignResult() : snapshot.worldResult();
    GameDesignPublicationTerminalEvidence terminal = candidate.orElseThrow();
    if (existing.isPresent()) {
      if (!Arrays.equals(existing.orElseThrow().operationBytes(), operation.canonicalBytes())
          || !Arrays.equals(existing.orElseThrow().terminalBytes(), terminal.canonicalBytes())) {
        throw new IllegalArgumentException(
            "Concurrent publication recovery changed immutable owner evidence");
      }
      return;
    }
    repository.recordOwnerResult(operation, owner, terminal);
  }

  private static GameDesignPublicationTerminalEvidence terminal(OwnerResultSnapshot result) {
    GameDesignPublicationTerminalEvidence terminal =
        GameDesignPublicationTerminalEvidence.fromStored(result.terminalBytes());
    if (terminal.outcome() != result.outcome()
        || !Arrays.equals(terminal.operationBytes(), result.operationBytes())) {
      throw new IllegalStateException(
          "Stored publication owner result differs from its terminal bytes");
    }
    return terminal;
  }

  private static void requireIdenticalTerminal(
      GameDesignPublicationTerminalEvidence gameDesign,
      GameDesignPublicationTerminalEvidence world) {
    if (!Arrays.equals(gameDesign.canonicalBytes(), world.canonicalBytes())) {
      throw new IllegalArgumentException("Game Design and World terminal evidence differs");
    }
  }
}
