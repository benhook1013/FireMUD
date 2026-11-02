package net.firedevops.firemud.accountservice.repository;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.Category;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest.RestrictionState;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest.SourceKind;
import net.firedevops.firemud.accountservice.repository.AccountFreshEnrollmentPolicySource.VerifiedPublication;
import net.firedevops.firemud.accountservice.repository.AccountRepository.FreshAccountInsert;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Inactive category-owner policy publication and explicit fresh-only decision owner. */
public final class AccountFreshEnrollmentPolicyRepository {
  private final DSLContext dsl;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Internal Account transaction collaborators are retained privately and never exposed.")
  public AccountFreshEnrollmentPolicyRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  /** Only an actual protected mounted-source read can construct this input. No default install. */
  public void install(VerifiedPublication publication) {
    requireWritable();
    Objects.requireNonNull(publication);
    // The advisory lock covers the initially absent pointer too, without locking any Account.
    for (Category category : Category.values()) lockCategory(category);
    publication.requireCurrentProtectedSource();
    Record existing =
        dsl.fetchOne(
            "SELECT * FROM account_fresh_enrollment_policy_publications WHERE publication_id = ?",
            publication.publicationId);
    if (existing == null) {
      dsl.execute(
          "INSERT INTO account_fresh_enrollment_policy_publications "
              + "(publication_id, category, revision, policy_id, closure_id, policy_rule, closure_rule, "
              + "source_identity, source_root, publication_digest, publication_bytes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          publication.publicationId,
          publication.category.storageValue(),
          publication.revision,
          publication.policyId,
          publication.closureId,
          publication.policyRule,
          publication.closureRule,
          publication.sourceIdentity,
          publication.sourceRoot,
          publication.digest,
          publication.bytes());
    } else if (!publication.digest.equals(existing.get("publication_digest", String.class))
        || !java.util.Arrays.equals(
            publication.bytes(), existing.get("publication_bytes", byte[].class))
        || !publication.sourceRoot.equals(existing.get("source_root", String.class))) {
      throw AccountFreshEnrollmentPolicySource.unavailable();
    }
    Record current =
        dsl.fetchOne(
            "SELECT * FROM account_fresh_enrollment_policy_current WHERE category = ? FOR UPDATE",
            publication.category.storageValue());
    if (current != null
        && publication.publicationId.equals(current.get("publication_id", UUID.class))) return;
    if (current == null) {
      dsl.execute(
          "INSERT INTO account_fresh_enrollment_policy_current (category, publication_id, revision) VALUES (?, ?, ?)",
          publication.category.storageValue(),
          publication.publicationId,
          publication.revision);
    } else {
      dsl.execute(
          "UPDATE account_fresh_enrollment_policy_current SET publication_id = ?, revision = ? WHERE category = ?",
          publication.publicationId,
          publication.revision,
          publication.category.storageValue());
    }
  }

  /** Fixed category order; these locks survive both decisions and enrollment result completion. */
  void lockCurrentPolicies() {
    requireWritable();
    for (Category category : Category.values()) currentPolicy(category);
  }

  private Record currentPolicy(Category category) {
    // Shared advisory lock excludes the publisher even while the current row is absent. The SQL
    // pointer guard takes the matching exclusive lock; direct writers share this same order.
    dsl.fetch(
        "SELECT pg_advisory_xact_lock_shared(hashtextextended(?, 0))", policyLockKey(category));
    Record row =
        dsl.fetchOne(
            "SELECT p.* FROM account_fresh_enrollment_policy_current c "
                + "JOIN account_fresh_enrollment_policy_publications p ON p.publication_id = c.publication_id "
                + "AND p.category = c.category AND p.revision = c.revision WHERE c.category = ? FOR SHARE OF c, p",
            category.storageValue());
    if (row == null) throw AccountFreshEnrollmentPolicySource.unavailable();
    return row;
  }

  private void lockCategory(Category category) {
    dsl.fetch("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", policyLockKey(category));
  }

  private static String policyLockKey(Category category) {
    return "account-fresh-enrollment-policy:" + category.storageValue();
  }

