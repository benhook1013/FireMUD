package net.firedevops.firemud.accountservice.service.session;

import java.sql.Connection;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadScope;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Immutable intake orders, exact terminal settlements, and full original source participation. */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Internal Account owner persistence collaborator.")
public final class AccountGameLogicIntakeAuthorizationRepository {
  private final DSLContext dsl;

  public AccountGameLogicIntakeAuthorizationRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  GameLogicIntakeSourceReadScope reserveSourceRead(
      UUID requestId,
      net.firedevops.firemud.common.authoring.DraftCommitBinding selected,
      AccountControlUiActorService.Current current,
      String namespace,
      Runnable admission) {
    requireTransaction();
    var row =
        dsl.fetchOne(
            "SELECT * FROM account_game_logic_intake_source_read_reservations WHERE tenant_uuid = ? AND intake_request_id = ? FOR UPDATE",
            current.stored().tenantId,
            requestId);
    if (row != null) {
      var original =
          GameLogicIntakeSourceReadScope.fromStored(row.get("scope_bytes", byte[].class));
      if (!namespace.equals(original.targetNamespace())
          || !original.actorAccountId().equals(current.stored().accountId)
          || !original.selected().equals(selected))
        throw new IllegalArgumentException("Intake request identity conflicts with original scope");
      requireReservation(row, original);
      requireCurrentReservation(row, current);
      if (aborted(original)) throw new IllegalStateException("Original source-read scope aborted");
      return original;
    }
    admission.run();
    var scope =
        new GameLogicIntakeSourceReadScope(
            namespace,
            UUID.randomUUID(),
            UUID.randomUUID(),
            requestId,
            current.stored().accountId,
            selected,
            "spiffe://firemud/ns/" + namespace + "/sa/account-service",
            GameLogicIntakeSourceReadScope.PURPOSE);
    dsl.execute(
        "INSERT INTO account_game_logic_intake_source_read_reservations "
            + "(operation_id, fence_id, intake_request_id, actor_account_uuid, tenant_uuid, version_uuid, "
            + "scope_bytes, scope_digest, issuance_operation_id, issuance_fence, source_payload, issuance_bundle, outbox_checkpoints) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        scope.operationId(),
        scope.fenceId(),
        requestId,
        scope.actorAccountId(),
        selected.target().canonicalTenantId(),
        selected.target().canonicalVersionId(),
        scope.canonicalBytes(),
        scope.digest(),
        current.stored().operationId,
        current.source().issuanceFence(),
        current.stored().sources,
        current.stored().bundle,
        net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority.canonical(
            current.source().outboxCheckpoints()));
    for (var source : current.source().sources())
      dsl.execute(
          "INSERT INTO account_game_logic_intake_source_read_sources (operation_id, source_key, source_evidence) VALUES (?, ?, ?)",
          scope.operationId(),
          source.key(),
          source.canonicalBytes());
    requireReservation(reservation(scope), scope);
    return scope;
  }

  GameLogicIntakeAuthorizationBinding finalizeSourceRead(
      GameLogicIntakeSourceReadScope scope,
      GameplayRuleSelectedSource source,
      AccountControlUiActorService.Current current,
      Runnable admission) {
    requireTransaction();
    var reserved = reservation(scope);
    requireReservation(reserved, scope);
    requireCurrentReservation(reserved, current);
    if (!scope.selected().equals(source.binding()))
      throw new IllegalArgumentException("Source differs from original selected scope");
    if (aborted(scope)) throw new IllegalStateException("Original source-read scope aborted");
    var row =
        dsl.fetchOne(
            "SELECT * FROM account_game_logic_intake_authorizations WHERE operation_id = ? FOR UPDATE",
            scope.operationId());
    if (row != null) {
      var original =
          GameLogicIntakeAuthorizationBinding.fromStored(row.get("binding", byte[].class));
      if (!original.source().equals(source))
        throw new IllegalArgumentException(
            "Intake request identity conflicts with original source");
      requireExact(row, original);
      return original;
    }
    admission.run();
    var binding =
        new GameLogicIntakeAuthorizationBinding(
            scope.operationId(),
            scope.fenceId(),
            scope.intakeRequestId(),
            scope.actorAccountId(),
            source,
            current.source().sources());
    dsl.execute(
        "INSERT INTO account_game_logic_intake_authorizations (operation_id, fence_id, actor_account_uuid, "
            + "tenant_uuid, version_uuid, intake_request_id, source_digest, binding, issuance_operation_id, "
            + "issuance_fence, source_payload, issuance_bundle, outbox_checkpoints) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        binding.operationId(),
        binding.fenceId(),
        binding.actorAccountId(),
        binding.tenantId(),
        binding.versionId(),
        scope.intakeRequestId(),
        source.digest(),
        binding.canonicalBytes(),
        current.stored().operationId,
        current.source().issuanceFence(),
        current.stored().sources,
        current.stored().bundle,
        net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority.canonical(
            current.source().outboxCheckpoints()));
    for (var value : binding.sources())
      dsl.execute(
          "INSERT INTO account_game_logic_intake_sources (operation_id, source_key, source_evidence) VALUES (?, ?, ?)",
          binding.operationId(),
          value.key(),
          value.canonicalBytes());
    requireExact(
        dsl.fetchOne(
            "SELECT * FROM account_game_logic_intake_authorizations WHERE operation_id = ? FOR UPDATE",
            binding.operationId()),
        binding);
    return binding;
  }

