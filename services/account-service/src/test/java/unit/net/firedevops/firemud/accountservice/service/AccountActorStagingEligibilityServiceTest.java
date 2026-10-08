package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountActorStagingEligibilityRepository;
import net.firedevops.firemud.accountservice.repository.AccountActorStagingEligibilityRepository.CurrentSnapshot;
import net.firedevops.firemud.accountservice.service.AccountActorStagingEligibilityService;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence.Purpose;
import org.junit.jupiter.api.Test;

class AccountActorStagingEligibilityServiceTest {
  private static final String NAMESPACE = "test";
  private static final UUID ACCOUNT_UUID = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
  private static final UUID TENANT_UUID = UUID.fromString("f8871fb0-7810-4b72-bb13-09e29a3509f2");
  private static final UUID REQUEST_UUID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID SOURCE_OPERATION_UUID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID EVENT_UUID = UUID.fromString("33333333-3333-4333-8333-333333333333");

  private final AccountActorStagingEligibilityRepository repository =
      mock(AccountActorStagingEligibilityRepository.class);
  private final AccountActorStagingEligibilityService service =
      new AccountActorStagingEligibilityService(repository, NAMESPACE);

  @Test
  void sealsEligibleEvidenceOnlyFromTheRequestedOwnerSnapshot() {
    when(repository.readCurrent(ACCOUNT_UUID, TENANT_UUID)).thenReturn(snapshot("ACTIVE", true));

    AccountActorStagingEligibilityEvidence evidence =
        service.resolve(
            AccountActorStagingEligibilityEvidence.SCHEMA_VERSION,
            NAMESPACE,
            REQUEST_UUID,
            ACCOUNT_UUID,
            TENANT_UUID,
            Purpose.PUBLIC_PRODUCTION_STAGING_ONLY);

    assertThat(evidence.decision())
        .isEqualTo(AccountActorStagingEligibilityEvidence.Decision.STAGING_ELIGIBLE);
    assertThat(evidence.currentness())
        .isEqualTo(AccountActorStagingEligibilityEvidence.Currentness.CURRENT_AT_REVALIDATION);
    assertThat(evidence.membershipVersion()).isEqualTo(2L);
    assertThat(evidence.membershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(evidence.membershipEventSequence()).isEqualTo(1L);
    assertThat(evidence.membershipEventId()).isEqualTo(EVENT_UUID);
    assertThat(evidence.membershipEventDigest()).startsWith("sha256:");
    assertThat(evidence.tenantSourceOperationId()).isEqualTo(SOURCE_OPERATION_UUID);
    assertThat(evidence.tenantProvenanceDigest()).startsWith("sha256:");
    assertThat(evidence.authoritySnapshotDigest()).hasSize(64);
    assertThat(evidence.eligibilityDecisionDigest()).hasSize(64);
    verify(repository).readCurrent(ACCOUNT_UUID, TENANT_UUID);
  }

  @Test
  void currentButInactiveOrAdmissionBlockedSourcesProduceIneligibleStagingEvidence() {
    when(repository.readCurrent(ACCOUNT_UUID, TENANT_UUID))
        .thenReturn(snapshot("SECURITY_LOCKED", true));

    AccountActorStagingEligibilityEvidence lockedAccount = resolve();

    assertThat(lockedAccount.decision())
        .isEqualTo(AccountActorStagingEligibilityEvidence.Decision.STAGING_INELIGIBLE);
    when(repository.readCurrent(ACCOUNT_UUID, TENANT_UUID)).thenReturn(snapshot("ACTIVE", false));

    AccountActorStagingEligibilityEvidence blockedMembership = resolve();

    assertThat(blockedMembership.decision())
        .isEqualTo(AccountActorStagingEligibilityEvidence.Decision.STAGING_INELIGIBLE);
  }

  @Test
  void rejectsUnsupportedScopeAndPurposeBeforeReadingOwnerSources() {
    assertThatThrownBy(
            () ->
                service.resolve(
                    2,
                    NAMESPACE,
                    REQUEST_UUID,
                    ACCOUNT_UUID,
                    TENANT_UUID,
                    Purpose.PUBLIC_PRODUCTION_STAGING_ONLY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                service.resolve(
                    1,
                    "other",
                    REQUEST_UUID,
                    ACCOUNT_UUID,
                    TENANT_UUID,
                    Purpose.PUBLIC_PRODUCTION_STAGING_ONLY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                service.resolve(
                    1,
                    NAMESPACE,
                    new UUID(0L, 0L),
                    ACCOUNT_UUID,
                    TENANT_UUID,
                    Purpose.PUBLIC_PRODUCTION_STAGING_ONLY))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(repository);
  }

  private AccountActorStagingEligibilityEvidence resolve() {
    return service.resolve(
        AccountActorStagingEligibilityEvidence.SCHEMA_VERSION,
        NAMESPACE,
        REQUEST_UUID,
        ACCOUNT_UUID,
        TENANT_UUID,
        Purpose.PUBLIC_PRODUCTION_STAGING_ONLY);
  }

  private static CurrentSnapshot snapshot(String lifecycle, boolean admissionAllowed) {
    return new CurrentSnapshot(
        ACCOUNT_UUID,
        AccountIdentityProvenance.ACCOUNT_V29_MIGRATION,
        lifecycle,
        TENANT_UUID,
        "FRESH_GAME_DESIGN",
        SOURCE_OPERATION_UUID,
        "sha256:" + "a".repeat(64),
        "ACTIVE",
        admissionAllowed,
        "EXPLICIT_JOIN",
        2L,
        1L,
        1L,
        EVENT_UUID,
        "sha256:" + "b".repeat(64),
        false);
  }
}
