package net.firedevops.firemud.accountservice.service.session;

import static net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseCacheFixture.BackendPhase.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.service.auth.*;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec.SourceContext;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Physical transaction boundaries only; no production registration or policy delivery. */
@Testcontainers
@Timeout(180)
@SuppressWarnings("resource")
class AccountCredentialAbuseTransactionPostgresIntegrationTest {
  private static final Network NETWORK = Network.newNetwork();
  private static final String PASSWORD = UUID.randomUUID().toString();

  @Container
  static final PostgreSQLContainer<?> postgres =
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
  static final GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis())
          .withLabel("firemud.proof", "account-shared-abuse-fixtures")
          .withLabel(
              "firemud.proof.run", System.getenv().getOrDefault("GITHUB_RUN_ID", "local-unrun"))
          .withStartupTimeout(java.time.Duration.ofSeconds(30))
          .withCreateContainerCmdModifier(
              command ->
                  command
                      .getHostConfig()
                      .withMemory(256L * 1024 * 1024)
                      .withMemorySwap(256L * 1024 * 1024)
                      .withPidsLimit(128L))
          .withNetwork(NETWORK)
          .withNetworkAliases("positive-control-ui-primary")
          .withExposedPorts(6379)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--maxmemory",
              "64mb",
              "--maxmemory-policy",
              "noeviction",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always");

  @Container
  static final GenericContainer<?> replica =
      new GenericContainer<>(TestContainerImages.redis())
          .withLabel("firemud.proof", "account-shared-abuse-fixtures")
          .withLabel(
              "firemud.proof.run", System.getenv().getOrDefault("GITHUB_RUN_ID", "local-unrun"))
          .withStartupTimeout(java.time.Duration.ofSeconds(30))
          .withCreateContainerCmdModifier(
              command ->
                  command
                      .getHostConfig()
                      .withMemory(256L * 1024 * 1024)
                      .withMemorySwap(256L * 1024 * 1024)
                      .withPidsLimit(128L))
          .withNetwork(NETWORK)
          .dependsOn(redis)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--maxmemory",
              "64mb",
              "--maxmemory-policy",
              "noeviction",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always",
              "--replicaof",
              "positive-control-ui-primary",
              "6379");

  @Container
  static final GenericContainer<?> credentialCache =
      AccountCredentialAbuseCacheFixture.container()
          .withLabel("firemud.proof", "account-shared-abuse-fixtures");

  @org.junit.jupiter.api.AfterAll
  static void closeOwnedNetwork() {
    credentialCache.stop();
    replica.stop();
    redis.stop();
    postgres.stop();
    NETWORK.close();
  }

  @TempDir Path temporary;

  private static final String OTP = "483927";

  private AccountControlUiOriginalOrderFixture owners() throws Exception {
    return new AccountControlUiOriginalOrderFixture(
        postgres.getJdbcUrl(),
        postgres.getUsername(),
        postgres.getPassword(),
        redis.getHost(),
        redis.getMappedPort(6379),
        temporary);
  }

  private static AccountControlUiIssuanceService.AccountSignInRequest request(
      Account account, String secret) {
    return new AccountControlUiIssuanceService.AccountSignInRequest(
        UUID.randomUUID(),
        account.getEmail(),
        secret,
        UUID.randomUUID(),
        new SourceContext("192.0.2.10"));
  }

  private static String issue(
      AccountControlUiIssuanceService issuer,
      AccountControlUiIssuanceService.AccountSignInRequest request)
      throws Exception {
    try (var peer =
            AccountControlUiOwnerSourcesFixture.withPeer(
                AccountControlUiOwnerSourcesFixture.CALLER);
        var result = issuer.issueAccount(request)) {
      return new String(result.compactBytes(), StandardCharsets.US_ASCII);
    }
  }

  private static int rows(AccountControlUiOwnerSourcesFixture f, Account account) {
    return f.dsl
        .fetchOne(
            "SELECT count(*) FROM account_control_ui_issuance_operations WHERE account_uuid = ?",
            account.getAccountUuid())
        .get(0, Integer.class);
  }

  private static void rendezvous(CyclicBarrier barrier) {
    try {
      barrier.await(1, TimeUnit.SECONDS);
    } catch (Exception failed) {
      throw new AssertionError("Concurrent admission rendezvous failed", failed);
    }
  }

  @Test
  void sameRequestConcurrentOtpSelectsOneOriginalOnExactRecovery() throws Exception {
    concurrentOtp(true);
  }

  @Test
  void differentRequestsConsumeOtpOnceAndRecordOneRealFailure() throws Exception {
    concurrentOtp(false);
  }

  private void concurrentOtp(boolean sameRequest) throws Exception {
    try (var cache = new AccountCredentialAbuseCacheFixture(credentialCache);
        var owners = owners()) {
      var f = owners.receivingSources();
      var account = owners.prepareReceivingAccount(PASSWORD);
      f.installGlobalOtp(account, OTP);
      var first = request(account, OTP);
      var second = sameRequest ? first : request(account, OTP);
      var barrier = new CyclicBarrier(2);
      var buckets = new AtomicReference<AccountCredentialAbuseService.Buckets>();
      var admissions = new AtomicInteger();
      var failures = new AtomicInteger();
      var issuer =
          owners.accountIssuer(
              cache.observedService(
                  event -> {
                    buckets.set(event.buckets());
                    if (event.phase() == AFTER_ADMISSION && admissions.incrementAndGet() <= 2)
                      rendezvous(barrier);
                    if (event.phase() == AFTER_FAILURE) failures.incrementAndGet();
                  }));
      var workers = Executors.newFixedThreadPool(2);
      try {
        Callable<Object> a = () -> outcome(issuer, first);
        Callable<Object> b = () -> outcome(issuer, second);
        var left = workers.submit(a);
        var right = workers.submit(b);
        var x = left.get(45, TimeUnit.SECONDS);
        var y = right.get(45, TimeUnit.SECONDS);
        assertThat(rows(f, account)).isEqualTo(1);
        assertThat(f.challenges.findByAccountId(account.getId())).isEmpty();
        if (sameRequest) {
          // A canonical in-flight refusal is not a second credential denial. Exact recovery
          // must return the one retained original after both finite calls have completed.
          assertThat(x instanceof String || y instanceof String).isTrue();
          for (var result : new Object[] {x, y}) {
            assertThat(
                    result instanceof String
                        || result instanceof AccountCredentialAbuseRefusal
                        || isInFlightUnavailable(result))
                .isTrue();
          }
          String original = x instanceof String ? (String) x : (String) y;
          assertThat(issue(issuer, first)).isEqualTo(original);
          if (x instanceof String && y instanceof String) assertThat(x).isEqualTo(y);
          assertThat(failures).hasValue(0);
        } else {
          assertThat((x instanceof String ? 1 : 0) + (y instanceof String ? 1 : 0)).isEqualTo(1);
          Object denial = x instanceof String ? y : x;
          assertThat(denial).isInstanceOf(AuthenticationException.class);
          assertThat(((AuthenticationException) denial).getCode())
              .isEqualTo("AUTH_INVALID_CREDENTIALS");
          assertThat(failures).hasValue(1);
          assertThat(cache.candidateImage(buckets.get()))
              .endsWith("|0000000000000001|0000000000000000");
        }
      } finally {
        workers.shutdownNow();
        assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  private static Object outcome(
      AccountControlUiIssuanceService issuer,
      AccountControlUiIssuanceService.AccountSignInRequest request)
      throws Exception {
    try {
      return issue(issuer, request);
    } catch (AuthenticationException | AccountCredentialAbuseRefusal expected) {
      return expected;
    } catch (IllegalStateException unavailable) {
      if (isInFlightUnavailable(unavailable)) return unavailable;
      throw unavailable;
    }
  }

  private static boolean isInFlightUnavailable(Object result) {
    return result instanceof IllegalStateException failure
        && "Control-ui original issuance/current recovery unavailable".equals(failure.getMessage());
  }

  @Test
  void remoteAdmissionReleasesSqlTransactionAndExactReplayLock() throws Exception {
    try (var cache = new AccountCredentialAbuseCacheFixture(credentialCache);
        var owners = owners()) {
      var f = owners.receivingSources();
      var account = owners.prepareReceivingAccount(PASSWORD);
      var original = f.installGlobalOtp(account, OTP);
      var request = request(account, OTP);
      var inspected = new AtomicInteger();
      var issuer =
          owners.accountIssuer(
              cache.observedService(
                  event -> {
                    if (event.phase() != BEFORE_ADMISSION) return;
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                        .isFalse();
                    // With no ambient transaction, this is a separate physical JDBC transaction.
                    f.tx(
                        () -> {
                          assertThat(
                                  f.dsl
                                      .fetchOne(
                                          "SELECT"
                                              + " pg_try_advisory_xact_lock(hashtextextended(?::text,"
                                              + " ?))",
                                          request.requestId().toString(),
                                          "control-ui-issuance-replay".hashCode())
                                      .get(0, Boolean.class))
                              .isTrue();
                          assertThat(
                                  f.challenges
                                      .findByAccountId(account.getId())
                                      .orElseThrow()
                                      .getId())
                              .isEqualTo(original.getId());
                          return null;
                        });
                    inspected.incrementAndGet();
                  }));
      assertThat(issue(issuer, request)).isNotBlank();
      assertThat(inspected).hasValue(1);
      assertThat(f.challenges.findByAccountId(account.getId())).isEmpty();
    }
  }

  @Test
  void committedLocalDenialSurvivesClosedCacheAndLostFailureReply() throws Exception {
    for (boolean disconnect : new boolean[] {true, false}) {
      try (var cache = new AccountCredentialAbuseCacheFixture(credentialCache);
          var owners = owners()) {
        var f = owners.receivingSources();
        var account = owners.prepareReceivingAccount(PASSWORD);
        var original = f.installGlobalOtp(account, OTP);
        var before = new AtomicInteger();
        var after = new AtomicInteger();
        var buckets = new AtomicReference<AccountCredentialAbuseService.Buckets>();
        var issuer =
            owners.accountIssuer(
                cache.observedService(
                    event -> {
                      if (event.phase() == BEFORE_FAILURE) {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                        var retained = f.challenges.findByAccountId(account.getId()).orElseThrow();
                        assertThat(retained.getInvalidAttemptCount()).isEqualTo(1);
                        assertThat(retained.getId()).isEqualTo(original.getId());
                        before.incrementAndGet();
                        if (disconnect) cache.disconnectApplication();
                      }
                      if (event.phase() == AFTER_FAILURE) {
                        buckets.set(event.buckets());
                        after.incrementAndGet();
                        throw new IllegalStateException(
                            "Controlled lost reply after real Redis commit");
                      }
                    }));
        assertThatThrownBy(() -> issue(issuer, request(account, "000000")))
            .isInstanceOf(AccountCredentialAbuseRefusal.class)
            .hasMessage("AUTH_ABUSE_CONTROL_UNAVAILABLE");
        assertThat(before).hasValue(1);
        assertThat(after).hasValue(disconnect ? 0 : 1);
        var retained = f.challenges.findByAccountId(account.getId()).orElseThrow();
        assertThat(retained.getCodeHash()).isEqualTo(original.getCodeHash());
        assertThat(retained.getInvalidAttemptCount()).isEqualTo(1);
        assertThat(rows(f, account)).isZero();
        if (!disconnect)
          assertThat(cache.candidateImage(buckets.get()))
              .endsWith("|0000000000000001|0000000000000000");
      }
    }
  }

  @Test
  void downstreamSqlFailureRestoresOtpWithoutCredentialFailureAccounting() throws Exception {
    try (var cache = new AccountCredentialAbuseCacheFixture(credentialCache);
        var owners = owners()) {
      var f = owners.receivingSources();
      var account = owners.prepareReceivingAccount(PASSWORD);
      var original = f.installGlobalOtp(account, OTP);
      var request = request(account, OTP);
      var failures = new AtomicInteger();
      var admissions = new AtomicInteger();
      var issuer =
          owners.accountIssuer(
              cache.observedService(
                  event -> {
                    if (event.phase() == BEFORE_FAILURE) failures.incrementAndGet();
                    if (event.phase() == AFTER_ADMISSION) admissions.incrementAndGet();
                  }));
      // Per-fixture schema; trigger rejects only this exact synthetic request after OTP
      // verification.
      f.dsl.execute(
          "CREATE FUNCTION abuse_proof_reject_prepare() RETURNS trigger LANGUAGE plpgsql AS $$"
              + " BEGIN IF NEW.request_id = '"
              + request.requestId()
              + "'::uuid THEN RAISE EXCEPTION 'controlled_abuse_prepare_failure'; END IF; RETURN"
              + " NEW; END $$");
      f.dsl.execute(
          "CREATE TRIGGER abuse_proof_reject_prepare BEFORE INSERT ON"
              + " account_control_ui_issuance_operations FOR EACH ROW EXECUTE FUNCTION"
              + " abuse_proof_reject_prepare()");
      try {
        assertThatThrownBy(() -> issue(issuer, request))
            .hasStackTraceContaining("controlled_abuse_prepare_failure");
        var retained = f.challenges.findByAccountId(account.getId()).orElseThrow();
        assertThat(retained.getId()).isEqualTo(original.getId());
        assertThat(retained.getCodeHash()).isEqualTo(original.getCodeHash());
        assertThat(retained.getInvalidAttemptCount()).isEqualTo(original.getInvalidAttemptCount());
        assertThat(rows(f, account)).isZero();
        assertThat(failures).hasValue(0);
        assertThat(admissions).hasValue(1);
      } finally {
        f.dsl.execute(
            "DROP TRIGGER abuse_proof_reject_prepare ON account_control_ui_issuance_operations");
        f.dsl.execute("DROP FUNCTION abuse_proof_reject_prepare()");
      }
      assertThat(issue(issuer, request)).isNotBlank();
      assertThat(f.challenges.findByAccountId(account.getId())).isEmpty();
      assertThat(rows(f, account)).isEqualTo(1);
      assertThat(failures).hasValue(0);
    }
  }
}
