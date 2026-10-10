package net.firedevops.firemud.accountservice.service.session;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.common.account.sourceintake.AccountSelectedOwnerIntakeSettlementReceipt;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeTerminalReadEvidence;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceContent;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Immutable Entity/Automation preliminary reservations, finalized source retention, and exact
 * Automation {@code COMMITTED_EMPTY} terminal settlement.
 */
public final class AccountSelectedOwnerIntakeSourceReservationRepository {
  private static final String RESERVATIONS =
      "account_selected_owner_intake_source_read_reservations";
  private static final String SOURCES = "account_selected_owner_intake_source_read_sources";
  private static final String ABORTS = "account_selected_owner_intake_source_read_aborts";
  private static final String AUTHORIZATIONS = "account_selected_owner_intake_authorizations";
  private static final String AUTHORIZATION_SOURCES = "account_selected_owner_intake_sources";
  private static final String SETTLEMENTS = "account_selected_owner_intake_settlements";

  private final DSLContext dsl;

  public AccountSelectedOwnerIntakeSourceReservationRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  public enum State {
    RESERVED,
    FINALIZED,
    ABORTED
  }

  /** Historical evidence only. RESERVED does not imply a live source-read permission. */
  public record Recovery(SelectedOwnerIntakeSourceReadScope scope, State state) {
    public Recovery {
      Objects.requireNonNull(scope, "scope");
      Objects.requireNonNull(state, "state");
    }
  }

  /** Exact database evidence sampled before the independent active-registry read. */
  record SourceReadCurrentness(
      String tokenHash,
      String registryDigest,
      long expiresAtEpochSecond,
      String issuerEvidenceDigest,
      String sourceVectorDigest) {
    SourceReadCurrentness {
      Objects.requireNonNull(tokenHash);
      Objects.requireNonNull(registryDigest);
      Objects.requireNonNull(issuerEvidenceDigest);
      Objects.requireNonNull(sourceVectorDigest);
    }
  }

  SelectedOwnerIntakeSourceReadScope reserveSourceRead(
      UUID intakeRequestId,
      Owner owner,
      DraftCommitBinding selected,
      AccountControlUiActorService.Current current,
      String namespace,
      Runnable admission) {
    requireTransaction();
    DraftAuthorizationFenceBinding.requireUuid(intakeRequestId);
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(selected, "selected");
    requireOwner(owner);
    Objects.requireNonNull(current, "current");
    Objects.requireNonNull(admission, "admission");

    UUID tenantId = selected.target().canonicalTenantId();
    Record existing =
        dsl.fetchOne(
            "SELECT * FROM "
                + RESERVATIONS
                + " WHERE tenant_uuid = ? AND intake_request_id = ? FOR UPDATE",
            tenantId,
            intakeRequestId);
    if (existing != null) {
      SelectedOwnerIntakeSourceReadScope original =
          SelectedOwnerIntakeSourceReadScope.fromStored(existing.get("scope_bytes", byte[].class));
      requireReservation(existing, original);
      requireSameScope(original, namespace, owner, selected, current.stored().accountId);
      requireCurrentReservation(existing, current);
      if (aborted(original)) throw new IllegalStateException("Original source-read scope aborted");
      return original;
    }

    admission.run();
    var scope =
        new SelectedOwnerIntakeSourceReadScope(
            owner,
            namespace,
            UUID.randomUUID(),
            UUID.randomUUID(),
            intakeRequestId,
            current.stored().accountId,
            selected);
    dsl.execute(
        "INSERT INTO "
            + RESERVATIONS
            + " (operation_id, fence_id, intake_request_id, owner, actor_account_uuid, tenant_uuid, "
            + "version_uuid, scope_bytes, scope_digest, issuance_operation_id, issuance_fence, "
            + "source_payload, issuance_bundle, outbox_checkpoints) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        scope.operationId(),
        scope.fenceId(),
        intakeRequestId,
        owner.name(),
        scope.actorAccountId(),
        tenantId,
        selected.target().canonicalVersionId(),
        scope.canonicalBytes(),
        scope.digest(),
        current.stored().operationId,
        current.source().issuanceFence(),
        current.stored().sources,
        current.stored().bundle,
        AccountControlUiAuthority.canonical(current.source().outboxCheckpoints()));
    for (var source : current.source().sources()) {
      dsl.execute(
          "INSERT INTO "
              + SOURCES
              + " (operation_id, source_key, source_evidence) VALUES (?, ?, ?)",
          scope.operationId(),
          source.key(),
          source.canonicalBytes());
    }
    requireReservation(reservation(scope), scope);
    return scope;
  }

