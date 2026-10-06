package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementEventV1Codec;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementEventV1Codec.Event;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot;
import net.firedevops.firemud.accountservice.dto.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.service.DemoTenantEntitlementException;
import net.firedevops.firemud.accountservice.service.DemoTenantEntitlementException.Code;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Account's exact-UUID demo entitlement source, commit, lookup, and currentness fence. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repositories and DSLContext are private transaction collaborators.")
public class AccountDemoTenantEntitlementRepository {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final DSLContext dsl;
  private final FreshTenantIdentityAssociationRepository tenantIdentityRepository;
  private final AccountAuthorityGenerationRepository authorityGenerationRepository;
  private final AccountTenantEntitlementOutboxRepository outboxRepository;
  private final AccountTenantAuthorityEventRepository tenantAuthorityEvents;

  public AccountDemoTenantEntitlementRepository(
      DSLContext dsl,
      FreshTenantIdentityAssociationRepository tenantIdentityRepository,
      AccountAuthorityGenerationRepository authorityGenerationRepository,
      AccountTenantEntitlementOutboxRepository outboxRepository,
      AccountTenantAuthorityEventRepository tenantAuthorityEvents) {
    this.dsl = Objects.requireNonNull(dsl);
    this.tenantIdentityRepository = Objects.requireNonNull(tenantIdentityRepository);
    this.authorityGenerationRepository = Objects.requireNonNull(authorityGenerationRepository);
    this.outboxRepository = Objects.requireNonNull(outboxRepository);
    this.tenantAuthorityEvents = Objects.requireNonNull(tenantAuthorityEvents);
  }

