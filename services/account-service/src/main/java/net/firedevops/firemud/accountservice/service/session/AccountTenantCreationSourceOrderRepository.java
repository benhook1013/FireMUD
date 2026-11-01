package net.firedevops.firemud.accountservice.service.session;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.client.GameDesignOriginalTenantCreationOutcomeClient.VerifiedTerminal;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationAdmissionRepository.Admitted;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationIntentRepository.OriginalIntent;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Outcome;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal immutable source-order storage. It is not a creation authorization or dispatch API. */
final class AccountTenantCreationSourceOrderRepository {
  private static final String ORDERS = "account_tenant_creation_source_orders";
  private static final String SOURCES = "account_tenant_creation_source_order_sources";
  private final DSLContext dsl;
  private final DraftAuthorizationFenceRepository sourceOrdering;

  AccountTenantCreationSourceOrderRepository(
      DSLContext dsl, DraftAuthorizationFenceRepository sourceOrdering) {
    this.dsl = Objects.requireNonNull(dsl, "Account owner SQL transaction is required");
    this.sourceOrdering =
        Objects.requireNonNull(sourceOrdering, "shared Account source ordering is required");
  }

  /**
   * Retains immutable captured data while excluding writers through the shared source locks.
   * Package scope and caller-owned exact evidence keep this as storage, never a grant factory.
   */
  AccountTenantCreationSourceOrderBinding acquireLocked(
      OriginalIntent intent, Admitted suppliedAdmission, List<SourceEvidence> suppliedSources) {
    requireWritableTransaction();
    Objects.requireNonNull(intent, "persisted original tenant-creation intent is required");
    Objects.requireNonNull(suppliedAdmission, "durable original admission is required");

    AccountTenantCreationSourceOrderBinding binding =
        AccountTenantCreationSourceOrderBinding.from(intent, suppliedAdmission, suppliedSources);

    // The common source writer/acquirer ordering is exact lexicographic source key order with
    // NOWAIT. Existing source-lock rows must already exist; missing rows are never seeded here.
    sourceOrdering.requirePublicationAdmission(binding.sources());

    Record existing = readOrderRow(binding.creationOperationId());
    if (existing != null) {
      return requireSameOriginal(existing, binding);
    }

    requireExactPersistedIntent(intent);
    requireExactPersistedAdmission(binding, suppliedAdmission);

    int inserted =
        dsl.execute(
            "INSERT INTO "
                + ORDERS
                + " (creation_operation_id, intent_request_id, target_namespace, "
                + "game_design_creation_request_id, actor_account_id, creator_party_id, "
                + "intent_request_payload, intent_request_digest, admission_binding_payload, "
                + "admission_binding_digest, authorization_binding_payload, authorization_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            binding.creationOperationId(),
            binding.intentRequestId(),
            binding.targetNamespace(),
            binding.gameDesignCreationRequestId(),
            binding.actorAccountId(),
            binding.creatorPartyId(),
            binding.intentPayload(),
            binding.intentDigest(),
            binding.admissionPayload(),
            binding.admissionDigest(),
            binding.canonicalBytes(),
            binding.authorizationDigest());
    if (inserted == 0) {
      existing = readOrderRow(binding.creationOperationId());
      if (existing == null) {
        throw conflict("Tenant-creation source-order identity is already claimed");
      }
      return requireSameOriginal(existing, binding);
    }

    for (SourceEvidence source : binding.sources()) {
      dsl.execute(
          "INSERT INTO "
              + SOURCES
              + " (creation_operation_id, source_key, source_evidence)"
              + " VALUES (?, ?, ?)",
          binding.creationOperationId(),
          source.key(),
          source.canonicalBytes());
    }

    AccountTenantCreationSourceOrderBinding stored =
        readOriginal(binding.creationOperationId())
            .orElseThrow(() -> conflict("Original tenant-creation source order was not retained"));
    requireSame(stored, binding);
    return stored;
  }

  /** Reads immutable original source-order data only; absence is not a terminal outcome. */
  Optional<AccountTenantCreationSourceOrderBinding> readOriginal(UUID creationOperationId) {
    requireTransaction();
    Objects.requireNonNull(creationOperationId, "creation operation ID is required");
    Record row = readOrderRow(creationOperationId);
    return row == null ? Optional.empty() : Optional.of(mapOriginal(row));
  }