  Optional<SelectedOwnerIntakeSourceReadScope> findSourceReadScope(
      UUID intakeRequestId,
      Owner owner,
      DraftCommitBinding selected,
      String namespace,
      String originalTokenHash) {
    requireTransaction();
    DraftAuthorizationFenceBinding.requireUuid(intakeRequestId);
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(selected, "selected");
    requireOwner(owner);
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(originalTokenHash, "originalTokenHash");
    var row =
        dsl.fetchOne(
            "SELECT * FROM " + RESERVATIONS + " WHERE tenant_uuid = ? AND intake_request_id = ?",
            selected.target().canonicalTenantId(),
            intakeRequestId);
    if (row == null) return Optional.empty();
    var scope = SelectedOwnerIntakeSourceReadScope.fromStored(row.get("scope_bytes", byte[].class));
    requireReservation(row, scope);
    requireSameScope(scope, namespace, owner, selected, scope.actorAccountId());
    var issuer = issuer(row.get("issuance_operation_id", UUID.class));
    if (!originalTokenHash.equals(issuer.tokenHash)) {
      throw new IllegalArgumentException("Original creator credential differs from reservation");
    }
    return Optional.of(scope);
  }

  Recovery recoverSourceRead(SelectedOwnerIntakeSourceReadScope scope) {
    requireTransaction();
    lockReservedSources(scope);
    Record row = reservation(scope);
    requireReservation(row, scope);
    if (aborted(scope)) return new Recovery(scope, State.ABORTED);
    var authorization = authorization(scope.operationId());
    if (authorization == null) return new Recovery(scope, State.RESERVED);
    var binding =
        SelectedOwnerIntakeAuthorizationBinding.fromStored(
            authorization.get("binding_bytes", byte[].class));
    requireAuthorization(authorization, binding);
    return new Recovery(scope, State.FINALIZED);
  }

  Recovery abortSourceRead(SelectedOwnerIntakeSourceReadScope scope) {
    requireTransaction();
    lockReservedSources(scope);
    Record row = reservation(scope);
    requireReservation(row, scope);
    if (authorization(scope.operationId()) != null) {
      throw new IllegalStateException("Finalized owner intake cannot be preliminarily aborted");
    }
    if (!aborted(scope)) {
      dsl.execute("INSERT INTO " + ABORTS + " (operation_id) VALUES (?)", scope.operationId());
    }
    return new Recovery(scope, State.ABORTED);
  }

  SelectedOwnerIntakeAuthorizationBinding finalizeSourceRead(
      SelectedOwnerIntakeSourceReadScope scope,
      SelectedOwnerIntakeSourceContent content,
      AccountControlUiActorService.Current current,
      Runnable admission) {
    requireTransaction();
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(current, "current");
    Objects.requireNonNull(admission, "admission");
    lockReservedSources(scope);
    Record reserved = reservation(scope);
    requireReservation(reserved, scope);
    requireCurrentReservation(reserved, current);
    if (!scope.equals(content.scope()))
      throw new IllegalArgumentException("Selected content differs from original source scope");
    if (aborted(scope)) throw new IllegalStateException("Original source-read scope aborted");

    Record row = authorization(scope.operationId());
    if (row != null) {
      var original =
          SelectedOwnerIntakeAuthorizationBinding.fromStored(
              row.get("binding_bytes", byte[].class));
      if (!Arrays.equals(original.content().canonicalBytes(), content.canonicalBytes()))
        throw new IllegalArgumentException(
            "Intake request identity conflicts with original selected content");
      requireAuthorization(row, original);
      return original;
    }

    List<SourceEvidence> sourceVector = current.source().sources();
    var binding = new SelectedOwnerIntakeAuthorizationBinding(content, sourceVector);
    admission.run();
    dsl.execute(
        "INSERT INTO "
            + AUTHORIZATIONS
            + " (operation_id, fence_id, intake_request_id, owner, target_namespace, actor_account_uuid, "
            + "tenant_uuid, version_uuid, content_bytes, content_digest, binding_bytes, binding_digest, "
            + "issuance_operation_id, issuance_fence, source_payload, issuance_bundle, outbox_checkpoints) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        binding.operationId(),
        binding.fenceId(),
        binding.intakeRequestId(),
        binding.owner().name(),
        binding.targetNamespace(),
        binding.actorAccountId(),
        binding.tenantId(),
        binding.versionId(),
        binding.content().canonicalBytes(),
        binding.content().digest(),
        binding.canonicalBytes(),
        binding.digest(),
        current.stored().operationId,
        current.source().issuanceFence(),
        current.stored().sources,
        current.stored().bundle,
        AccountControlUiAuthority.canonical(current.source().outboxCheckpoints()));
    for (SourceEvidence source : binding.sources()) {
      dsl.execute(
          "INSERT INTO "
              + AUTHORIZATION_SOURCES
              + " (operation_id, source_key, source_evidence) VALUES (?, ?, ?)",
          binding.operationId(),
          source.key(),
          source.canonicalBytes());
    }
    requireAuthorization(
        dsl.fetchOne(
            "SELECT * FROM " + AUTHORIZATIONS + " WHERE operation_id = ? FOR UPDATE",
            binding.operationId()),
        binding);
    return binding;
  }

