package net.firedevops.firemud.accountservice.hostedterms;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsDisclosureHandoff.DisclosureResult;
import net.firedevops.firemud.accountservice.hostedterms.HostedTermsDisclosureHandoff.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Immutable Account-owned disclosure intent and exact owner-result journal. It performs no
 * authentication and no RPC; only a future trusted Account verifier/dispatcher may supply the
 * opaque authority and result bytes. Every write and source-fence check is one writable owner
 * READ_COMMITTED transaction, which must finish before external dispatch.
 */
public final class HostedTermsDisclosureHandoffRepository {
  private static final String HANDOFFS = "account_hosted_terms_disclosure_handoffs";
  private static final String SOURCES = "account_hosted_terms_disclosure_sources";
  private final DSLContext dsl;
  private final DraftAuthorizationFenceRepository fenceRepository;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Injected owner persistence collaborators are retained privately and never exposed.")
  public HostedTermsDisclosureHandoffRepository(
      DSLContext dsl, DraftAuthorizationFenceRepository fenceRepository) {
    this.dsl = Objects.requireNonNull(dsl);
    this.fenceRepository = Objects.requireNonNull(fenceRepository);
  }

  public enum Status {
    PREPARED,
    DISPATCH_AUTHORIZED,
    AMBIGUOUS,
    DISCLOSED,
    DEFINITIVELY_NOT_DISCLOSED
  }

  public record Snapshot(
      HostedTermsDisclosureHandoff handoff,
      Status status,
      int dispatchAttempts,
      Optional<DisclosureResult> result,
      OffsetDateTime createdAt,
      OffsetDateTime resultRecordedAt) {
    public Snapshot {
      Objects.requireNonNull(handoff);
      Objects.requireNonNull(status);
      if (dispatchAttempts < 0) {
        throw new IllegalArgumentException("Nonnegative dispatch attempt count required");
      }
      result = Objects.requireNonNull(result);
      Objects.requireNonNull(createdAt);
      if (status == Status.DISCLOSED || status == Status.DEFINITIVELY_NOT_DISCLOSED) {
        if (result.isEmpty() || resultRecordedAt == null) {
          throw new IllegalArgumentException("Terminal status requires exact durable result");
        }
      } else if (result.isPresent() || resultRecordedAt != null) {
        throw new IllegalArgumentException("Nonterminal status cannot carry a result");
      }
    }
  }

  /** True only when this transaction durably authorized a new exact dispatch attempt. */
  public record DispatchPermit(Snapshot snapshot, boolean newlyAuthorized) {
    public DispatchPermit {
      Objects.requireNonNull(snapshot);
    }
  }

  /**
   * Prepare the immutable exact source handoff. Existing exact identities are idempotent; a new
   * identity first locks all sorted source keys, refuses unsettled original operations without
   * modifying them, and writes the journal before those locks are released.
   */
  public Snapshot prepare(HostedTermsDisclosureHandoff handoff) {
    requireWriteTransaction();
    Objects.requireNonNull(handoff);
    byte[] exactBinding = handoff.canonicalBytes();
    fenceRepository.lockDisclosureSources(handoff.sources());
    Record existing = findByIdentity(handoff.handoffId(), handoff.requestId());
    if (existing != null) {
      return exactSnapshot(existing, handoff, exactBinding);
    }

    fenceRepository.requireDisclosurePreparation(handoff.sources());
    dsl.execute(
        "INSERT INTO "
            + HANDOFFS
            + " (handoff_id, request_id, kind, source_key, predecessor_digest, candidate_digest, "
            + "effective_at, binding, binding_digest, status) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?::timestamptz, ?, ?, 'PREPARED') "
            + "ON CONFLICT DO NOTHING",
        handoff.handoffId(),
        handoff.requestId(),
        handoff.kind().name(),
        handoff.sourceKey(),
        handoff.predecessorDigest(),
        handoff.candidateDigest(),
        java.time.OffsetDateTime.ofInstant(handoff.effectiveAt(), java.time.ZoneOffset.UTC),
        exactBinding,
        HostedTermsEncoding.digest(exactBinding));

    Record row = findByIdentity(handoff.handoffId(), handoff.requestId());
    if (row == null) {
      throw new IllegalStateException("Disclosure handoff durable readback is unavailable");
    }
    Snapshot current = snapshot(row);
    requireExactHandoffColumns(row, handoff, exactBinding);
    if (current.status() == Status.PREPARED && current.dispatchAttempts() == 0) {
      for (SourceEvidence source : handoff.sources()) {
        byte[] evidence = source.canonicalBytes();
        dsl.execute(
            "INSERT INTO "
                + SOURCES
                + " (handoff_id, source_key, source_evidence, source_evidence_digest) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
            handoff.handoffId(),
            source.key(),
            evidence,
            HostedTermsEncoding.digest(evidence));
      }
      row = findByIdentity(handoff.handoffId(), handoff.requestId());
      if (row == null) {
        throw new IllegalStateException("Disclosure handoff disappeared during source readback");
      }
    }
    return exactSnapshot(row, handoff, exactBinding);
  }