  /**
   * Closes one exact original source holder from the Account-owned client's privately verified
   * terminal read. This records terminal facts only; it neither grants nor dispatches creation.
   */
  GameTenantCreationOriginalOutcomeEvidence settleLocked(
      AccountTenantCreationSourceOrderBinding suppliedOriginal, VerifiedTerminal verifiedTerminal) {
    requireWritableTransaction();
    Objects.requireNonNull(suppliedOriginal, "persisted original source order is required");
    Objects.requireNonNull(verifiedTerminal, "verified Game Design terminal read is required");

    GameTenantCreationOriginalOutcomeEvidence evidence =
        Objects.requireNonNull(
            verifiedTerminal.evidence(), "verified terminal evidence is required");
    AccountTenantCreationSourceOrderBinding persisted =
        readOriginal(suppliedOriginal.creationOperationId())
            .orElseThrow(() -> conflict("Original tenant-creation source order is missing"));
    requireSame(persisted, suppliedOriginal);
    requireExactTerminalBinding(persisted, verifiedTerminal.binding(), evidence);

    // Settlement takes only the established source serialization locks. It deliberately does not
    // run publication admission's WAITING-source-change predicate: a pending change must be able
    // to wait for this exact terminal closure and then proceed after every matching holder settles.
    sourceOrdering.lockProducerSourcesNowait(persisted.sources());

    AccountTenantCreationSourceOrderBinding lockedOriginal =
        readOriginal(persisted.creationOperationId())
            .orElseThrow(() -> conflict("Original tenant-creation source order disappeared"));
    requireSame(lockedOriginal, persisted);
    requireExactTerminalBinding(lockedOriginal, verifiedTerminal.binding(), evidence);

    Optional<GameTenantCreationOriginalOutcomeEvidence> existing =
        readSettlement(lockedOriginal.creationOperationId());
    if (existing.isPresent()) {
      GameTenantCreationOriginalOutcomeEvidence retained = existing.orElseThrow();
      if (!Arrays.equals(retained.canonicalBytes(), evidence.canonicalBytes())
          || !retained.evidenceDigest().equals(evidence.evidenceDigest())) {
        throw conflict("Tenant-creation source order already has a different terminal outcome");
      }
      return retained;
    }

    var receipt = evidence.receipt().orElse(null);
    int inserted =
        dsl.execute(
            "INSERT INTO account_tenant_creation_source_order_settlements "
                + "(creation_operation_id, target_namespace, game_design_creation_request_id, "
                + "account_authorization_operation_id, initiating_account_id, creation_request_digest, "
                + "account_authorization_digest, outcome_status, game_design_creation_operation_id, "
                + "creation_evidence_digest, terminal_read_payload, terminal_read_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            lockedOriginal.creationOperationId(),
            evidence.binding().targetNamespace(),
            evidence.binding().creationRequestId(),
            evidence.binding().accountAuthorizationOperationId(),
            evidence.binding().initiatingAccountId(),
            evidence.binding().creationRequestDigest(),
            evidence.binding().accountAuthorizationDigest(),
            evidence.outcome().name(),
            receipt == null ? null : receipt.operationId(),
            receipt == null ? null : receipt.evidenceDigest(),
            evidence.canonicalBytes(),
            evidence.evidenceDigest());
    if (inserted == 0) {
      GameTenantCreationOriginalOutcomeEvidence retained =
          readSettlement(lockedOriginal.creationOperationId())
              .orElseThrow(
                  () -> conflict("Tenant-creation source order settlement identity conflicted"));
      if (!Arrays.equals(retained.canonicalBytes(), evidence.canonicalBytes())
          || !retained.evidenceDigest().equals(evidence.evidenceDigest())) {
        throw conflict("Tenant-creation source order already has a different terminal outcome");
      }
      return retained;
    }

    GameTenantCreationOriginalOutcomeEvidence retained =
        readSettlement(lockedOriginal.creationOperationId())
            .orElseThrow(() -> conflict("Verified terminal outcome was not retained"));
    if (!Arrays.equals(retained.canonicalBytes(), evidence.canonicalBytes())
        || !retained.evidenceDigest().equals(evidence.evidenceDigest())) {
      throw conflict("Stored tenant-creation terminal differs from the exact verified read");
    }
    return retained;
  }

