package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.Category;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest.RestrictionState;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionMutationRequest.SourceKind;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionOperationRepository;
import net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseCacheFixture;
import net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseService;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec.SourceContext;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real Account/primary/source transactions and current-restriction denial, not positive issuance.
 * The separate uncertified global Account has no tenant membership or current restriction
 * projection. Existing creator/legal and deployment inputs remain controlled. No current
 * restriction row, private owner-proof bypass, committed signer or positive registry receipt is
 * manufactured here. The certified signer/Coordination/actor composition is defined separately in
 * AccountControlUiPositiveOwnerPostgresIntegrationTest, with its own qualification limits.
 */
@Testcontainers
@org.junit.jupiter.api.Timeout(180)
class AccountGlobalControlUiIssuancePostgresIntegrationTest {
  private static final String PASSWORD = "test-only-global-password";
  private static final String OTP = "test-only-global-ordinary-otp";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres())
          .withLabel("firemud.proof", "account-shared-abuse-fixtures")
          .withLabel(
              "firemud.proof.run", System.getenv().getOrDefault("GITHUB_RUN_ID", "local-unrun"))
          .withStartupTimeout(java.time.Duration.ofSeconds(60))
          .withCreateContainerCmdModifier(
              command ->
                  command
                      .getHostConfig()
                      .withMemory(512L * 1024 * 1024)
                      .withMemorySwap(512L * 1024 * 1024)
                      .withPidsLimit(256L));

  @Container
  static final org.testcontainers.containers.GenericContainer<?> credentialCache =
      AccountCredentialAbuseCacheFixture.container()
          .withLabel("firemud.proof", "account-shared-abuse-fixtures");

  @TempDir Path temporary;

  @Test
  void realPasswordAndOtpAuthenticateSeparateAccountWithoutCreatingTenantMembership()
      throws Exception {
    var fixture = fixture();
    var account = fixture.createGlobalAccount(PASSWORD);
    assertNoMembership(fixture, account);

    var passwordIdentity =
        fixture.tx(
            () ->
                fixture.primary.authenticateControlUiPrimaryIdentity(account.getEmail(), PASSWORD));
    assertThat(passwordIdentity.accountId()).isEqualTo(account.getAccountUuid());
    assertThat(passwordIdentity.accountId()).isNotEqualTo(fixture.account.getAccountUuid());
    fixture.installGlobalOtp(account, OTP);
    var otpIdentity =
        fixture.tx(
            () -> fixture.primary.authenticateControlUiPrimaryIdentity(account.getEmail(), OTP));
    assertThat(otpIdentity.accountId()).isEqualTo(account.getAccountUuid());
    assertThat(fixture.challenges.findByAccountId(account.getId())).isEmpty();
    assertNoMembership(fixture, account);
    fixture.assertNoIssuance();
  }

  @Test
  void unavailableCurrentRestrictionRollsBackActualOtpAndRetainsSameChallengeAcrossRetries()
      throws Exception {
    try (var abuse = new AccountCredentialAbuseCacheFixture(credentialCache)) {
      var fixture = fixture(abuse.service());
      var account = fixture.createGlobalAccount(PASSWORD);
      var original = fixture.installGlobalOtp(account, OTP);
      var request =
          new AccountControlUiIssuanceService.AccountSignInRequest(
              UUID.randomUUID(),
              account.getEmail(),
              OTP,
              UUID.randomUUID(),
              new SourceContext("192.0.2.10"));

      try (var ignored =
          AccountControlUiOwnerSourcesFixture.withPeer(
              AccountControlUiOwnerSourcesFixture.CALLER)) {
        for (int attempt = 0; attempt < 2; attempt++) {
          assertThatThrownBy(() -> fixture.service.issueAccount(request))
              .isInstanceOf(
                  AccountPlatformRestrictionOperationRepository
                      .RestrictionProjectionUnavailableException.class);
          var retained = fixture.challenges.findByAccountId(account.getId()).orElseThrow();
          assertThat(retained.getId()).isEqualTo(original.getId());
          assertThat(retained.getCodeHash()).isEqualTo(original.getCodeHash());
          assertThat(retained.getInvalidAttemptCount())
              .isEqualTo(original.getInvalidAttemptCount());
          assertNoMembership(fixture, account);
          fixture.assertNoIssuance();
        }
      }
      // The retained credential is still actually usable; neither failed issuer transaction
      // consumed
      // it.
      assertThat(
              fixture
                  .tx(
                      () ->
                          fixture.primary.authenticateControlUiPrimaryIdentity(
                              account.getEmail(), OTP))
                  .accountId())
          .isEqualTo(account.getAccountUuid());
      assertThat(fixture.challenges.findByAccountId(account.getId())).isEmpty();
    }
  }

  @Test
  void exactBirthPredecessorsCannotAuthorizePublicRestrictionCommitOrCurrentAbsence()
      throws Exception {
    var fixture = fixture();
    var account = fixture.createGlobalAccount(PASSWORD);
    var birth =
        fixture.tx(
            () -> fixture.restrictionBirths.readCurrentBirthSource(account.getAccountUuid()));

    for (Category category : Category.values()) {
      var predecessor =
          category == Category.ACCOUNT_SECURITY_LOCK
              ? birth.accountSecurityLock()
              : birth.platformAccessBan();
      // Correlation-only source input is deliberately not authenticated recovery/moderation proof.
      var request =
          new AccountPlatformRestrictionMutationRequest(
              UUID.randomUUID(),
              account.getAccountUuid(),
              category,
              birth.currentAuthority().generation(),
              birth.currentAuthority().sourceVersion(),
              predecessor.revision(),
              predecessor.enforcementEpoch(),
              predecessor.resultId(),
              RestrictionState.NONRESTRICTED,
              category == Category.ACCOUNT_SECURITY_LOCK
                  ? SourceKind.ACCOUNT_SECURITY_RECOVERY
                  : SourceKind.LOGGING_ADMIN_MODERATION,
              UUID.randomUUID(),
              "sha256:" + "7".repeat(64));
      assertThatThrownBy(() -> fixture.tx(() -> fixture.restrictionOperations.commit(request)))
          .isInstanceOf(
              AccountPlatformRestrictionOperationRepository.OwnerAuthorizationUnresolvedException
                  .class);
      assertThatThrownBy(
              () ->
                  fixture.tx(
                      () ->
                          fixture.restrictionOperations.readCurrent(
                              account.getAccountUuid(), category)))
          .isInstanceOf(
              AccountPlatformRestrictionOperationRepository
                  .RestrictionProjectionUnavailableException.class);
    }
    assertThat(
            Objects.requireNonNull(
                    fixture.dsl.fetchOne(
                        "SELECT count(*) AS result_count FROM"
                            + " account_platform_restriction_current_projections WHERE account_uuid"
                            + " = ?",
                        account.getAccountUuid()))
                .get("result_count", Long.class))
        .isZero();
    assertNoMembership(fixture, account);
    fixture.assertNoIssuance();
  }

  @Test
  void accountSourceObservationCannotUseHistoricalNonrestrictedBirthAsCurrentAuthority()
      throws Exception {
    var fixture = fixture();
    var account = fixture.createGlobalAccount(PASSWORD);
    var identity =
        fixture.tx(
            () ->
                fixture.primary.authenticateControlUiPrimaryIdentity(account.getEmail(), PASSWORD));

    assertThatThrownBy(
            () -> fixture.tx(() -> fixture.authority.captureAccount(identity.accountId())))
        .isInstanceOf(
            AccountPlatformRestrictionOperationRepository.RestrictionProjectionUnavailableException
                .class);
    assertNoMembership(fixture, account);
    fixture.assertNoIssuance();
  }

  private AccountControlUiOwnerSourcesFixture fixture(AccountCredentialAbuseService sharedOwner)
      throws Exception {
    UUID tenant = UUID.randomUUID();
    return new AccountControlUiOwnerSourcesFixture(
        postgres.getJdbcUrl(),
        postgres.getUsername(),
        postgres.getPassword(),
        temporary,
        true,
        tenant,
        null,
        false,
        AccountControlUiOwnerSourcesFixture.defaultCreation(tenant),
        Objects.requireNonNull(sharedOwner));
  }

  private AccountControlUiOwnerSourcesFixture fixture() throws Exception {
    return new AccountControlUiOwnerSourcesFixture(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), temporary, true);
  }

  private static void assertNoMembership(
      AccountControlUiOwnerSourcesFixture fixture, Account account) {
    assertThat(
            Objects.requireNonNull(
                    fixture.dsl.fetchOne(
                        "SELECT count(*) AS result_count FROM account_tenant_membership WHERE"
                            + " account_id = ?",
                        account.getId()))
                .get("result_count", Long.class))
        .isZero();
  }
}
