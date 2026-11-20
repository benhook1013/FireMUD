package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
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
import net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseSubjectKeys;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec.SourceContext;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
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
@Testcontainers(disabledWithoutDocker = true)
class AccountGlobalControlUiIssuancePostgresIntegrationTest {
  private static final String PASSWORD = "test-only-global-password";
  private static final String OTP = "test-only-global-ordinary-otp";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static GenericContainer<?> credentialCache = AccountCredentialAbuseCacheFixture.container();

  @BeforeAll
  static void awaitMappedOwnerJdbc() {
    net.firedevops.firemud.test.PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(postgres);
  }

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
      // consumed it.
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

  @Test
  void consumedOtpCannotAuthenticateASecondNewPrimaryAttempt() throws Exception {
    var fixture = fixture();
    var account = fixture.createGlobalAccount(PASSWORD);
    fixture.installGlobalOtp(account, OTP);
    assertThat(
            fixture
                .tx(
                    () ->
                        fixture.primary.authenticateControlUiPrimaryIdentity(
                            account.getEmail(), OTP))
                .accountId())
        .isEqualTo(account.getAccountUuid());
    assertThat(fixture.challenges.findByAccountId(account.getId())).isEmpty();
    var before = retainedOwners(fixture);

    // A new primary attempt, not recovery of an original issuance request.
    assertInvalidCredentials(
        () ->
            fixture.tx(
                () ->
                    fixture.primary.authenticateControlUiPrimaryIdentity(account.getEmail(), OTP)));

    assertThat(fixture.challenges.findByAccountId(account.getId())).isEmpty();
    assertThat(retainedOwners(fixture)).isEqualTo(before);
    assertNoMembership(fixture, account);
    fixture.assertNoIssuance();
  }

  @Test
  void invalidPasswordAndOtpCommitOnlyChallengeFailuresAndRealSharedAbuseAccounting()
      throws Exception {
    var admin =
        RedisClient.create(
            RedisURI.Builder.redis(credentialCache.getHost(), credentialCache.getMappedPort(6379))
                .withTimeout(java.time.Duration.ofSeconds(2))
                .build());
    try (var inspector = admin.connect();
        var abuse = new AccountCredentialAbuseCacheFixture(credentialCache)) {
      var fixture = fixture(abuse.service());
      var account = fixture.createGlobalAccount(PASSWORD);
      fixture.installGlobalOtp(account, OTP);
      var ownersBefore = retainedOwners(fixture);
      var challengeBefore = challengeContent(fixture, account);
      var oldKeys =
          new java.util.HashSet<>(
              inspector.sync().keys(AccountCredentialAbuseSubjectKeys.PREFIX + "subject:*"));
      try (var ignored =
          AccountControlUiOwnerSourcesFixture.withPeer(
              AccountControlUiOwnerSourcesFixture.CALLER)) {
        int failures = 0;
        for (String secret : java.util.List.of("incorrect-password", "incorrect-otp")) {
          var request =
              new AccountControlUiIssuanceService.AccountSignInRequest(
                  UUID.randomUUID(),
                  account.getEmail(),
                  secret,
                  UUID.randomUUID(),
                  new SourceContext("192.0.2.41"));
          assertInvalidCredentials(() -> fixture.service.issueAccount(request));
          failures++;
          assertThat(
                  fixture
                      .challenges
                      .findByAccountId(account.getId())
                      .orElseThrow()
                      .getInvalidAttemptCount())
              .isEqualTo(failures);
          assertThat(challengeContent(fixture, account)).isEqualTo(challengeBefore);
          assertThat(retainedOwners(fixture)).isEqualTo(ownersBefore);
          assertNoMembership(fixture, account);
          fixture.assertNoIssuance();

          var keys = inspector.sync().keys(AccountCredentialAbuseSubjectKeys.PREFIX + "subject:*");
          keys.removeAll(oldKeys);
          // The fixture uses a unique policy scope; only this new attempt creates these buckets.
          // Sum across retained windows so a wall-clock window boundary cannot lose accounting.
          for (String subject : java.util.List.of("source", "candidate")) {
            var subjectKeys =
                keys.stream().filter(key -> key.contains(":" + subject + ":")).toList();
            assertThat(subjectKeys).isNotEmpty();
            long actualFailures = 0;
            for (String key : subjectKeys) {
              String[] fields = inspector.sync().get(key).split("\\|", -1);
              assertThat(fields).hasSize(4);
              assertThat(fields[0]).isEqualTo("v1");
              assertThat(fields[3]).isEqualTo("0000000000000000");
              actualFailures += Long.parseLong(fields[2]);
              assertThat(inspector.sync().pttl(key)).isPositive();
            }
            assertThat(actualFailures).isEqualTo(failures);
          }
        }
      }
    } finally {
      admin.shutdown();
    }
  }

  private static void assertInvalidCredentials(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(AuthenticationException.class)
        .satisfies(
            failure ->
                assertThat(((AuthenticationException) failure).getCode())
                    .isEqualTo("AUTH_INVALID_CREDENTIALS"));
  }

  private static java.util.Map<String, Object> challengeContent(
      AccountControlUiOwnerSourcesFixture fixture, Account account) {
    var row =
        Objects.requireNonNull(
                fixture.dsl.fetchOne(
                    "SELECT * FROM account_email_login_challenge WHERE account_id = ?",
                    account.getId()))
            .intoMap();
    // These two fields are the intended, committed failed-attempt change.
    row.remove("invalid_attempt_count");
    row.remove("updated_at");
    return row;
  }

  private static java.util.Map<String, java.util.List<java.util.Map<String, Object>>>
      retainedOwners(AccountControlUiOwnerSourcesFixture fixture) {
    var result =
        new java.util.LinkedHashMap<String, java.util.List<java.util.Map<String, Object>>>();
    for (String table :
        java.util.List.of(
            "accounts",
            "account_global_role_sources",
            "account_authority_generations",
            "account_authority_issuance_fences",
            "account_authority_outbox_events",
            "account_platform_restriction_current_projections",
            "account_tenant_membership",
            "account_control_ui_issuance_operations",
            "account_control_ui_response_envelopes")) {
      result.put(
          table,
          fixture
              .dsl
              .fetch(
                  "SELECT to_jsonb(t)::text AS content, xmin::text AS row_version FROM "
                      + table
                      + " t ORDER BY to_jsonb(t)::text")
              .intoMaps());
    }
    return result;
  }

  private AccountControlUiOwnerSourcesFixture fixture() throws Exception {
    return new AccountControlUiOwnerSourcesFixture(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), temporary, true);
  }

  private AccountControlUiOwnerSourcesFixture fixture(AccountCredentialAbuseService credentialAbuse)
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
        credentialAbuse);
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