  Optional<SelectedOwnerIntakeAuthorizationBinding> findFinalAuthorization(
      SelectedOwnerIntakeSourceReadScope scope) {
    requireTransaction();
    lockReservedSources(scope);
    requireReservation(reservation(scope), scope);
    Record row = authorization(scope.operationId());
    if (row == null) return Optional.empty();
    var binding =
        SelectedOwnerIntakeAuthorizationBinding.fromStored(row.get("binding_bytes", byte[].class));
    requireAuthorization(row, binding);
    if (!scope.equals(binding.content().scope()))
      throw new IllegalArgumentException("Finalized authorization differs from original scope");
    return Optional.of(binding);
  }

  /** Historical exact authorization read; settled orders remain recoverable through this path. */
  void readFinalAuthorization(SelectedOwnerIntakeAuthorizationBinding requested) {
    readFinalAuthorization(requested, false);
  }

  /**
   * External HELD reads deny after exact terminal settlement; historical reads remain available.
   */
  void readHeldFinalAuthorization(SelectedOwnerIntakeAuthorizationBinding requested) {
    readFinalAuthorization(requested, true);
  }

  private void readFinalAuthorization(
      SelectedOwnerIntakeAuthorizationBinding requested, boolean requireHeld) {
    requireTransaction();
    Objects.requireNonNull(requested, "requested");
    Record lookup = authorizationLookup(requested.operationId());
    if (lookup == null
        || !Arrays.equals(requested.canonicalBytes(), lookup.get("binding_bytes", byte[].class)))
      throw new IllegalArgumentException(
          "Original finalized owner authorization absent or changed");
    var original =
        SelectedOwnerIntakeAuthorizationBinding.fromStored(
            lookup.get("binding_bytes", byte[].class));
    SelectedOwnerIntakeSourceReadScope scope = original.content().scope();
    lockReservedSources(scope);
    requireReservation(reservation(scope), scope);
    Record row = authorization(original.operationId());
    requireAuthorization(row, original);
    if (aborted(original.content().scope()))
      throw new IllegalArgumentException("Finalized owner authorization cannot be aborted");
    if (requireHeld && settlement(original.operationId()) != null) {
      throw new IllegalArgumentException("Settled owner authorization is no longer held");
    }
  }

  /**
   * Exact committed settlement recovery; callers perform this before any external terminal read.
   */
  Optional<AccountSelectedOwnerIntakeSettlementReceipt> findSettlement(
      SelectedOwnerIntakeAuthorizationBinding requested) {
    requireTransaction();
    Objects.requireNonNull(requested, "original selected-owner authorization is required");
    if (requested.owner() != Owner.AUTOMATION_SCRIPTING) {
      throw new IllegalArgumentException("Automation terminal settlement required");
    }
    SelectedOwnerIntakeSourceReadScope scope = requested.content().scope();
    lockReservedSources(scope);
    requireReservation(reservation(scope), scope);
    Record order = authorization(requested.operationId());
    requireAuthorization(order, requested);
    if (aborted(scope))
      throw new IllegalArgumentException("Finalized owner authorization was aborted");
    Record row = settlement(requested.operationId());
    if (row == null) return Optional.empty();
    return Optional.of(exactSettlement(row, requested, null));
  }

