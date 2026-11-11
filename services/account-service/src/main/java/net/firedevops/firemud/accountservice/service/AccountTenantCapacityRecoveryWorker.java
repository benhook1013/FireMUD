package net.firedevops.firemud.accountservice.service;

import static net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository.at;
import static net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository.conflict;
import static net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository.number;
import static net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository.unavailable;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Result;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.State;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * Unregistered Account reservation recovery. Only this owner mutates reservation outcomes. Its
 * protected selected-owner read client is a required dependency, not an automatically registered
 * source or a caller-supplied attestation. Original-NEW Game Session has a bounded exact reader;
 * World lifecycle and other action families remain unsupported. No timeout synthesizes no-commit.
 */
@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Private owner/source collaborators.")
public final class AccountTenantCapacityRecoveryWorker {
  public enum Disposition {
    COMMITTED,
    NOT_EXECUTED_AFTER_AUTHORIZATION,
    INDETERMINATE
  }

  public enum NoCommitAction {
    RETAIN_FOR_REARM,
    RELEASE
  }

  /**
   * An implementation must use the exact selected owner's authenticated mTLS readback and validate
   * its current owner fence and authoritative state, including durable original-attempt rejection
   * and no mutation for NOT_EXECUTED_AFTER_AUTHORIZATION. Content construction is not authority.
   * FENCE_REJECTED, FAILED, pending and ambiguous responses map only to INDETERMINATE.
   */
  public interface ExecutionReadClient {
    Observation readExact(Request request, Result reservation, Instant deadline);
  }

  public record Observation(
      String authenticatedOwnerWorkload,
      Result reservation,
      byte[] canonicalAttemptPayload,
      Disposition disposition,
      UUID proofId,
      long currentOwnerFence,
      String authoritativeStateDigest,
      byte[] canonicalOwnerReadback) {
    public Observation {
      Objects.requireNonNull(reservation);
      Objects.requireNonNull(disposition);
      TenantCapacityAdmission.uuid(proofId);
      if (authenticatedOwnerWorkload == null
          || currentOwnerFence <= 0
          || authoritativeStateDigest == null
          || !authoritativeStateDigest.matches("sha256:[0-9a-f]{64}")
          || canonicalAttemptPayload == null
          || canonicalAttemptPayload.length == 0
          || canonicalAttemptPayload.length > CapacityAdmissionDigest.MAX_PREIMAGE_BYTES
          || canonicalOwnerReadback == null
          || canonicalOwnerReadback.length == 0
          || canonicalOwnerReadback.length > 256 * 1024)
        throw new IllegalArgumentException("Complete owner readback required");
      canonicalAttemptPayload = canonicalAttemptPayload.clone();
      canonicalOwnerReadback = canonicalOwnerReadback.clone();
    }

    @Override
    public byte[] canonicalAttemptPayload() {
      return canonicalAttemptPayload.clone();
    }

    @Override
    public byte[] canonicalOwnerReadback() {
      return canonicalOwnerReadback.clone();
    }

    @Override
    public String toString() {
      return "CapacityOwnerObservation[redacted]";
    }
  }

  private final AccountTenantCapacityOwner owner;
  private final DSLContext dsl;
  private final AccountTenantCapacityRepository capacity;
  private final ExecutionReadClient source;
  private final Clock clock;
  private final UUID workerId = UUID.randomUUID();

  public AccountTenantCapacityRecoveryWorker(
      AccountTenantCapacityOwner owner, DSLContext dsl, ExecutionReadClient source, Clock clock) {
    this.owner = Objects.requireNonNull(owner);
    this.dsl = Objects.requireNonNull(dsl);
    this.capacity = new AccountTenantCapacityRepository(dsl);
    this.source = Objects.requireNonNull(source);
    this.clock = Objects.requireNonNull(clock);
  }

