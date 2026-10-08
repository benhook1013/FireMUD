package net.firedevops.firemud.accountservice.service.session;

import java.sql.Connection;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Internal owner-transaction storage; remote evidence must first be authenticated by composition.
 */
public final class AccountPublicationAuthorizationRepository {
  private final DSLContext dsl;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Internal Account owner persistence collaborator.")
  public AccountPublicationAuthorizationRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  AccountPublicationAuthorizationBinding authorize(
      AccountPublicationAuthorizationBinding.PreallocationInput input,
      AccountControlUiActorService.Current current,
      Runnable admission) {
    requireTransaction();
    Record prior =
        dsl.fetchOne(
            "SELECT * FROM account_selected_publication_authorizations"
                + " WHERE tenant_uuid = ? AND publish_request_id = ? FOR UPDATE",
            current.stored().tenantId,
            input.selection().intent().publishRequestId());
    if (prior != null) {
      var original =
          AccountPublicationAuthorizationBinding.fromStored(prior.get("binding", byte[].class));
      requireExact(prior, original, input, current);
      return original;
    }
    admission.run();
    var binding =
        new AccountPublicationAuthorizationBinding(
            UUID.randomUUID(), UUID.randomUUID(), input, current.source().sources());
    dsl.execute(
        "INSERT INTO account_selected_publication_authorizations"
            + " (operation_id, fence_id, actor_account_uuid, tenant_uuid, publish_request_id,"
            + " input_digest, binding, issuance_operation_id, issuance_fence,"
            + " source_payload, issuance_bundle, outbox_checkpoints) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        binding.operationId(),
        binding.fenceId(),
        input.actorAccountId(),
        binding.tenantId(),
        binding.publishRequestId(),
        input.digest(),
        binding.canonicalBytes(),
        current.stored().operationId,
        current.source().issuanceFence(),
        current.stored().sources,
        current.stored().bundle,
        net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority.canonical(
            current.source().outboxCheckpoints()));
    for (var source : binding.sources()) {
      dsl.execute(
          "INSERT INTO account_selected_publication_sources"
              + " (operation_id, source_key, source_evidence) VALUES (?, ?, ?)",
          binding.operationId(),
          source.key(),
          source.canonicalBytes());
    }
    Record retained =
        dsl.fetchOne(
            "SELECT * FROM account_selected_publication_authorizations WHERE operation_id = ? FOR UPDATE",
            binding.operationId());
    requireExact(retained, binding, input, current);
    return binding;
  }

  /** Exact owner read; caller-carried structure alone can never establish HELD. */
  public void readHeld(AccountPublicationAuthorizationBinding requested) {
    readOriginal(requested);
    if (dsl.fetchOne(
            "SELECT 1 FROM account_selected_publication_settlements WHERE operation_id = ?",
            requested.operationId())
        != null) {
      throw new IllegalArgumentException("Publication authorization is already settled");
    }
  }

