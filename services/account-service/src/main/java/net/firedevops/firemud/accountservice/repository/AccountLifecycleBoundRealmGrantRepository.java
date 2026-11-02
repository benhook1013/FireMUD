package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.account.authority.GrantAuthorityScopeCodec;
import net.firedevops.firemud.common.account.authority.GrantAuthorityScopeCodec.Scope;
import net.firedevops.firemud.common.account.authority.RealmGrantAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.RealmGrantAuthorityEventV1Codec.GrantPreimage;
import net.firedevops.firemud.common.operator.RealmGrantMutation;
import net.firedevops.firemud.common.operator.RealmGrantMutation.Operation;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Account's lifecycle-bound grant storage primitive, not an authorizing management surface.
 *
 * <p>The authorizing Account owner must authenticate the actual actor, qualify the exact current
 * account/tenant/world/realm/lifecycle and obtain its current fork expiry before a new write. These
 * arguments are transaction inputs, never caller authority. No legacy grant is read or promoted.
 * Source-event evidence is retained here; delivery, consumer qualification and binding ejection are
 * separate integration obligations.
 */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext and outbox are internal Spring collaborators.")
public class AccountLifecycleBoundRealmGrantRepository {
  private static final String HISTORY = "account_lifecycle_bound_realm_grant_revisions";
  private static final String CURRENT = "account_lifecycle_bound_realm_grants";
  private final DSLContext dsl;
  private final AccountAuthorityOutboxRepository outbox;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Validate internal injected collaborators; no reference escapes and no resource is acquired.")
  public AccountLifecycleBoundRealmGrantRepository(
      DSLContext dsl, AccountAuthorityOutboxRepository outbox) {
    this.dsl = Objects.requireNonNull(dsl);
    this.outbox = Objects.requireNonNull(outbox);
  }