  /** Twelve bounded observations or the original deadline quarantine the same complete hold. */
  public Result reconcile(Request request, NoCommitAction noCommitAction) {
    Objects.requireNonNull(noCommitAction);
    request.requireCommittable();
    Claim claim = claim(request);
    if (claim.reservation().state() != State.RESERVED) return claim.reservation();
    if (claim.noCommitProof() != null) {
      if (noCommitAction == NoCommitAction.RETAIN_FOR_REARM) return claim.reservation();
      return owner.inTransaction(
          () -> {
            Record row = capacity.find(request.identity());
            if (row == null) throw conflict();
            Result current = capacity.exact(request, row);
            AccountTenantCapacityOwner.requireClaim(row, workerId, claim.fence(), clock.instant());
            if (!current.equals(claim.reservation())
                || !"NOT_EXECUTED_AFTER_AUTHORIZATION"
                    .equals(row.get("execution_status", String.class))
                || !Arrays.equals(claim.noCommitProof(), row.get("owner_readback", byte[].class)))
              throw conflict();
            moveUsage(request, false);
            int changed =
                dsl.execute(
                    "UPDATE account_tenant_capacity_reservations SET state = 'RELEASED', reservation_fence = ?, next_reconcile_at = NULL WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ? AND recovery_owner = ? AND recovery_claim_fence = ? AND state = 'RESERVED' AND execution_status = 'NOT_EXECUTED_AFTER_AUTHORIZATION'",
                    Math.incrementExact(current.reservationFence()),
                    request.identity().tenantId(),
                    request.identity().requestId(),
                    request.identity().admissionId(),
                    workerId,
                    claim.fence());
            if (changed != 1) throw conflict();
            return capacity.exact(
                request, Objects.requireNonNull(capacity.find(request.identity())));
          });
    }
    Observation candidate = null;
    byte[] candidateProof = null;
    if (claim.attempts() < 12) {
      Instant started = clock.instant();
      boolean positiveOnly = !started.isBefore(claim.reconcileDeadline());
      Instant deadline = started.plusSeconds(5);
      if (!positiveOnly && claim.reconcileDeadline().isBefore(deadline))
        deadline = claim.reconcileDeadline();
      try {
        candidate = source.readExact(request, claim.reservation(), deadline);
        if (!clock.instant().isBefore(deadline)) throw unavailable();
        requireExactSource(request, claim.reservation(), candidate);
        // A delayed first reconciliation must still recognize an exact committed effect. The
        // expired negative-evidence window never permits a late read to release the hold.
        if (positiveOnly && candidate.disposition() != Disposition.COMMITTED) throw unavailable();
        candidateProof = proof(request, candidate);
      } catch (RuntimeException unavailableSource) {
        // Missing, malformed, late and failed reads are indeterminate, never no-commit proof.
        candidate = null;
        candidateProof = null;
      }
    }
    final Observation observed = candidate;
    final byte[] exactProof = candidateProof;
    return owner.inTransaction(
        () -> {
          Record row = capacity.find(request.identity());
          if (row == null) throw conflict();
          Result current = capacity.exact(request, row);
          AccountTenantCapacityOwner.requireClaim(row, workerId, claim.fence(), clock.instant());
          if (!current.equals(claim.reservation())) throw conflict();
          int attempt = Math.incrementExact(Math.toIntExact(number(row, "recovery_attempt_count")));
          if (attempt > 12) throw unavailable();
          Instant now = clock.instant();
          boolean indeterminate =
              observed == null || observed.disposition() == Disposition.INDETERMINATE;
          boolean quarantine =
              indeterminate && (attempt == 12 || !now.isBefore(claim.reconcileDeadline()));
          String outcome =
              quarantine
                  ? State.QUARANTINED_REQUIRES_AUTHORITY_REMEDIATION.name()
                  : observed == null ? "SOURCE_UNAVAILABLE" : observed.disposition().name();
          int retained =
              dsl.execute(
                  "INSERT INTO account_tenant_capacity_recovery_evidence (tenant_uuid, request_id, admission_id, admission_attempt_id, recovery_claim_fence, recovery_policy_version, recovery_attempt_number, observed_at, outcome, canonical_readback, readback_digest) VALUES (?, ?, ?, ?, ?, 'capacity-recovery/1', ?, ?::timestamptz, ?, ?, ?)",
                  request.identity().tenantId(),
                  request.identity().requestId(),
                  request.identity().admissionId(),
                  request.admissionAttemptId(),
                  claim.fence(),
                  attempt,
                  at(now),
                  outcome,
                  exactProof,
                  exactProof == null ? null : sha256(exactProof));
          if (retained != 1) throw unavailable();
          if (indeterminate) {
            int updated =
                dsl.execute(
                    "UPDATE account_tenant_capacity_reservations SET recovery_attempt_count = ?, state = ?, recovery_escalated_at = ?::timestamptz, next_reconcile_at = ?::timestamptz WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ? AND recovery_owner = ? AND recovery_claim_fence = ? AND state = 'RESERVED' AND execution_status = 'OWNER_EXECUTION_PENDING'",
                    attempt,
                    quarantine
                        ? State.QUARANTINED_REQUIRES_AUTHORITY_REMEDIATION.name()
                        : State.RESERVED.name(),
                    quarantine ? at(now) : null,
                    quarantine
                        ? null
                        : at(
                            now.plusSeconds(5).isBefore(claim.reconcileDeadline())
                                ? now.plusSeconds(5)
                                : claim.reconcileDeadline()),
                    request.identity().tenantId(),
                    request.identity().requestId(),
                    request.identity().admissionId(),
                    workerId,
                    claim.fence());
            if (updated != 1) throw conflict();
            return capacity.exact(
                request, Objects.requireNonNull(capacity.find(request.identity())));
          }
          boolean committed = observed.disposition() == Disposition.COMMITTED;
          State next =
              committed
                  ? State.FINALIZED
                  : noCommitAction == NoCommitAction.RELEASE ? State.RELEASED : State.RESERVED;
          if (committed || next == State.RELEASED) moveUsage(request, committed);
          long reservationFence =
              next == State.RELEASED
                  ? Math.incrementExact(current.reservationFence())
                  : current.reservationFence();
          int changed =
              dsl.execute(
                  "UPDATE account_tenant_capacity_reservations SET state = ?, execution_status = ?, owner_readback = ?, owner_readback_digest = ?, reservation_fence = ?, recovery_attempt_count = ?, next_reconcile_at = NULL "
                      + "WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ? AND admission_attempt_id = ? AND mutation_digest = ? AND reservation_fence = ? AND recovery_owner = ? AND recovery_claim_fence = ? AND state = 'RESERVED' AND execution_status = 'OWNER_EXECUTION_PENDING'",
                  next.name(),
                  committed ? "COMMITTED" : "NOT_EXECUTED_AFTER_AUTHORIZATION",
                  exactProof,
                  sha256(exactProof),
                  reservationFence,
                  attempt,
                  request.identity().tenantId(),
                  request.identity().requestId(),
                  request.identity().admissionId(),
                  request.admissionAttemptId(),
                  request.mutationDigest(),
                  current.reservationFence(),
                  workerId,
                  claim.fence());
          if (changed != 1) throw conflict();
          return capacity.exact(request, Objects.requireNonNull(capacity.find(request.identity())));
        });
  }

