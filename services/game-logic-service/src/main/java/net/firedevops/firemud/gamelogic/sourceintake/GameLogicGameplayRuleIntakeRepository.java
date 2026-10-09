package net.firedevops.firemud.gamelogic.sourceintake;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Owner-local immutable intake evidence storage. All writes require the caller's local transaction.
 */
public final class GameLogicGameplayRuleIntakeRepository {
  private static final long OPERATION_LOCK_NAMESPACE = 0x474c494e54414b45L;

  private final DSLContext dsl;

  public GameLogicGameplayRuleIntakeRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  /** Serializes fresh retention and explicit abort before a row exists and on exact retries. */
  public void lockOperation(UUID operationId) {
    requireReadCommittedTransaction();
    dsl.fetch(
        "select pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(?, ?))",
        "game-logic-source-intake:" + operationId,
        OPERATION_LOCK_NAMESPACE);
  }

  /** Immutable readback is independent of Account's later settlement state. */
  public Optional<GameLogicGameplayRuleIntakeTerminal> findTerminal(UUID operationId) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM game_logic_gameplay_rule_intake_terminal WHERE operation_id = ?",
            Objects.requireNonNull(operationId, "operationId"));
    if (row == null) return Optional.empty();
    byte[] terminalBytes = row.get("terminal_bytes", byte[].class);
    var terminal = GameLogicGameplayRuleIntakeTerminal.fromStored(terminalBytes);
    var operation = terminal.operation();
    var authorization = operation.authorization();
    var sourceBinding = authorization.source().binding();
    if (!operationId.equals(row.get("operation_id", UUID.class))
        || !operation.targetNamespace().equals(row.get("target_namespace", String.class))
        || !authorization.actorAccountId().equals(row.get("actor_account_id", UUID.class))
        || !authorization.tenantId().equals(row.get("canonical_tenant_id", UUID.class))
        || !authorization.versionId().equals(row.get("canonical_version_id", UUID.class))
        || !sourceBinding.commitId().equals(row.get("selected_commit_id", UUID.class))
        || !operation.digest().equals(row.get("operation_digest", String.class))
        || !authorization.digest().equals(row.get("authorization_digest", String.class))
        || !terminal.outcome().name().equals(row.get("outcome", String.class))
        || !terminal.digest().equals(row.get("terminal_digest", String.class))
        || !java.util.Arrays.equals(
            authorization.canonicalBytes(), row.get("authorization_bytes", byte[].class))
        || !same(terminal.selectedSourceBytes(), row.get("selected_source_bytes", byte[].class))
        || !same(terminal.manifestBytes(), row.get("manifest_bytes", byte[].class))
        || !Objects.equals(
            terminal.selectedSourceDigest(), row.get("selected_source_digest", String.class))
        || !Objects.equals(terminal.manifestDigest(), row.get("manifest_digest", String.class))) {
      throw new IllegalStateException("GL_GAMEPLAY_RULE_INTAKE_READBACK_CONFLICT");
    }
    return Optional.of(terminal);
  }

  /** One INSERT stores order, exact GD provenance, manifest, and RETAINED terminal atomically. */
  public void insertTerminal(GameLogicGameplayRuleIntakeTerminal terminal) {
    requireReadCommittedTransaction();
    Objects.requireNonNull(terminal, "terminal");
    var operation = terminal.operation();
    var authorization = operation.authorization();
    dsl.execute(
        "INSERT INTO game_logic_gameplay_rule_intake_terminal (operation_id, target_namespace, "
            + "actor_account_id, canonical_tenant_id, canonical_version_id, selected_commit_id, "
            + "operation_digest, authorization_digest, authorization_bytes, outcome, "
            + "selected_source_bytes, selected_source_digest, manifest_bytes, manifest_digest, "
            + "terminal_bytes, terminal_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        authorization.operationId(),
        operation.targetNamespace(),
        authorization.actorAccountId(),
        authorization.tenantId(),
        authorization.versionId(),
        authorization.source().binding().commitId(),
        operation.digest(),
        authorization.digest(),
        terminal.authorizationBytes(),
        terminal.outcome().name(),
        terminal.selectedSourceBytes(),
        terminal.selectedSourceDigest(),
        terminal.manifestBytes(),
        terminal.manifestDigest(),
        terminal.canonicalBytes(),
        terminal.digest());
  }

  private static void requireReadCommittedTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !Integer.valueOf(
                org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "GL intake storage requires an owner READ COMMITTED transaction");
    }
  }

  private static boolean same(byte[] left, byte[] right) {
    return java.util.Arrays.equals(left, right);
  }
}