  /** Historical validation uses the original committed issuance, never current credentials. */
  private void readOriginal(AccountPublicationAuthorizationBinding requested) {
    requireTransaction();
    Objects.requireNonNull(requested);
    // Sources precede the operation. No transport is permitted in this owner transaction.
    for (var source :
        requested.sources().stream()
            .sorted(
                java.util.Comparator.comparing(DraftAuthorizationFenceBinding.SourceEvidence::key))
            .toList()) {
      if (dsl.fetchOne(
              "SELECT source_key FROM account_draft_authorization_source_locks"
                  + " WHERE source_key = ? FOR UPDATE",
              source.key())
          == null) {
        throw new IllegalArgumentException("Original publication source lock is absent");
      }
    }
    Record row =
        dsl.fetchOne(
            "SELECT * FROM account_selected_publication_authorizations"
                + " WHERE operation_id = ? FOR UPDATE",
            requested.operationId());
    if (row == null
        || !Arrays.equals(requested.canonicalBytes(), row.get("binding", byte[].class))) {
      throw new IllegalArgumentException("Exact held publication authorization is absent");
    }
    var retained =
        AccountPublicationAuthorizationBinding.fromStored(row.get("binding", byte[].class));
    validateRetainedWorldIfPresent(row, retained);
    // Issuance producers acquire this row before source capture. Do not wait backwards while
    // holding sources; a concurrent issuer/source writer must cause a retry, never a lock cycle.
    Record issuance =
        dsl.fetchOne(
            "SELECT * FROM account_control_ui_issuance_operations"
                + " WHERE operation_id = ? FOR UPDATE NOWAIT",
            row.get("issuance_operation_id", UUID.class));
    if (issuance == null) {
      throw new IllegalArgumentException("Original committed issuance is absent");
    }
    var originalIssuer = new AccountControlUiIssuanceRepository.Stored(issuance);
    var bundle = AccountControlUiIssuanceRepository.object(originalIssuer.bundle);
    var sources = AccountControlUiIssuanceRepository.object(originalIssuer.sources);
    var rawVector = sources.get("sources");
    if (!"COMMITTED".equals(originalIssuer.status)
        || !retained.operationId().equals(row.get("operation_id", UUID.class))
        || !retained.fenceId().equals(row.get("fence_id", UUID.class))
        || !retained.input().actorAccountId().equals(row.get("actor_account_uuid", UUID.class))
        || !retained.tenantId().equals(row.get("tenant_uuid", UUID.class))
        || !retained.publishRequestId().equals(row.get("publish_request_id", String.class))
        || !retained.input().digest().equals(row.get("input_digest", String.class))
        || !retained.input().actorAccountId().equals(originalIssuer.accountId)
        || !retained.tenantId().equals(originalIssuer.tenantId)
        || !originalIssuer.operationId.equals(row.get("issuance_operation_id", UUID.class))
        || !Arrays.equals(originalIssuer.sources, row.get("source_payload", byte[].class))
        || !Arrays.equals(originalIssuer.bundle, row.get("issuance_bundle", byte[].class))
        || !(bundle.get("issuanceFence") instanceof Number fence)
        || fence.longValue() != row.get("issuance_fence", Long.class)
        || !Arrays.equals(
            net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority.canonical(
                bundle.get("outboxCheckpoints")),
            row.get("outbox_checkpoints", byte[].class))
        || !originalIssuer.accountId.toString().equals(sources.get("accountId"))
        || !originalIssuer.tenantId.toString().equals(sources.get("tenantId"))
        || !(rawVector instanceof java.util.List<?> vector)
        || vector.size() != retained.sources().size()) {
      throw new IllegalArgumentException("Changed original publication issuance evidence");
    }
    var participation =
        dsl
            .fetch(
                "SELECT source_key, source_evidence FROM account_selected_publication_sources"
                    + " WHERE operation_id = ?",
                retained.operationId())
            .stream()
            .sorted(java.util.Comparator.comparing(r -> r.get("source_key", String.class)))
            .toList();
    if (participation.size() != retained.sources().size()) {
      throw new IllegalArgumentException("Incomplete original publication source participation");
    }
    for (int i = 0; i < retained.sources().size(); i++) {
      var source = retained.sources().get(i);
      if (!(vector.get(i) instanceof String encoded)
          || !Arrays.equals(source.canonicalBytes(), java.util.Base64.getDecoder().decode(encoded))
          || !source.key().equals(participation.get(i).get("source_key", String.class))
          || !Arrays.equals(
              source.canonicalBytes(), participation.get(i).get("source_evidence", byte[].class))) {
        throw new IllegalArgumentException("Changed original publication source participation");
      }
    }
  }

  /**
   * Persist only after strict authenticated reads from both owners, outside this transaction.
   * Structure alone is not owner authentication. World retains the same complete terminal bytes
   * with PUBLISHED or ABORTED phase. Transport request IDs deliberately do not enter identity.
   */
  public byte[] settle(
      GameDesignPublicationOperationBinding operation,
      GameDesignPublicationTerminalEvidence gameDesign,
      String worldOutcome,
      byte[] worldTerminalEvidence) {
    requireTransaction();
    operation = GameDesignPublicationOperationBinding.fromStored(operation.canonicalBytes());
    gameDesign = GameDesignPublicationTerminalEvidence.fromStored(gameDesign.canonicalBytes());
    var world = GameDesignPublicationTerminalEvidence.fromStored(worldTerminalEvidence);
    String expectedPhase =
        gameDesign.outcome() == GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
            ? "PUBLISHED"
            : "ABORTED";
    if (!expectedPhase.equals(worldOutcome)
        || !Arrays.equals(operation.canonicalBytes(), gameDesign.operationBytes())
        || !Arrays.equals(gameDesign.canonicalBytes(), world.canonicalBytes())) {
      throw new IllegalArgumentException("Exact publication owner outcomes disagree");
    }
    readOriginal(operation.account());
    byte[] receipt = settlementReceipt(operation, gameDesign, worldOutcome, worldTerminalEvidence);
    Record prior =
        dsl.fetchOne(
            "SELECT receipt FROM account_selected_publication_settlements WHERE operation_id = ?",
            operation.account().operationId());
    if (prior != null) {
      if (!Arrays.equals(receipt, prior.get("receipt", byte[].class))) {
        throw new IllegalArgumentException("Changed original publication settlement");
      }
      return prior.get("receipt", byte[].class);
    }
    dsl.execute(
        "INSERT INTO account_selected_publication_settlements"
            + " (operation_id, fence_id, account_binding, publication_operation,"
            + " game_design_outcome, game_design_terminal, world_outcome, world_terminal, receipt)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        operation.account().operationId(),
        operation.account().fenceId(),
        operation.account().canonicalBytes(),
        operation.canonicalBytes(),
        gameDesign.outcome().name(),
        gameDesign.canonicalBytes(),
        worldOutcome,
        worldTerminalEvidence,
        receipt);
    Record retained =
        dsl.fetchOne(
            "SELECT receipt FROM account_selected_publication_settlements WHERE operation_id = ?",
            operation.account().operationId());
    if (retained == null || !Arrays.equals(receipt, retained.get("receipt", byte[].class))) {
      throw new IllegalStateException("Exact publication settlement readback is absent");
    }
    return retained.get("receipt", byte[].class);
  }