  /** Commits one protected-fixture mutation with source readback, shared generation, and outbox. */
  @Transactional(propagation = Propagation.MANDATORY)
  public DemoTenantEntitlementSnapshot provision(
      DemoTenantEntitlementRequest request, FreshTenantCreationEvidence authenticatedSource) {
    requireTransaction();
    Objects.requireNonNull(request, "Demo entitlement request is required");
    requireExactSource(request, authenticatedSource);
    FreshTenantCreationEvidence currentSource =
        tenantIdentityRepository
            .read(request.canonicalTenantId())
            .orElseThrow(
                () ->
                    new DemoTenantEntitlementException(
                        Code.SOURCE_EVIDENCE_INVALID,
                        "Exact fresh Game Design source evidence is absent at Account commit"));
    if (!authenticatedSource.equals(currentSource)) {
      throw new DemoTenantEntitlementException(
          Code.SOURCE_EVIDENCE_INVALID,
          "Authenticated source evidence differs from exact Account UUID readback at commit");
    }

    int reserved =
        dsl.execute(
            "INSERT INTO account_demo_tenant_entitlement_operations (request_id, tenant_uuid, "
                + "request_digest, source_creation_request_id, source_request_digest, "
                + "source_operation_id, source_evidence_digest, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING') ON CONFLICT (request_id) DO NOTHING",
            request.requestId(),
            request.canonicalTenantId(),
            request.requestDigest(),
            authenticatedSource.creationRequestId(),
            authenticatedSource.requestDigest(),
            authenticatedSource.operationId(),
            authenticatedSource.evidenceDigest());
    if (reserved == 0) {
      return replay(request, authenticatedSource);
    }
    if (reserved != 1) {
      throw new IllegalStateException("Demo entitlement request reservation was ambiguous");
    }

    ScopeState oldAuthority =
        authorityGenerationRepository.read(AuthorityScope.tenant(request.canonicalTenantId()));
    Record oldEntitlement = readCurrentRow(request.canonicalTenantId(), true);
    if (oldEntitlement == null) {
      tenantAuthorityEvents.establishFirstEventSource(
          request.canonicalTenantId(), request.requestId(), authenticatedSource, oldAuthority);
    }
    long nextEntitlementVersion;
    if (oldEntitlement == null) {
      if (request.expectedEntitlementVersion() != null) {
        throw new DemoTenantEntitlementException(
            Code.ENTITLEMENT_VERSION_CONFLICT,
            "Demo entitlement create request supplied an update fence");
      }
      nextEntitlementVersion = 1L;
    } else {
      if (request.expectedEntitlementVersion() == null
          || request.expectedEntitlementVersion() != value(oldEntitlement, "entitlement_version")
          || request.expectedTenantAuthorityGeneration() != oldAuthority.generation()
          || request.expectedTenantAuthoritySourceVersion() != oldAuthority.sourceVersion()) {
        throw new DemoTenantEntitlementException(
            Code.TENANT_AUTHORITY_STALE,
            "Demo entitlement update does not match the exact current entitlement and tenant authority");
      }
      nextEntitlementVersion = increment(value(oldEntitlement, "entitlement_version"));
    }
    ScopeState nextAuthority = authorityGenerationRepository.advance(oldAuthority, null);
    final long committedEntitlementVersion = nextEntitlementVersion;
    Event committedEvent =
        outboxRepository.append(
            request.canonicalTenantId(),
            request.requestId(),
            sequence ->
                DemoTenantEntitlementEventV1Codec.seal(
                    request,
                    authenticatedSource,
                    committedEntitlementVersion,
                    nextAuthority.generation(),
                    nextAuthority.sourceVersion(),
                    sequence));

    TenantAuthorityEventV1Codec.Event authorityEvent =
        tenantAuthorityEvents.append(request, authenticatedSource, nextAuthority, committedEvent);

    writeCurrent(
        request, authenticatedSource, committedEvent, authorityEvent, oldEntitlement == null);
    int committed =
        dsl.execute(
            "UPDATE account_demo_tenant_entitlement_operations SET status = 'COMMITTED', "
                + "tenant_billing_sequence = ?, event_id = ?, event_digest = ?, "
                + "entitlement_version = ?, tenant_authority_generation = ?, "
                + "tenant_authority_source_version = ?, tenant_authority_outbox_stream_key = ?, "
                + "tenant_authority_outbox_sequence = ?, tenant_authority_event_id = ?, "
                + "tenant_authority_event_digest = ?, committed_at = CURRENT_TIMESTAMP "
                + "WHERE request_id = ? AND status = 'PENDING'",
            committedEvent.tenantBillingSequence(),
            committedEvent.eventId(),
            committedEvent.eventDigest(),
            committedEvent.entitlementVersion(),
            committedEvent.tenantAuthorityGeneration(),
            committedEvent.tenantAuthoritySourceVersion(),
            authorityEvent.outboxStreamKey(),
            authorityEvent.outboxSequence(),
            authorityEvent.eventId().toString(),
            authorityEvent.eventDigest(),
            request.requestId());
    if (committed != 1) {
      throw new IllegalStateException("Demo entitlement operation receipt was not committed");
    }
    return snapshot(committedEvent, authorityEvent);
  }