  AccountGameLogicIntakeSourceReadRecovery recoverSourceRead(GameLogicIntakeSourceReadScope scope) {
    requireTransaction();
    lockReservedSources(scope);
    requireReservation(reservation(scope), scope);
    var row =
        dsl.fetchOne(
            "SELECT binding FROM account_game_logic_intake_authorizations WHERE operation_id = ?",
            scope.operationId());
    var state = AccountGameLogicIntakeSourceReadRecovery.State.RESERVED;
    java.util.Optional<GameLogicIntakeAuthorizationBinding> result = java.util.Optional.empty();
    if (row != null) {
      state = AccountGameLogicIntakeSourceReadRecovery.State.FINALIZED;
      var finalOrder =
          GameLogicIntakeAuthorizationBinding.fromStored(row.get("binding", byte[].class));
      if (!scope.operationId().equals(finalOrder.operationId())
          || !scope.fenceId().equals(finalOrder.fenceId())
          || !scope.intakeRequestId().equals(finalOrder.intakeRequestId())
          || !scope.actorAccountId().equals(finalOrder.actorAccountId())
          || !scope.selected().equals(finalOrder.source().binding()))
        throw new IllegalArgumentException("Finalized order differs from original scope");
      result = java.util.Optional.of(finalOrder);
    } else if (aborted(scope)) state = AccountGameLogicIntakeSourceReadRecovery.State.ABORTED;
    return new AccountGameLogicIntakeSourceReadRecovery(scope, state, result);
  }

  java.util.Optional<GameLogicIntakeSourceReadScope> findSourceReadScope(
      UUID requestId,
      net.firedevops.firemud.common.authoring.DraftCommitBinding selected,
      String namespace,
      String originalTokenHash) {
    requireTransaction();
    var row =
        dsl.fetchOne(
            "SELECT * FROM account_game_logic_intake_source_read_reservations WHERE tenant_uuid = ? AND intake_request_id = ?",
            selected.target().canonicalTenantId(),
            requestId);
    if (row == null) return java.util.Optional.empty();
    var scope = GameLogicIntakeSourceReadScope.fromStored(row.get("scope_bytes", byte[].class));
    requireReservation(row, scope);
    var issuer =
        dsl.fetchOne(
            "SELECT * FROM account_control_ui_issuance_operations WHERE operation_id = ?",
            row.get("issuance_operation_id", UUID.class));
    if (!namespace.equals(scope.targetNamespace())
        || !selected.equals(scope.selected())
        || !originalTokenHash.equals(
            new AccountControlUiIssuanceRepository.Stored(issuer).tokenHash))
      throw new IllegalArgumentException(
          "Intake request conflicts with original actor credential or selection");
    return java.util.Optional.of(scope);
  }