  /**
   * Commits one immutable COMMITTED_EMPTY owner receipt after the authenticated owner read has
   * completed outside SQL. The read correlation is retained on first write but is not settlement
   * identity; a fresh correlation with identical owner receipt bytes recovers the first receipt.
   */
  AccountSelectedOwnerIntakeSettlementReceipt settleCommittedEmpty(
      AutomationSelectedSourceIntakeTerminalReadEvidence evidence) {
    requireTransaction();
    Objects.requireNonNull(evidence, "Automation terminal evidence is required");
    var requested = evidence.request().binding();
    if (requested.owner() != Owner.AUTOMATION_SCRIPTING
        || !"COMMITTED_EMPTY".equals(evidence.receipt().outcome())) {
      throw new IllegalArgumentException("Exact Automation COMMITTED_EMPTY evidence required");
    }
    SelectedOwnerIntakeSourceReadScope scope = requested.content().scope();
    lockReservedSources(scope);
    requireReservation(reservation(scope), scope);
    Record order = authorization(requested.operationId());
    requireAuthorization(order, requested);
    if (aborted(scope))
      throw new IllegalArgumentException("Finalized owner authorization was aborted");

    Record prior = settlement(requested.operationId());
    if (prior != null) return exactSettlement(prior, requested, evidence);

    AccountSelectedOwnerIntakeSettlementReceipt receipt =
        AccountSelectedOwnerIntakeSettlementReceipt.create(evidence);
    dsl.execute(
        "INSERT INTO "
            + SETTLEMENTS
            + " (operation_id, fence_id, intake_request_id, owner, target_namespace, tenant_uuid, "
            + "version_uuid, binding_bytes, binding_digest, terminal_read_request_id, "
            + "terminal_receipt_bytes, terminal_receipt_digest, receipt_bytes, receipt_digest) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        receipt.operationId(),
        requested.fenceId(),
        requested.intakeRequestId(),
        requested.owner().name(),
        requested.targetNamespace(),
        requested.tenantId(),
        requested.versionId(),
        requested.canonicalBytes(),
        requested.digest(),
        evidence.request().readRequestId(),
        receipt.ownerReceiptBytes(),
        receipt.ownerReceiptDigest(),
        receipt.canonicalBytes(),
        receipt.digest());
    return exactSettlement(settlement(requested.operationId()), requested, evidence);
  }

  private AccountSelectedOwnerIntakeSettlementReceipt exactSettlement(
      Record row,
      SelectedOwnerIntakeAuthorizationBinding requested,
      AutomationSelectedSourceIntakeTerminalReadEvidence retryEvidence) {
    if (row == null) throw new IllegalStateException("Account settlement commit readback absent");
    byte[] storedBytes = row.get("receipt_bytes", byte[].class);
    var receipt = AccountSelectedOwnerIntakeSettlementReceipt.fromStored(storedBytes);
    var original = receipt.authorizationBinding();
    var request = receipt.terminalEvidence().request();
    var ownerReceipt = receipt.terminalEvidence().receipt();
    if (!Arrays.equals(original.canonicalBytes(), requested.canonicalBytes())
        || !Arrays.equals(storedBytes, receipt.canonicalBytes())
        || !receipt.digest().equals(row.get("receipt_digest", String.class))
        || !Arrays.equals(
            ownerReceipt.canonicalBytes(), row.get("terminal_receipt_bytes", byte[].class))
        || !ownerReceipt.receiptDigest().equals(row.get("terminal_receipt_digest", String.class))
        || !request.readRequestId().equals(row.get("terminal_read_request_id", UUID.class))
        || !original.operationId().equals(row.get("operation_id", UUID.class))
        || !original.fenceId().equals(row.get("fence_id", UUID.class))
        || !original.intakeRequestId().equals(row.get("intake_request_id", UUID.class))
        || !original.owner().name().equals(row.get("owner", String.class))
        || !original.targetNamespace().equals(row.get("target_namespace", String.class))
        || !original.tenantId().equals(row.get("tenant_uuid", UUID.class))
        || !original.versionId().equals(row.get("version_uuid", UUID.class))
        || !Arrays.equals(original.canonicalBytes(), row.get("binding_bytes", byte[].class))
        || !original.digest().equals(row.get("binding_digest", String.class))) {
      throw new IllegalArgumentException("Changed exact selected-owner terminal settlement");
    }
    if (retryEvidence != null && !receipt.sameImmutableOwnerReceipt(retryEvidence)) {
      throw new IllegalArgumentException("Changed Automation owner terminal receipt");
    }
    return receipt;
  }

