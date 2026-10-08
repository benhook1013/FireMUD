package net.firedevops.firemud.accountservice.service.session;

import java.sql.Connection;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Owner-transaction persistence only; no caller-carried terminal proof is accepted. */
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
      WorldPublishedStartLocationEvidence world,
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
      requireExact(prior, original, input, world, current);
      return original;
    }
    admission.run();
    var binding =
        new AccountPublicationAuthorizationBinding(
            UUID.randomUUID(), UUID.randomUUID(), input, current.source().sources());
    // The shared closed operation checks the entire selection and World relation, not authority.
    new GameDesignPublicationOperationBinding(binding, world);
    dsl.execute(
        "INSERT INTO account_selected_publication_authorizations"
            + " (operation_id, fence_id, actor_account_uuid, tenant_uuid, publish_request_id,"
            + " input_digest, binding, world_evidence, issuance_operation_id, issuance_fence,"
            + " source_payload, issuance_bundle, outbox_checkpoints) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        binding.operationId(),
        binding.fenceId(),
        input.actorAccountId(),
        binding.tenantId(),
        binding.publishRequestId(),
        input.digest(),
        binding.canonicalBytes(),
        world.canonicalBytes(),
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
    requireExact(retained, binding, input, world, current);
    return binding;
  }

  /** Exact owner read; caller-carried structure alone can never establish HELD. */
  public void readHeld(AccountPublicationAuthorizationBinding requested) {
    requireTransaction();
    Objects.requireNonNull(requested);
    // Sources precede the operation. No transport is permitted in this owner transaction.
    for (var source : requested.sources()) {
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
    var world =
        WorldPublishedStartLocationEvidence.fromStored(row.get("world_evidence", byte[].class));
    new GameDesignPublicationOperationBinding(retained, world);
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

  private void requireExact(
      Record row,
      AccountPublicationAuthorizationBinding binding,
      AccountPublicationAuthorizationBinding.PreallocationInput input,
      WorldPublishedStartLocationEvidence world,
      AccountControlUiActorService.Current current) {
    new GameDesignPublicationOperationBinding(binding, world);
    if (row == null
        || !binding.operationId().equals(row.get("operation_id", UUID.class))
        || !binding.fenceId().equals(row.get("fence_id", UUID.class))
        || !input.actorAccountId().equals(row.get("actor_account_uuid", UUID.class))
        || !binding.tenantId().equals(row.get("tenant_uuid", UUID.class))
        || !binding.publishRequestId().equals(row.get("publish_request_id", String.class))
        || !input.digest().equals(row.get("input_digest", String.class))
        || !Arrays.equals(input.canonicalBytes(), binding.input().canonicalBytes())
        || !Arrays.equals(binding.canonicalBytes(), row.get("binding", byte[].class))
        || !Arrays.equals(world.canonicalBytes(), row.get("world_evidence", byte[].class))
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