  AccountGameLogicIntakeSourceReadRecovery abortSourceRead(GameLogicIntakeSourceReadScope scope) {
    var original = recoverSourceRead(scope);
    if (original.state() == AccountGameLogicIntakeSourceReadRecovery.State.RESERVED)
      dsl.execute(
          "INSERT INTO account_game_logic_intake_source_read_aborts (operation_id) VALUES (?)",
          scope.operationId());
    return recoverSourceRead(scope);
  }

  record SourceReadCurrentness(String tokenHash, String registryDigest, long expiresAt) {}

  SourceReadCurrentness sourceReadCurrentness(GameLogicIntakeSourceReadScope scope) {
    requireTransaction();
    // No locks survive this transaction or the following remote registry read.
    var row =
        dsl.fetchOne(
            "SELECT * FROM account_game_logic_intake_source_read_reservations WHERE operation_id = ?",
            scope.operationId());
    if (row == null)
      throw new IllegalArgumentException("Original preliminary scope absent or changed");
    requireReservation(row, scope);
    var issuer =
        dsl.fetchOne(
            "SELECT * FROM account_control_ui_issuance_operations WHERE operation_id = ?",
            row.get("issuance_operation_id", UUID.class));
    if (issuer == null) throw new IllegalStateException("Original creator issuance absent");
    var stored = new AccountControlUiIssuanceRepository.Stored(issuer);
    if (!"COMMITTED".equals(stored.status))
      throw new IllegalStateException("Original creator issuance is no longer current");
    return new SourceReadCurrentness(
        stored.tokenHash,
        net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.digest(
            stored.activeRegistry),
        stored.expiresAt.getEpochSecond());
  }

  void readSourceScope(
      GameLogicIntakeSourceReadScope scope, SourceReadCurrentness expected, java.time.Instant now) {
    var recovered = recoverSourceRead(scope);
    if (recovered.state() != AccountGameLogicIntakeSourceReadRecovery.State.RESERVED
        || !sourceReadCurrentness(scope).equals(expected)
        || now.getEpochSecond() >= expected.expiresAt())
      throw new IllegalStateException("Exact current held preliminary scope required");
  }

  void readFinalizedSourceScope(
      GameLogicIntakeAuthorizationBinding authorization, String namespace) {
    requireTransaction();
    var row =
        dsl.fetchOne(
            "SELECT scope_bytes FROM account_game_logic_intake_source_read_reservations WHERE operation_id = ?",
            authorization.operationId());
    if (row == null) throw new IllegalArgumentException("Original source-read reservation absent");
    var scope = GameLogicIntakeSourceReadScope.fromStored(row.get("scope_bytes", byte[].class));
    if (!namespace.equals(scope.targetNamespace()))
      throw new IllegalArgumentException("Changed original source namespace");
    var recovered = recoverSourceRead(scope);
    if (recovered.authorization().isEmpty()
        || !Arrays.equals(
            authorization.canonicalBytes(),
            recovered.authorization().orElseThrow().canonicalBytes()))
      throw new IllegalArgumentException("Exact finalized intake authorization required");
    readHeld(authorization);
  }

  private Record reservation(GameLogicIntakeSourceReadScope scope) {
    return dsl.fetchOne(
        "SELECT * FROM account_game_logic_intake_source_read_reservations WHERE operation_id = ? FOR UPDATE",
        scope.operationId());
  }

  private boolean aborted(GameLogicIntakeSourceReadScope scope) {
    return dsl.fetchOne(
            "SELECT operation_id FROM account_game_logic_intake_source_read_aborts WHERE operation_id = ?",
            scope.operationId())
        != null;
  }

  private void lockReservedSources(GameLogicIntakeSourceReadScope scope) {
    // Reject changed caller bytes before using the retained original's sorted lock vector.
    var row =
        dsl.fetchOne(
            "SELECT * FROM account_game_logic_intake_source_read_reservations WHERE operation_id = ?",
            scope.operationId());
    requireReservation(row, scope);
    for (var value :
        dsl.fetch(
            "SELECT source_key FROM account_game_logic_intake_source_read_sources WHERE operation_id = ? ORDER BY account_publication_authorization_source_sort_key(source_key)",
            scope.operationId()))
      if (dsl.fetchOne(
              "SELECT source_key FROM account_draft_authorization_source_locks WHERE source_key = ? FOR UPDATE",
              value.get("source_key", String.class))
          == null) throw new IllegalStateException("Original preliminary source lock absent");
  }

