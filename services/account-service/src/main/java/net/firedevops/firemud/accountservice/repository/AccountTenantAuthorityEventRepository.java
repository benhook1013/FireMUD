package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementEventV1Codec;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.dto.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.accountservice.dto.TenantAuthorityEventV1Codec.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Typed tenant-authority source, outbox, receipt, and current-checkpoint readback. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "Injected Account repositories and DSLContext are private transaction collaborators.")
public class AccountTenantAuthorityEventRepository {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final DSLContext dsl;
  private final AccountAuthorityOutboxRepository authorityOutbox;
  private final AccountTenantEntitlementOutboxRepository billingOutbox;
  private final FreshTenantIdentityAssociationRepository tenantIdentity;
  private final AccountAuthorityGenerationRepository generations;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Preserve injected collaborator preconditions; Spring must proxy this non-final repository.")
  public AccountTenantAuthorityEventRepository(
      DSLContext dsl,
      AccountAuthorityOutboxRepository authorityOutbox,
      AccountTenantEntitlementOutboxRepository billingOutbox,
      FreshTenantIdentityAssociationRepository tenantIdentity,
      AccountAuthorityGenerationRepository generations) {
    this.dsl = Objects.requireNonNull(dsl);
    this.authorityOutbox = Objects.requireNonNull(authorityOutbox);
    this.billingOutbox = Objects.requireNonNull(billingOutbox);
    this.tenantIdentity = Objects.requireNonNull(tenantIdentity);
    this.generations = Objects.requireNonNull(generations);
  }

