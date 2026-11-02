package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongFunction;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementEventV1Codec;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementEventV1Codec.Event;
import net.firedevops.firemud.accountservice.service.DemoTenantEntitlementException;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Account-owned shared tenant billing stream; DEMO events retain their explicit typed reader. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Account transaction collaborator.")
public class AccountTenantEntitlementOutboxRepository {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Preserve the injected DSLContext precondition; Spring must proxy this non-final repository.")
  public AccountTenantEntitlementOutboxRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  public static String streamKey(UUID canonicalTenantId) {
    return TenantAuthorityEventV1Codec.tenantBillingStreamKey(canonicalTenantId);
  }

  /**
   * Production hosting writer over the SAME canonical tenant billing stream; no demo conversion.
   */
  AccountHostingSubscriptionLifecycleEvidence.BillingEvent appendHosting(
      UUID tenantId,
      UUID requestId,
      LongFunction<AccountHostingSubscriptionLifecycleEvidence.BillingEvent> factory) {
    requireTransaction();
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Hosting billing events require a writable owner transaction");
    }
    streamKey(tenantId);
    dsl.execute(
        "INSERT INTO account_tenant_entitlement_outbox_streams (tenant_uuid, last_sequence) "
            + "VALUES (?, 0) ON CONFLICT (tenant_uuid) DO NOTHING",
        tenantId);
    Record head =
        dsl.fetchOne(
            "SELECT last_sequence FROM account_tenant_entitlement_outbox_streams "
                + "WHERE tenant_uuid = ? FOR UPDATE",
            tenantId);
    Record prior =
        dsl.fetchOne(
            "SELECT * FROM account_tenant_entitlement_outbox_events WHERE tenant_uuid = ? AND request_id = ?",
            tenantId,
            requestId);
    long sequence =
        prior == null
            ? Math.addExact(nonnegative(head, "last_sequence"), 1L)
            : positive(prior, "tenant_billing_sequence");
    var event = Objects.requireNonNull(factory.apply(sequence));
    if (!tenantId.equals(event.tenantId)
        || !requestId.equals(event.requestId)
        || event.sequence != sequence) {
      throw new IllegalStateException("Hosting event differs from its canonical billing stream");
    }
    if (prior != null) {
      if (!event.eventId.equals(prior.get("event_id", UUID.class))
          || !event.matches(
              prior.get("payload", byte[].class), prior.get("event_digest", String.class))) {
        throw new IllegalStateException("Hosting billing request conflicts with retained evidence");
      }
      return event;
    }
    if (dsl.execute(
            "UPDATE account_tenant_entitlement_outbox_streams SET last_sequence = ? WHERE tenant_uuid = ? AND last_sequence = ?",
            sequence,
            tenantId,
            sequence - 1)
        != 1) {
      throw new IllegalStateException("Hosting billing stream CAS rejected");
    }
    dsl.execute(
        "INSERT INTO account_tenant_entitlement_outbox_events "
            + "(tenant_uuid, tenant_billing_sequence, request_id, event_id, event_digest, payload) VALUES (?, ?, ?, ?, ?, ?)",
        tenantId,
        sequence,
        requestId,
        event.eventId,
        event.digest(),
        event.payload());
    return event;
  }

  /** Appends one event or returns only an exact request and payload replay. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Event append(UUID tenantId, UUID requestId, LongFunction<Event> eventFactory) {
    requireTransaction();
    streamKey(tenantId);
    if (requestId == null || NIL_UUID.equals(requestId) || eventFactory == null) {
      throw new IllegalArgumentException(
          "Exact entitlement request identity and event are required");
    }
    String key = streamKey(tenantId);
    dsl.execute(
        "INSERT INTO account_tenant_entitlement_outbox_streams (tenant_uuid, last_sequence) "
            + "VALUES (?, 0) ON CONFLICT (tenant_uuid) DO NOTHING",
        tenantId);
    Record stream =
        dsl.fetchOne(
            "SELECT last_sequence FROM account_tenant_entitlement_outbox_streams "
                + "WHERE tenant_uuid = ? FOR UPDATE",
            tenantId);
    long currentSequence = nonnegative(stream, "last_sequence");
    Record priorRequest =
        dsl.fetchOne(
            "SELECT tenant_billing_sequence, request_id, event_id, event_digest, payload "
                + "FROM account_tenant_entitlement_outbox_events "
                + "WHERE tenant_uuid = ? AND request_id = ?",
            tenantId,
            requestId);
    if (priorRequest != null) {
      Event committed = event(tenantId, priorRequest);
      Event candidate =
          Objects.requireNonNull(eventFactory.apply(committed.tenantBillingSequence()));
      if (!matches(committed, candidate) || currentSequence < committed.tenantBillingSequence()) {
        throw new DemoTenantEntitlementException(
            DemoTenantEntitlementException.Code.IDEMPOTENCY_CONFLICT,
            "Tenant entitlement outbox request was reused with different event evidence");
      }
      return committed;
    }

    long nextSequence;
    try {
      nextSequence = Math.addExact(currentSequence, 1L);
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException("Tenant entitlement event sequence is exhausted", overflow);
    }
    Event candidate = Objects.requireNonNull(eventFactory.apply(nextSequence));
    if (!tenantId.equals(candidate.canonicalTenantId())
        || !requestId.equals(candidate.requestId())
        || !key.equals(candidate.outboxStreamKey())
        || candidate.tenantBillingSequence() != nextSequence) {
      throw new IllegalArgumentException("Tenant entitlement event differs from its exact stream");
    }
    Record advanced =
        dsl.fetchOne(
            "UPDATE account_tenant_entitlement_outbox_streams SET last_sequence = ? "
                + "WHERE tenant_uuid = ? AND last_sequence = ? RETURNING last_sequence",
            nextSequence,
            tenantId,
            currentSequence);
    if (advanced == null) {
      throw new IllegalStateException("Tenant entitlement outbox stream changed concurrently");
    }
    int inserted =
        dsl.execute(
            "INSERT INTO account_tenant_entitlement_outbox_events "
                + "(tenant_uuid, tenant_billing_sequence, request_id, event_id, event_digest, payload) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
            tenantId,
            nextSequence,
            requestId,
            candidate.eventId(),
            candidate.eventDigest(),
            candidate.payload());
    if (inserted != 1) {
      throw new IllegalStateException("Tenant entitlement outbox event was not appended");
    }
    return candidate;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Checkpoint> readCheckpoint(UUID tenantId) {
    requireTransaction();
    Record stream =
        dsl.fetchOne(
            "SELECT last_sequence FROM account_tenant_entitlement_outbox_streams "
                + "WHERE tenant_uuid = ? FOR SHARE",
            tenantId);
    if (stream == null || nonnegative(stream, "last_sequence") == 0L) {
      return Optional.empty();
    }
    long sequence = positive(stream, "last_sequence");
    Record row =
        dsl.fetchOne(
            "SELECT tenant_uuid, tenant_billing_sequence, request_id, event_id, event_digest, payload "
                + "FROM account_tenant_entitlement_outbox_events "
                + "WHERE tenant_uuid = ? AND tenant_billing_sequence = ?",
            tenantId,
            sequence);
    if (row == null
        || !tenantId.equals(row.get("tenant_uuid", UUID.class))
        || positive(row, "tenant_billing_sequence") != sequence) {
      throw new IllegalStateException("Tenant entitlement checkpoint has no exact event");
    }
    byte[] payload = row.get("payload", byte[].class);
    if (payload == null || payload.length == 0 || payload.length > 32768) {
      throw new IllegalStateException("Tenant billing envelope is absent or oversized");
    }
    String schema;
    try {
      schema = JSON.readTree(payload).path("schemaVersion").asText();
    } catch (RuntimeException failure) {
      throw new IllegalStateException("Tenant billing envelope is malformed", failure);
    }
    return switch (schema) {
      case DemoTenantEntitlementEventV1Codec.SCHEMA_VERSION -> {
        Event demo = event(tenantId, row);
        yield Optional.of(
            new Checkpoint(streamKey(tenantId), sequence, demo.eventId(), demo.eventDigest()));
      }
      case "account-hosting-subscription-event/v1" -> {
        var hosting = AccountHostingSubscriptionLifecycleEvidence.verifyBilling(payload);
        if (!tenantId.equals(hosting.tenantId)
            || hosting.sequence != sequence
            || !hosting.requestId.equals(row.get("request_id", UUID.class))
            || !hosting.eventId.equals(row.get("event_id", UUID.class))
            || !hosting.digest().equals(row.get("event_digest", String.class))) {
          throw new IllegalStateException("Stored hosting billing event differs from its payload");
        }
        yield Optional.of(
            new Checkpoint(streamKey(tenantId), sequence, hosting.eventId, hosting.digest()));
      }
      default -> throw new IllegalStateException("Tenant billing source family is unknown");
    };
  }

  /** DEMO-only event read; hosting callers use the source-neutral checkpoint and owning receipt. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Event> readEvent(UUID tenantId, long sequence) {
    requireTransaction();
    if (sequence <= 0L) {
      throw new IllegalArgumentException("Tenant entitlement event sequence must be positive");
    }
    Record row =
        dsl.fetchOne(
            "SELECT tenant_billing_sequence, request_id, event_id, event_digest, payload "
                + "FROM account_tenant_entitlement_outbox_events "
                + "WHERE tenant_uuid = ? AND tenant_billing_sequence = ?",
            tenantId,
            sequence);
    return row == null ? Optional.empty() : Optional.of(event(tenantId, row));
  }

  private Event event(UUID tenantId, Record row) {
    byte[] payload = row.get("payload", byte[].class);
    Event event = DemoTenantEntitlementEventV1Codec.verify(payload);
    if (!tenantId.equals(event.canonicalTenantId())
        || row.get("tenant_billing_sequence", Long.class) != event.tenantBillingSequence()
        || !row.get("event_id", UUID.class).equals(event.eventId())
        || !row.get("event_digest", String.class).equals(event.eventDigest())
        || !row.get("request_id", UUID.class).equals(event.requestId())) {
      throw new IllegalStateException("Stored tenant entitlement event differs from its payload");
    }
    return event;
  }

  private static boolean matches(Event left, Event right) {
    return left.eventId().equals(right.eventId())
        && left.eventDigest().equals(right.eventDigest())
        && java.util.Arrays.equals(left.payload(), right.payload());
  }

  private static long positive(Record row, String field) {
    Long value = row == null ? null : row.get(field, Long.class);
    if (value == null || value <= 0L) {
      throw new IllegalStateException("Tenant entitlement outbox " + field + " is malformed");
    }
    return value;
  }

  private static long nonnegative(Record row, String field) {
    Long value = row == null ? null : row.get(field, Long.class);
    if (value == null || value < 0L) {
      throw new IllegalStateException("Tenant entitlement outbox " + field + " is malformed");
    }
    return value;
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Tenant entitlement outbox requires an owner transaction");
    }
  }

  public record Checkpoint(
      String outboxStreamKey, long sequence, UUID eventId, String eventDigest) {
    public Checkpoint {
      if (outboxStreamKey == null || sequence <= 0L || eventId == null || eventDigest == null) {
        throw new IllegalArgumentException("Complete tenant entitlement checkpoint is required");
      }
    }
  }
}