  private static void requireCurrentReservation(
      Record row, AccountControlUiActorService.Current current) {
    if (!current.stored().operationId.equals(row.get("issuance_operation_id", UUID.class))
        || !Arrays.equals(current.stored().sources, row.get("source_payload", byte[].class))
        || !Arrays.equals(current.stored().bundle, row.get("issuance_bundle", byte[].class)))
      throw new IllegalArgumentException("Changed original intake creator authority");
  }

  private void requireReservation(Record row, GameLogicIntakeSourceReadScope scope) {
    if (row == null
        || !Arrays.equals(scope.canonicalBytes(), row.get("scope_bytes", byte[].class))
        || !scope.digest().equals(row.get("scope_digest", String.class))
        || !scope.operationId().equals(row.get("operation_id", UUID.class))
        || !scope.fenceId().equals(row.get("fence_id", UUID.class))
        || !scope.intakeRequestId().equals(row.get("intake_request_id", UUID.class))
        || !scope.actorAccountId().equals(row.get("actor_account_uuid", UUID.class))
        || !scope.selected().target().canonicalTenantId().equals(row.get("tenant_uuid", UUID.class))
        || !scope
            .selected()
            .target()
            .canonicalVersionId()
            .equals(row.get("version_uuid", UUID.class)))
      throw new IllegalArgumentException("Original preliminary scope absent or changed");
    var issuer =
        dsl.fetchOne(
            "SELECT * FROM account_control_ui_issuance_operations WHERE operation_id = ?",
            row.get("issuance_operation_id", UUID.class));
    if (issuer == null) throw new IllegalStateException("Original creator issuance absent");
    var original = new AccountControlUiIssuanceRepository.Stored(issuer);
    if (!scope.actorAccountId().equals(original.accountId)
        || !scope.selected().target().canonicalTenantId().equals(original.tenantId)
        || !Arrays.equals(original.sources, row.get("source_payload", byte[].class))
        || !Arrays.equals(original.bundle, row.get("issuance_bundle", byte[].class)))
      throw new IllegalArgumentException("Changed preliminary creator evidence");
    var vector = AccountControlUiIssuanceRepository.object(original.sources).get("sources");
    var held =
        dsl.fetch(
            "SELECT source_key, source_evidence FROM account_game_logic_intake_source_read_sources WHERE operation_id = ? ORDER BY account_publication_authorization_source_sort_key(source_key)",
            scope.operationId());
    if (!(vector instanceof java.util.List<?> values)
        || values.isEmpty()
        || held.size() != values.size())
      throw new IllegalArgumentException("Incomplete preliminary source participation");
    for (int i = 0; i < held.size(); i++) {
      var bytes = java.util.Base64.getDecoder().decode((String) values.get(i));
      var source =
          net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence
              .fromStored(bytes);
      if (!source.key().equals(held.get(i).get("source_key", String.class))
          || !Arrays.equals(bytes, held.get(i).get("source_evidence", byte[].class)))
        throw new IllegalArgumentException("Changed preliminary source participation");
    }
  }

  public void readHeld(GameLogicIntakeAuthorizationBinding requested) {
    requireTransaction();
    // Deny a changed lookup before acquiring caller-selected authority locks.
    var lookup =
        dsl.fetchOne(
            "SELECT binding FROM account_game_logic_intake_authorizations WHERE operation_id = ?",
            requested.operationId());
    if (lookup == null
        || !Arrays.equals(requested.canonicalBytes(), lookup.get("binding", byte[].class)))
      throw new IllegalArgumentException("Original intake order absent or changed");
    var original =
        GameLogicIntakeAuthorizationBinding.fromStored(lookup.get("binding", byte[].class));
    for (var value : original.sources())
      if (dsl.fetchOne(
              "SELECT source_key FROM account_draft_authorization_source_locks WHERE source_key = ? FOR UPDATE",
              value.key())
          == null) throw new IllegalArgumentException("Original intake source lock absent");
    requireExact(
        dsl.fetchOne(
            "SELECT * FROM account_game_logic_intake_authorizations WHERE operation_id = ? FOR UPDATE",
            original.operationId()),
        original);
    if (dsl.fetchOne(
            "SELECT operation_id FROM account_game_logic_intake_settlements WHERE operation_id = ?",
            original.operationId())
        != null) throw new IllegalArgumentException("Original intake order already settled");
  }