  /** Establishes sequence zero only from the same Account transaction that enrolled the tenant. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void establishFirstEventSource(
      UUID tenantId, UUID requestId, FreshTenantCreationEvidence source, ScopeState authority) {
    requireTransaction();
    requireExactTenant(tenantId, source, authority);
    if (authority.generation() != 1L || authority.sourceVersion() != 1L) {
      throw new IllegalStateException(
          "Tenant authority has advanced without canonical source-event evidence");
    }

    String streamKey = TenantAuthorityEventV1Codec.streamKey(tenantId);
    Record existingSource =
        dsl.fetchOne(
            "SELECT last_outbox_sequence FROM account_tenant_authority_source_records "
                + "WHERE outbox_stream_key = ? FOR UPDATE",
            streamKey);
    if (existingSource != null) {
      throw new IllegalStateException(
          "Tenant authority source already exists without its current entitlement row");
    }

    Record stream =
        dsl.fetchOne(
            "SELECT last_sequence FROM account_authority_outbox_streams "
                + "WHERE outbox_stream_key = ? FOR UPDATE",
            streamKey);
    if (stream != null) {
      throw new IllegalStateException(
          "Tenant authority stream exists without Account source initialization proof");
    }

    Record sourceInitialization =
        dsl.fetchOne(
            "SELECT fresh.authority_source_transaction_id, generation.created_transaction_id, "
                + "generation.generation, generation.source_version "
                + "FROM account_fresh_tenant_identity_associations fresh "
                + "JOIN account_canonical_tenant_identity_claims claim "
                + "ON claim.canonical_tenant_id = fresh.canonical_tenant_id "
                + "AND claim.identity_kind = 'FRESH_GAME_DESIGN' "
                + "AND claim.source_operation_id = fresh.operation_id "
                + "AND claim.source_creation_request_id = fresh.creation_request_id "
                + "AND claim.source_request_digest = fresh.request_digest "
                + "AND claim.source_game_row_id = fresh.source_game_row_id "
                + "AND claim.source_game_tenant_key = fresh.source_game_tenant_key "
                + "AND claim.source_provenance_kind = fresh.provenance_kind "
                + "AND claim.source_evidence_digest = fresh.evidence_digest "
                + "JOIN account_authority_generations generation "
                + "ON generation.scope_kind = 'TENANT' "
                + "AND generation.tenant_uuid = fresh.canonical_tenant_id "
                + "WHERE fresh.canonical_tenant_id = ?",
            tenantId);
    if (sourceInitialization == null
        || sourceInitialization.get("authority_source_transaction_id", Long.class) == null
        || !Objects.equals(
            sourceInitialization.get("authority_source_transaction_id", Long.class),
            sourceInitialization.get("created_transaction_id", Long.class))
        || !Long.valueOf(1L).equals(sourceInitialization.get("generation", Long.class))
        || !Long.valueOf(1L).equals(sourceInitialization.get("source_version", Long.class))) {
      throw new IllegalStateException(
          "Fresh tenant source and Account generation lack one owner initialization proof");
    }

    Record billingStream =
        dsl.fetchOne(
            "SELECT last_sequence FROM account_tenant_entitlement_outbox_streams "
                + "WHERE tenant_uuid = ? FOR SHARE",
            tenantId);
    Record priorOperation =
        dsl.fetchOne(
            "SELECT request_id FROM account_demo_tenant_entitlement_operations "
                + "WHERE tenant_uuid = ? AND request_id <> ? LIMIT 1",
            tenantId,
            requestId);
    if (billingStream != null || priorOperation != null) {
      throw new IllegalStateException(
          "Tenant has retained billing history without canonical authority source evidence");
    }

    int streamInserted =
        dsl.execute(
            "INSERT INTO account_authority_outbox_streams (outbox_stream_key, last_sequence) "
                + "VALUES (?, 0) ON CONFLICT (outbox_stream_key) DO NOTHING",
            streamKey);
    if (streamInserted != 1) {
      throw new IllegalStateException(
          "Tenant authority stream changed before Account source enrollment");
    }
    long initializationTransactionId =
        sourceInitialization.get("created_transaction_id", Long.class);
    int sourceInserted =
        dsl.execute(
            "INSERT INTO account_tenant_authority_source_records (outbox_stream_key, tenant_uuid, "
                + "initialization_transaction_id, baseline_generation, baseline_source_version, "
                + "current_generation, current_source_version, last_outbox_sequence) "
                + "VALUES (?, ?, ?, 1, 1, 1, 1, 0)",
            streamKey,
            tenantId,
            initializationTransactionId);
    if (sourceInserted != 1) {
      throw new IllegalStateException("Tenant authority source baseline was not enrolled");
    }
  }

  /** Appends one tenant-scoped event after the owner has advanced the exact generation row. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Event append(
      DemoTenantEntitlementRequest request,
      FreshTenantCreationEvidence source,
      ScopeState nextAuthority,
      DemoTenantEntitlementEventV1Codec.Event billingEvent) {
    requireTransaction();
    if (request == null || billingEvent == null) {
      throw new IllegalArgumentException("Tenant authority request and billing event are required");
    }
    requireExactTenant(request.canonicalTenantId(), source, nextAuthority);
    String streamKey = TenantAuthorityEventV1Codec.streamKey(request.canonicalTenantId());
    Record sourceHead =
        dsl.fetchOne(
            "SELECT current_generation, current_source_version, last_outbox_sequence "
                + "FROM account_tenant_authority_source_records WHERE outbox_stream_key = ? FOR UPDATE",
            streamKey);
    if (sourceHead == null || nonnegative(sourceHead, "last_outbox_sequence") < 0L) {
      throw new IllegalStateException(
          "Tenant authority source head does not match the actual prior generation");
    }
    if (increment(positive(sourceHead, "current_generation"), "generation")
            != nextAuthority.generation()
        || increment(positive(sourceHead, "current_source_version"), "source version")
            != nextAuthority.sourceVersion()) {
      throw new IllegalStateException(
          "Tenant authority source head does not match the actual prior generation");
    }
    AccountAuthorityOutboxRepository.Event committed =
        authorityOutbox.append(
            streamKey,
            request.requestId().toString(),
            sequence ->
                evidence(
                    TenantAuthorityEventV1Codec.seal(
                        request,
                        source,
                        nextAuthority.generation(),
                        nextAuthority.sourceVersion(),
                        sequence,
                        billingEvent)));
    TenantAuthorityEventV1Codec.Event typed =
        TenantAuthorityEventV1Codec.verify(committed.payload());
    if (!committed.outboxStreamKey().equals(streamKey)
        || !committed.requestId().equals(request.requestId().toString())
        || typed.outboxSequence() != committed.outboxSequence()
        || !typed.eventId().toString().equals(committed.eventId())
        || !typed.eventDigest().equals(committed.eventDigest())
        || !request.requestId().equals(typed.requestId())
        || !request.requestDigest().equals(typed.requestDigest())
        || !source.equals(typed.sourceEvidence())
        || typed.tenantAuthorityGeneration() != nextAuthority.generation()
        || typed.tenantAuthoritySourceVersion() != nextAuthority.sourceVersion()
        || typed.tenantBillingSequence() != billingEvent.tenantBillingSequence()
        || !typed.tenantBillingEventId().equals(billingEvent.eventId())
        || !typed.tenantBillingEventDigest().equals(billingEvent.eventDigest())) {
      throw new IllegalStateException(
          "Committed tenant authority outbox event differs from its exact Account source");
    }
    int updated =
        dsl.execute(
            "UPDATE account_tenant_authority_source_records SET current_generation = ?, "
                + "current_source_version = ?, last_outbox_sequence = ?, last_request_id = ?, "
                + "last_request_digest = ?, last_event_id = ?, last_event_digest = ?, "
                + "tenant_billing_sequence = ?, tenant_billing_event_id = ?, "
                + "tenant_billing_event_digest = ? WHERE outbox_stream_key = ? "
                + "AND current_generation = ? AND current_source_version = ? "
                + "AND last_outbox_sequence = ?",
            typed.tenantAuthorityGeneration(),
            typed.tenantAuthoritySourceVersion(),
            typed.outboxSequence(),
            typed.requestId(),
            typed.requestDigest(),
            typed.eventId().toString(),
            typed.eventDigest(),
            typed.tenantBillingSequence(),
            typed.tenantBillingEventId(),
            typed.tenantBillingEventDigest(),
            streamKey,
            typed.tenantAuthorityGeneration() - 1L,
            typed.tenantAuthoritySourceVersion() - 1L,
            typed.outboxSequence() - 1L);
    if (updated != 1) {
      throw new IllegalStateException("Tenant authority source checkpoint was not advanced once");
    }
    return typed;
  }

  /**
   * Verifies the exact event and owning committed receipt, without requiring it to remain latest.
   */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Event readCommittedReceipt(
      UUID tenantId,
      UUID requestId,
      String requestDigest,
      FreshTenantCreationEvidence source,
      long billingSequence,
      UUID billingEventId,
      String billingEventDigest,
      long authoritySequence,
      String authorityEventId,
      String authorityEventDigest) {
    requireTransaction();
    requireTenant(tenantId);
    Record operation =
        dsl.fetchOne(
            "SELECT tenant_uuid, request_digest, source_creation_request_id, source_request_digest, "
                + "source_operation_id, source_evidence_digest, status, tenant_billing_sequence, "
                + "event_id, event_digest, tenant_authority_outbox_stream_key, "
                + "tenant_authority_outbox_sequence, tenant_authority_event_id, "
                + "tenant_authority_event_digest FROM account_demo_tenant_entitlement_operations "
                + "WHERE request_id = ?",
            requestId);
    String streamKey = TenantAuthorityEventV1Codec.streamKey(tenantId);
    if (operation == null
        || !tenantId.equals(operation.get("tenant_uuid", UUID.class))
        || !requestDigest.equals(operation.get("request_digest", String.class))
        || !"COMMITTED".equals(operation.get("status", String.class))
        || !source
            .creationRequestId()
            .equals(operation.get("source_creation_request_id", UUID.class))
        || !source.requestDigest().equals(operation.get("source_request_digest", String.class))
        || !source.operationId().equals(operation.get("source_operation_id", UUID.class))
        || !source.evidenceDigest().equals(operation.get("source_evidence_digest", String.class))
        || billingSequence != positive(operation, "tenant_billing_sequence")
        || !billingEventId.equals(operation.get("event_id", UUID.class))
        || !billingEventDigest.equals(operation.get("event_digest", String.class))
        || !streamKey.equals(operation.get("tenant_authority_outbox_stream_key", String.class))
        || authoritySequence != positive(operation, "tenant_authority_outbox_sequence")
        || !authorityEventId.equals(operation.get("tenant_authority_event_id", String.class))
        || !authorityEventDigest.equals(
            operation.get("tenant_authority_event_digest", String.class))) {
      throw new IllegalStateException(
          "Tenant authority readback has no exact owning committed entitlement receipt");
    }
    AccountAuthorityOutboxRepository.Event stored =
        authorityOutbox
            .findEvent(streamKey, authoritySequence)
            .orElseThrow(
                () -> new IllegalStateException("Tenant authority event is absent at its receipt"));
    Event event = TenantAuthorityEventV1Codec.verify(stored.payload());
    if (!stored.eventId().equals(event.eventId().toString())
        || !stored.eventDigest().equals(event.eventDigest())
        || !stored.requestId().equals(requestId.toString())
        || !event.requestDigest().equals(requestDigest)
        || !event.tenantId().equals(tenantId)
        || !source.equals(event.sourceEvidence())
        || event.tenantBillingSequence() != billingSequence
        || !event.tenantBillingEventId().equals(billingEventId)
        || !event.tenantBillingEventDigest().equals(billingEventDigest)) {
      throw new IllegalStateException(
          "Tenant authority event differs from its exact entitlement receipt or source");
    }
    DemoTenantEntitlementEventV1Codec.Event billing =
        billingOutbox
            .readEvent(tenantId, billingSequence)
            .orElseThrow(
                () -> new IllegalStateException("Tenant billing event is absent at its receipt"));
    if (!billing.eventId().equals(billingEventId)
        || !billing.eventDigest().equals(billingEventDigest)
        || !requestId.equals(billing.requestId())
        || !requestDigest.equals(billing.requestDigest())
        || !source.equals(billing.sourceEvidence())) {
      throw new IllegalStateException(
          "Tenant authority event does not link to the exact original billing event");
    }
    return event;
  }