  private Record settlement(UUID operationId) {
    return dsl.fetchOne(
        "SELECT * FROM " + SETTLEMENTS + " WHERE operation_id = ? FOR UPDATE", operationId);
  }

  private Record authorization(UUID operationId) {
    return dsl.fetchOne(
        "SELECT * FROM " + AUTHORIZATIONS + " WHERE operation_id = ? FOR UPDATE", operationId);
  }

  private Record authorizationLookup(UUID operationId) {
    return dsl.fetchOne("SELECT * FROM " + AUTHORIZATIONS + " WHERE operation_id = ?", operationId);
  }

  private void requireAuthorization(Record row, SelectedOwnerIntakeAuthorizationBinding binding) {
    if (row == null
        || !Arrays.equals(binding.canonicalBytes(), row.get("binding_bytes", byte[].class))
        || !binding.digest().equals(row.get("binding_digest", String.class))
        || !Arrays.equals(
            binding.content().canonicalBytes(), row.get("content_bytes", byte[].class))
        || !binding.content().digest().equals(row.get("content_digest", String.class))
        || !binding.operationId().equals(row.get("operation_id", UUID.class))
        || !binding.fenceId().equals(row.get("fence_id", UUID.class))
        || !binding.intakeRequestId().equals(row.get("intake_request_id", UUID.class))
        || !binding.owner().name().equals(row.get("owner", String.class))
        || !binding.targetNamespace().equals(row.get("target_namespace", String.class))
        || !binding.actorAccountId().equals(row.get("actor_account_uuid", UUID.class))
        || !binding.tenantId().equals(row.get("tenant_uuid", UUID.class))
        || !binding.versionId().equals(row.get("version_uuid", UUID.class))) {
      throw new IllegalArgumentException(
          "Original finalized owner authorization absent or changed");
    }
    Record reserved = reservation(binding.content().scope());
    requireReservation(reserved, binding.content().scope());
    if (!binding.operationId().equals(reserved.get("operation_id", UUID.class))
        || !binding.fenceId().equals(reserved.get("fence_id", UUID.class))
        || !binding.intakeRequestId().equals(reserved.get("intake_request_id", UUID.class))
        || !binding.owner().name().equals(reserved.get("owner", String.class))
        || !binding.actorAccountId().equals(reserved.get("actor_account_uuid", UUID.class))
        || !binding.tenantId().equals(reserved.get("tenant_uuid", UUID.class))
        || !binding.versionId().equals(reserved.get("version_uuid", UUID.class))) {
      throw new IllegalArgumentException(
          "Finalized authorization differs from original reservation");
    }
    var issuer = issuer(reserved.get("issuance_operation_id", UUID.class));
    if (!issuer.operationId.equals(row.get("issuance_operation_id", UUID.class))
        || !Objects.equals(
            reserved.get("issuance_fence", Long.class), row.get("issuance_fence", Long.class))
        || !Arrays.equals(issuer.sources, row.get("source_payload", byte[].class))
        || !Arrays.equals(issuer.bundle, row.get("issuance_bundle", byte[].class))
        || !Arrays.equals(
            reserved.get("outbox_checkpoints", byte[].class),
            row.get("outbox_checkpoints", byte[].class))) {
      throw new IllegalArgumentException("Changed finalized creator evidence");
    }
    List<Record> sources =
        dsl.fetch(
            "SELECT source_key, source_evidence FROM "
                + AUTHORIZATION_SOURCES
                + " WHERE operation_id = ? ORDER BY account_publication_authorization_source_sort_key(source_key)",
            binding.operationId());
    if (sources.size() != binding.sources().size())
      throw new IllegalArgumentException("Incomplete finalized source participation");
    for (int index = 0; index < sources.size(); index++) {
      SourceEvidence source = binding.sources().get(index);
      if (!source.key().equals(sources.get(index).get("source_key", String.class))
          || !Arrays.equals(
              source.canonicalBytes(), sources.get(index).get("source_evidence", byte[].class))) {
        throw new IllegalArgumentException("Changed finalized source participation");
      }
    }
  }