  private void moveUsage(Request request, boolean committed) {
    Record usage = capacity.usage(request);
    long delta = Objects.requireNonNull(request.capacityDelta());
    capacity.updateUsage(
        request,
        usage,
        Math.subtractExact(number(usage, "held_units"), delta),
        committed
            ? Math.addExact(number(usage, "used_units"), delta)
            : number(usage, "used_units"));
  }

  public Result rearm(Request prior, Request next) {
    prior.requireCommittable();
    next.requireCommittable();
    java.util.Optional<Result> replay =
        owner.inTransaction(
            () -> {
              Record row = capacity.find(next.identity());
              if (row != null
                  && next.admissionAttemptId()
                      .equals(row.get("admission_attempt_id", UUID.class))) {
                capacity.requirePriorHistory(prior, next);
                return java.util.Optional.of(capacity.exact(next, row));
              }
              return java.util.Optional.empty();
            });
    if (replay.isPresent()) return replay.orElseThrow();
    Claim claim = claim(prior);
    return owner.rearm(prior, next, workerId, claim.fence());
  }

  private Claim claim(Request request) {
    return owner.inTransaction(
        () -> {
          Record row = capacity.find(request.identity());
          if (row == null) throw conflict();
          Result result = capacity.exact(request, row);
          if (result.state() != State.RESERVED)
            return new Claim(
                result,
                number(row, "recovery_claim_fence"),
                Math.toIntExact(number(row, "recovery_attempt_count")),
                AccountTenantCapacityRepository.time(row, "reconcile_deadline"),
                row.get("owner_readback", byte[].class));
          Instant now = clock.instant();
          if (row.get("recovery_owner", UUID.class) != null
              && !workerId.equals(row.get("recovery_owner", UUID.class))
              && now.isBefore(
                  AccountTenantCapacityRepository.time(row, "recovery_claim_expires_at")))
            throw unavailable();
          long fence = Math.incrementExact(number(row, "recovery_claim_fence"));
          int changed =
              dsl.execute(
                  "UPDATE account_tenant_capacity_reservations SET recovery_owner = ?, recovery_claim_fence = ?, recovery_claim_expires_at = ?::timestamptz, next_reconcile_at = LEAST(?::timestamptz, reconcile_deadline) "
                      + "WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ? AND recovery_claim_fence = ?",
                  workerId,
                  fence,
                  at(now.plusSeconds(15)),
                  at(now.plusSeconds(5)),
                  request.identity().tenantId(),
                  request.identity().requestId(),
                  request.identity().admissionId(),
                  number(row, "recovery_claim_fence"));
          if (changed != 1) throw conflict();
          return new Claim(
              result,
              fence,
              Math.toIntExact(number(row, "recovery_attempt_count")),
              AccountTenantCapacityRepository.time(row, "reconcile_deadline"),
              row.get("owner_readback", byte[].class));
        });
  }