  /** Reads one exact current snapshot and denies stale or incomplete Account/source state. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public DemoTenantEntitlementSnapshot readCurrent(UUID canonicalTenantId) {
    requireTenantUuid(canonicalTenantId);
    Optional<FreshTenantCreationEvidence> evidence =
        tenantIdentityRepository.read(canonicalTenantId);
    FreshTenantCreationEvidence source =
        evidence.orElseThrow(
            () ->
                new DemoTenantEntitlementException(
                    Code.SOURCE_EVIDENCE_INVALID,
                    "No exact fresh Game Design source evidence exists for this tenant UUID"));
    ScopeState authority =
        authorityGenerationRepository.read(AuthorityScope.tenant(canonicalTenantId));
    Record row = readCurrentRow(canonicalTenantId, false);
    if (row == null) {
      throw new DemoTenantEntitlementException(
          Code.ENTITLEMENT_UNAVAILABLE, "No Account demo entitlement exists for this tenant UUID");
    }
    CurrentEvents current = currentEvents(row, source, authority);
    return snapshot(current.billingEvent(), current.authorityEvent());
  }

  /** Re-reads all source, generation, version, and outbox evidence at the final admission fence. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public DemoTenantEntitlementSnapshot revalidate(DemoTenantEntitlementSnapshot evaluated) {
    Objects.requireNonNull(evaluated, "Complete evaluated entitlement evidence is required");
    DemoTenantEntitlementSnapshot current = readCurrent(evaluated.canonicalTenantId());
    if (!current.equals(evaluated)) {
      throw new DemoTenantEntitlementException(
          Code.STALE_ADMISSION_EVIDENCE,
          "Account demo entitlement changed after its complete evaluation snapshot");
    }
    return current;
  }

  private DemoTenantEntitlementSnapshot replay(
      DemoTenantEntitlementRequest request, FreshTenantCreationEvidence authenticatedSource) {
    Record operation =
        dsl.fetchOne(
            "SELECT tenant_uuid, request_digest, source_creation_request_id, "
                + "source_request_digest, source_operation_id, source_evidence_digest, status, "
                + "tenant_billing_sequence, event_id, event_digest, "
                + "tenant_authority_outbox_stream_key, tenant_authority_outbox_sequence, "
                + "tenant_authority_event_id, tenant_authority_event_digest "
                + "FROM account_demo_tenant_entitlement_operations WHERE request_id = ? FOR UPDATE",
            request.requestId());
    if (operation == null
        || !request.canonicalTenantId().equals(get(operation, "tenant_uuid", UUID.class))
        || !request.requestDigest().equals(get(operation, "request_digest", String.class))
        || !authenticatedSource
            .creationRequestId()
            .equals(get(operation, "source_creation_request_id", UUID.class))
        || !authenticatedSource
            .requestDigest()
            .equals(get(operation, "source_request_digest", String.class))
        || !authenticatedSource
            .operationId()
            .equals(get(operation, "source_operation_id", UUID.class))
        || !authenticatedSource
            .evidenceDigest()
            .equals(get(operation, "source_evidence_digest", String.class))) {
      throw new DemoTenantEntitlementException(
          Code.IDEMPOTENCY_CONFLICT,
          "Demo entitlement request ID was reused for a different tenant or payload");
    }
    if (!"COMMITTED".equals(get(operation, "status", String.class))) {
      throw new IllegalStateException("Committed Account contains an unfinished demo operation");
    }
    long sequence = value(operation, "tenant_billing_sequence");
    Event event =
        outboxRepository
            .readEvent(request.canonicalTenantId(), sequence)
            .orElseThrow(
                () ->
                    new IllegalStateException("Committed demo entitlement operation has no event"));
    if (!request.requestId().equals(event.requestId())
        || !request.requestDigest().equals(event.requestDigest())
        || !event.eventId().equals(get(operation, "event_id", UUID.class))
        || !event.eventDigest().equals(get(operation, "event_digest", String.class))
        || !authenticatedSource.equals(event.sourceEvidence())) {
      throw new IllegalStateException("Committed demo entitlement receipt differs from its event");
    }
    var authorityEvent =
        tenantAuthorityEvents.readCommittedReceipt(
            request.canonicalTenantId(),
            request.requestId(),
            request.requestDigest(),
            authenticatedSource,
            event.tenantBillingSequence(),
            event.eventId(),
            event.eventDigest(),
            value(operation, "tenant_authority_outbox_sequence"),
            get(operation, "tenant_authority_event_id", String.class),
            get(operation, "tenant_authority_event_digest", String.class));
    if (!authorityEvent
            .outboxStreamKey()
            .equals(get(operation, "tenant_authority_outbox_stream_key", String.class))
        || authorityEvent.tenantAuthorityGeneration() != event.tenantAuthorityGeneration()
        || authorityEvent.tenantAuthoritySourceVersion() != event.tenantAuthoritySourceVersion()) {
      throw new IllegalStateException(
          "Committed demo entitlement receipt differs from its tenant authority event");
    }
    return snapshot(event, authorityEvent);
  }

  private CurrentEvents currentEvents(
      Record row, FreshTenantCreationEvidence source, ScopeState authority) {
    long storedGeneration = value(row, "tenant_authority_generation");
    long storedSourceVersion = value(row, "tenant_authority_source_version");
    if (storedGeneration != authority.generation()
        || storedSourceVersion != authority.sourceVersion()) {
      throw new DemoTenantEntitlementException(
          Code.TENANT_AUTHORITY_STALE,
          "Stored demo entitlement is stale against Account's shared tenant authority generation");
    }
    if (source.schemaVersion() != get(row, "source_schema_version", Integer.class)
        || !source.targetNamespace().equals(get(row, "source_target_namespace", String.class))
        || !source.creationRequestId().equals(get(row, "source_creation_request_id", UUID.class))
        || !source.operationId().equals(get(row, "source_operation_id", UUID.class))
        || !source.requestDigest().equals(get(row, "source_request_digest", String.class))
        || source.sourceGameRowId() != value(row, "source_game_row_id")
        || !source.sourceGameTenantKey().equals(get(row, "source_game_tenant_key", String.class))
        || !source.provenanceKind().equals(get(row, "source_provenance_kind", String.class))
        || !source.evidenceDigest().equals(get(row, "source_evidence_digest", String.class))) {
      throw new DemoTenantEntitlementException(
          Code.SOURCE_EVIDENCE_INVALID,
          "Stored demo entitlement source tuple differs from current authenticated Account source evidence");
    }
    long sequence = value(row, "tenant_billing_sequence");
    var checkpoint =
        outboxRepository
            .readCheckpoint(source.canonicalTenantId())
            .orElseThrow(
                () ->
                    new IllegalStateException("Current demo entitlement has no outbox checkpoint"));
    if (checkpoint.sequence() != sequence
        || !checkpoint.eventId().equals(get(row, "event_id", UUID.class))
        || !checkpoint.eventDigest().equals(get(row, "event_digest", String.class))) {
      throw new DemoTenantEntitlementException(
          Code.TENANT_AUTHORITY_STALE,
          "Current demo entitlement does not match the exact tenant entitlement outbox head");
    }
    Event event =
        outboxRepository
            .readEvent(source.canonicalTenantId(), sequence)
            .orElseThrow(
                () -> new IllegalStateException("Current demo entitlement event is absent"));
    if (!source.equals(event.sourceEvidence())
        || event.entitlementVersion() != value(row, "entitlement_version")
        || event.tenantAuthorityGeneration() != storedGeneration
        || event.tenantAuthoritySourceVersion() != storedSourceVersion
        || !event.requestId().equals(get(row, "last_request_id", UUID.class))
        || !event.requestDigest().equals(get(row, "last_request_digest", String.class))
        || !event.eventId().equals(get(row, "event_id", UUID.class))
        || !event.eventDigest().equals(get(row, "event_digest", String.class))
        || event.gameplayAvailable() != get(row, "gameplay_available", Boolean.class)
        || event.allowPublicJoin() != get(row, "allow_public_join", Boolean.class)
        || event.allowNewGameplayBindings()
            != get(row, "allow_new_gameplay_bindings", Boolean.class)
        || event.allowNewInstanceStarts() != get(row, "allow_new_instance_starts", Boolean.class)
        || !event
            .quotas()
            .equals(
                new DemoTenantEntitlementRequest.Quotas(
                    value(row, "max_active_sessions"),
                    value(row, "max_concurrent_game_instances"),
                    value(row, "max_storage_bytes")))) {
      throw new IllegalStateException("Current demo entitlement projection differs from its event");
    }
    TenantAuthorityEventV1Codec.Event authorityEvent =
        tenantAuthorityEvents.readCurrentCheckpoint(
            source.canonicalTenantId(),
            source,
            authority,
            event.requestId(),
            event.requestDigest(),
            sequence,
            event.eventId(),
            event.eventDigest());
    if (authorityEvent.tenantAuthorityGeneration() != storedGeneration
        || authorityEvent.tenantAuthoritySourceVersion() != storedSourceVersion) {
      throw new IllegalStateException(
          "Current tenant authority event differs from Account's actual generation");
    }
    return new CurrentEvents(event, authorityEvent);
  }

  private void writeCurrent(
      DemoTenantEntitlementRequest request,
      FreshTenantCreationEvidence source,
      Event event,
      TenantAuthorityEventV1Codec.Event authorityEvent,
      boolean insert) {
    String sql =
        insert
            ? "INSERT INTO account_demo_tenant_entitlements (tenant_uuid, source_schema_version, "
                + "source_target_namespace, source_creation_request_id, source_operation_id, "
                + "source_request_digest, source_game_row_id, source_game_tenant_key, "
                + "source_provenance_kind, source_evidence_digest, entitlement_kind, status, "
                + "subscription_status, paid, gameplay_available, allow_public_join, "
                + "allow_new_gameplay_bindings, allow_new_instance_starts, max_active_sessions, "
                + "max_concurrent_game_instances, max_storage_bytes, entitlement_version, "
                + "tenant_authority_generation, tenant_authority_source_version, "
                + "tenant_billing_sequence, event_id, event_digest, last_request_id, last_request_digest, "
                + "tenant_authority_outbox_stream_key, tenant_authority_outbox_sequence, "
                + "tenant_authority_event_id, tenant_authority_event_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NON_PAID_DEMO', 'ACTIVE', NULL, FALSE, "
                + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            : "UPDATE account_demo_tenant_entitlements SET gameplay_available = ?, "
                + "allow_public_join = ?, allow_new_gameplay_bindings = ?, allow_new_instance_starts = ?, "
                + "max_active_sessions = ?, max_concurrent_game_instances = ?, max_storage_bytes = ?, "
                + "entitlement_version = ?, tenant_authority_generation = ?, "
                + "tenant_authority_source_version = ?, tenant_billing_sequence = ?, event_id = ?, "
                + "event_digest = ?, last_request_id = ?, last_request_digest = ?, "
                + "tenant_authority_outbox_stream_key = ?, tenant_authority_outbox_sequence = ?, "
                + "tenant_authority_event_id = ?, tenant_authority_event_digest = ? "
                + "WHERE tenant_uuid = ?";
    Object[] values =
        insert
            ? new Object[] {
              request.canonicalTenantId(),
              source.schemaVersion(),
              source.targetNamespace(),
              source.creationRequestId(),
              source.operationId(),
              source.requestDigest(),
              source.sourceGameRowId(),
              source.sourceGameTenantKey(),
              source.provenanceKind(),
              source.evidenceDigest(),
              request.gameplayAvailable(),
              request.allowPublicJoin(),
              request.allowNewGameplayBindings(),
              request.allowNewInstanceStarts(),
              request.quotas().maxActiveSessions(),
              request.quotas().maxConcurrentGameInstances(),
              request.quotas().maxStorageBytes(),
              event.entitlementVersion(),
              event.tenantAuthorityGeneration(),
              event.tenantAuthoritySourceVersion(),
              event.tenantBillingSequence(),
              event.eventId(),
              event.eventDigest(),
              request.requestId(),
              request.requestDigest(),
              authorityEvent.outboxStreamKey(),
              authorityEvent.outboxSequence(),
              authorityEvent.eventId().toString(),
              authorityEvent.eventDigest()
            }
            : new Object[] {
              request.gameplayAvailable(),
              request.allowPublicJoin(),
              request.allowNewGameplayBindings(),
              request.allowNewInstanceStarts(),
              request.quotas().maxActiveSessions(),
              request.quotas().maxConcurrentGameInstances(),
              request.quotas().maxStorageBytes(),
              event.entitlementVersion(),
              event.tenantAuthorityGeneration(),
              event.tenantAuthoritySourceVersion(),
              event.tenantBillingSequence(),
              event.eventId(),
              event.eventDigest(),
              request.requestId(),
              request.requestDigest(),
              authorityEvent.outboxStreamKey(),
              authorityEvent.outboxSequence(),
              authorityEvent.eventId().toString(),
              authorityEvent.eventDigest(),
              request.canonicalTenantId()
            };
    int changed = dsl.execute(sql, values);
    if (changed != 1) {
      throw new IllegalStateException(
          "Demo entitlement current state was not committed exactly once");
    }
  }

  private Record readCurrentRow(UUID tenantId, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT source_schema_version, source_target_namespace, source_creation_request_id, "
            + "source_operation_id, source_request_digest, source_game_row_id, "
            + "source_game_tenant_key, source_provenance_kind, source_evidence_digest, "
            + "entitlement_version, gameplay_available, allow_public_join, "
            + "allow_new_gameplay_bindings, allow_new_instance_starts, max_active_sessions, "
            + "max_concurrent_game_instances, max_storage_bytes, tenant_authority_generation, "
            + "tenant_authority_source_version, tenant_billing_sequence, event_id, event_digest, "
            + "last_request_id, last_request_digest, tenant_authority_outbox_stream_key, "
            + "tenant_authority_outbox_sequence, tenant_authority_event_id, "
            + "tenant_authority_event_digest FROM account_demo_tenant_entitlements "
            + "WHERE tenant_uuid = ?"
            + (forUpdate ? " FOR UPDATE" : " FOR SHARE"),
        tenantId);
  }

  private static DemoTenantEntitlementSnapshot snapshot(
      Event event, TenantAuthorityEventV1Codec.Event authorityEvent) {
    return new DemoTenantEntitlementSnapshot(
        event.canonicalTenantId(),
        event.sourceEvidence(),
        "NON_PAID_DEMO",
        "ACTIVE",
        null,
        false,
        event.gameplayAvailable(),
        event.allowPublicJoin(),
        event.allowNewGameplayBindings(),
        event.allowNewInstanceStarts(),
        event.quotas(),
        event.entitlementVersion(),
        event.tenantAuthorityGeneration(),
        event.tenantAuthoritySourceVersion(),
        event.outboxStreamKey(),
        event.tenantBillingSequence(),
        event.eventId(),
        event.eventDigest(),
        event.eventDigest(),
        authorityEvent.outboxStreamKey(),
        authorityEvent.outboxSequence(),
        authorityEvent.eventId(),
        authorityEvent.eventDigest());
  }

  private record CurrentEvents(
      Event billingEvent, TenantAuthorityEventV1Codec.Event authorityEvent) {}

  private static void requireExactSource(
      DemoTenantEntitlementRequest request, FreshTenantCreationEvidence source) {
    if (source == null
        || !request.canonicalTenantId().equals(source.canonicalTenantId())
        || !request.tenantCreationRequestId().equals(source.creationRequestId())
        || !request.tenantCreationRequestDigest().equals(source.requestDigest())
        || !"NEW_GAME_ROW".equals(source.provenanceKind())) {
      throw new DemoTenantEntitlementException(
          Code.SOURCE_EVIDENCE_INVALID,
          "Only exact authenticated fresh Game Design tenant creation evidence can receive a demo entitlement");
    }
  }

  private static void requireTenantUuid(UUID tenantId) {
    if (tenantId == null || NIL_UUID.equals(tenantId)) {
      throw new DemoTenantEntitlementException(
          Code.ENTITLEMENT_UNAVAILABLE, "Exact non-nil canonical tenant UUID is required");
    }
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Account demo entitlement operation requires one owner transaction");
    }
  }

  private static long value(Record row, String column) {
    Long value = row.get(column, Long.class);
    if (value == null || value < 0) {
      throw new IllegalStateException("Account demo entitlement " + column + " is malformed");
    }
    return value;
  }

  private static long increment(long current) {
    try {
      return Math.addExact(current, 1L);
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException("Account demo entitlement version is exhausted", overflow);
    }
  }

  private static <T> T get(Record row, String column, Class<T> type) {
    T result = row.get(column, type);
    if (result == null) {
      throw new IllegalStateException("Account demo entitlement " + column + " is absent");
    }
    return result;
  }
}