  /** Applies one already-authorized owner command in the owner's mandatory transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public ApplyResult apply(
      UUID requestId,
      UUID actorAccountId,
      RealmGrantMutation mutation,
      Instant currentForkExpiresAt) {
    requireOwnerTransaction();
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Grant mutation requires a writable owner transaction");
    }
    requireIdentity(requestId, "requestId");
    requireIdentity(actorAccountId, "actorAccountId");
    Objects.requireNonNull(mutation, "mutation");
    String scopeId = scopeId(mutation.grant());
    byte[] canonicalPreimage = mutation.canonicalPreimage();
    String mutationDigest = mutation.mutationDigest();
    // Request lock precedes scope lock. Hash collisions serialize unrelated work without aliasing
    // it.
    lock("lifecycle-grant-request/" + requestId);
    Record prior = dsl.fetchOne("SELECT * FROM " + HISTORY + " WHERE request_id = ?", requestId);
    if (prior != null) {
      if (!actorAccountId.equals(prior.get("actor_account_uuid", UUID.class))
          || !mutationDigest.equals(prior.get("mutation_digest", String.class))
          || !Arrays.equals(canonicalPreimage, prior.get("mutation_preimage", byte[].class))) {
        throw new IdempotencyConflictException();
      }
      return new ApplyResult(receipt(prior), true);
    }
    // Historical exact replay does not require a newly available current fork observation.
    requireSupportedInstant(currentForkExpiresAt);
    lock("lifecycle-grant-scope/" + scopeId);
    Record current = current(scopeId);
    Instant now = databaseNow();
    if (!preconditionMatches(mutation, current, currentForkExpiresAt, now)) {
      throw new GrantRevisionConflictException();
    }
    Instant grantExpiresAt;
    if (mutation.operation() == Operation.REVOKE) {
      grantExpiresAt = grantExpiry(Objects.requireNonNull(current));
    } else {
      grantExpiresAt = mutation.grantExpiresAt();
      requireSupportedInstant(grantExpiresAt);
      if (!now.isBefore(grantExpiresAt) || grantExpiresAt.isAfter(currentForkExpiresAt)) {
        throw new IllegalArgumentException(
            "Grant expiry must be future and bounded by current fork expiry");
      }
      if (mutation.operation() == Operation.EXTEND
          && !grantExpiresAt.isAfter(grantExpiry(Objects.requireNonNull(current)))) {
        throw new IllegalArgumentException("Extension must increase explicit grant expiry");
      }
    }
    long revision =
        current == null ? 1L : Math.addExact(current.get("grant_revision", Long.class), 1L);
    State state = mutation.operation() == Operation.REVOKE ? State.REVOKED : State.ACTIVE;
    UUID grantAuthorityGeneration = UUID.randomUUID();
    UUID auditEventId = UUID.randomUUID();
    String stream =
        GrantAuthorityScopeCodec.outboxStreamKey(
            mutation.grant(), GrantAuthorityScopeCodec.V1_BOUNDS);
    final RealmGrantAuthorityEventV1Codec.Event[] sealed =
        new RealmGrantAuthorityEventV1Codec.Event[1];
    var event =
        outbox.append(
            stream,
            requestId.toString(),
            sequence -> {
              sealed[0] =
                  RealmGrantAuthorityEventV1Codec.seal(
                      new GrantPreimage(
                          requestId,
                          mutation.grant(),
                          revision,
                          grantAuthorityGeneration,
                          state.name(),
                          grantExpiresAt,
                          actorAccountId,
                          mutation.operation().name(),
                          mutationDigest,
                          mutation.auditReason(),
                          sequence));
              return new AccountAuthorityOutboxRepository.EventEvidence(
                  sealed[0].eventId(), sealed[0].eventDigest(), sealed[0].payload());
            });
    byte[] payload = event.payload();
    dsl.execute(
        "INSERT INTO account_audit_outbox (audit_event_id, scope, tenant_identity_version, tenant_uuid, "
            + "producer_service, event_type, occurred_at, schema_version, payload_digest_version, payload_digest, payload) "
            + "VALUES (?, 'tenant', 2, ?, 'account-service', 'REALM_GRANT_CHANGED', "
            + "clock_timestamp() AT TIME ZONE 'UTC', 1, 1, ?, ?)",
        auditEventId,
        mutation.grant().tenantId(),
        "sha256:" + sha256(payload),
        new String(payload, StandardCharsets.UTF_8));
    Scope grant = mutation.grant();
    dsl.execute(
        "INSERT INTO "
            + HISTORY
            + " (scope_id, grant_revision, account_uuid, tenant_uuid, world_slug, realm_slug, "
            + "playtest_lifecycle_id, request_id, actor_account_uuid, operation, expected_grant_revision, state, "
            + "grant_expiry_second, grant_expiry_nano, fork_expiry_second, fork_expiry_nano, audit_reason, "
            + "mutation_preimage, mutation_digest, grant_expiry_text, event_preimage, event_payload, event_id, event_digest, grant_authority_generation, "
            + "outbox_stream_key, outbox_sequence, audit_event_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        scopeId,
        revision,
        grant.accountId(),
        grant.tenantId(),
        grant.worldSlug(),
        grant.realmSlug(),
        grant.playtestLifecycleId(),
        requestId,
        actorAccountId,
        mutation.operation().name(),
        mutation.expectedGrantRevision(),
        state.name(),
        grantExpiresAt.getEpochSecond(),
        grantExpiresAt.getNano(),
        currentForkExpiresAt.getEpochSecond(),
        currentForkExpiresAt.getNano(),
        mutation.auditReason(),
        canonicalPreimage,
        mutationDigest,
        grantExpiresAt.toString(),
        sealed[0].canonicalPreimage(),
        payload,
        event.eventId(),
        event.eventDigest(),
        grantAuthorityGeneration,
        stream,
        event.outboxSequence(),
        auditEventId);
    if (current == null) {
      dsl.execute(
          "INSERT INTO " + CURRENT + " (scope_id, grant_revision) VALUES (?, ?)",
          scopeId,
          revision);
    } else {
      int changed =
          dsl.execute(
              "UPDATE "
                  + CURRENT
                  + " SET grant_revision = ? WHERE scope_id = ? AND grant_revision = ?",
              revision,
              scopeId,
              current.get("grant_revision", Long.class));
      if (changed != 1) throw new IllegalStateException("Locked grant head changed concurrently");
    }
    return new ApplyResult(
        receipt(
            Objects.requireNonNull(
                dsl.fetchOne("SELECT * FROM " + HISTORY + " WHERE request_id = ?", requestId))),
        false);
  }

  /** Reads the exact current grant or tombstone; validity uses the real database clock. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Optional<CurrentGrant> readCurrent(Scope scope, Instant currentForkExpiresAt) {
    requireOwnerTransaction();
    requireSupportedInstant(currentForkExpiresAt);
    Record row = current(scopeId(scope));
    if (row == null) return Optional.empty();
    Receipt receipt = receipt(row);
    Instant effective = min(receipt.grantExpiresAt(), currentForkExpiresAt);
    Instant observedAt = databaseNow();
    return Optional.of(
        new CurrentGrant(
            receipt,
            effective,
            receipt.state() == State.ACTIVE && observedAt.isBefore(effective),
            observedAt));
  }

  private Record current(String scopeId) {
    return dsl.fetchOne(
        "SELECT history.* FROM "
            + CURRENT
            + " head JOIN "
            + HISTORY
            + " history USING (scope_id, grant_revision) WHERE head.scope_id = ?",
        scopeId);
  }

  private void lock(String identity) {
    dsl.fetch("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", identity);
  }

  private Instant databaseNow() {
    return Objects.requireNonNull(dsl.fetchOne("SELECT clock_timestamp() AS observed_at"))
        .get("observed_at", OffsetDateTime.class)
        .toInstant();
  }

  private static boolean preconditionMatches(
      RealmGrantMutation mutation, Record current, Instant forkExpiry, Instant now) {
    if (mutation.operation() == Operation.CREATE) return current == null;
    if (current == null
        || !Objects.equals(
            mutation.expectedGrantRevision(), current.get("grant_revision", Long.class)))
      return false;
    if (mutation.operation() == Operation.REGRANT) return true;
    return State.ACTIVE.name().equals(current.get("state", String.class))
        && now.isBefore(min(grantExpiry(current), forkExpiry));
  }

  private static Receipt receipt(Record row) {
    Scope scope =
        new Scope(
            row.get("account_uuid", UUID.class),
            row.get("tenant_uuid", UUID.class),
            row.get("world_slug", String.class),
            row.get("realm_slug", String.class),
            row.get("playtest_lifecycle_id", UUID.class));
    return new Receipt(
        row.get("request_id", UUID.class),
        row.get("actor_account_uuid", UUID.class),
        scope,
        Operation.valueOf(row.get("operation", String.class)),
        row.get("expected_grant_revision", Long.class),
        row.get("grant_revision", Long.class),
        row.get("grant_authority_generation", UUID.class),
        State.valueOf(row.get("state", String.class)),
        grantExpiry(row),
        instant(row, "fork_expiry"),
        row.get("audit_reason", String.class),
        row.get("mutation_digest", String.class),
        row.get("mutation_preimage", byte[].class),
        row.get("event_preimage", byte[].class),
        new AccountAuthorityOutboxRepository.Event(
            row.get("outbox_stream_key", String.class),
            row.get("request_id", UUID.class).toString(),
            row.get("outbox_sequence", Long.class),
            row.get("event_id", String.class),
            row.get("event_digest", String.class),
            row.get("event_payload", byte[].class)),
        row.get("audit_event_id", UUID.class));
  }

  private static Instant grantExpiry(Record row) {
    return instant(row, "grant_expiry");
  }

  private static Instant instant(Record row, String prefix) {
    return Instant.ofEpochSecond(
        row.get(prefix + "_second", Long.class), row.get(prefix + "_nano", Integer.class));
  }

  private static Instant min(Instant first, Instant second) {
    return first.isBefore(second) ? first : second;
  }

  private static String scopeId(Scope scope) {
    return GrantAuthorityScopeCodec.scopeId(scope, GrantAuthorityScopeCodec.V1_BOUNDS);
  }

  private static void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Lifecycle-bound grant primitive requires an owner transaction");
    }
  }

  private static void requireIdentity(UUID identity, String name) {
    if (identity == null || identity.equals(new UUID(0L, 0L)))
      throw new IllegalArgumentException(name + " must be non-nil");
  }

  private static void requireSupportedInstant(Instant instant) {
    Objects.requireNonNull(instant, "expiry");
    if (instant.getEpochSecond() < -62135596800L || instant.getEpochSecond() > 253402300799L) {
      throw new IllegalArgumentException("Expiry must be within UTC years 0001 through 9999");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public enum State {
    ACTIVE,
    REVOKED
  }

  public record ApplyResult(Receipt receipt, boolean replayed) {}

  public record CurrentGrant(
      Receipt receipt, Instant effectiveExpiresAt, boolean active, Instant observedAt) {}

  /** Original immutable semantic outcome; later revisions and expiry do not rewrite it. */
  public record Receipt(
      UUID requestId,
      UUID actorAccountId,
      Scope scope,
      Operation operation,
      Long expectedGrantRevision,
      long grantRevision,
      UUID grantAuthorityGeneration,
      State state,
      Instant grantExpiresAt,
      Instant forkExpiresAtAtMutation,
      String auditReason,
      String mutationDigest,
      byte[] canonicalPreimage,
      byte[] eventPreimage,
      AccountAuthorityOutboxRepository.Event event,
      UUID auditEventId) {
    public Receipt {
      canonicalPreimage = canonicalPreimage.clone();
      eventPreimage = eventPreimage.clone();
    }

    @Override
    public byte[] canonicalPreimage() {
      return canonicalPreimage.clone();
    }

    @Override
    public byte[] eventPreimage() {
      return eventPreimage.clone();
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof Receipt receipt)) return false;
      return grantRevision == receipt.grantRevision
          && Objects.equals(requestId, receipt.requestId)
          && Objects.equals(actorAccountId, receipt.actorAccountId)
          && Objects.equals(scope, receipt.scope)
          && operation == receipt.operation
          && Objects.equals(expectedGrantRevision, receipt.expectedGrantRevision)
          && Objects.equals(grantAuthorityGeneration, receipt.grantAuthorityGeneration)
          && state == receipt.state
          && Objects.equals(grantExpiresAt, receipt.grantExpiresAt)
          && Objects.equals(forkExpiresAtAtMutation, receipt.forkExpiresAtAtMutation)
          && Objects.equals(auditReason, receipt.auditReason)
          && Objects.equals(mutationDigest, receipt.mutationDigest)
          && Arrays.equals(canonicalPreimage, receipt.canonicalPreimage)
          && Arrays.equals(eventPreimage, receipt.eventPreimage)
          && Objects.equals(event, receipt.event)
          && Objects.equals(auditEventId, receipt.auditEventId);
    }

    @Override
    public int hashCode() {
      int result =
          Objects.hash(
              requestId,
              actorAccountId,
              scope,
              operation,
              expectedGrantRevision,
              grantRevision,
              grantAuthorityGeneration,
              state,
              grantExpiresAt,
              forkExpiresAtAtMutation,
              auditReason,
              mutationDigest,
              event,
              auditEventId);
      result = 31 * result + Arrays.hashCode(canonicalPreimage);
      return 31 * result + Arrays.hashCode(eventPreimage);
    }
  }

  public static final class IdempotencyConflictException extends IllegalStateException {
    public IdempotencyConflictException() {
      super("IDEMPOTENCY_CONFLICT");
    }
  }

  public static final class GrantRevisionConflictException extends IllegalStateException {
    public GrantRevisionConflictException() {
      super("GRANT_REVISION_CONFLICT");
    }
  }
}