  private static void requireExactSource(Request request, Result expected, Observation observed) {
    if (observed == null
        || !expected.equals(observed.reservation())
        || !TenantCapacityAdmission.expectedWorkload(
                request.targetNamespace(), request.context().owner())
            .equals(observed.authenticatedOwnerWorkload())
        || !Arrays.equals(
            CapacityAdmissionDigest.preimage(request), observed.canonicalAttemptPayload()))
      throw unavailable();
  }

  private static byte[] proof(Request request, Observation observed) {
    // Fixed content binding includes the entire attempt and exact owner readback, not a boolean.
    var output = new java.io.ByteArrayOutputStream();
    for (byte[] bytes :
        new byte[][] {
          "account-capacity-owner-readback/v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          CapacityAdmissionDigest.preimage(request),
          observed
              .reservation()
              .reservationId()
              .toString()
              .getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          Long.toString(observed.reservation().reservationFence())
              .getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          observed
              .authenticatedOwnerWorkload()
              .getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          observed.disposition().name().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          observed.proofId().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          Long.toString(observed.currentOwnerFence())
              .getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          observed.authoritativeStateDigest().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          observed.canonicalOwnerReadback()
        }) {
      output.writeBytes(
          Integer.toString(bytes.length).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      output.write(':');
      output.writeBytes(bytes);
    }
    byte[] proof = output.toByteArray();
    if (proof.length > 1024 * 1024) throw unavailable();
    return proof;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }

  private record Claim(
      Result reservation,
      long fence,
      int attempts,
      Instant reconcileDeadline,
      byte[] noCommitProof) {
    private Claim {
      noCommitProof = noCommitProof == null ? null : noCommitProof.clone();
    }

    @Override
    public byte[] noCommitProof() {
      return noCommitProof == null ? null : noCommitProof.clone();
    }
  }
}