  /**
   * Durably authorizes one dispatch before the caller leaves the transaction. A pre-existing
   * DISPATCH_AUTHORIZED row is recovered by readback, not by guessing that a previous RPC failed.
   * An exact retry after explicit ambiguity may reauthorize the same stable request identity; the
   * external receiver must deduplicate it. Sorted source locks precede journal and operation locks;
   * a prepared handoff rechecks original settlement before its first dispatch authorization.
   */
  public DispatchPermit authorizeDispatch(HostedTermsDisclosureHandoff handoff) {
    requireWriteTransaction();
    Objects.requireNonNull(handoff);
    fenceRepository.requireWorldParticipationDisclosureAdmission(handoff.sources());
    Record row = requireExactRow(handoff);
    Status status = Status.valueOf(row.get("status", String.class));
    boolean newlyAuthorized = false;
    if (status == Status.PREPARED) {
      fenceRepository.requireDisclosurePreparation(handoff.sources());
    }
    if (status == Status.PREPARED || status == Status.AMBIGUOUS) {
      int updated =
          dsl.execute(
              "UPDATE "
                  + HANDOFFS
                  + " SET status = 'DISPATCH_AUTHORIZED', dispatch_attempts = dispatch_attempts + 1 "
                  + "WHERE handoff_id = ? AND status = ?",
              handoff.handoffId(),
              status.name());
      if (updated != 1) {
        throw new IllegalStateException("Disclosure dispatch authorization did not advance once");
      }
      newlyAuthorized = true;
    }
    row = requireExactRow(handoff);
    return new DispatchPermit(snapshot(row), newlyAuthorized);
  }

  /** Records uncertainty only; absence, timeout, or an empty readback is never no-disclosure. */
  public Snapshot recordAmbiguous(HostedTermsDisclosureHandoff handoff) {
    requireWriteTransaction();
    Objects.requireNonNull(handoff);
    fenceRepository.lockDisclosureSources(handoff.sources());
    Record row = requireExactRow(handoff);
    Status status = Status.valueOf(row.get("status", String.class));
    if (status == Status.DISPATCH_AUTHORIZED) {
      int updated =
          dsl.execute(
              "UPDATE "
                  + HANDOFFS
                  + " SET status = 'AMBIGUOUS' WHERE handoff_id = ? AND status = 'DISPATCH_AUTHORIZED'",
              handoff.handoffId());
      if (updated != 1) {
        throw new IllegalStateException("Ambiguous disclosure outcome did not persist once");
      }
      row = requireExactRow(handoff);
    } else if (status != Status.AMBIGUOUS) {
      throw new IllegalStateException("Only an authorized unresolved dispatch can be ambiguous");
    }
    return snapshot(row);
  }

