package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores immutable Account authority-event evidence in independently sequenced SQL streams.
 *
 * <p>The caller owns the transaction that composes authority changes with this outbox. The digest
 * is supplied by the producer's canonical event contract and is persisted and compared exactly;
 * this repository does not define or recompute its preimage. An absent checkpoint is uninitialized
 * storage evidence, not a sequence-zero authority proof. A caller may report zero only after its
 * own same-transaction membership and retained-history checks prove that the exact scope has never
 * committed an authority event. The repository checks the stream-key prefix and preserves its exact
 * value; callers must validate the scope ID with its owning issuer, account, tenant, membership, or
 * grant-scope rule before calling it.
 */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class AccountAuthorityOutboxRepository {
  private static final String STREAM_PREFIX = "account:auth-authority:v1:";
  private static final int MAX_STREAM_KEY_LENGTH = 2048;
  private static final int MAX_REQUEST_ID_LENGTH = 512;
  private static final int MAX_EVENT_ID_LENGTH = 512;

  private final DSLContext dsl;

  public AccountAuthorityOutboxRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * Appends one immutable event or returns the committed event for an exact retry.
   *
   * <p>A request ID is unique within its exact stream. Retrying that ID with any changed event ID,
   * digest, or payload is denied as an idempotency conflict. Sequence allocation, event insertion,
   * and stream-head advancement participate in the caller's transaction.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Event append(
      String outboxStreamKey,
      String requestId,
      String eventId,
      String eventDigest,
      byte[] payload) {
    validateStreamKey(outboxStreamKey);
    requireBoundedNonBlank(requestId, "request ID", MAX_REQUEST_ID_LENGTH);
    requireBoundedNonBlank(eventId, "event ID", MAX_EVENT_ID_LENGTH);
    requireNonBlank(eventDigest, "event digest");
    byte[] exactPayload = copyPayload(payload);

    dsl.execute(
        "INSERT INTO account_authority_outbox_streams (outbox_stream_key, last_sequence) "
            + "VALUES (?, 0) ON CONFLICT (outbox_stream_key) DO NOTHING",
        outboxStreamKey);
    Record stream = lockStream(outboxStreamKey);
    long currentSequence = requiredNonnegative(stream, "last_sequence");

    Record priorRequest =
        dsl.fetchOne(
            "SELECT outbox_sequence, event_id, event_digest, payload "
                + "FROM account_authority_outbox_events "
                + "WHERE outbox_stream_key = ? AND request_id = ?",
            outboxStreamKey,
            requestId);
    if (priorRequest != null) {
      Event committed = toEvent(outboxStreamKey, requestId, priorRequest);
      if (!committed.matches(eventId, eventDigest, exactPayload)) {
        throw new IdempotencyConflictException(
            "Account authority outbox request was reused with different event evidence");
      }
      if (currentSequence < committed.outboxSequence()) {
        throw new IllegalStateException("Account authority outbox stream head regressed");
      }
      return committed;
    }

    Record priorEventId =
        dsl.fetchOne(
            "SELECT request_id FROM account_authority_outbox_events "
                + "WHERE outbox_stream_key = ? AND event_id = ?",
            outboxStreamKey,
            eventId);
    if (priorEventId != null) {
      throw new IdempotencyConflictException(
          "Account authority outbox event ID is already bound to another request");
    }

    long nextSequence;
    try {
      nextSequence = Math.addExact(currentSequence, 1L);
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException("Account authority outbox sequence is exhausted", overflow);
    }
    Record advancedHead =
        dsl.fetchOne(
            "UPDATE account_authority_outbox_streams SET last_sequence = ? "
                + "WHERE outbox_stream_key = ? AND last_sequence = ? "
                + "RETURNING last_sequence",
            nextSequence,
            outboxStreamKey,
            currentSequence);
    if (advancedHead == null) {
      throw new IllegalStateException("Account authority outbox stream changed concurrently");
    }

    int inserted =
        dsl.execute(
            "INSERT INTO account_authority_outbox_events "
                + "(outbox_stream_key, outbox_sequence, request_id, event_id, event_digest, payload) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
            outboxStreamKey,
            nextSequence,
            requestId,
            eventId,
            eventDigest,
            exactPayload);
    if (inserted != 1) {
      throw new IllegalStateException("Account authority outbox event was not appended");
    }
    return new Event(outboxStreamKey, requestId, nextSequence, eventId, eventDigest, exactPayload);
  }

  /** Exact event readback for a positive sequence; sequence zero is not an event identity. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Event> findEvent(String outboxStreamKey, long outboxSequence) {
    validateStreamKey(outboxStreamKey);
    if (outboxSequence <= 0) {
      throw new IllegalArgumentException("Authority outbox event sequence must be positive");
    }
    Record row =
        dsl.fetchOne(
            "SELECT outbox_sequence, request_id, event_id, event_digest, payload "
                + "FROM account_authority_outbox_events "
                + "WHERE outbox_stream_key = ? AND outbox_sequence = ?",
            outboxStreamKey,
            outboxSequence);
    return row == null
        ? Optional.empty()
        : Optional.of(toEvent(outboxStreamKey, row.get("request_id", String.class), row));
  }

  /**
   * Reads the exact latest positive checkpoint for a stream, or empty when no canonical event has
   * ever been committed to that stream.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Checkpoint> readCheckpoint(String outboxStreamKey) {
    validateStreamKey(outboxStreamKey);
    Record stream =
        dsl.fetchOne(
            "SELECT last_sequence FROM account_authority_outbox_streams "
                + "WHERE outbox_stream_key = ? FOR SHARE",
            outboxStreamKey);
    if (stream == null) {
      return Optional.empty();
    }
    long sequence = requiredPositive(stream, "last_sequence");
    Event event =
        findEvent(outboxStreamKey, sequence)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Account authority outbox checkpoint has no matching event evidence"));
    return Optional.of(
        new Checkpoint(
            outboxStreamKey, event.outboxSequence(), event.eventId(), event.eventDigest()));
  }

  private Record lockStream(String outboxStreamKey) {
    Record stream =
        dsl.fetchOne(
            "SELECT last_sequence FROM account_authority_outbox_streams "
                + "WHERE outbox_stream_key = ? FOR UPDATE",
            outboxStreamKey);
    if (stream == null) {
      throw new IllegalStateException("Account authority outbox stream could not be locked");
    }
    return stream;
  }

  private Event toEvent(String outboxStreamKey, String requestId, Record row) {
    return new Event(
        outboxStreamKey,
        requestId,
        requiredPositive(row, "outbox_sequence"),
        requiredText(row, "event_id"),
        requiredText(row, "event_digest"),
        row.get("payload", byte[].class));
  }

  private static long requiredNonnegative(Record row, String field) {
    Long value = row.get(field, Long.class);
    if (value == null || value < 0L) {
      throw new IllegalStateException("Malformed Account authority outbox " + field);
    }
    return value;
  }

  private static long requiredPositive(Record row, String field) {
    Long value = row.get(field, Long.class);
    if (value == null || value <= 0L) {
      throw new IllegalStateException("Malformed Account authority outbox " + field);
    }
    return value;
  }

  private static String requiredText(Record row, String field) {
    String value = row.get(field, String.class);
    requireNonBlank(value, field);
    return value;
  }

  private static void validateStreamKey(String outboxStreamKey) {
    if (outboxStreamKey == null
        || !outboxStreamKey.startsWith(STREAM_PREFIX)
        || outboxStreamKey.length() == STREAM_PREFIX.length()
        || outboxStreamKey.length() > MAX_STREAM_KEY_LENGTH
        || outboxStreamKey.substring(STREAM_PREFIX.length()).isBlank()
        || !outboxStreamKey.equals(outboxStreamKey.strip())
        || outboxStreamKey.indexOf('\n') >= 0
        || outboxStreamKey.indexOf('\r') >= 0) {
      throw new IllegalArgumentException(
          "Account authority outbox key with canonical prefix and exact scope is required");
    }
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Account authority outbox " + field + " is required");
    }
  }

  private static void requireBoundedNonBlank(String value, String field, int maxLength) {
    requireNonBlank(value, field);
    if (value.length() > maxLength) {
      throw new IllegalArgumentException(
          "Account authority outbox " + field + " exceeds its maximum length");
    }
  }

  private static byte[] copyPayload(byte[] payload) {
    if (payload == null || payload.length == 0) {
      throw new IllegalArgumentException("Account authority outbox payload is required");
    }
    return payload.clone();
  }

  /** Immutable event evidence returned by append and exact readback. */
  public record Event(
      String outboxStreamKey,
      String requestId,
      long outboxSequence,
      String eventId,
      String eventDigest,
      byte[] payload) {
    public Event {
      validateStreamKey(outboxStreamKey);
      requireBoundedNonBlank(requestId, "request ID", MAX_REQUEST_ID_LENGTH);
      requireBoundedNonBlank(eventId, "event ID", MAX_EVENT_ID_LENGTH);
      requireNonBlank(eventDigest, "event digest");
      if (outboxSequence <= 0) {
        throw new IllegalArgumentException("Authority outbox event sequence must be positive");
      }
      payload = copyPayload(payload);
    }

    @Override
    public byte[] payload() {
      return payload.clone();
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Event event)) {
        return false;
      }
      return outboxSequence == event.outboxSequence
          && Objects.equals(outboxStreamKey, event.outboxStreamKey)
          && Objects.equals(requestId, event.requestId)
          && Objects.equals(eventId, event.eventId)
          && Objects.equals(eventDigest, event.eventDigest)
          && Arrays.equals(payload, event.payload);
    }

    @Override
    public int hashCode() {
      int result = Objects.hash(outboxStreamKey, requestId, outboxSequence, eventId, eventDigest);
      return 31 * result + Arrays.hashCode(payload);
    }

    private boolean matches(
        String candidateEventId, String candidateDigest, byte[] candidatePayload) {
      return Objects.equals(eventId, candidateEventId)
          && Objects.equals(eventDigest, candidateDigest)
          && Arrays.equals(payload, candidatePayload);
    }
  }

  /** Positive Account checkpoint; an absent checkpoint must not be serialized as sequence zero. */
  public record Checkpoint(
      String outboxStreamKey, long outboxSequence, String sourceEventId, String sourceEventDigest) {
    public Checkpoint {
      validateStreamKey(outboxStreamKey);
      if (outboxSequence <= 0) {
        throw new IllegalArgumentException("Authority outbox checkpoint sequence must be positive");
      }
      requireNonBlank(sourceEventId, "checkpoint source event ID");
      requireNonBlank(sourceEventDigest, "checkpoint source event digest");
    }
  }

  /** A stable request identity was reused with different event evidence. */
  public static final class IdempotencyConflictException extends IllegalStateException {
    public IdempotencyConflictException(String message) {
      super(message);
    }
  }
}
