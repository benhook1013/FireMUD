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
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real Account/primary/source transactions and current-restriction denial, not positive issuance.
 * The separate global Account has no tenant membership. Existing fixture creator/legal inputs and
 * deployment identity remain controlled setup. No current restriction row, private owner-proof
 * bypass, committed signer or positive registry receipt is manufactured. Public restriction commit
 * remains unavailable, so the complete positive signer/Coordination/actor path is not established.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountGlobalControlUiIssuancePostgresIntegrationTest {
  private static final String PASSWORD = "test-only-global-password";
  private static final String OTP = "test-only-global-ordinary-otp";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

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
    var fixture = fixture();
    var account = fixture.createGlobalAccount(PASSWORD);
    var original = fixture.installGlobalOtp(account, OTP);
    var request =
        new AccountControlUiIssuanceService.AccountSignInRequest(
            UUID.randomUUID(), account.getEmail(), OTP, UUID.randomUUID());

    try (var ignored =
        AccountControlUiOwnerSourcesFixture.withPeer(AccountControlUiOwnerSourcesFixture.CALLER)) {
      for (int attempt = 0; attempt < 2; attempt++) {
        assertThatThrownBy(() -> fixture.service.issueAccount(request))
            .isInstanceOf(
                AccountPlatformRestrictionOperationRepository
                    .RestrictionProjectionUnavailableException.class);
        var retained = fixture.challenges.findByAccountId(account.getId()).orElseThrow();
        assertThat(retained.getId()).isEqualTo(original.getId());
        assertThat(retained.getCodeHash()).isEqualTo(original.getCodeHash());
        assertThat(retained.getInvalidAttemptCount()).isEqualTo(original.getInvalidAttemptCount());
        assertNoMembership(fixture, account);
        fixture.assertNoIssuance();
      }
    }
    // The retained credential is still actually usable; neither failed issuer transaction consumed
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
                        "SELECT count(*) AS result_count FROM account_platform_restriction_current_projections "
                            + "WHERE account_uuid = ?",
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

  private AccountControlUiOwnerSourcesFixture fixture() throws Exception {
    return new AccountControlUiOwnerSourcesFixture(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), temporary, true);
  }

  private static void assertNoMembership(
      AccountControlUiOwnerSourcesFixture fixture, Account account) {
    assertThat(
            Objects.requireNonNull(
                    fixture.dsl.fetchOne(
                        "SELECT count(*) AS result_count FROM account_tenant_membership WHERE account_id = ?",
                        account.getId()))
                .get("result_count", Long.class))
        .isZero();
  }
}