  /** Exact terminal facts only; absent or malformed storage never implies settlement. */
  Optional<GameTenantCreationOriginalOutcomeEvidence> readSettlement(UUID creationOperationId) {
    requireTransaction();
    Objects.requireNonNull(creationOperationId, "creation operation ID is required");
    Record row =
        dsl.fetchOne(
            "SELECT * FROM account_tenant_creation_source_order_settlements "
                + "WHERE creation_operation_id = ?",
            creationOperationId);
    if (row == null) {
      return Optional.empty();
    }
    AccountTenantCreationSourceOrderBinding original =
        readOriginal(creationOperationId)
            .orElseThrow(() -> conflict("Tenant-creation settlement has no original source order"));
    byte[] payload = required(row, "terminal_read_payload", byte[].class);
    String digest = required(row, "terminal_read_digest", String.class);
    final GameTenantCreationOriginalOutcomeEvidence evidence;
    try {
      evidence = GameTenantCreationOriginalOutcomeEvidence.fromStored(payload);
    } catch (IllegalArgumentException malformed) {
      throw conflict("Stored tenant-creation terminal evidence is malformed");
    }
    requireExactTerminalBinding(original, evidence.binding(), evidence);
    if (!DraftAuthorizationFenceBinding.digest(payload).equals(digest)
        || !evidence.evidenceDigest().equals(digest)
        || !original.targetNamespace().equals(row.get("target_namespace", String.class))
        || !original
            .gameDesignCreationRequestId()
            .equals(row.get("game_design_creation_request_id", UUID.class))
        || !original
            .creationOperationId()
            .equals(row.get("account_authorization_operation_id", UUID.class))
        || !original.actorAccountId().equals(row.get("initiating_account_id", UUID.class))
        || !original
            .gameDesignRequestDigest()
            .equals(row.get("creation_request_digest", String.class))
        || !original
            .authorizationDigest()
            .equals(row.get("account_authorization_digest", String.class))
        || !evidence.outcome().name().equals(row.get("outcome_status", String.class))) {
      throw conflict("Stored tenant-creation terminal digest or status differs from its bytes");
    }
    var receipt = evidence.receipt().orElse(null);
    if (!Objects.equals(
            receipt == null ? null : receipt.operationId(),
            row.get("game_design_creation_operation_id", UUID.class))
        || !Objects.equals(
            receipt == null ? null : receipt.evidenceDigest(),
            row.get("creation_evidence_digest", String.class))) {
      throw conflict("Stored tenant-creation settlement receipt differs from exact terminal bytes");
    }
    return Optional.of(evidence);
  }

  private static void requireExactTerminalBinding(
      AccountTenantCreationSourceOrderBinding original,
      GameTenantCreationOriginalOutcomeEvidence.Binding terminalBinding,
      GameTenantCreationOriginalOutcomeEvidence evidence) {
    GameTenantCreationOriginalOutcomeEvidence.Binding expected = original.outcomeBinding();
    if (!expected.equals(terminalBinding) || !expected.equals(evidence.binding())) {
      throw conflict("Game Design terminal does not match the exact original source-order binding");
    }
    if (evidence.outcome() == Outcome.CREATED && evidence.receipt().isEmpty()) {
      throw conflict("CREATED terminal lacks its complete Game Design receipt");
    }
    if (evidence.outcome() == Outcome.NOT_CREATED && evidence.receipt().isPresent()) {
      throw conflict("NOT_CREATED terminal contains a Game Design receipt");
    }
  }