  private static byte[] settlementReceipt(
      GameDesignPublicationOperationBinding operation,
      GameDesignPublicationTerminalEvidence gameDesign,
      String worldOutcome,
      byte[] worldTerminalEvidence) {
    var out = new java.io.ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, "account-selected-publication-settlement/v1");
    DraftAuthorizationFenceBinding.frame(out, operation.account().canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, operation.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, gameDesign.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, worldOutcome);
    DraftAuthorizationFenceBinding.frame(out, worldTerminalEvidence);
    return out.toByteArray();
  }

  private void requireExact(
      Record row,
      AccountPublicationAuthorizationBinding binding,
      AccountPublicationAuthorizationBinding.PreallocationInput input,
      AccountControlUiActorService.Current current) {
    validateRetainedWorldIfPresent(row, binding);
    if (row == null
        || !binding.operationId().equals(row.get("operation_id", UUID.class))
        || !binding.fenceId().equals(row.get("fence_id", UUID.class))
        || !input.actorAccountId().equals(row.get("actor_account_uuid", UUID.class))
        || !binding.tenantId().equals(row.get("tenant_uuid", UUID.class))
        || !binding.publishRequestId().equals(row.get("publish_request_id", String.class))
        || !input.digest().equals(row.get("input_digest", String.class))
        || !Arrays.equals(input.canonicalBytes(), binding.input().canonicalBytes())
        || !Arrays.equals(binding.canonicalBytes(), row.get("binding", byte[].class))
        || !current.stored().operationId.equals(row.get("issuance_operation_id", UUID.class))
        || current.source().issuanceFence() != row.get("issuance_fence", Long.class)
        || !Arrays.equals(current.stored().sources, row.get("source_payload", byte[].class))
        || !Arrays.equals(current.stored().bundle, row.get("issuance_bundle", byte[].class))
        || !Arrays.equals(
            net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority.canonical(
                current.source().outboxCheckpoints()),
            row.get("outbox_checkpoints", byte[].class))) {
      throw new IllegalArgumentException("Changed immutable selected-publication authorization");
    }
    var stored =
        dsl
            .fetch(
                "SELECT source_key, source_evidence FROM account_selected_publication_sources"
                    + " WHERE operation_id = ?",
                binding.operationId())
            .stream()
            .sorted(java.util.Comparator.comparing(r -> r.get("source_key", String.class)))
            .toList();
    if (stored.size() != binding.sources().size()
        || stored.size() != current.source().sources().size()) {
      throw new IllegalStateException("Incomplete publication source participation");
    }
    for (int i = 0; i < stored.size(); i++) {
      var source = binding.sources().get(i);
      if (!source.key().equals(stored.get(i).get("source_key", String.class))
          || !Arrays.equals(
              source.canonicalBytes(), stored.get(i).get("source_evidence", byte[].class))
          || !Arrays.equals(
              source.canonicalBytes(), current.source().sources().get(i).canonicalBytes())) {
        throw new IllegalArgumentException("Changed original publication source vector");
      }
    }
  }

  /** Historical V103 World evidence remains immutable and is checked when a row contains it. */
  private static void validateRetainedWorldIfPresent(
      Record row, AccountPublicationAuthorizationBinding binding) {
    byte[] worldEvidence = row == null ? null : row.get("world_evidence", byte[].class);
    if (worldEvidence != null) {
      new GameDesignPublicationOperationBinding(
          binding, WorldPublishedStartLocationEvidence.fromStored(worldEvidence));
    }
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Objects.equals(
            TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
            Connection.TRANSACTION_READ_COMMITTED)) {
      throw new IllegalStateException("Writable READ_COMMITTED Account owner transaction required");
    }
  }
}