  /** Each category explicitly evaluates its own current publication and closure for this insert. */
  OwnerDecision evaluate(FreshAccountInsert proof, UUID enrollmentId, Category category) {
    requireWritable();
    proof.requireCurrent(dsl);
    Record policy = currentPolicy(category);
    if (!"CERTIFY_NEW_ACTIVE_ACCOUNT".equals(policy.get("policy_rule", String.class))
        || !"EXACT_FRESH_INSERT_ONLY".equals(policy.get("closure_rule", String.class))) {
      throw AccountFreshEnrollmentPolicySource.unavailable();
    }
    var birth =
        new AccountPlatformRestrictionBirthRepository(dsl)
            .readCurrentBirthSource(proof.accountUuid());
    var predecessor =
        category == Category.ACCOUNT_SECURITY_LOCK
            ? birth.accountSecurityLock()
            : birth.platformAccessBan();
    var current =
        birth.currentAuthority(); // Rereads the advanced Account source for the second category.
    UUID decisionId = identity(enrollmentId, category, "decision");
    UUID requestId = identity(enrollmentId, category, "command");
    Record stored =
        dsl.fetchOne(
            "INSERT INTO account_fresh_enrollment_decisions "
                + "(decision_id, enrollment_id, account_uuid, account_id, insertion_xid, category, "
                + "publication_id, publication_digest, policy_id, closure_id, request_id, "
                + "expected_generation, expected_source_version, expected_fence, expected_fence_source_version, "
                + "expected_revision, expected_epoch, expected_result_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING decision_digest",
            decisionId,
            enrollmentId,
            proof.accountUuid(),
            proof.accountId(),
            proof.insertionTransactionId(),
            category.storageValue(),
            policy.get("publication_id", UUID.class),
            policy.get("publication_digest", String.class),
            policy.get("policy_id", UUID.class),
            policy.get("closure_id", UUID.class),
            requestId,
            current.generation(),
            current.sourceVersion(),
            current.issuanceFence().value(),
            current.issuanceFence().sourceVersion(),
            predecessor.revision(),
            predecessor.enforcementEpoch(),
            predecessor.resultId());
    if (stored == null) throw AccountFreshEnrollmentPolicySource.unavailable();
    var request =
        new AccountPlatformRestrictionMutationRequest(
            requestId,
            proof.accountUuid(),
            category,
            current.generation(),
            current.sourceVersion(),
            predecessor.revision(),
            predecessor.enforcementEpoch(),
            predecessor.resultId(),
            RestrictionState.NONRESTRICTED,
            SourceKind.ACCOUNT_FRESH_ENROLLMENT,
            decisionId,
            stored.get("decision_digest", String.class));
    return new OwnerDecision(request, proof.insertionTransactionId());
  }

  private static UUID identity(UUID enrollmentId, Category category, String purpose) {
    return UUID.nameUUIDFromBytes(
        ("account-fresh-enrollment/v1:"
                + enrollmentId
                + ":"
                + category.storageValue()
                + ":"
                + purpose)
            .getBytes(StandardCharsets.UTF_8));
  }

  static void requireWritable() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Fresh enrollment requires a writable Account transaction");
    }
  }

  /** Cannot be supplied by a request DTO; constructed only by this category decision owner. */
  public static final class OwnerDecision {
    private final AccountPlatformRestrictionMutationRequest request;
    private final long insertionXid;

    private OwnerDecision(AccountPlatformRestrictionMutationRequest request, long insertionXid) {
      this.request = request;
      this.insertionXid = insertionXid;
    }

    AccountPlatformRestrictionMutationRequest requireCurrent(
        DSLContext dsl, FreshAccountInsert proof) {
      proof.requireCurrent(dsl);
      if (insertionXid != proof.insertionTransactionId()
          || !request.accountUuid().equals(proof.accountUuid())) {
        throw AccountFreshEnrollmentPolicySource.unavailable();
      }
      dsl.fetch(
          "SELECT account_fresh_enrollment_require_decision(?, true)", request.sourceRequestId());
      return request;
    }
  }
}