  /**
   * Bounded data-only discovery. A missing terminal result, including an empty page, conveys no
   * permission and does not make a source order settled.
   */
  List<PendingSourceOrder> readPending(PendingCursor after, int limit) {
    requireTransaction();
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Source-order discovery limit must be between 1 and 100");
    }
    String sql =
        "SELECT order_row.* FROM "
            + ORDERS
            + " order_row WHERE NOT account_tenant_creation_source_order_is_settled("
            + "order_row.creation_operation_id)";
    Object[] parameters;
    if (after == null) {
      parameters = new Object[] {limit};
    } else {
      sql +=
          " AND (order_row.created_at, order_row.creation_operation_id) > (?::timestamptz, ?::uuid)";
      parameters = new Object[] {after.createdAt(), after.creationOperationId(), limit};
    }
    sql += " ORDER BY order_row.created_at, order_row.creation_operation_id LIMIT ?";
    return dsl.fetch(sql, parameters).stream()
        .map(
            row -> {
              AccountTenantCreationSourceOrderBinding binding = mapOriginal(row);
              OffsetDateTime createdAt = required(row, "created_at", OffsetDateTime.class);
              return new PendingSourceOrder(
                  binding, createdAt, new PendingCursor(createdAt, binding.creationOperationId()));
            })
        .toList();
  }

  private void requireExactPersistedIntent(OriginalIntent intent) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM account_tenant_creation_original_intents "
                + "WHERE creation_operation_id = ? AND request_id = ?",
            intent.creationOperationId(),
            intent.requestId());
    if (row == null
        || !intent.creationOperationId().equals(row.get("creation_operation_id", UUID.class))
        || !intent.requestId().equals(row.get("request_id", UUID.class))
        || !intent
            .gameDesignCreationRequestId()
            .equals(row.get("game_design_creation_request_id", UUID.class))
        || !intent.initiatingAccountId().equals(row.get("initiating_account_uuid", UUID.class))
        || !intent.targetNamespace().equals(row.get("target_namespace", String.class))
        || !intent.sourceGameTenantKey().equals(row.get("source_game_tenant_key", String.class))
        || !intent.name().equals(row.get("name", String.class))
        || !Objects.equals(intent.description(), row.get("description", String.class))
        || !intent
            .recentReauthenticationEvidenceId()
            .equals(row.get("recent_reauthentication_evidence_id", UUID.class))
        || !intent.creatorPartyId().equals(row.get("creator_party_id", UUID.class))
        || !intent.consentOperationId().equals(row.get("consent_operation_id", UUID.class))
        || !Integer.valueOf(intent.consentStatementVersion())
            .equals(row.get("consent_statement_version", Integer.class))
        || !Boolean.valueOf(intent.affirmativeConsent())
            .equals(row.get("affirmative_consent", Boolean.class))
        || !intent
            .gameDesignRequestDigest()
            .equals(row.get("game_design_request_digest", String.class))
        || !Arrays.equals(intent.requestPayload(), row.get("request_payload", byte[].class))
        || !intent.requestDigest().equals(row.get("request_digest", String.class))) {
      throw conflict("Source order requires the exact durable original intent");
    }
  }

  private void requireExactPersistedAdmission(
      AccountTenantCreationSourceOrderBinding binding, Admitted suppliedAdmission) {
    Admitted persisted =
        new AccountTenantCreationAdmissionRepository(dsl)
            .readAdmitted(binding.creationOperationId())
            .orElseThrow(() -> conflict("Source order requires an existing durable admission"));
    if (!Arrays.equals(suppliedAdmission.receiptPayload(), persisted.receiptPayload())
        || !suppliedAdmission.receiptDigest().equals(persisted.receiptDigest())
        || !Arrays.equals(binding.admissionPayload(), persisted.binding().canonicalReceipt())) {
      throw conflict("Source order admission differs from the durable admitted row");
    }
  }

  private Record readOrderRow(UUID creationOperationId) {
    return dsl.fetchOne(
        "SELECT * FROM " + ORDERS + " WHERE creation_operation_id = ?", creationOperationId);
  }

  private AccountTenantCreationSourceOrderBinding requireSameOriginal(
      Record row, AccountTenantCreationSourceOrderBinding expected) {
    AccountTenantCreationSourceOrderBinding stored = mapOriginal(row);
    requireSame(stored, expected);
    return stored;
  }

  private AccountTenantCreationSourceOrderBinding mapOriginal(Record row) {
    UUID operationId = required(row, "creation_operation_id", UUID.class);
    byte[] bindingBytes = required(row, "authorization_binding_payload", byte[].class);
    String digest = required(row, "authorization_digest", String.class);
    AccountTenantCreationSourceOrderBinding binding =
        AccountTenantCreationSourceOrderBinding.fromStored(bindingBytes);
    if (!operationId.equals(binding.creationOperationId())
        || !binding.intentRequestId().equals(row.get("intent_request_id", UUID.class))
        || !binding.targetNamespace().equals(row.get("target_namespace", String.class))
        || !binding
            .gameDesignCreationRequestId()
            .equals(row.get("game_design_creation_request_id", UUID.class))
        || !binding.actorAccountId().equals(row.get("actor_account_id", UUID.class))
        || !binding.creatorPartyId().equals(row.get("creator_party_id", UUID.class))
        || !Arrays.equals(binding.intentPayload(), row.get("intent_request_payload", byte[].class))
        || !binding.intentDigest().equals(row.get("intent_request_digest", String.class))
        || !Arrays.equals(
            binding.admissionPayload(), row.get("admission_binding_payload", byte[].class))
        || !binding.admissionDigest().equals(row.get("admission_binding_digest", String.class))
        || !binding.authorizationDigest().equals(digest)
        || !DraftAuthorizationFenceBinding.digest(bindingBytes).equals(digest)) {
      throw conflict("Stored tenant-creation source-order columns differ from exact binding");
    }
    if (dsl.fetchOne(
            "SELECT account_tenant_creation_source_order_validate_binding(order_row) "
                + "FROM account_tenant_creation_source_orders order_row "
                + "WHERE order_row.creation_operation_id = ?",
            operationId)
        == null) {
      throw conflict("Durable tenant-creation intent/admission correlation is unavailable");
    }
    requireExactSourceRows(binding);
    return binding;
  }

  private void requireExactSourceRows(AccountTenantCreationSourceOrderBinding binding) {
    List<Record> rows =
        dsl.fetch(
            "SELECT source_key, source_evidence FROM "
                + SOURCES
                + " WHERE creation_operation_id = ? ORDER BY source_key",
            binding.creationOperationId());
    if (rows.size() != AccountTenantCreationSourceOrderBinding.SOURCE_COUNT) {
      throw conflict("Stored tenant-creation source vector is incomplete");
    }
    List<SourceEvidence> stored =
        rows.stream()
            .map(
                row -> {
                  byte[] evidence = required(row, "source_evidence", byte[].class);
                  SourceEvidence source = SourceEvidence.fromStored(evidence);
                  if (!source.key().equals(row.get("source_key", String.class))) {
                    throw conflict("Stored source key differs from canonical owner evidence");
                  }
                  return source;
                })
            .toList();
    List<SourceEvidence> expected =
        binding.sources().stream().sorted(Comparator.comparing(SourceEvidence::key)).toList();
    if (!stored.equals(expected)) {
      throw conflict("Stored tenant-creation source values differ from the immutable original");
    }
  }

  private static void requireSame(
      AccountTenantCreationSourceOrderBinding stored,
      AccountTenantCreationSourceOrderBinding expected) {
    if (!Arrays.equals(stored.canonicalBytes(), expected.canonicalBytes())
        || !stored.authorizationDigest().equals(expected.authorizationDigest())) {
      throw conflict("Tenant-creation source-order identity was reused with changed binding");
    }
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    T value = row.get(field, type);
    if (value == null) {
      throw conflict("Stored tenant-creation source order lacks " + field);
    }
    return value;
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Tenant-creation source order requires an Account transaction");
    }
  }

  private static void requireWritableTransaction() {
    requireTransaction();
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Tenant-creation source-order acquisition requires a writable Account transaction");
    }
  }

  private static IllegalStateException conflict(String message) {
    return new IllegalStateException(message);
  }

  record PendingCursor(OffsetDateTime createdAt, UUID creationOperationId) {
    PendingCursor {
      Objects.requireNonNull(createdAt);
      DraftAuthorizationFenceBinding.requireUuid(creationOperationId);
    }
  }

  record PendingSourceOrder(
      AccountTenantCreationSourceOrderBinding binding,
      OffsetDateTime createdAt,
      PendingCursor cursor) {}
}