  /**
   * Stores immutable exact owner evidence after its authenticated verifier proves either outcome.
   * The repository itself cannot authenticate the supplied evidence, and never infers a negative
   * result from timeout, process restart, or a missing owner row.
   */
  public Snapshot recordResult(HostedTermsDisclosureHandoff handoff, DisclosureResult result) {
    requireWriteTransaction();
    Objects.requireNonNull(handoff);
    Objects.requireNonNull(result);
    fenceRepository.lockDisclosureSources(handoff.sources());
    Record row = requireExactRow(handoff);
    byte[] resultBytes = result.canonicalBytes();
    String resultDigest = HostedTermsEncoding.digest(resultBytes);
    requireResultBinding(handoff, result);
    Status status = Status.valueOf(row.get("status", String.class));
    if (status == Status.DISCLOSED || status == Status.DEFINITIVELY_NOT_DISCLOSED) {
      if (!Arrays.equals(resultBytes, row.get("result_payload", byte[].class))) {
        throw new IllegalArgumentException("Changed terminal disclosure result evidence");
      }
      return snapshot(row);
    }
    if (status != Status.DISPATCH_AUTHORIZED && status != Status.AMBIGUOUS) {
      throw new IllegalStateException(
          "A disclosure result requires durable dispatch authorization");
    }
    Status terminal =
        result.outcome() == Outcome.DISCLOSED
            ? Status.DISCLOSED
            : Status.DEFINITIVELY_NOT_DISCLOSED;
    int updated =
        dsl.execute(
            "UPDATE "
                + HANDOFFS
                + " SET status = ?, result_outcome = ?, result_payload = ?, result_digest = ?, "
                + "result_recorded_at = CURRENT_TIMESTAMP WHERE handoff_id = ? "
                + "AND status IN ('DISPATCH_AUTHORIZED', 'AMBIGUOUS')",
            terminal.name(),
            result.outcome().name(),
            resultBytes,
            resultDigest,
            handoff.handoffId());
    if (updated != 1) {
      Record prior = requireExactRow(handoff);
      if (Status.valueOf(prior.get("status", String.class)) != terminal
          || !Arrays.equals(resultBytes, prior.get("result_payload", byte[].class))) {
        throw new IllegalStateException(
            "Disclosure result did not persist as exact terminal readback");
      }
      return snapshot(prior);
    }
    return snapshot(requireExactRow(handoff));
  }

  public Optional<Snapshot> read(UUID handoffId) {
    requireTransaction();
    HostedTermsEncoding.requireUuid(handoffId, "disclosure handoff");
    Record row = dsl.fetchOne("SELECT * FROM " + HANDOFFS + " WHERE handoff_id = ?", handoffId);
    if (row == null) {
      return Optional.empty();
    }
    Snapshot snapshot = snapshot(row);
    requireExactSourceRows(snapshot.handoff());
    return Optional.of(snapshot);
  }

  private Record requireExactRow(HostedTermsDisclosureHandoff handoff) {
    Record row = findByIdentity(handoff.handoffId(), handoff.requestId());
    if (row == null) {
      throw new IllegalStateException("Exact disclosure handoff is unavailable");
    }
    exactSnapshot(row, handoff, handoff.canonicalBytes());
    return row;
  }

