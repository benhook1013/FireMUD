package net.firedevops.firemud.gamelogic.sourceintake;

import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeOperation;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeTerminal;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationReadClient;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationReadEvidence;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadClient;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountSelectedPublicationOrderCredentials;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered owner-local exact-source intake. The caller is the same-namespace GD workload;
 * Account and GD reads complete before this service opens its independent retention transaction.
 */
public final class GameLogicGameplayRuleIntakeService {
  private final GameLogicGameplayRuleIntakeRepository repository;
  private final GameLogicIntakeAuthorizationReadClient accountAuthorizationReader;
  private final GameplayRuleSourceReadClient gameDesignSourceReader;
  private final String workloadNamespace;
  private final TransactionTemplate ownerTransaction;

  public GameLogicGameplayRuleIntakeService(
      GameLogicGameplayRuleIntakeRepository repository,
      PlatformTransactionManager transactionManager,
      GameLogicIntakeAuthorizationReadClient accountAuthorizationReader,
      GameplayRuleSourceReadClient gameDesignSourceReader,
      String workloadNamespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.accountAuthorizationReader =
        Objects.requireNonNull(accountAuthorizationReader, "accountAuthorizationReader");
    this.gameDesignSourceReader =
        Objects.requireNonNull(gameDesignSourceReader, "gameDesignSourceReader");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Logic workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    ownerTransaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    ownerTransaction.setName("game-logic-gameplay-rule-source-intake");
    ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    ownerTransaction.setReadOnly(false);
  }

  /** Returns the original terminal for exact retries, including after Account settles the order. */
  public GameLogicGameplayRuleIntakeTerminal retain(
      net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding authorization) {
    var operation = requestOperation(authorization);
    requireNoActiveTransaction();
    Optional<GameLogicGameplayRuleIntakeTerminal> prior =
        repository.findTerminal(authorization.operationId());
    if (prior.isPresent()) return exactTerminal(prior.orElseThrow(), operation);

    var authorizationRequest =
        GameLogicIntakeAuthorizationReadEvidence.Request.create(workloadNamespace, authorization);
    var held = accountAuthorizationReader.read(authorizationRequest);
    if (held == null || !authorizationRequest.equals(held.request())) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Exact HELD Account intake authorization required")
          .asRuntimeException();
    }

    var sourceRequest =
        GameplayRuleSourceReadEvidence.Request.forFinalizedIntake(workloadNamespace, authorization);
    var sourceEvidence = gameDesignSourceReader.read(sourceRequest);
    if (sourceEvidence == null
        || !sourceRequest.equals(sourceEvidence.request())
        || !Arrays.equals(
            authorization.source().canonicalBytes(), sourceEvidence.source().canonicalBytes())) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Exact complete selected GD source differs from Account authorization")
          .asRuntimeException();
    }

    byte[] sourceBytes = sourceEvidence.source().canonicalBytes();
    byte[] manifestBytes =
        sourceEvidence.source().manifest().canonicalJson().getBytes(StandardCharsets.UTF_8);
    var retained =
        GameLogicGameplayRuleIntakeTerminal.retained(operation, sourceBytes, manifestBytes);
    return ownerTransaction.execute(
        status -> {
          repository.lockOperation(authorization.operationId());
          var concurrent = repository.findTerminal(authorization.operationId());
          if (concurrent.isPresent()) return exactTerminal(concurrent.orElseThrow(), operation);
          repository.insertTerminal(retained);
          return repository
              .findTerminal(authorization.operationId())
              .filter(value -> Arrays.equals(value.canonicalBytes(), retained.canonicalBytes()))
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "GL_GAMEPLAY_RULE_INTAKE_COMMIT_READBACK_MISMATCH"));
        });
  }

  /** No value means there is no actual owner terminal; ABORTED is an explicit durable terminal. */
  public Optional<GameLogicGameplayRuleIntakeTerminal> readTerminal(
      net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding authorization) {
    GameLogicGameplayRuleIntakeTerminalReadService.requirePeer(workloadNamespace);
    var operation = new GameLogicGameplayRuleIntakeOperation(workloadNamespace, authorization);
    requireNoActiveTransaction();
    return repository
        .findTerminal(authorization.operationId())
        .map(value -> exactTerminal(value, operation));
  }

  /** Explicit owner abort is serialized with retention and never inferred from a read failure. */
  public GameLogicGameplayRuleIntakeTerminal abort(
      net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding authorization) {
    var operation = requestOperation(authorization);
    requireNoActiveTransaction();
    Optional<GameLogicGameplayRuleIntakeTerminal> prior =
        repository.findTerminal(authorization.operationId());
    if (prior.isPresent()) return exactTerminal(prior.orElseThrow(), operation);

    var authorizationRequest =
        GameLogicIntakeAuthorizationReadEvidence.Request.create(workloadNamespace, authorization);
    var held = accountAuthorizationReader.read(authorizationRequest);
    if (held == null || !authorizationRequest.equals(held.request())) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Exact HELD Account intake authorization required for explicit abort")
          .asRuntimeException();
    }

    return ownerTransaction.execute(
        status -> {
          repository.lockOperation(authorization.operationId());
          var concurrent = repository.findTerminal(authorization.operationId());
          if (concurrent.isPresent()) return exactTerminal(concurrent.orElseThrow(), operation);
          var aborted = GameLogicGameplayRuleIntakeTerminal.aborted(operation);
          repository.insertTerminal(aborted);
          return repository
              .findTerminal(authorization.operationId())
              .filter(value -> Arrays.equals(value.canonicalBytes(), aborted.canonicalBytes()))
              .orElseThrow(
                  () ->
                      new IllegalStateException("GL_GAMEPLAY_RULE_INTAKE_ABORT_READBACK_MISMATCH"));
        });
  }

  private GameLogicGameplayRuleIntakeOperation requestOperation(
      net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding authorization) {
    var peer = GrpcPeerIdentity.current();
    if (peer == null)
      throw Status.UNAUTHENTICATED
          .withDescription("Verified workload identity required")
          .asRuntimeException();
    if (!peer.isInNamespace(workloadNamespace)
        || !peer.isService("game-design-service")
        || !("spiffe://firemud/ns/" + workloadNamespace + "/sa/game-design-service")
            .equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace Game Design workload required")
          .asRuntimeException();
    }
    if (AccountSelectedPublicationOrderCredentials.CONTEXT_KEY.get() != null) {
      throw Status.PERMISSION_DENIED
          .withDescription("Creator publication credentials cannot accompany GL source intake")
          .asRuntimeException();
    }
    if (authorization == null) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Original GL intake authorization required")
          .asRuntimeException();
    }
    return new GameLogicGameplayRuleIntakeOperation(workloadNamespace, authorization);
  }

  private static GameLogicGameplayRuleIntakeTerminal exactTerminal(
      GameLogicGameplayRuleIntakeTerminal terminal,
      GameLogicGameplayRuleIntakeOperation requested) {
    if (!Arrays.equals(terminal.operation().canonicalBytes(), requested.canonicalBytes())) {
      throw Status.ALREADY_EXISTS
          .withDescription("GL intake operation identity was reused with changed authorization")
          .asRuntimeException();
    }
    return terminal;
  }

  private static void requireNoActiveTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("GL intake owner reads must run outside SQL")
          .asRuntimeException();
    }
  }
}
