package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.accountservice.repository.CanonicalHostingSubscriptionTestFixture;
import integration.net.firedevops.firemud.accountservice.repository.CanonicalHostingSubscriptionTestFixture.Fixture;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecyclePolicy.Status;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.Outcome;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.TransitionResult;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionIntentRepository.FenceRejectedException;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiOriginalOrderFixture.IssuedAccount;
import net.firedevops.firemud.accountservice.service.session.AccountHostingSubscriptionTerminalResolutionService.Input;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Coupled genuine Account issuance/current actor, primary authentication and PostgreSQL
 * cancellation proof. Policy/signer platform inputs and Stripe transport remain controlled.
 * Physical Redis primary/replica AOF acknowledgement executes; no deployed custody, provider
 * cancellation, public registration, transfer, provider absence or runtime activation is
 * established.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountHostingSubscriptionTerminalResolutionCompositionPostgresIntegrationTest {
  private static final String PASSWORD = "test-only-terminal-resolution-password";
  private static final String OTP = "test-only-terminal-resolution-otp";
  private static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("terminal-resolution-primary")
          .withExposedPorts(6379)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always");

  @Container
  static final GenericContainer<?> replica =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .dependsOn(redis)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always",
              "--replicaof",
              "terminal-resolution-primary",
              "6379");

  @TempDir Path temporary;

  @Test
  void actualAccountAndFreshOtpCommitOneAtomicCancellationAndAuthenticatedReplay()
      throws Exception {
    try (var accountOwners = accountOwners()) {
      var failed = failedHosting(accountOwners);
      var issued = failed.issued();
      var hosting = failed.hosting();
      var actor = issued.actors().authenticateAccount(issued.compact());
      var issuance =
          hosting
              .dsl()
              .fetchSingle(
                  "SELECT * FROM account_control_ui_issuance_operations WHERE operation_id = ?",
                  actor.issuanceOperationId());
      assertThat(issuance.get("authorization_scope", String.class)).isEqualTo("ACCOUNT");
      assertThat(issuance.get("tenant_uuid", UUID.class)).isNull();
      assertThat(issuance.get("account_uuid", UUID.class)).isEqualTo(hosting.accountUuid());
      assertThat(issuance.get("status", String.class)).isEqualTo("COMMITTED");
      assertThat(issuance.get("token_hash", String.class)).isEqualTo(actor.tokenHash());
      assertThat(hosting.accountId()).isEqualTo(issued.account().getId());
      assertThat(hosting.accountUuid()).isEqualTo(actor.accountId());
      assertThat(
              hosting
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) FROM account_tenant_membership WHERE account_id = ? AND tenant_uuid = ?",
                      hosting.accountId(),
                      hosting.tenantId())
                  .get(0, Long.class))
          .isZero();

      issued.sources().installGlobalOtp(issued.account(), OTP);
      long auditBefore = count(hosting, "account_audit_outbox");
      int readsBefore = hosting.transport().reads();
      var result = resolve(failed, issued.account().getEmail(), OTP);
      assertThat(result.outcome()).isEqualTo(Outcome.COMMITTED);
      assertThat(hosting.transport().reads()).isEqualTo(readsBefore + 1);
      assertThat(issued.sources().challenges.findByAccountId(hosting.accountId())).isEmpty();
      assertCommittedCancellation(failed, result);
      assertThat(count(hosting, "account_audit_outbox")).isEqualTo(auditBefore + 1);
      assertThat(failureImage(failed)).isEqualTo(failed.failureImage());
      assertThat(hosting.service().readCurrent(hosting.tenantId()).subscriptionStatus())
          .isEqualTo(Status.CANCELED);

      var committed = financialImage(failed);
      int readsAfterCommit = hosting.transport().reads();
      // Exact replay authenticates the real current actor but needs neither OTP nor another SDK
      // read.
      assertThat(service(failed).resolve(issued.compact(), null, null, failed.input()))
          .isEqualTo(result);
      assertThat(hosting.transport().reads()).isEqualTo(readsAfterCommit);
      assertThat(financialImage(failed)).isEqualTo(committed);
      assertThat(failureImage(failed)).isEqualTo(failed.failureImage());
    }
  }

  @Test
  void wrongPrimaryCredentialRetainsFinancialFenceAndUnconsumedOtp() throws Exception {
    try (var accountOwners = accountOwners()) {
      var failed = failedHosting(accountOwners);
      var issued = failed.issued();
      var challenge = issued.sources().installGlobalOtp(issued.account(), OTP);
      var before = financialImage(failed);
      assertThatThrownBy(
              () -> resolve(failed, issued.account().getEmail(), "wrong-test-credential"))
          .isInstanceOfSatisfying(
              AuthenticationException.class,
              failure -> assertThat(failure.getCode()).isEqualTo("AUTH_INVALID_CREDENTIALS"));
      assertThat(financialImage(failed)).isEqualTo(before);
      assertThat(failureImage(failed)).isEqualTo(failed.failureImage());
      // Real failed-login accounting may advance; it must not consume the challenge.
      assertThat(issued.sources().challenges.findByAccountId(failed.hosting().accountId()))
          .isPresent()
          .get()
          .extracting(value -> value.getId())
          .isEqualTo(challenge.getId());
    }
  }

  @Test
  void lateBillingSqlFailureRollsBackCancellationAndOtpThenSameOtpSucceeds() throws Exception {
    try (var accountOwners = accountOwners()) {
      var failed = failedHosting(accountOwners);
      var issued = failed.issued();
      var hosting = failed.hosting();
      var challenge = issued.sources().installGlobalOtp(issued.account(), OTP);
      var before = financialImage(failed);
      hosting
          .dsl()
          .execute(
              "CREATE FUNCTION reject_resolution_billing() RETURNS trigger LANGUAGE plpgsql AS $$ "
                  + "BEGIN RAISE EXCEPTION 'controlled resolution billing failure' USING ERRCODE = '23514'; END; $$");
      hosting
          .dsl()
          .execute(
              "CREATE TRIGGER reject_resolution_billing BEFORE INSERT ON account_tenant_entitlement_outbox_events "
                  + "FOR EACH ROW EXECUTE FUNCTION reject_resolution_billing()");
      try {
        assertThatThrownBy(() -> resolve(failed, issued.account().getEmail(), OTP))
            .hasStackTraceContaining("controlled resolution billing failure");
        assertThat(financialImage(failed)).isEqualTo(before);
        assertThat(failureImage(failed)).isEqualTo(failed.failureImage());
        assertThat(issued.sources().challenges.findByAccountId(hosting.accountId()))
            .contains(challenge);
      } finally {
        hosting
            .dsl()
            .execute(
                "DROP TRIGGER reject_resolution_billing ON account_tenant_entitlement_outbox_events");
        hosting.dsl().execute("DROP FUNCTION reject_resolution_billing()");
      }
      var result = resolve(failed, issued.account().getEmail(), OTP);
      assertCommittedCancellation(failed, result);
      assertThat(issued.sources().challenges.findByAccountId(hosting.accountId())).isEmpty();
      assertThat(failureImage(failed)).isEqualTo(failed.failureImage());
    }
  }

  @Test
  void missingFailedOperationCopyRejectsBeforeCurrentCasAndRollsBackAuditJournalAndOtp()
      throws Exception {
    try (var accountOwners = accountOwners()) {
      var failed = failedHosting(accountOwners);
      var issued = failed.issued();
      var hosting = failed.hosting();
      var challenge = issued.sources().installGlobalOtp(issued.account(), OTP);
      var before = financialImage(failed);
      hosting
          .dsl()
          .execute(
              "CREATE FUNCTION suppress_resolution_operation() RETURNS trigger LANGUAGE plpgsql AS $$ "
                  + "BEGIN RETURN NULL; END; $$");
      hosting
          .dsl()
          .execute(
              "CREATE TRIGGER suppress_resolution_operation BEFORE INSERT ON subscription_lifecycle_operations "
                  + "FOR EACH ROW WHEN (NEW.request_id = '"
                  + failed.input().resolutionRequestId()
                  + "'::uuid) EXECUTE FUNCTION suppress_resolution_operation()");
      try {
        // A controlled row trigger makes INSERT...SELECT affect zero rows despite the locked
        // original still existing. The exact copy must fail before the current-row CAS.
        assertThatThrownBy(() -> resolve(failed, issued.account().getEmail(), OTP))
            .isExactlyInstanceOf(FenceRejectedException.class)
            .hasMessage("Terminal resolution exact failed-operation copy rejected");
        assertThat(financialImage(failed)).isEqualTo(before);
        assertThat(failureImage(failed)).isEqualTo(failed.failureImage());
        assertThat(issued.sources().challenges.findByAccountId(hosting.accountId()))
            .contains(challenge);
      } finally {
        hosting
            .dsl()
            .execute(
                "DROP TRIGGER suppress_resolution_operation ON subscription_lifecycle_operations");
        hosting.dsl().execute("DROP FUNCTION suppress_resolution_operation()");
      }
      var result = resolve(failed, issued.account().getEmail(), OTP);
      assertCommittedCancellation(failed, result);
      assertThat(issued.sources().challenges.findByAccountId(hosting.accountId())).isEmpty();
      assertThat(failureImage(failed)).isEqualTo(failed.failureImage());
    }
  }

  @Test
  void genericAccountAuthorityAdvanceDuringSdkReadMakesSourceUnavailableAndKeepsOtp()
      throws Exception {
    try (var accountOwners = accountOwners()) {
      var failed = failedHosting(accountOwners);
      var issued = failed.issued();
      var hosting = failed.hosting();
      var challenge = issued.sources().installGlobalOtp(issued.account(), OTP);
      var before = financialImage(failed);
      var generations = new AccountAuthorityGenerationRepository(hosting.dsl());
      var authorityBefore =
          issued
              .sources()
              .tx(() -> generations.read(AuthorityScope.account(hosting.accountUuid())));
      hosting
          .transport()
          .duringRead(
              () ->
                  issued
                      .sources()
                      .tx(
                          () -> {
                            var account =
                                issued
                                    .sources()
                                    .accounts
                                    .findById(hosting.accountId())
                                    .orElseThrow();
                            account.setRole("admin");
                            // Generic authority events lack the closed operation receipt required
                            // by current-source readback, so this advance must fail closed there.
                            return issued.sources().accounts.save(account);
                          }));
      assertThatThrownBy(() -> resolve(failed, issued.account().getEmail(), OTP))
          .isExactlyInstanceOf(SourceEvidenceUnavailableException.class)
          .hasMessage("Account authority source evidence is unavailable or inconsistent");
      var authorityAfter =
          issued
              .sources()
              .tx(() -> generations.read(AuthorityScope.account(hosting.accountUuid())));
      assertThat(authorityAfter.generation()).isGreaterThan(authorityBefore.generation());
      assertThat(authorityAfter.issuanceFence().value())
          .isGreaterThan(authorityBefore.issuanceFence().value());
      // The deliberately changed ACCOUNT authority/source rows are outside this financial image.
      assertThat(financialImage(failed)).isEqualTo(before);
      assertThat(failureImage(failed)).isEqualTo(failed.failureImage());
      assertThat(issued.sources().challenges.findByAccountId(hosting.accountId()))
          .contains(challenge);
    }
  }

  @Test
  void otherCertifiedAccountsFreshOtpCannotAuthorizeBillingOwnerAndConsumptionRollsBack()
      throws Exception {
    try (var accountOwners = accountOwners()) {
      var failed = failedHosting(accountOwners);
      var other = accountOwners.issueCertifiedAccount("test-only-other-account-password");
      assertThat(other.actors().authenticateAccount(other.compact()).accountId())
          .isNotEqualTo(failed.hosting().accountUuid());
      var challenge = other.sources().installGlobalOtp(other.account(), OTP);
      var before = financialImage(failed);
      assertThatThrownBy(() -> resolve(failed, other.account().getEmail(), OTP))
          .isInstanceOf(FenceRejectedException.class)
          .hasMessageContaining("another Account");
      assertThat(financialImage(failed)).isEqualTo(before);
      assertThat(failureImage(failed)).isEqualTo(failed.failureImage());
      assertThat(other.sources().challenges.findByAccountId(other.account().getId()))
          .contains(challenge);
    }
  }

  private AccountControlUiOriginalOrderFixture accountOwners() throws Exception {
    return new AccountControlUiOriginalOrderFixture(
        postgres.getJdbcUrl(),
        postgres.getUsername(),
        postgres.getPassword(),
        redis.getHost(),
        redis.getMappedPort(6379),
        temporary);
  }

  private static FailedHosting failedHosting(AccountControlUiOriginalOrderFixture accountOwners) {
    var issued = accountOwners.issueCertifiedAccount(PASSWORD);
    var hosting =
        CanonicalHostingSubscriptionTestFixture.create(
            Objects.requireNonNull(issued.sources().manager.getDataSource()), issued.account());
    var claim =
        Objects.requireNonNull(
            hosting
                .service()
                .claimProvisioning(hosting.creation().requestId(), 1, UUID.randomUUID())
                .claim());
    UUID failedRequest = UUID.randomUUID();
    hosting.providerStatus("incomplete");
    Outcome outcome = null;
    // This shared fixture owns a four-attempt/three-stall bound; retain one original operation.
    for (int attempt = 0; attempt < 4; attempt++) {
      outcome =
          hosting
              .service()
              .reconcile(
                  failedRequest,
                  hosting.creation().requestId(),
                  claim.subscriptionRowVersion(),
                  hosting.providerId(),
                  claim.attemptId())
              .outcome();
      if (outcome == Outcome.TERMINAL_FAILURE) break;
      assertThat(outcome).isEqualTo(Outcome.RECONCILING);
      hosting.clock().advance(Duration.ofSeconds(2));
    }
    assertThat(outcome).isEqualTo(Outcome.TERMINAL_FAILURE);
    long failedVersion =
        hosting
            .dsl()
            .fetchSingle(
                "SELECT row_version FROM subscription_lifecycle_operations WHERE request_id = ?",
                failedRequest)
            .get(0, Long.class);
    long subscriptionVersion =
        hosting.intents().findCurrentByTenant(hosting.tenantId()).orElseThrow().rowVersion();
    var input =
        new Input(
            UUID.randomUUID(),
            failedRequest,
            hosting.creation().requestId(),
            hosting.creation().subscriptionId(),
            hosting.tenantId(),
            failedVersion,
            subscriptionVersion,
            UUID.randomUUID(),
            "Same-owner recovery from independently observed terminal cancellation");
    hosting.providerStatus("canceled");
    var failed = new FailedHosting(issued, hosting, input, null);
    return new FailedHosting(issued, hosting, input, failureImage(failed));
  }

  private static AccountHostingSubscriptionTerminalResolutionService service(FailedHosting failed) {
    return new AccountHostingSubscriptionTerminalResolutionService(
        failed.issued().actors(),
        failed.issued().sources().primary,
        failed.hosting().lifecycleRepository(),
        failed.hosting().observer(),
        failed.hosting().clock());
  }

  private static TransitionResult resolve(FailedHosting failed, String email, String secret) {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    return service(failed).resolve(failed.issued().compact(), email, secret, failed.input());
  }

  private static void assertCommittedCancellation(FailedHosting failed, TransitionResult result) {
    var hosting = failed.hosting();
    var input = failed.input();
    assertThat(result.outcome()).isEqualTo(Outcome.COMMITTED);
    assertThat(result.committedRowVersion()).isEqualTo(input.expectedSubscriptionVersion() + 2);
    var actor = failed.issued().actors().authenticateAccount(failed.issued().compact());
    var journal =
        hosting
            .dsl()
            .fetchSingle(
                "SELECT *, xmin::text AS row_xmin FROM subscription_lifecycle_terminal_resolutions "
                    + "WHERE resolution_request_id = ?",
                input.resolutionRequestId());
    assertThat(journal.get("actor_account_id", UUID.class)).isEqualTo(actor.accountId());
    assertThat(journal.get("control_ui_issuance_operation_id", UUID.class))
        .isEqualTo(actor.issuanceOperationId());
    assertThat(journal.get("control_ui_token_hash", String.class)).isEqualTo(actor.tokenHash());
    assertThat(journal.get("authorization_outcome", String.class))
        .isEqualTo("PRIMARY_AUTHENTICATED");
    assertThat(journal.get("state", String.class)).isEqualTo("COMMITTED");
    assertThat(journal.get("authenticated_at", OffsetDateTime.class).toInstant())
        .isEqualTo(hosting.clock().instant());
    var receipt =
        hosting
            .dsl()
            .fetchSingle(
                "SELECT *, xmin::text AS row_xmin FROM subscription_lifecycle_receipts WHERE request_id = ?",
                input.resolutionRequestId());
    assertThat(receipt.get("status", String.class)).isEqualTo("canceled");
    assertThat(receipt.get("receipt_digest", String.class))
        .isEqualTo(journal.get("receipt_digest"));
    assertThat(receipt.get("subscription_row_version", Long.class))
        .isEqualTo(result.committedRowVersion());
    assertThat(receipt.get("committed_transaction_id", Long.class))
        .isEqualTo(journal.get("created_transaction_id", Long.class));
    var subscription =
        hosting
            .dsl()
            .fetchSingle(
                "SELECT *, xmin::text AS row_xmin FROM subscription WHERE subscription_id = ?",
                input.subscriptionId());
    assertThat(subscription.get("status", String.class)).isEqualTo("canceled");
    assertThat(subscription.get("lifecycle_inflight_request_id", UUID.class)).isNull();
    assertThat(subscription.get("current_receipt_request_id", UUID.class))
        .isEqualTo(input.resolutionRequestId());
    assertThat(subscription.get("row_version", Long.class)).isEqualTo(result.committedRowVersion());
    var reservation =
        hosting
            .dsl()
            .fetchSingle(
                "SELECT *, xmin::text AS row_xmin FROM subscription_instrument_reservations WHERE request_id = ?",
                input.creationRequestId());
    assertThat(reservation.get("released_at")).isNotNull();
    assertThat(reservation.get("terminal_resolution_id", UUID.class))
        .isEqualTo(subscription.get("terminal_resolution_id", UUID.class));
    var terminal =
        hosting
            .dsl()
            .fetchSingle(
                "SELECT *, xmin::text AS row_xmin FROM subscription_provider_terminal_resolutions WHERE resolution_id = ?",
                subscription.get("terminal_resolution_id", UUID.class));
    assertThat(terminal.get("outcome", String.class)).isEqualTo("CANCELED");
    assertThat(terminal.get("provider_subscription_id", String.class))
        .isEqualTo(hosting.providerId());
    var audit =
        hosting
            .dsl()
            .fetchSingle(
                "SELECT *, xmin::text AS row_xmin FROM account_audit_outbox WHERE audit_event_id = ?",
                input.auditEventId());
    assertThat(audit.get("event_type", String.class))
        .isEqualTo("HOSTING_SUBSCRIPTION_TERMINAL_RESOLVED");
    assertThat(audit.get("payload_digest", String.class)).isEqualTo(journal.get("request_digest"));
    var billing =
        hosting
            .dsl()
            .fetchSingle(
                "SELECT *, xmin::text AS row_xmin FROM account_tenant_entitlement_outbox_events "
                    + "WHERE tenant_uuid = ? AND request_id = ?",
                hosting.tenantId(),
                input.resolutionRequestId());
    assertThat(billing.get("event_id", UUID.class))
        .isEqualTo(receipt.get("billing_event_id", UUID.class));
    assertThat(billing.get("event_digest", String.class))
        .isEqualTo(receipt.get("billing_event_digest"));
    var authority =
        hosting
            .dsl()
            .fetchSingle(
                "SELECT *, xmin::text AS row_xmin FROM account_authority_outbox_events "
                    + "WHERE outbox_stream_key = ? AND outbox_sequence = ?",
                TenantAuthorityEventV1Codec.streamKey(hosting.tenantId()),
                receipt.get("authority_sequence", Long.class));
    var event = TenantAuthorityEventV1Codec.verify(authority.get("payload", byte[].class));
    assertThat(event.eventDigest()).isEqualTo(receipt.get("authority_event_digest", String.class));
    assertThat(event.tenantBillingSequence())
        .isEqualTo(receipt.get("billing_sequence", Long.class));
    for (var row :
        List.of(receipt, subscription, reservation, terminal, audit, billing, authority)) {
      assertThat(row.get("row_xmin", String.class))
          .isEqualTo(journal.get("row_xmin", String.class));
    }
    assertThat(count(hosting, "subscription_lifecycle_terminal_resolutions")).isOne();
    assertThat(count(hosting, "subscription_lifecycle_receipts")).isOne();
    assertThat(count(hosting, "subscription_provider_terminal_resolutions")).isOne();
  }

  private static long count(Fixture hosting, String table) {
    return hosting.dsl().fetchSingle("SELECT count(*) FROM " + table).get(0, Long.class);
  }

  private static String failureImage(FailedHosting failed) {
    return failed
        .hosting()
        .dsl()
        .fetchSingle(
            "SELECT to_jsonb(operation)::text || operation.xmin::text || to_jsonb(notice)::text || notice.xmin::text "
                + "FROM subscription_lifecycle_operations operation JOIN subscription_lifecycle_operator_outbox notice "
                + "USING (request_id) WHERE request_id = ?",
            failed.input().failedRequestId())
        .get(0, String.class);
  }

  private static Map<String, String> financialImage(FailedHosting failed) {
    Map<String, String> image = new LinkedHashMap<>();
    DSLContext dsl = failed.hosting().dsl();
    for (String table :
        List.of(
            "subscription",
            "subscription_current_observations",
            "subscription_provisioning_work",
            "subscription_lifecycle_operations",
            "subscription_lifecycle_attempts",
            "subscription_lifecycle_observations",
            "subscription_lifecycle_receipts",
            "subscription_lifecycle_terminal_resolutions",
            "subscription_lifecycle_operator_outbox",
            "subscription_provider_history",
            "subscription_provider_terminal_resolutions",
            "subscription_instrument_reservations",
            "account_audit_outbox",
            "account_tenant_entitlement_outbox_events",
            "account_tenant_entitlement_outbox_streams",
            "account_tenant_authority_source_records")) {
      image.put(table, rows(dsl, table, ""));
    }
    image.put(
        "tenant_generations",
        rows(dsl, "account_authority_generations", " WHERE scope_kind = 'TENANT'"));
    String stream = TenantAuthorityEventV1Codec.streamKey(failed.hosting().tenantId());
    for (String table :
        List.of("account_authority_outbox_events", "account_authority_outbox_streams")) {
      image.put(
          table,
          dsl.fetchSingle(
                  "SELECT coalesce(jsonb_agg(snapshot ORDER BY snapshot::text)::text, '[]') FROM "
                      + "(SELECT to_jsonb(value) || jsonb_build_object('xmin',value.xmin::text) AS snapshot FROM "
                      + table
                      + " value WHERE outbox_stream_key = ?) retained",
                  stream)
              .get(0, String.class));
    }
    return image;
  }

  private static String rows(DSLContext dsl, String table, String where) {
    return dsl.fetchSingle(
            "SELECT coalesce(jsonb_agg(snapshot ORDER BY snapshot::text)::text, '[]') FROM "
                + "(SELECT to_jsonb(value) || jsonb_build_object('xmin',value.xmin::text) AS snapshot FROM "
                + table
                + " value"
                + where
                + ") retained")
        .get(0, String.class);
  }

  private record FailedHosting(
      IssuedAccount issued, Fixture hosting, Input input, String failureImage) {}
}
