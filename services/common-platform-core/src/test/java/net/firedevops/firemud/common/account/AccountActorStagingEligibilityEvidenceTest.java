package net.firedevops.firemud.common.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountActorStagingEligibilityEvidenceTest {
  private static final String NAMESPACE = "test";
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ACCOUNT_ID = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
  private static final UUID TENANT_ID = UUID.fromString("f8871fb0-7810-4b72-bb13-09e29a3509f2");
  private static final UUID TENANT_OPERATION_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID MEMBERSHIP_EVENT_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final Instant OBSERVED_AT = Instant.parse("2026-10-08T00:00:00.123456789Z");

  @Test
  void sealsCurrentActiveMembershipAsStagingEligibleWithSeparateDigests() {
    AccountActorStagingEligibilityEvidence evidence = seal("ACTIVE", "ACTIVE", true, REQUEST_ID);

    assertThat(evidence.decision())
        .isEqualTo(AccountActorStagingEligibilityEvidence.Decision.STAGING_ELIGIBLE);
    assertThat(evidence.currentness())
        .isEqualTo(AccountActorStagingEligibilityEvidence.Currentness.CURRENT_AT_REVALIDATION);
    assertThat(evidence.authoritySnapshotDigest()).matches("[0-9a-f]{64}");
    assertThat(evidence.eligibilityDecisionDigest()).matches("[0-9a-f]{64}");
    assertThat(evidence.authoritySnapshotDigest())
        .isNotEqualTo(evidence.eligibilityDecisionDigest());
    assertThat(evidence.observedAt()).isEqualTo(OBSERVED_AT);
  }

  @Test
  void accountOrMembershipEligibilityFailureIsNotOverstated() {
    assertThat(seal("SECURITY_LOCKED", "ACTIVE", true, REQUEST_ID).decision())
        .isEqualTo(AccountActorStagingEligibilityEvidence.Decision.STAGING_INELIGIBLE);
    assertThat(seal("ACTIVE", "INACTIVE", false, REQUEST_ID).decision())
        .isEqualTo(AccountActorStagingEligibilityEvidence.Decision.STAGING_INELIGIBLE);
    assertThat(seal("ACTIVE", "ACTIVE", false, REQUEST_ID).decision())
        .isEqualTo(AccountActorStagingEligibilityEvidence.Decision.STAGING_INELIGIBLE);
  }

  @Test
  void authorityAndDecisionDigestsBindDifferentRequestEvidence() {
    AccountActorStagingEligibilityEvidence first = seal("ACTIVE", "ACTIVE", true, REQUEST_ID);
    AccountActorStagingEligibilityEvidence second =
        seal("ACTIVE", "ACTIVE", true, UUID.fromString("44444444-4444-4444-8444-444444444444"));

    assertThat(second.authoritySnapshotDigest()).isNotEqualTo(first.authoritySnapshotDigest());
    assertThat(second.eligibilityDecisionDigest()).isNotEqualTo(first.eligibilityDecisionDigest());
  }

  @Test
  void rejectsTamperedAuthorityAndNonpositiveMembershipVersion() {
    AccountActorStagingEligibilityEvidence evidence = seal("ACTIVE", "ACTIVE", true, REQUEST_ID);

    assertThatThrownBy(
            () ->
                new AccountActorStagingEligibilityEvidence(
                    evidence.schemaVersion(),
                    evidence.targetNamespace(),
                    evidence.requestId(),
                    evidence.canonicalAccountId(),
                    evidence.canonicalTenantId(),
                    evidence.purpose(),
                    evidence.currentness(),
                    evidence.observedAt(),
                    evidence.decision(),
                    evidence.accountUuidProvenance(),
                    evidence.accountLifecycleState(),
                    evidence.membershipLifecycleState(),
                    evidence.gameplayAdmissionAllowed(),
                    evidence.membershipAuthorityProvenance(),
                    evidence.membershipVersion() + 1L,
                    evidence.membershipAuthorityGeneration(),
                    evidence.tenantProvenanceKind(),
                    evidence.tenantSourceOperationId(),
                    evidence.tenantProvenanceDigest(),
                    evidence.membershipEventSequence(),
                    evidence.membershipEventId(),
                    evidence.membershipEventDigest(),
                    evidence.lastTransitionInvalidated(),
                    evidence.eligibilityDecisionDigest(),
                    evidence.authoritySnapshotDigest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Authority snapshot digest does not match its fields");

    assertThatThrownBy(
            () ->
                AccountActorStagingEligibilityEvidence.seal(
                    NAMESPACE,
                    REQUEST_ID,
                    ACCOUNT_ID,
                    TENANT_ID,
                    AccountActorStagingEligibilityEvidence.Purpose.PUBLIC_PRODUCTION_STAGING_ONLY,
                    OBSERVED_AT,
                    "ACCOUNT_V29_MIGRATION",
                    "ACTIVE",
                    "ACTIVE",
                    true,
                    "EXPLICIT_JOIN",
                    0L,
                    1L,
                    "FRESH_GAME_DESIGN",
                    TENANT_OPERATION_ID,
                    "sha256:" + "a".repeat(64),
                    1L,
                    MEMBERSHIP_EVENT_ID,
                    "sha256:" + "b".repeat(64),
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Positive membership authority counters are required");
  }

  private static AccountActorStagingEligibilityEvidence seal(
      String accountLifecycle, String membershipLifecycle, boolean admission, UUID requestId) {
    return AccountActorStagingEligibilityEvidence.seal(
        NAMESPACE,
        requestId,
        ACCOUNT_ID,
        TENANT_ID,
        AccountActorStagingEligibilityEvidence.Purpose.PUBLIC_PRODUCTION_STAGING_ONLY,
        OBSERVED_AT,
        "ACCOUNT_V29_MIGRATION",
        accountLifecycle,
        membershipLifecycle,
        admission,
        "EXPLICIT_JOIN",
        2L,
        1L,
        "FRESH_GAME_DESIGN",
        TENANT_OPERATION_ID,
        "sha256:" + "a".repeat(64),
        1L,
        MEMBERSHIP_EVENT_ID,
        "sha256:" + "b".repeat(64),
        false);
  }
}