  SourceReadCurrentness sourceReadCurrentness(SelectedOwnerIntakeSourceReadScope scope) {
    requireTransaction();
    Record row = reservation(scope);
    requireReservation(row, scope);
    if (aborted(scope)) throw new IllegalStateException("Original source-read scope aborted");
    if (authorization(scope.operationId()) != null)
      throw new IllegalStateException("Finalized owner intake is not a preliminary source read");
    var original = issuer(row.get("issuance_operation_id", UUID.class));
    if (!"COMMITTED".equals(original.status))
      throw new IllegalStateException("Original creator issuance is no longer committed");
    return currentness(row, original);
  }

  void readSourceScope(SelectedOwnerIntakeSourceReadScope scope, SourceReadCurrentness expected) {
    requireTransaction();
    lockReservedSources(scope);
    Record row = reservation(scope);
    requireReservation(row, scope);
    if (aborted(scope)) throw new IllegalStateException("Original source-read scope aborted");
    if (authorization(scope.operationId()) != null)
      throw new IllegalStateException("Finalized owner intake is not a preliminary source read");
    var original = issuer(row.get("issuance_operation_id", UUID.class));
    if (!"COMMITTED".equals(original.status) || !currentness(row, original).equals(expected)) {
      throw new IllegalStateException("Exact current pending source-read scope required");
    }
    // Use the Account database clock only after the final sorted source and operation locks.
    // A caller clock sampled before lock acquisition is not a live-read expiry proof.
    if (dsl.fetchOne(
            "SELECT operation_id FROM account_control_ui_issuance_operations"
                + " WHERE operation_id = ? AND status = 'COMMITTED'"
                + " AND extract(epoch FROM clock_timestamp()) < expires_at_epoch_second",
            original.operationId)
        == null) {
      throw new IllegalStateException("Original creator issuance expired before final readback");
    }
  }

  private SourceReadCurrentness currentness(
      Record row, AccountControlUiIssuanceRepository.Stored original) {
    return new SourceReadCurrentness(
        original.tokenHash,
        DraftAuthorizationFenceBinding.digest(original.activeRegistry),
        original.expiresAt.getEpochSecond(),
        issuerEvidenceDigest(row, original),
        sourceVectorDigest(row.get("operation_id", UUID.class)));
  }

  private String issuerEvidenceDigest(
      Record reservation, AccountControlUiIssuanceRepository.Stored original) {
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("operationId", original.operationId.toString());
    evidence.put("requestId", original.requestId.toString());
    evidence.put("jti", original.jti.toString());
    evidence.put("accountId", original.accountId.toString());
    evidence.put("tenantId", original.tenantId.toString());
    evidence.put("caller", original.caller);
    evidence.put("callerContextId", original.callerContextId.toString());
    evidence.put("requestMacKeyId", original.requestMacKeyId);
    evidence.put("requestDigest", original.requestDigest);
    evidence.put("status", original.status);
    evidence.put("tokenHash", original.tokenHash);
    evidence.put("claims", Base64.getEncoder().encodeToString(original.claims));
    evidence.put("sources", Base64.getEncoder().encodeToString(original.sources));
    evidence.put("bundle", Base64.getEncoder().encodeToString(original.bundle));
    evidence.put("signerReceipt", Base64.getEncoder().encodeToString(original.signerReceipt));
    evidence.put("pendingRegistry", Base64.getEncoder().encodeToString(original.pendingRegistry));
    evidence.put("activeRegistry", Base64.getEncoder().encodeToString(original.activeRegistry));
    evidence.put("issuedAt", original.issuedAt.toString());
    evidence.put("expiresAt", original.expiresAt.toString());
    evidence.put("recoveryExpiresAt", original.recoveryExpiry.toString());
    evidence.put(
        "issuanceOperationId", reservation.get("issuance_operation_id", UUID.class).toString());
    evidence.put("issuanceFence", reservation.get("issuance_fence", Long.class).toString());
    evidence.put(
        "outboxCheckpoints",
        Base64.getEncoder().encodeToString(reservation.get("outbox_checkpoints", byte[].class)));
    return DraftAuthorizationFenceBinding.digest(AccountControlUiAuthority.canonical(evidence));
  }