  /** Only called after the producer's authenticated GL read, with no remote read under locks. */
  java.util.Optional<AccountGameLogicIntakeSettlement> findSettlement(
      GameLogicIntakeAuthorizationBinding requested, String namespace) {
    requireTransaction();
    var order =
        dsl.fetchOne(
            "SELECT binding FROM account_game_logic_intake_authorizations WHERE operation_id = ?",
            requested.operationId());
    if (order == null
        || !Arrays.equals(requested.canonicalBytes(), order.get("binding", byte[].class)))
      throw new IllegalArgumentException("Original intake order absent or changed");
    var row =
        dsl.fetchOne(
            "SELECT * FROM account_game_logic_intake_settlements WHERE operation_id = ?",
            requested.operationId());
    if (row == null) return java.util.Optional.empty();
    var receipt =
        AccountGameLogicIntakeSettlement.fromStored(row.get("receipt_bytes", byte[].class));
    if (!namespace.equals(receipt.terminal().operation().targetNamespace())
        || !Arrays.equals(requested.canonicalBytes(), receipt.terminal().authorizationBytes()))
      throw new IllegalArgumentException("Changed exact intake settlement");
    return java.util.Optional.of(exactSettlement(row, receipt));
  }

  /** Only called after the producer's authenticated GL read, with no remote read under locks. */
  AccountGameLogicIntakeSettlement settle(AccountGameLogicIntakeSettlement requested) {
    requireTransaction();
    var binding = requested.terminal().operation().authorization();
    var lookup =
        dsl.fetchOne(
            "SELECT binding FROM account_game_logic_intake_authorizations WHERE operation_id = ?",
            binding.operationId());
    if (lookup == null
        || !Arrays.equals(binding.canonicalBytes(), lookup.get("binding", byte[].class)))
      throw new IllegalArgumentException("Original intake order absent or changed");
    // The retained original determines the sorted locks, never an unchecked caller source vector.
    var original =
        GameLogicIntakeAuthorizationBinding.fromStored(lookup.get("binding", byte[].class));
    for (var source : original.sources())
      if (dsl.fetchOne(
              "SELECT source_key FROM account_draft_authorization_source_locks WHERE source_key = ? FOR UPDATE",
              source.key())
          == null) throw new IllegalArgumentException("Original intake source lock absent");
    var order =
        dsl.fetchOne(
            "SELECT * FROM account_game_logic_intake_authorizations WHERE operation_id = ? FOR UPDATE",
            original.operationId());
    if (order == null
        || !Arrays.equals(original.canonicalBytes(), order.get("binding", byte[].class)))
      throw new IllegalArgumentException("Changed original intake order");
    var prior =
        dsl.fetchOne(
            "SELECT * FROM account_game_logic_intake_settlements WHERE operation_id = ? FOR UPDATE",
            original.operationId());
    if (prior != null) return exactSettlement(prior, requested);
    requireExact(order, original);
    var terminal = requested.terminal();
    dsl.execute(
        "INSERT INTO account_game_logic_intake_settlements (operation_id, target_namespace, outcome, terminal_bytes, terminal_digest, receipt_bytes, receipt_digest) VALUES (?, ?, ?, ?, ?, ?, ?)",
        original.operationId(),
        terminal.operation().targetNamespace(),
        terminal.outcome().name(),
        terminal.canonicalBytes(),
        terminal.digest(),
        requested.canonicalBytes(),
        requested.digest());
    return exactSettlement(
        dsl.fetchOne(
            "SELECT * FROM account_game_logic_intake_settlements WHERE operation_id = ? FOR UPDATE",
            original.operationId()),
        requested);
  }