  /** Verifies that a current Account generation is represented by the exact latest source event. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Event readCurrentCheckpoint(
      UUID tenantId,
      FreshTenantCreationEvidence source,
      ScopeState authority,
      UUID requestId,
      String requestDigest,
      long billingSequence,
      UUID billingEventId,
      String billingEventDigest) {
    requireTransaction();
    requireExactTenant(tenantId, source, authority);
    String streamKey = TenantAuthorityEventV1Codec.streamKey(tenantId);
    Record head =
        dsl.fetchOne(
            "SELECT current_generation, current_source_version, last_outbox_sequence, "
                + "last_event_id, last_event_digest, last_request_id, last_request_digest, "
                + "tenant_billing_sequence, tenant_billing_event_id, tenant_billing_event_digest "
                + "FROM account_tenant_authority_source_records WHERE outbox_stream_key = ?",
            streamKey);
    if (head == null
        || positive(head, "current_generation") != authority.generation()
        || positive(head, "current_source_version") != authority.sourceVersion()
        || !requestId.equals(head.get("last_request_id", UUID.class))
        || !requestDigest.equals(head.get("last_request_digest", String.class))
        || billingSequence != positive(head, "tenant_billing_sequence")
        || !billingEventId.equals(head.get("tenant_billing_event_id", UUID.class))
        || !billingEventDigest.equals(head.get("tenant_billing_event_digest", String.class))) {
      throw new IllegalStateException(
          "Tenant authority source checkpoint is absent, stale, or differs from Account current state");
    }
    long sequence = positive(head, "last_outbox_sequence");
    String eventId = requiredText(head, "last_event_id");
    String eventDigest = requiredText(head, "last_event_digest");
    AccountAuthorityOutboxRepository.Checkpoint checkpoint =
        authorityOutbox
            .readCheckpoint(streamKey)
            .orElseThrow(
                () -> new IllegalStateException("Tenant authority outbox checkpoint is absent"));
    if (checkpoint.outboxSequence() != sequence
        || !checkpoint.sourceEventId().equals(eventId)
        || !checkpoint.sourceEventDigest().equals(eventDigest)) {
      throw new IllegalStateException(
          "Tenant authority source checkpoint differs from its canonical outbox head");
    }
    return readCommittedReceipt(
        tenantId,
        requestId,
        requestDigest,
        source,
        billingSequence,
        billingEventId,
        billingEventDigest,
        sequence,
        eventId,
        eventDigest);
  }

  /** Resolves current Account owner state itself; callers cannot supply a receipt or checkpoint. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Event readCurrentByTenant(UUID tenantId) {
    requireTransaction();
    requireTenant(tenantId);
    Record freshIdentity =
        dsl.fetchOne(
            "SELECT canonical_tenant_id FROM account_fresh_tenant_identity_associations "
                + "WHERE canonical_tenant_id = ? FOR SHARE",
            tenantId);
    if (freshIdentity == null
        || !tenantId.equals(freshIdentity.get("canonical_tenant_id", UUID.class))) {
      throw new IllegalStateException(
          "Exact fresh Account tenant source association is unavailable");
    }
    FreshTenantCreationEvidence source =
        tenantIdentity
            .read(tenantId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Exact fresh Account tenant source evidence is unavailable"));
    ScopeState authority =
        generations.read(AccountAuthorityGenerationRepository.AuthorityScope.tenant(tenantId));
    Record current =
        dsl.fetchOne(
            "SELECT source_schema_version, source_target_namespace, source_creation_request_id, "
                + "source_operation_id, source_request_digest, source_game_row_id, "
                + "source_game_tenant_key, source_provenance_kind, source_evidence_digest, "
                + "entitlement_kind, status, subscription_status, paid, gameplay_available, "
                + "allow_public_join, allow_new_gameplay_bindings, allow_new_instance_starts, "
                + "max_active_sessions, max_concurrent_game_instances, max_storage_bytes, "
                + "entitlement_version, "
                + "tenant_authority_generation, tenant_authority_source_version, "
                + "tenant_authority_outbox_stream_key, tenant_authority_outbox_sequence, "
                + "tenant_authority_event_id, tenant_authority_event_digest, "
                + "tenant_billing_sequence, event_id, event_digest, last_request_id, last_request_digest "
                + "FROM account_demo_tenant_entitlements WHERE tenant_uuid = ? FOR SHARE",
            tenantId);
    if (current == null
        || !source.equals(sourceFromCurrent(tenantId, current))
        || positive(current, "tenant_authority_generation") != authority.generation()
        || positive(current, "tenant_authority_source_version") != authority.sourceVersion()
        || !TenantAuthorityEventV1Codec.streamKey(tenantId)
            .equals(current.get("tenant_authority_outbox_stream_key", String.class))) {
      throw new IllegalStateException(
          "Account current tenant entitlement lacks matching fresh source and authority generation");
    }
    long billingSequence = positive(current, "tenant_billing_sequence");
    UUID billingEventId = requiredUuid(current, "event_id");
    String billingEventDigest = requiredText(current, "event_digest");
    UUID requestId = requiredUuid(current, "last_request_id");
    String requestDigest = requiredText(current, "last_request_digest");
    long authoritySequence = positive(current, "tenant_authority_outbox_sequence");
    String authorityEventId = requiredText(current, "tenant_authority_event_id");
    String authorityEventDigest = requiredText(current, "tenant_authority_event_digest");
    Event event =
        readCurrentCheckpoint(
            tenantId,
            source,
            authority,
            requestId,
            requestDigest,
            billingSequence,
            billingEventId,
            billingEventDigest);
    if (event.outboxSequence() != authoritySequence
        || !event.eventId().toString().equals(authorityEventId)
        || !event.eventDigest().equals(authorityEventDigest)
        || event.tenantAuthorityGeneration() != authority.generation()
        || event.tenantAuthoritySourceVersion() != authority.sourceVersion()) {
      throw new IllegalStateException(
          "Account current tenant row differs from its exact typed authority event");
    }
    DemoTenantEntitlementEventV1Codec.Event billingEvent =
        billingOutbox
            .readEvent(tenantId, billingSequence)
            .orElseThrow(() -> new IllegalStateException("Current tenant billing event is absent"));
    if (!billingEvent.eventId().equals(billingEventId)
        || !billingEvent.eventDigest().equals(billingEventDigest)
        || !billingEvent.requestId().equals(requestId)
        || !billingEvent.requestDigest().equals(requestDigest)
        || !source.equals(billingEvent.sourceEvidence())
        || billingEvent.tenantAuthorityGeneration() != authority.generation()
        || billingEvent.tenantAuthoritySourceVersion() != authority.sourceVersion()
        || !"NON_PAID_DEMO".equals(current.get("entitlement_kind", String.class))
        || !"ACTIVE".equals(current.get("status", String.class))
        || current.get("subscription_status", String.class) != null
        || !Boolean.FALSE.equals(current.get("paid", Boolean.class))
        || positive(current, "entitlement_version") != billingEvent.entitlementVersion()
        || !Objects.equals(
            current.get("gameplay_available", Boolean.class), billingEvent.gameplayAvailable())
        || !Objects.equals(
            current.get("allow_public_join", Boolean.class), billingEvent.allowPublicJoin())
        || !Objects.equals(
            current.get("allow_new_gameplay_bindings", Boolean.class),
            billingEvent.allowNewGameplayBindings())
        || !Objects.equals(
            current.get("allow_new_instance_starts", Boolean.class),
            billingEvent.allowNewInstanceStarts())
        || nonnegative(current, "max_active_sessions") != billingEvent.quotas().maxActiveSessions()
        || nonnegative(current, "max_concurrent_game_instances")
            != billingEvent.quotas().maxConcurrentGameInstances()
        || nonnegative(current, "max_storage_bytes") != billingEvent.quotas().maxStorageBytes()) {
      throw new IllegalStateException(
          "Current tenant entitlement row differs from its exact committed billing event");
    }
    return event;
  }

  private static FreshTenantCreationEvidence sourceFromCurrent(UUID tenantId, Record current) {
    return new FreshTenantCreationEvidence(
        current.get("source_schema_version", Integer.class),
        requiredText(current, "source_target_namespace"),
        requiredUuid(current, "source_creation_request_id"),
        requiredUuid(current, "source_operation_id"),
        requiredText(current, "source_request_digest"),
        tenantId,
        positive(current, "source_game_row_id"),
        requiredText(current, "source_game_tenant_key"),
        requiredText(current, "source_provenance_kind"),
        requiredText(current, "source_evidence_digest"));
  }

  private static AccountAuthorityOutboxRepository.EventEvidence evidence(Event event) {
    return new AccountAuthorityOutboxRepository.EventEvidence(
        event.eventId().toString(), event.eventDigest(), event.payload());
  }

  private static void requireExactTenant(
      UUID tenantId, FreshTenantCreationEvidence source, ScopeState authority) {
    requireTenant(tenantId);
    if (source == null
        || authority == null
        || !tenantId.equals(source.canonicalTenantId())
        || !AccountAuthorityGenerationRepository.AuthorityScope.tenant(tenantId)
            .equals(authority.scope())
        || authority.generation() <= 0L
        || authority.sourceVersion() <= 0L) {
      throw new IllegalArgumentException("Exact fresh tenant authority evidence is required");
    }
  }

  private static void requireTenant(UUID tenantId) {
    if (tenantId == null || NIL_UUID.equals(tenantId)) {
      throw new IllegalArgumentException("Exact tenant UUID is required");
    }
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Tenant authority event operation requires the Account owner transaction");
    }
  }

  private static long positive(Record row, String field) {
    Long value = row.get(field, Long.class);
    if (value == null || value <= 0L) {
      throw new IllegalStateException("Tenant authority " + field + " is absent or malformed");
    }
    return value;
  }

  private static long nonnegative(Record row, String field) {
    Long value = row.get(field, Long.class);
    if (value == null || value < 0L) {
      throw new IllegalStateException("Tenant authority " + field + " is absent or malformed");
    }
    return value;
  }

  private static long increment(long value, String field) {
    try {
      return Math.addExact(value, 1L);
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException("Tenant authority " + field + " is exhausted", overflow);
    }
  }

  private static String requiredText(Record row, String field) {
    String value = row.get(field, String.class);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Tenant authority " + field + " is absent or malformed");
    }
    return value;
  }

  private static UUID requiredUuid(Record row, String field) {
    UUID value = row.get(field, UUID.class);
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalStateException("Tenant authority " + field + " is absent or malformed");
    }
    return value;
  }
}