  private String sourceVectorDigest(UUID operationId) {
    List<Map<String, String>> vector = new ArrayList<>();
    for (Record row :
        dsl.fetch(
            "SELECT source_key, source_evidence FROM "
                + SOURCES
                + " WHERE operation_id = ? ORDER BY account_publication_authorization_source_sort_key(source_key)",
            operationId)) {
      vector.add(
          Map.of(
              "sourceKey", row.get("source_key", String.class),
              "sourceEvidence",
                  Base64.getEncoder().encodeToString(row.get("source_evidence", byte[].class))));
    }
    return DraftAuthorizationFenceBinding.digest(
        AccountControlUiAuthority.canonical(Map.of("sources", vector)));
  }

  private Record reservation(SelectedOwnerIntakeSourceReadScope scope) {
    return dsl.fetchOne(
        "SELECT * FROM " + RESERVATIONS + " WHERE operation_id = ? FOR UPDATE",
        scope.operationId());
  }

  private boolean aborted(SelectedOwnerIntakeSourceReadScope scope) {
    return dsl.fetchOne(
            "SELECT operation_id FROM " + ABORTS + " WHERE operation_id = ?", scope.operationId())
        != null;
  }

  private void lockReservedSources(SelectedOwnerIntakeSourceReadScope scope) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM " + RESERVATIONS + " WHERE operation_id = ?", scope.operationId());
    requireReservation(row, scope);
    for (Record source :
        dsl.fetch(
            "SELECT source_key FROM "
                + SOURCES
                + " WHERE operation_id = ? ORDER BY account_publication_authorization_source_sort_key(source_key)",
            scope.operationId())) {
      if (dsl.fetchOne(
              "SELECT source_key FROM account_draft_authorization_source_locks WHERE source_key = ? FOR UPDATE",
              source.get("source_key", String.class))
          == null) {
        throw new IllegalStateException("Original preliminary source lock is unavailable");
      }
    }
  }

  private void requireReservation(Record row, SelectedOwnerIntakeSourceReadScope scope) {
    if (row == null
        || !Arrays.equals(scope.canonicalBytes(), row.get("scope_bytes", byte[].class))
        || !scope.digest().equals(row.get("scope_digest", String.class))
        || !scope.operationId().equals(row.get("operation_id", UUID.class))
        || !scope.fenceId().equals(row.get("fence_id", UUID.class))
        || !scope.intakeRequestId().equals(row.get("intake_request_id", UUID.class))
        || !scope.owner().name().equals(row.get("owner", String.class))
        || !scope.actorAccountId().equals(row.get("actor_account_uuid", UUID.class))
        || !scope.selected().target().canonicalTenantId().equals(row.get("tenant_uuid", UUID.class))
        || !scope
            .selected()
            .target()
            .canonicalVersionId()
            .equals(row.get("version_uuid", UUID.class))) {
      throw new IllegalArgumentException("Original preliminary source scope is absent or changed");
    }
    var original = issuer(row.get("issuance_operation_id", UUID.class));
    if (!scope.actorAccountId().equals(original.accountId)
        || !scope.selected().target().canonicalTenantId().equals(original.tenantId)
        || !Arrays.equals(original.sources, row.get("source_payload", byte[].class))
        || !Arrays.equals(original.bundle, row.get("issuance_bundle", byte[].class))) {
      throw new IllegalArgumentException("Original preliminary creator evidence changed");
    }
    var bundle = AccountControlUiIssuanceRepository.object(original.bundle);
    Object fence = bundle.get("issuanceFence");
    if (!Long.toString(row.get("issuance_fence", Long.class)).equals(String.valueOf(fence))) {
      throw new IllegalArgumentException("Original creator issuance fence changed");
    }
    var checkpoints = bundle.get("outboxCheckpoints");
    if (!Arrays.equals(
        AccountControlUiAuthority.canonical(checkpoints),
        row.get("outbox_checkpoints", byte[].class))) {
      throw new IllegalArgumentException("Original creator checkpoints changed");
    }

    Object storedSources =
        AccountControlUiIssuanceRepository.object(original.sources).get("sources");
    List<Record> held =
        dsl.fetch(
            "SELECT source_key, source_evidence FROM "
                + SOURCES
                + " WHERE operation_id = ? ORDER BY account_publication_authorization_source_sort_key(source_key)",
            scope.operationId());
    if (!(storedSources instanceof List<?> values)
        || values.isEmpty()
        || held.size() != values.size()) {
      throw new IllegalArgumentException("Incomplete original creator source participation");
    }
    for (int index = 0; index < values.size(); index++) {
      if (!(values.get(index) instanceof String encoded)) {
        throw new IllegalArgumentException("Malformed original creator source vector");
      }
      byte[] exact = Base64.getDecoder().decode(encoded);
      var source = DraftAuthorizationFenceBinding.SourceEvidence.fromStored(exact);
      if (!source.key().equals(held.get(index).get("source_key", String.class))
          || !Arrays.equals(exact, held.get(index).get("source_evidence", byte[].class))) {
        throw new IllegalArgumentException("Changed original complete source participation");
      }
    }
  }

  private AccountControlUiIssuanceRepository.Stored issuer(UUID operationId) {
    Record issuer =
        dsl.fetchOne(
            "SELECT * FROM account_control_ui_issuance_operations WHERE operation_id = ?",
            operationId);
    if (issuer == null) throw new IllegalStateException("Original creator issuance is unavailable");
    return new AccountControlUiIssuanceRepository.Stored(issuer);
  }

  private static void requireSameScope(
      SelectedOwnerIntakeSourceReadScope scope,
      String namespace,
      Owner owner,
      DraftCommitBinding selected,
      UUID actorAccountId) {
    if (!namespace.equals(scope.targetNamespace())
        || owner != scope.owner()
        || !selected.equals(scope.selected())
        || !actorAccountId.equals(scope.actorAccountId())) {
      throw new IllegalArgumentException("Intake request conflicts with its original source scope");
    }
  }

  private static void requireOwner(Owner owner) {
    if (owner != Owner.ENTITY_MANAGEMENT && owner != Owner.AUTOMATION_SCRIPTING) {
      throw new IllegalArgumentException("Entity or Automation selected-source owner required");
    }
  }

  private void requireCurrentReservation(Record row, AccountControlUiActorService.Current current) {
    var original = issuer(row.get("issuance_operation_id", UUID.class));
    if (!"COMMITTED".equals(original.status)
        || !original.operationId.equals(current.stored().operationId)
        || !original.accountId.equals(current.stored().accountId)
        || !original.tenantId.equals(current.stored().tenantId)
        || !original.tokenHash.equals(current.stored().tokenHash)
        || !Arrays.equals(current.stored().sources, row.get("source_payload", byte[].class))
        || !Arrays.equals(current.stored().bundle, row.get("issuance_bundle", byte[].class))
        || !Arrays.equals(
            AccountControlUiAuthority.canonical(current.source().outboxCheckpoints()),
            row.get("outbox_checkpoints", byte[].class))) {
      throw new IllegalArgumentException("Changed original intake creator authority");
    }
    List<SourceEvidence> currentSources = current.source().sources();
    List<Record> heldSources =
        dsl.fetch(
            "SELECT source_key, source_evidence FROM "
                + SOURCES
                + " WHERE operation_id = ? ORDER BY account_publication_authorization_source_sort_key(source_key)",
            row.get("operation_id", UUID.class));
    if (currentSources.size() != heldSources.size())
      throw new IllegalArgumentException("Changed original intake source vector");
    for (int index = 0; index < currentSources.size(); index++) {
      SourceEvidence source = currentSources.get(index);
      Record held = heldSources.get(index);
      if (!source.key().equals(held.get("source_key", String.class))
          || !Arrays.equals(source.canonicalBytes(), held.get("source_evidence", byte[].class)))
        throw new IllegalArgumentException("Changed original intake source vector");
    }
  }

  private void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Writable Account owner transaction required");
    }
    dsl.connection(
        connection -> {
          if (connection.getAutoCommit()
              || connection.isReadOnly()
              || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException(
                "Writable READ_COMMITTED Account owner transaction required");
          }
        });
  }
}