  private Record findByIdentity(UUID handoffId, UUID requestId) {
    List<Record> rows =
        dsl.fetch(
            "SELECT * FROM " + HANDOFFS + " WHERE handoff_id = ? OR request_id = ? FOR UPDATE",
            handoffId,
            requestId);
    if (rows.size() > 1) {
      throw new IllegalStateException("Disclosure handoff and request identities conflict");
    }
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private Snapshot exactSnapshot(
      Record row, HostedTermsDisclosureHandoff expected, byte[] expectedBinding) {
    Snapshot snapshot = snapshot(row);
    requireExactHandoffColumns(row, expected, expectedBinding);
    requireExactSourceRows(expected);
    return snapshot;
  }

  private void requireExactHandoffColumns(
      Record row, HostedTermsDisclosureHandoff expected, byte[] expectedBinding) {
    if (!Arrays.equals(expectedBinding, row.get("binding", byte[].class))
        || !expected.handoffId().equals(row.get("handoff_id", UUID.class))
        || !expected.requestId().equals(row.get("request_id", UUID.class))
        || !expected.kind().name().equals(row.get("kind", String.class))
        || !expected.sourceKey().equals(row.get("source_key", String.class))
        || !expected.predecessorDigest().equals(row.get("predecessor_digest", String.class))
        || !expected.candidateDigest().equals(row.get("candidate_digest", String.class))
        || !expected.effectiveAt().equals(row.get("effective_at", OffsetDateTime.class).toInstant())
        || !HostedTermsEncoding.digest(expectedBinding)
            .equals(row.get("binding_digest", String.class))) {
      throw new IllegalArgumentException(
          "Disclosure handoff identity conflicts with prior evidence");
    }
  }

  private Snapshot snapshot(Record row) {
    byte[] bindingBytes = row.get("binding", byte[].class);
    String bindingDigest = HostedTermsEncoding.digest(bindingBytes);
    if (!bindingDigest.equals(row.get("binding_digest", String.class))) {
      throw new IllegalStateException(
          "Disclosure handoff binding digest conflicts with exact bytes");
    }
    HostedTermsDisclosureHandoff handoff = HostedTermsDisclosureHandoff.fromStored(bindingBytes);
    if (!handoff.handoffId().equals(row.get("handoff_id", UUID.class))
        || !handoff.requestId().equals(row.get("request_id", UUID.class))
        || !handoff.kind().name().equals(row.get("kind", String.class))
        || !handoff.sourceKey().equals(row.get("source_key", String.class))
        || !handoff.predecessorDigest().equals(row.get("predecessor_digest", String.class))
        || !handoff.candidateDigest().equals(row.get("candidate_digest", String.class))
        || !handoff
            .effectiveAt()
            .equals(row.get("effective_at", OffsetDateTime.class).toInstant())) {
      throw new IllegalStateException("Disclosure handoff columns conflict with exact binding");
    }
    Status status = Status.valueOf(row.get("status", String.class));
    String resultOutcome = row.get("result_outcome", String.class);
    byte[] resultPayload = row.get("result_payload", byte[].class);
    String resultDigest = row.get("result_digest", String.class);
    OffsetDateTime resultRecordedAt = row.get("result_recorded_at", OffsetDateTime.class);
    Optional<DisclosureResult> result = Optional.empty();
    if (resultPayload != null) {
      if (resultDigest == null
          || !HostedTermsEncoding.digest(resultPayload).equals(resultDigest)
          || resultOutcome == null) {
        throw new IllegalStateException("Disclosure result digest or outcome is incomplete");
      }
      DisclosureResult decoded = DisclosureResult.fromStored(resultPayload);
      requireResultBinding(handoff, decoded);
      if (!decoded.outcome().name().equals(resultOutcome)) {
        throw new IllegalStateException("Disclosure result outcome differs from exact evidence");
      }
      if ((status == Status.DISCLOSED && decoded.outcome() != Outcome.DISCLOSED)
          || (status == Status.DEFINITIVELY_NOT_DISCLOSED
              && decoded.outcome() != Outcome.DEFINITIVELY_NOT_DISCLOSED)) {
        throw new IllegalStateException("Terminal disclosure status differs from exact result");
      }
      result = Optional.of(decoded);
    } else if (resultOutcome != null || resultDigest != null || resultRecordedAt != null) {
      throw new IllegalStateException("Partial disclosure result columns are not permitted");
    }
    return new Snapshot(
        handoff,
        status,
        row.get("dispatch_attempts", Integer.class),
        result,
        row.get("created_at", OffsetDateTime.class),
        resultRecordedAt);
  }

  private void requireExactSourceRows(HostedTermsDisclosureHandoff handoff) {
    List<Record> rows =
        dsl
            .fetch(
                "SELECT source_key, source_evidence, source_evidence_digest FROM "
                    + SOURCES
                    + " WHERE handoff_id = ?",
                handoff.handoffId())
            .stream()
            .sorted(Comparator.comparing(row -> row.get("source_key", String.class)))
            .toList();
    if (rows.size() != handoff.sources().size()) {
      throw new IllegalStateException("Disclosure handoff source participation is incomplete");
    }
    for (int index = 0; index < rows.size(); index++) {
      SourceEvidence expected = handoff.sources().get(index);
      Record row = rows.get(index);
      byte[] evidence = row.get("source_evidence", byte[].class);
      if (!expected.key().equals(row.get("source_key", String.class))
          || !Arrays.equals(expected.canonicalBytes(), evidence)
          || !HostedTermsEncoding.digest(evidence)
              .equals(row.get("source_evidence_digest", String.class))) {
        throw new IllegalStateException("Disclosure handoff source evidence differs from binding");
      }
    }
  }

  private void requireResultBinding(HostedTermsDisclosureHandoff handoff, DisclosureResult result) {
    if (!handoff.handoffId().equals(result.handoffId())
        || !handoff.requestId().equals(result.requestId())
        || !HostedTermsEncoding.digest(handoff.canonicalBytes()).equals(result.bindingDigest())) {
      throw new IllegalArgumentException("Disclosure result is not bound to the exact handoff");
    }
  }

  private void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Caller Account transaction required");
    }
  }

  private void requireWriteTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Writable caller Account transaction required");
    }
    dsl.connection(
        connection -> {
          if (connection.getAutoCommit()
              || connection.isReadOnly()
              || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException(
                "Transaction-bound writable READ_COMMITTED connection required");
          }
        });
  }
}