  private static AccountGameLogicIntakeSettlement exactSettlement(
      Record row, AccountGameLogicIntakeSettlement requested) {
    if (row == null) throw new IllegalStateException("Settlement commit readback absent");
    var original =
        AccountGameLogicIntakeSettlement.fromStored(row.get("receipt_bytes", byte[].class));
    var terminal = original.terminal();
    if (!Arrays.equals(original.canonicalBytes(), requested.canonicalBytes())
        || !original.digest().equals(row.get("receipt_digest", String.class))
        || !terminal.digest().equals(row.get("terminal_digest", String.class))
        || !Arrays.equals(terminal.canonicalBytes(), row.get("terminal_bytes", byte[].class))
        || !terminal
            .operation()
            .authorization()
            .operationId()
            .equals(row.get("operation_id", UUID.class))
        || !terminal.operation().targetNamespace().equals(row.get("target_namespace", String.class))
        || !terminal.outcome().name().equals(row.get("outcome", String.class)))
      throw new IllegalArgumentException("Changed exact intake settlement");
    return original;
  }

  private void requireExact(Record row, GameLogicIntakeAuthorizationBinding binding) {
    if (row == null
        || !Arrays.equals(binding.canonicalBytes(), row.get("binding", byte[].class))
        || !binding.operationId().equals(row.get("operation_id", UUID.class))
        || !binding.fenceId().equals(row.get("fence_id", UUID.class))
        || !binding.actorAccountId().equals(row.get("actor_account_uuid", UUID.class))
        || !binding.tenantId().equals(row.get("tenant_uuid", UUID.class))
        || !binding.versionId().equals(row.get("version_uuid", UUID.class))
        || !binding.intakeRequestId().equals(row.get("intake_request_id", UUID.class))
        || !binding.source().digest().equals(row.get("source_digest", String.class)))
      throw new IllegalArgumentException("Original intake order absent or changed");
    var issuer =
        dsl.fetchOne(
            "SELECT * FROM account_control_ui_issuance_operations WHERE operation_id = ? FOR UPDATE NOWAIT",
            row.get("issuance_operation_id", UUID.class));
    if (issuer == null) throw new IllegalArgumentException("Original committed issuance absent");
    var original = new AccountControlUiIssuanceRepository.Stored(issuer);
    // Source-writer guards prevent revocation while this operation participates.
    if (!"COMMITTED".equals(original.status)
        || !binding.actorAccountId().equals(original.accountId)
        || !binding.tenantId().equals(original.tenantId)
        || !Arrays.equals(original.sources, row.get("source_payload", byte[].class))
        || !Arrays.equals(original.bundle, row.get("issuance_bundle", byte[].class)))
      throw new IllegalArgumentException("Changed original intake issuance");
    var bundle = AccountControlUiIssuanceRepository.object(original.bundle);
    var payload = AccountControlUiIssuanceRepository.object(original.sources);
    if (!(bundle.get("issuanceFence") instanceof Number fence)
        || fence.longValue() != row.get("issuance_fence", Long.class)
        || !Arrays.equals(
            net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority.canonical(
                bundle.get("outboxCheckpoints")),
            row.get("outbox_checkpoints", byte[].class))
        || !(payload.get("sources") instanceof java.util.List<?> vector)
        || vector.size() != binding.sources().size())
      throw new IllegalArgumentException("Incomplete original issuance vector");
    var participation =
        dsl
            .fetch(
                "SELECT source_key, source_evidence FROM account_game_logic_intake_sources WHERE operation_id = ?",
                binding.operationId())
            .stream()
            .sorted(java.util.Comparator.comparing(v -> v.get("source_key", String.class)))
            .toList();
    if (participation.size() != binding.sources().size())
      throw new IllegalArgumentException("Incomplete intake participation");
    for (int i = 0; i < participation.size(); i++) {
      var source = binding.sources().get(i);
      if (!(vector.get(i) instanceof String encoded)
          || !Arrays.equals(source.canonicalBytes(), java.util.Base64.getDecoder().decode(encoded))
          || !source.key().equals(participation.get(i).get("source_key", String.class))
          || !Arrays.equals(
              source.canonicalBytes(), participation.get(i).get("source_evidence", byte[].class)))
        throw new IllegalArgumentException("Changed intake participation");
    }
  }

  private void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
      throw new IllegalStateException("Writable Account owner transaction required");
    dsl.connection(
        connection -> {
          if (connection.getAutoCommit()
              || connection.isReadOnly()
              || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED)
            throw new IllegalStateException(
                "Writable READ_COMMITTED Account owner transaction required");
        });
  }
}
