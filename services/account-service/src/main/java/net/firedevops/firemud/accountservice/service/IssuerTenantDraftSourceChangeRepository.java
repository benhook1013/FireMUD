package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.IdempotencyConflictException;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Unwired exact issuer/tenant request journal; callers retain their actual source row locks. */
public final class IssuerTenantDraftSourceChangeRepository {
  private static final String TABLE = "account_issuer_tenant_draft_source_changes";
  private static final String REQUEST_SCHEMA = "account-issuer-tenant-draft-source-request/v1";
  private final DSLContext dsl;
  private final DraftAuthorizationFenceRepository fences;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The transaction-aware DSLContext is an internal persistence collaborator.")
  public IssuerTenantDraftSourceChangeRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
    this.fences = new DraftAuthorizationFenceRepository(dsl);
  }

  /** The source kind, exact scope and request UUID jointly identify an immutable request. */
  SourceChange participate(
      SourceKind kind,
      String scopeId,
      UUID requestId,
      long expectedGeneration,
      long expectedVersion,
      SourceEvidence current) {
    byte[] request = requestBytes(kind, scopeId, requestId, expectedGeneration, expectedVersion);
    Record prior = find(kind, scopeId, requestId);
    SourceChange change;
    if (prior != null) {
      change = requireRequest(prior, request);
      if (!"WAITING".equals(prior.get("status", String.class))) {
        throw new IllegalStateException("Completed source request has no retained event");
      }
      if (!Arrays.equals(change.sources().getFirst().canonicalBytes(), current.canonicalBytes())) {
        throw new IllegalStateException("Pending source request differs from its original capture");
      }
    } else {
      change = new SourceChange(UUID.randomUUID(), List.of(current), request);
    }
    fences.requestSourceChange(change);
    if (prior == null) {
      dsl.execute(
          "INSERT INTO "
              + TABLE
              + " (source_kind, scope_id, request_id, expected_generation, expected_source_version,"
              + " request_payload, source_change_id, source_evidence, source_change_binding, status)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'WAITING')",
          kind.name(),
          scopeId,
          requestId,
          expectedGeneration,
          expectedVersion,
          request,
          change.changeId(),
          current.canonicalBytes(),
          change.canonicalBytes());
    }
    requireRequest(find(kind, scopeId, requestId), request);
    return change;
  }

  /** Check original pending input before comparing against today's source state. */
  void verifyPendingRequest(
      SourceKind kind, String scopeId, UUID requestId, long generation, long version) {
    Record row = find(kind, scopeId, requestId);
    if (row != null) {
      requireRequest(row, requestBytes(kind, scopeId, requestId, generation, version));
    }
  }

  boolean permitted(SourceChange change) {
    return fences.sourceMutationPermitted(change);
  }

  /** Source counters, outbox and both journal transitions share the caller's short transaction. */
  void complete(SourceKind kind, String scopeId, UUID requestId, SourceChange change, Event event) {
    fences.markSourceCommitted(change);
    int updated =
        dsl.execute(
            "UPDATE "
                + TABLE
                + " SET status = 'SOURCE_COMMITTED', committed_at = CURRENT_TIMESTAMP,"
                + " event_stream = ?, event_sequence = ?, event_id = ?, event_digest = ?, event_payload = ?"
                + " WHERE source_kind = ? AND scope_id = ? AND request_id = ?"
                + " AND source_change_id = ? AND status = 'WAITING'",
            event.outboxStreamKey(),
            event.outboxSequence(),
            event.eventId(),
            event.eventDigest(),
            event.payload(),
            kind.name(),
            scopeId,
            requestId,
            change.changeId());
    if (updated != 1) {
      throw new IllegalStateException("Source request did not complete exactly once");
    }
  }

  /** Existing pre-V59 events remain historical evidence; they are never backfilled. */
  void verifyCommittedIfPresent(
      SourceKind kind, String scopeId, UUID requestId, long generation, long version, Event event) {
    Record row = find(kind, scopeId, requestId);
    if (row == null) {
      return;
    }
    SourceChange change =
        requireRequest(row, requestBytes(kind, scopeId, requestId, generation, version));
    if (!"SOURCE_COMMITTED".equals(row.get("status", String.class))
        || !"SOURCE_COMMITTED".equals(fences.readSourceChange(change).status())
        || !event.outboxStreamKey().equals(row.get("event_stream", String.class))
        || !Long.valueOf(event.outboxSequence()).equals(row.get("event_sequence", Long.class))
        || !event.eventId().equals(row.get("event_id", String.class))
        || !event.eventDigest().equals(row.get("event_digest", String.class))
        || !Arrays.equals(event.payload(), row.get("event_payload", byte[].class))) {
      throw new IllegalStateException("Source request and retained event linkage differ");
    }
  }

  void verifyWaiting(
      SourceKind kind,
      String scopeId,
      UUID requestId,
      long generation,
      long version,
      SourceChange change) {
    Record row = find(kind, scopeId, requestId);
    SourceChange stored =
        requireRequest(row, requestBytes(kind, scopeId, requestId, generation, version));
    if (!Arrays.equals(stored.canonicalBytes(), change.canonicalBytes())
        || !"WAITING".equals(row.get("status", String.class))
        || !"WAITING".equals(fences.readSourceChange(stored).status())) {
      throw new IllegalStateException("Pending source request readback differs from its capture");
    }
  }

  static SourceEvidence capture(
      SourceKind kind,
      String scopeId,
      long generation,
      long version,
      String stream,
      long sequence,
      Optional<Event> latest) {
    if ((sequence == 0) == latest.isPresent()) {
      throw new IllegalStateException(
          "Source checkpoint does not bind its verified latest evidence");
    }
    ByteArrayOutputStream baseline = new ByteArrayOutputStream();
    if (sequence == 0) {
      if (generation != 1 || version != 1) {
        throw new IllegalStateException("Source baseline must already be proved positive 1/1");
      }
      for (String field :
          List.of(
              "account-issuer-tenant-source-baseline/v1",
              kind.name(),
              scopeId,
              "1",
              "1",
              stream,
              "0")) {
        frame(baseline, field);
      }
    }
    return new SourceEvidence(
        kind,
        scopeId,
        Long.toString(generation),
        Long.toString(version),
        stream,
        Long.toString(sequence),
        latest.map(Event::payload).orElseGet(baseline::toByteArray));
  }

  private Record find(SourceKind kind, String scopeId, UUID requestId) {
    return dsl.fetchOne(
        "SELECT * FROM "
            + TABLE
            + " WHERE source_kind = ? AND scope_id = ? AND request_id = ? FOR UPDATE",
        kind.name(),
        scopeId,
        requestId);
  }

  private SourceChange requireRequest(Record row, byte[] request) {
    if (row == null || !Arrays.equals(request, row.get("request_payload", byte[].class))) {
      throw new IdempotencyConflictException(
          "Source request identity was reused with different expected source state");
    }
    SourceChange change = SourceChange.fromStored(row.get("source_change_binding", byte[].class));
    if (!change.changeId().equals(row.get("source_change_id", UUID.class))
        || !Arrays.equals(change.mutation(), request)
        || change.sources().size() != 1
        || !Arrays.equals(
            change.sources().getFirst().canonicalBytes(),
            row.get("source_evidence", byte[].class))) {
      throw new IllegalStateException("Source journal differs from its immutable V57 capture");
    }
    fences.readSourceChange(change);
    return change;
  }

  private static byte[] requestBytes(
      SourceKind kind, String scopeId, UUID requestId, long generation, long version) {
    if (kind != SourceKind.ISSUER && kind != SourceKind.TENANT) {
      throw new IllegalArgumentException("Only issuer and tenant source writers participate here");
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (String field :
        List.of(
            REQUEST_SCHEMA,
            kind.name(),
            scopeId,
            requestId.toString(),
            Long.toString(generation),
            Long.toString(version))) {
      frame(bytes, field);
    }
    return bytes.toByteArray();
  }

  private static void frame(ByteArrayOutputStream output, String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    output.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    output.writeBytes(bytes);
  }

  /**
   * Deterministic nonterminal result, thrown only after the owner's WAITING transaction commits.
   */
  public static final class PendingSourceChangeException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    private final UUID sourceChangeId;

    PendingSourceChangeException(UUID sourceChangeId) {
      super("Draft owner outcomes remain pending for source change " + sourceChangeId);
      this.sourceChangeId = sourceChangeId;
    }

    public UUID sourceChangeId() {
      return sourceChangeId;
    }
  }
}
