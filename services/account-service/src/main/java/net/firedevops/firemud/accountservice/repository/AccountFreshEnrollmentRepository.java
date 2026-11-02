package net.firedevops.firemud.accountservice.repository;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.Category;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionOperationRepository.Operation;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * Manually composed internal new-Account enrollment. No Spring bean, registration route, issuer or
 * PKI activation. The caller supplies one writable transaction around the complete operation with
 * the owner constraints in their default deferred mode. Completed source boundaries are checked
 * before the next advance; enrollment completeness remains deferred until the normal commit.
 */
public final class AccountFreshEnrollmentRepository {
  private static final String SOURCE_CONSTRAINTS =
      "account_authority_source_generation_consistent, "
          + "account_authority_source_fence_consistent, account_authority_source_record_consistent";

  private final DSLContext dsl;
  private final AccountRepository accounts;
  private final AccountFreshEnrollmentPolicyRepository policies;
  private final AccountPlatformRestrictionOperationRepository restrictions;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Internal Account transaction collaborators are retained privately and never exposed.")
  public AccountFreshEnrollmentRepository(
      DSLContext dsl,
      AccountRepository accounts,
      AccountFreshEnrollmentPolicyRepository policies,
      AccountPlatformRestrictionOperationRepository restrictions) {
    this.dsl = Objects.requireNonNull(dsl);
    this.accounts = Objects.requireNonNull(accounts);
    this.policies = Objects.requireNonNull(policies);
    this.restrictions = Objects.requireNonNull(restrictions);
  }

  public Result enroll(AccountFreshEnrollmentRequest request) {
    AccountFreshEnrollmentPolicyRepository.requireWritable();
    Objects.requireNonNull(request);
    dsl.fetch(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        "account-fresh-enrollment:" + request.enrollmentId());
    Record prior =
        dsl.fetchOne(
            "SELECT * FROM account_fresh_enrollments WHERE enrollment_id = ? FOR SHARE",
            request.enrollmentId());
    if (prior != null) {
      if (!request.requestDigest().equals(prior.get("request_digest", String.class))) {
        throw new AccountAuthorityOutboxRepository.IdempotencyConflictException(
            "Fresh enrollment input changed");
      }
      return readResult(prior); // Exact immutable original results; no minting or re-certification.
    }
    policies.lockCurrentPolicies();
    return accounts.insertFreshAndApply(
        request.newAccount(),
        proof -> {
          checkCompletedSourceBoundary();
          dsl.execute(
              "INSERT INTO account_fresh_enrollments "
                  + "(enrollment_id, request_digest, account_uuid, account_id, insertion_xid) VALUES (?, ?, ?, ?, ?)",
              request.enrollmentId(),
              request.requestDigest(),
              proof.accountUuid(),
              proof.accountId(),
              proof.insertionTransactionId());
          Operation lock =
              restrictions.certifyFreshEnrollment(
                  proof,
                  policies.evaluate(proof, request.enrollmentId(), Category.ACCOUNT_SECURITY_LOCK));
          checkCompletedSourceBoundary();
          Operation ban =
              restrictions.certifyFreshEnrollment(
                  proof,
                  policies.evaluate(proof, request.enrollmentId(), Category.PLATFORM_ACCESS_BAN));
          dsl.execute(
              "INSERT INTO account_fresh_enrollment_results "
                  + "(enrollment_id, security_request_id, ban_request_id) VALUES (?, ?, ?)",
              request.enrollmentId(),
              lock.request().requestId(),
              ban.request().requestId());
          Record completed =
              dsl.fetchOne(
                  "SELECT * FROM account_fresh_enrollments WHERE enrollment_id = ?",
                  request.enrollmentId());
          return readResult(Objects.requireNonNull(completed));
        });
  }

  private void checkCompletedSourceBoundary() {
    // These guards compare each queued row image to the current source head. Validate the
    // complete baseline/first result before advancing it, retaining every intermediate check.
    // Only source constraints run here: both category results must still commit atomically.
    dsl.execute("SET CONSTRAINTS " + SOURCE_CONSTRAINTS + " IMMEDIATE");
    dsl.execute("SET CONSTRAINTS " + SOURCE_CONSTRAINTS + " DEFERRED");
  }

  private Result readResult(Record enrollment) {
    UUID enrollmentId = enrollment.get("enrollment_id", UUID.class);
    dsl.fetch("SELECT account_fresh_enrollment_require_complete(?)", enrollmentId);
    Record result =
        dsl.fetchOne(
            "SELECT * FROM account_fresh_enrollment_results WHERE enrollment_id = ? FOR SHARE",
            enrollmentId);
    if (result == null) throw AccountFreshEnrollmentPolicySource.unavailable();
    Operation lock =
        AccountPlatformRestrictionOperationRepository.findCommittedByRequestIdShared(
                dsl, result.get("security_request_id", UUID.class))
            .orElseThrow(AccountFreshEnrollmentPolicySource::unavailable);
    Operation ban =
        AccountPlatformRestrictionOperationRepository.findCommittedByRequestIdShared(
                dsl, result.get("ban_request_id", UUID.class))
            .orElseThrow(AccountFreshEnrollmentPolicySource::unavailable);
    return new Result(
        enrollmentId,
        enrollment.get("account_id", Long.class),
        enrollment.get("account_uuid", UUID.class),
        lock,
        ban);
  }

  /** Original receipts may later be superseded by their independent category owner revisions. */
  public record Result(
      UUID enrollmentId,
      long accountId,
      UUID accountUuid,
      Operation accountSecurityLock,
      Operation platformAccessBan) {}
}
