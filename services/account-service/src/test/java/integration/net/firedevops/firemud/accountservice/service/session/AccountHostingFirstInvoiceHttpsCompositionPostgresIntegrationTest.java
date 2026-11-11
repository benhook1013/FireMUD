package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.accountservice.repository.CanonicalHostingSubscriptionTestFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import net.firedevops.firemud.accountservice.controller.AccountHostingFirstInvoiceController;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.StripeEventInboxRepository;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.TestContainerImages;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Actual HTTPS/MVC to genuine Account control-ui/current primary authentication and PostgreSQL.
 * Test signer/policy/custody and Stripe SDK transport are controlled. No live charge, registration
 * or installed custody is established. This definition requires physical Docker execution.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountHostingFirstInvoiceHttpsCompositionPostgresIntegrationTest {
  private static final String PASSWORD = "test-only-first-invoice-password";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("first-invoice-primary")
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
              "first-invoice-primary",
              "6379");

  @TempDir Path temporary;

  @Test
  void actualHttpsOwnerConsentScaAndExactSignedCompletionGrantAuthorityOnlyAfterReadback()
      throws Exception {
    try (var owners =
        new AccountControlUiOriginalOrderFixture(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            redis.getHost(),
            redis.getMappedPort(6379),
            temporary)) {
      var issued = owners.issueCertifiedAccount(PASSWORD);
      var hosting =
          CanonicalHostingSubscriptionTestFixture.createAwaitingPayment(
              Objects.requireNonNull(issued.sources().manager.getDataSource()), issued.account());
      var reauthentication =
          new AccountRecentReauthenticationService(
              issued.sources().primary,
              issued.sources().accounts,
              new AccountAuthorityGenerationRepository(hosting.dsl()),
              new AccountRecentReauthenticationRepository(hosting.dsl()),
              new AccountRecentReauthenticationPolicyRepository(hosting.dsl()),
              issued.sources().manager,
              hosting.clock(),
              new AccountControlUiResponseCryptography(
                  new AccountControlUiKeyring(temporary.resolve("custody")), hosting.clock()));
      var payments =
          new AccountHostingFirstInvoicePaymentService(
              issued.actors(),
              reauthentication,
              hosting.firstInvoiceRepository(),
              new StripeEventInboxRepository(hosting.dsl()),
              hosting.firstInvoiceProvider(),
              hosting.webhookVerifier(),
              hosting.service(),
              issued.sources().manager);
      var renewalGate = new OwnerEntryGate("/first-invoice/renew");
      try (var https =
              new HttpsReceiver(
                  temporary, new AccountHostingFirstInvoiceController(payments), renewalGate);
          var requestExecutor = new TestRequestExecutor();
          var releaseRenewalGate = renewalGate) {
        String path =
            "/billing/hosting/subscriptions/"
                + hosting.creation().subscriptionId()
                + "/first-invoice";
        var unauthenticated = https.request("GET", path, null, null);
        assertThat(unauthenticated.statusCode()).isEqualTo(401);
        assertThat(unauthenticated.body()).doesNotContain("secret_", PASSWORD);
        var owner = https.request("GET", path, issued.compact(), null);
        assertThat(owner.statusCode()).isEqualTo(200);
        assertThat(owner.headers().firstValue("cache-control")).contains("no-store");
        JsonNode tuple = JSON.readTree(owner.body());
        assertThat(tuple.path("state").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(tuple.path("amount").asText()).isEqualTo("2400");
        assertThat(tuple.path("currency").asText()).isEqualTo("usd");
        assertThat(tuple.path("confirmationRequestId").isNull()).isTrue();
        assertNoReceipt(hosting);

        UUID confirmation = UUID.randomUUID();
        var unboundProof =
            https.request(
                "POST",
                path + "/reauthenticate",
                issued.compact(),
                Map.of(
                    "requestId",
                    UUID.randomUUID(),
                    "confirmationRequestId",
                    confirmation,
                    "email",
                    issued.account().getEmail(),
                    "secret",
                    PASSWORD));
        assertThat(unboundProof.statusCode()).isEqualTo(409);
        assertThat(count(hosting, "SELECT count(*) FROM account_recent_reauthentication_evidence"))
            .isZero();
        assertThat(
                count(hosting, "SELECT count(*) FROM subscription_first_invoice_operation_fences"))
            .isZero();
        var proof =
            https.request(
                "POST",
                path + "/reauthenticate",
                issued.compact(),
                billingReauthenticationRequest(
                    tuple,
                    UUID.randomUUID(),
                    confirmation,
                    confirmation,
                    0L,
                    "INITIAL_CONFIRMATION",
                    issued.account().getEmail()));
        assertThat(proof.statusCode()).isEqualTo(200);
        UUID evidence = UUID.fromString(JSON.readTree(proof.body()).path("evidenceId").asText());
        assertThat(
                hosting
                    .dsl()
                    .fetchSingle(
                        "SELECT purpose FROM account_recent_reauthentication_evidence WHERE evidence_id = ?",
                        evidence)
                    .get(0, String.class))
            .isEqualTo(AccountRecentReauthenticationPolicy.BILLING_PURPOSE);
        UUID competingAuthorization = UUID.randomUUID();
        var competingProof =
            https.request(
                "POST",
                path + "/reauthenticate",
                issued.compact(),
                billingReauthenticationRequest(
                    tuple,
                    UUID.randomUUID(),
                    competingAuthorization,
                    confirmation,
                    0L,
                    "PRE_ROW_RENEWAL",
                    issued.account().getEmail()));
        assertThat(competingProof.statusCode()).isEqualTo(200);
        UUID competingEvidence =
            UUID.fromString(JSON.readTree(competingProof.body()).path("evidenceId").asText());
        Map<String, Object> consent =
            Map.of(
                "requestId",
                confirmation,
                "expectedRowVersion",
                tuple.path("expectedRowVersion").asLong(),
                "reauthenticationEvidenceId",
                evidence,
                "acceptedTupleDigest",
                tuple.path("tupleDigest").asText(),
                "acceptCharge",
                true);
        Map<String, Object> stalePreRowRenewal =
            Map.of(
                "requestId",
                confirmation,
                "authorizationRequestId",
                competingAuthorization,
                "expectedRowVersion",
                tuple.path("expectedRowVersion").asLong(),
                "expectedAuthorizationVersion",
                0L,
                "reauthenticationEvidenceId",
                competingEvidence,
                "acceptedTupleDigest",
                tuple.path("tupleDigest").asText(),
                "acceptCharge",
                true);
        var delayedPreRowRenewal =
            asyncRequest(
                https,
                "POST",
                path + "/renew",
                issued.compact(),
                stalePreRowRenewal,
                null,
                requestExecutor);
        assertThat(renewalGate.arrived.await(30, TimeUnit.SECONDS)).isTrue();
        hosting.firstInvoiceConfirmationStatus("requires_action");
        var openingEvidenceBefore =
            hosting
                .dsl()
                .fetchSingle(
                    "SELECT encode(request_payload, 'hex') AS request_payload_hex, request_digest, "
                        + "billing_context_digest, xmin::text AS row_xmin "
                        + "FROM account_recent_reauthentication_evidence WHERE evidence_id = ?",
                    evidence);
        hosting
            .dsl()
            .execute(
                "CREATE FUNCTION reject_first_invoice_authorization() RETURNS TRIGGER LANGUAGE plpgsql AS $$ "
                    + "BEGIN RAISE EXCEPTION 'controlled first-invoice authorization rollback' USING ERRCODE='23514'; END; $$");
        hosting
            .dsl()
            .execute(
                "CREATE TRIGGER reject_first_invoice_authorization BEFORE INSERT ON "
                    + "subscription_first_invoice_authorizations FOR EACH ROW "
                    + "EXECUTE FUNCTION reject_first_invoice_authorization()");
        try {
          assertThat(
                  https.request("POST", path + "/confirm", issued.compact(), consent).statusCode())
              .isEqualTo(500);
          assertThat(consumptionCount(hosting, evidence)).isZero();
          assertThat(
                  count(hosting, "SELECT count(*) FROM subscription_first_invoice_confirmations"))
              .isZero();
          assertThat(count(hosting, "SELECT count(*) FROM subscription_first_invoice_dispatches"))
              .isZero();
          assertThat(
                  count(hosting, "SELECT count(*) FROM subscription_first_invoice_authorizations"))
              .isZero();
          assertThat(
                  count(
                      hosting, "SELECT count(*) FROM subscription_first_invoice_operation_fences"))
              .isOne();
          assertNoReceipt(hosting);
          assertThat(
                  hosting
                      .dsl()
                      .fetchSingle(
                          "SELECT encode(request_payload, 'hex') AS request_payload_hex, request_digest, "
                              + "billing_context_digest, xmin::text AS row_xmin "
                              + "FROM account_recent_reauthentication_evidence WHERE evidence_id = ?",
                          evidence))
              .isEqualTo(openingEvidenceBefore);
        } finally {
          hosting
              .dsl()
              .execute(
                  "DROP TRIGGER reject_first_invoice_authorization ON subscription_first_invoice_authorizations");
          hosting.dsl().execute("DROP FUNCTION reject_first_invoice_authorization()");
        }
        hosting.loseNextFirstInvoiceConfirmationResponse();
        var uncertain = https.request("POST", path + "/confirm", issued.compact(), consent);
        assertThat(uncertain.statusCode()).isEqualTo(502);
        assertThat(uncertain.body()).contains("FIRST_INVOICE_PROVIDER_UNCERTAIN");
        assertThat(hosting.firstInvoiceConfirmations()).isEqualTo(1);
        assertThat(count(hosting, "SELECT count(*) FROM subscription_first_invoice_command_claims"))
            .isOne();
        var retryStart = new CountDownLatch(1);
        var exactRetryOne =
            asyncRequest(
                https,
                "POST",
                path + "/confirm",
                issued.compact(),
                consent,
                retryStart,
                requestExecutor);
        var exactRetryTwo =
            asyncRequest(
                https,
                "POST",
                path + "/confirm",
                issued.compact(),
                consent,
                retryStart,
                requestExecutor);
        retryStart.countDown();
        var handoff = exactRetryOne.get(60, TimeUnit.SECONDS);
        var duplicateHandoff = exactRetryTwo.get(60, TimeUnit.SECONDS);
        assertThat(handoff.statusCode()).isEqualTo(200);
        assertThat(duplicateHandoff.statusCode()).isEqualTo(200);
        assertThat(handoff.headers().firstValue("cache-control")).contains("no-store");
        assertThat(JSON.readTree(handoff.body()).path("status").asText())
            .isEqualTo("requires_action");
        assertThat(JSON.readTree(duplicateHandoff.body()).path("status").asText())
            .isEqualTo("requires_action");
        assertThat(JSON.readTree(handoff.body()).path("clientSecret").asText())
            .startsWith(hosting.firstInvoice().paymentIntentId() + "_secret_");
        assertThat(hosting.firstInvoiceConfirmations()).isEqualTo(1);
        assertThat(
                hosting
                    .dsl()
                    .fetchSingle(
                        "SELECT provider_idempotency_id FROM subscription_first_invoice_confirmations "
                            + "WHERE request_id = ?",
                        confirmation)
                    .get("provider_idempotency_id", UUID.class)
                    .toString())
            .isEqualTo(hosting.lastFirstInvoiceConfirmationIdempotencyKey());
        assertNoReceipt(hosting);
        releaseRenewalGate.releaseNow();
        assertThat(delayedPreRowRenewal.get(60, TimeUnit.SECONDS).statusCode()).isEqualTo(409);
        assertThat(consumptionCount(hosting, competingEvidence)).isZero();
        assertThat(consumptionCount(hosting, evidence)).isOne();
        UUID authorization = UUID.randomUUID();
        var renewedProof =
            https.request(
                "POST",
                path + "/reauthenticate",
                issued.compact(),
                billingReauthenticationRequest(
                    tuple,
                    UUID.randomUUID(),
                    authorization,
                    confirmation,
                    1L,
                    "FOLLOWUP_RENEWAL",
                    issued.account().getEmail()));
        assertThat(renewedProof.statusCode()).isEqualTo(200);
        Map<String, Object> renewal = new java.util.HashMap<>(consent);
        renewal.put("authorizationRequestId", authorization);
        renewal.put("expectedAuthorizationVersion", 1);
        renewal.put(
            "reauthenticationEvidenceId",
            JSON.readTree(renewedProof.body()).path("evidenceId").asText());
        var changed = new java.util.HashMap<>(renewal);
        changed.put("acceptedTupleDigest", "sha256:" + "c".repeat(64));
        assertThat(https.request("POST", path + "/renew", issued.compact(), changed).statusCode())
            .isEqualTo(409);
        var renewed = https.request("POST", path + "/renew", issued.compact(), renewal);
        assertThat(renewed.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(renewed.body()).path("status").asText())
            .isEqualTo("requires_action");
        assertThat(renewed.headers().firstValue("cache-control")).contains("no-store");
        assertThat(https.request("POST", path + "/renew", issued.compact(), renewal).statusCode())
            .isEqualTo(200);
        assertThat(hosting.firstInvoiceConfirmations()).isEqualTo(1);
        assertThat(
                hosting
                    .dsl()
                    .fetchSingle("SELECT count(*) FROM subscription_first_invoice_command_claims")
                    .get(0, Long.class))
            .isOne();
        assertThat(
                hosting
                    .dsl()
                    .fetchSingle("SELECT count(*) FROM subscription_first_invoice_authorizations")
                    .get(0, Long.class))
            .isEqualTo(2);
        // A newer explicit authorization invalidates older disclosure/command permissions.
        assertThat(https.request("POST", path + "/confirm", issued.compact(), consent).statusCode())
            .isEqualTo(409);
        assertThat(
                JSON.readTree(https.request("GET", path, issued.compact(), null).body())
                    .path("state")
                    .asText())
            .isEqualTo("AWAITING_PAYMENT");
        // Even provider success/browser return remains pending until exact signed completion.
        hosting.firstInvoiceStatus("succeeded");
        hosting.providerStatus("active");
        assertThat(
                JSON.readTree(https.request("GET", path, issued.compact(), null).body())
                    .path("state")
                    .asText())
            .isEqualTo("AWAITING_PAYMENT");
        assertNoReceipt(hosting);
        // Expired authorization cannot redisclose the secret or issue another payment command.
        hosting.clock().advance(Duration.ofMinutes(6));
        var expired = https.request("POST", path + "/confirm", issued.compact(), consent);
        assertThat(expired.statusCode()).isEqualTo(409);
        assertThat(expired.body()).doesNotContain("clientSecret", "secret_");
        assertThat(hosting.firstInvoiceConfirmations()).isEqualTo(1);
        String signedPayload = hosting.signedSuccessPayload();
        String signedHeader = hosting.signedSuccessHeader();
        long auditBefore =
            hosting
                .dsl()
                .fetchSingle("SELECT count(*) FROM account_audit_outbox")
                .get(0, Long.class);
        hosting
            .dsl()
            .execute(
                "CREATE FUNCTION reject_first_invoice_billing() RETURNS TRIGGER LANGUAGE plpgsql AS $$ "
                    + "BEGIN RAISE EXCEPTION 'controlled first-invoice billing rollback' USING ERRCODE='23514'; END; $$");
        hosting
            .dsl()
            .execute(
                "CREATE TRIGGER reject_first_invoice_billing BEFORE INSERT ON account_tenant_entitlement_outbox_events "
                    + "FOR EACH ROW EXECUTE FUNCTION reject_first_invoice_billing()");
        try {
          assertThatThrownBy(() -> payments.completeSigned(signedPayload, signedHeader))
              .hasStackTraceContaining("controlled first-invoice billing rollback");
          assertNoReceipt(hosting);
          assertThat(
                  hosting
                      .dsl()
                      .fetchSingle("SELECT count(*) FROM subscription_first_invoice_completions")
                      .get(0, Long.class))
              .isZero();
          assertThat(
                  hosting
                      .dsl()
                      .fetchSingle("SELECT count(*) FROM account_audit_outbox")
                      .get(0, Long.class))
              .isEqualTo(auditBefore);
          assertThat(
                  hosting
                      .dsl()
                      .fetchSingle("SELECT count(*) FROM stripe_event_inbox")
                      .get(0, Long.class))
              .isOne();
          assertThat(
                  hosting
                      .dsl()
                      .fetchSingle(
                          "SELECT count(*) FROM subscription_first_invoice_success_evidence")
                      .get(0, Long.class))
              .isOne();
        } finally {
          hosting
              .dsl()
              .execute(
                  "DROP TRIGGER reject_first_invoice_billing ON account_tenant_entitlement_outbox_events");
          hosting.dsl().execute("DROP FUNCTION reject_first_invoice_billing()");
        }
        var immutableIntake =
            hosting
                .dsl()
                .fetchSingle(
                    "SELECT raw_payload, payload_sha256, xmin::text AS retained_xmin FROM stripe_event_inbox");
        hosting.clock().advance(Duration.ofSeconds(12));
        assertThat(payments.completeSigned(signedPayload, signedHeader).state())
            .isEqualTo("COMMITTED");
        assertThat(
                hosting
                    .dsl()
                    .fetchSingle(
                        "SELECT raw_payload, payload_sha256, xmin::text AS retained_xmin FROM stripe_event_inbox"))
            .isEqualTo(immutableIntake);
        assertThat(hosting.firstInvoiceConfirmations()).isEqualTo(1);
        var complete = https.request("GET", path, issued.compact(), null);
        assertThat(JSON.readTree(complete.body()).path("state").asText()).isEqualTo("COMMITTED");
        assertThat(complete.body()).doesNotContain("clientSecret", "secret_", PASSWORD);
        var atomic =
            hosting
                .dsl()
                .fetchSingle(
                    "SELECT c.committed_transaction_id AS completion_tx, r.committed_transaction_id AS receipt_tx "
                        + "FROM subscription_first_invoice_completions c JOIN subscription_lifecycle_receipts r "
                        + "ON r.request_id=c.lifecycle_request_id WHERE c.subscription_id=?",
                    hosting.creation().subscriptionId());
        assertThat(atomic.get("completion_tx", Long.class))
            .isEqualTo(atomic.get("receipt_tx", Long.class));
      }
    }
  }

  @Test
  void delayedOriginalConfirmationLosesToExplicitPreRowRenewalWithoutConsumingItsProof()
      throws Exception {
    try (var owners =
        new AccountControlUiOriginalOrderFixture(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            redis.getHost(),
            redis.getMappedPort(6379),
            temporary)) {
      var issued = owners.issueCertifiedAccount(PASSWORD);
      var hosting =
          CanonicalHostingSubscriptionTestFixture.createAwaitingPayment(
              Objects.requireNonNull(issued.sources().manager.getDataSource()), issued.account());
      var reauthentication =
          new AccountRecentReauthenticationService(
              issued.sources().primary,
              issued.sources().accounts,
              new AccountAuthorityGenerationRepository(hosting.dsl()),
              new AccountRecentReauthenticationRepository(hosting.dsl()),
              new AccountRecentReauthenticationPolicyRepository(hosting.dsl()),
              issued.sources().manager,
              hosting.clock(),
              new AccountControlUiResponseCryptography(
                  new AccountControlUiKeyring(temporary.resolve("custody")), hosting.clock()));
      var payments =
          new AccountHostingFirstInvoicePaymentService(
              issued.actors(),
              reauthentication,
              hosting.firstInvoiceRepository(),
              new StripeEventInboxRepository(hosting.dsl()),
              hosting.firstInvoiceProvider(),
              hosting.webhookVerifier(),
              hosting.service(),
              issued.sources().manager);
      var gate = new OwnerEntryGate("/first-invoice/confirm");
      var renewalGate = new OwnerEntryGate("/first-invoice/renew", 2, false);
      try (var https =
              new HttpsReceiver(
                  temporary,
                  new AccountHostingFirstInvoiceController(payments),
                  gate,
                  renewalGate);
          var requestExecutor = new TestRequestExecutor();
          var releaseConfirmationGate = gate;
          var releaseRenewalGate = renewalGate) {
        String path =
            "/billing/hosting/subscriptions/"
                + hosting.creation().subscriptionId()
                + "/first-invoice";
        JsonNode tuple = JSON.readTree(https.request("GET", path, issued.compact(), null).body());
        UUID originalRequestId = UUID.randomUUID();
        var openingProof =
            https.request(
                "POST",
                path + "/reauthenticate",
                issued.compact(),
                billingReauthenticationRequest(
                    tuple,
                    UUID.randomUUID(),
                    originalRequestId,
                    originalRequestId,
                    0L,
                    "INITIAL_CONFIRMATION",
                    issued.account().getEmail()));
        assertThat(openingProof.statusCode()).isEqualTo(200);
        UUID openingEvidence =
            UUID.fromString(JSON.readTree(openingProof.body()).path("evidenceId").asText());
        Map<String, Object> originalConsent =
            Map.of(
                "requestId",
                originalRequestId,
                "expectedRowVersion",
                tuple.path("expectedRowVersion").asLong(),
                "reauthenticationEvidenceId",
                openingEvidence,
                "acceptedTupleDigest",
                tuple.path("tupleDigest").asText(),
                "acceptCharge",
                true);
        hosting.firstInvoiceConfirmationStatus("requires_action");
        var delayedOriginal =
            asyncRequest(
                https,
                "POST",
                path + "/confirm",
                issued.compact(),
                originalConsent,
                null,
                requestExecutor);
        assertThat(gate.arrived.await(30, TimeUnit.SECONDS)).isTrue();

        JsonNode whileDelayed =
            JSON.readTree(https.request("GET", path, issued.compact(), null).body());
        assertThat(whileDelayed.path("confirmationRequestId").isNull()).isTrue();
        assertThat(whileDelayed.path("authorizationVersion").asLong()).isZero();
        UUID recoveredOriginalRequestId =
            UUID.fromString(whileDelayed.path("preRowOriginalRequestId").asText());
        assertThat(recoveredOriginalRequestId).isEqualTo(originalRequestId);
        assertThat(whileDelayed.has("reauthenticationEvidenceId")).isFalse();
        assertThat(whileDelayed.has("clientSecret")).isFalse();
        assertThat(count(hosting, "SELECT count(*) FROM subscription_first_invoice_confirmations"))
            .isZero();
        assertThat(count(hosting, "SELECT count(*) FROM subscription_first_invoice_authorizations"))
            .isZero();
        assertThat(consumptionCount(hosting, openingEvidence)).isZero();
        assertNoReceipt(hosting);

        UUID winningAuthorization = UUID.randomUUID();
        var winningProof =
            https.request(
                "POST",
                path + "/reauthenticate",
                issued.compact(),
                billingReauthenticationRequest(
                    tuple,
                    UUID.randomUUID(),
                    winningAuthorization,
                    recoveredOriginalRequestId,
                    0L,
                    "PRE_ROW_RENEWAL",
                    issued.account().getEmail()));
        assertThat(winningProof.statusCode()).isEqualTo(200);
        UUID winningEvidence =
            UUID.fromString(JSON.readTree(winningProof.body()).path("evidenceId").asText());
        UUID competingAuthorization = UUID.randomUUID();
        var competingProof =
            https.request(
                "POST",
                path + "/reauthenticate",
                issued.compact(),
                billingReauthenticationRequest(
                    tuple,
                    UUID.randomUUID(),
                    competingAuthorization,
                    originalRequestId,
                    0L,
                    "PRE_ROW_RENEWAL",
                    issued.account().getEmail()));
        assertThat(competingProof.statusCode()).isEqualTo(200);
        UUID competingEvidence =
            UUID.fromString(JSON.readTree(competingProof.body()).path("evidenceId").asText());
        Map<String, Object> renewal =
            Map.of(
                "requestId",
                recoveredOriginalRequestId,
                "authorizationRequestId",
                winningAuthorization,
                "expectedRowVersion",
                tuple.path("expectedRowVersion").asLong(),
                "expectedAuthorizationVersion",
                0L,
                "reauthenticationEvidenceId",
                winningEvidence,
                "acceptedTupleDigest",
                tuple.path("tupleDigest").asText(),
                "acceptCharge",
                true);
        var changedRenewal = new java.util.HashMap<>(renewal);
        changedRenewal.put("acceptedTupleDigest", "sha256:" + "c".repeat(64));
        assertThat(
                https
                    .request("POST", path + "/renew", issued.compact(), changedRenewal)
                    .statusCode())
            .isEqualTo(409);
        assertThat(consumptionCount(hosting, winningEvidence)).isZero();
        assertThat(consumptionCount(hosting, openingEvidence)).isZero();
        assertThat(count(hosting, "SELECT count(*) FROM subscription_first_invoice_confirmations"))
            .isZero();

        Map<String, Object> competingRenewal =
            Map.of(
                "requestId",
                originalRequestId,
                "authorizationRequestId",
                competingAuthorization,
                "expectedRowVersion",
                tuple.path("expectedRowVersion").asLong(),
                "expectedAuthorizationVersion",
                0L,
                "reauthenticationEvidenceId",
                competingEvidence,
                "acceptedTupleDigest",
                tuple.path("tupleDigest").asText(),
                "acceptCharge",
                true);
        var renewalStart = new CountDownLatch(1);
        renewalGate.arm();
        var firstRenewal =
            asyncRequest(
                https,
                "POST",
                path + "/renew",
                issued.compact(),
                renewal,
                renewalStart,
                requestExecutor);
        var secondRenewal =
            asyncRequest(
                https,
                "POST",
                path + "/renew",
                issued.compact(),
                competingRenewal,
                renewalStart,
                requestExecutor);
        renewalStart.countDown();
        assertThat(renewalGate.arrived.await(30, TimeUnit.SECONDS)).isTrue();
        releaseRenewalGate.releaseNow();
        var firstRenewalResponse = firstRenewal.get(60, TimeUnit.SECONDS);
        var secondRenewalResponse = secondRenewal.get(60, TimeUnit.SECONDS);
        boolean firstRenewalWon = firstRenewalResponse.statusCode() == 200;
        assertThat(firstRenewalResponse.statusCode()).isIn(200, 409);
        assertThat(secondRenewalResponse.statusCode()).isEqualTo(firstRenewalWon ? 409 : 200);
        var renewalWinner = firstRenewalWon ? firstRenewalResponse : secondRenewalResponse;
        Map<String, Object> winningRenewal = firstRenewalWon ? renewal : competingRenewal;
        Map<String, Object> losingRenewal = firstRenewalWon ? competingRenewal : renewal;
        UUID winnerAuthorizationId =
            firstRenewalWon ? winningAuthorization : competingAuthorization;
        UUID winnerEvidenceId = firstRenewalWon ? winningEvidence : competingEvidence;
        UUID losingEvidenceId = firstRenewalWon ? competingEvidence : winningEvidence;
        assertThat(renewalWinner.statusCode()).isEqualTo(200);
        JsonNode afterRenewal =
            JSON.readTree(https.request("GET", path, issued.compact(), null).body());
        assertThat(afterRenewal.path("confirmationRequestId").asText())
            .isEqualTo(recoveredOriginalRequestId.toString());
        assertThat(afterRenewal.path("preRowOriginalRequestId").isNull()).isTrue();
        assertThat(JSON.readTree(renewalWinner.body()).path("status").asText())
            .isEqualTo("requires_action");
        assertThat(hosting.firstInvoiceConfirmations()).isEqualTo(1);
        assertThat(count(hosting, "SELECT count(*) FROM subscription_first_invoice_authorizations"))
            .isOne();
        assertThat(count(hosting, "SELECT count(*) FROM subscription_first_invoice_command_claims"))
            .isOne();
        assertThat(
                hosting
                    .dsl()
                    .fetchSingle(
                        "SELECT request_id, authorization_request_id, reauthentication_evidence_id FROM "
                            + "subscription_first_invoice_authorizations")
                    .get("request_id", UUID.class))
            .isEqualTo(originalRequestId);
        assertThat(
                hosting
                    .dsl()
                    .fetchSingle(
                        "SELECT authorization_request_id FROM "
                            + "subscription_first_invoice_authorizations")
                    .get("authorization_request_id", UUID.class))
            .isEqualTo(winnerAuthorizationId);
        assertThat(
                hosting
                    .dsl()
                    .fetchSingle(
                        "SELECT reauthentication_evidence_id FROM "
                            + "subscription_first_invoice_authorizations")
                    .get("reauthentication_evidence_id", UUID.class))
            .isEqualTo(winnerEvidenceId);
        var evidenceBefore =
            hosting
                .dsl()
                .fetchSingle(
                    "SELECT encode(request_payload, 'hex') AS request_payload_hex, request_digest, "
                        + "billing_context_digest, xmin::text AS row_xmin "
                        + "FROM account_recent_reauthentication_evidence WHERE evidence_id = ?",
                    openingEvidence);
        var originalFence =
            hosting
                .dsl()
                .fetchSingle(
                    "SELECT request_id, reauthentication_evidence_id, binding_digest "
                        + "FROM subscription_first_invoice_operation_fences");
        assertThat(originalFence.get("request_id", UUID.class)).isEqualTo(originalRequestId);
        assertThat(originalFence.get("reauthentication_evidence_id", UUID.class))
            .isEqualTo(openingEvidence);
        assertThat(originalFence.get("binding_digest", String.class))
            .isEqualTo(evidenceBefore.get("billing_context_digest", String.class));
        assertThat(consumptionCount(hosting, winnerEvidenceId)).isOne();
        assertThat(consumptionCount(hosting, openingEvidence)).isZero();
        assertThat(hosting.firstInvoiceConfirmations()).isEqualTo(1);
        assertThat(
                hosting
                    .dsl()
                    .fetchSingle(
                        "SELECT provider_idempotency_id FROM subscription_first_invoice_confirmations "
                            + "WHERE request_id = ?",
                        originalRequestId)
                    .get("provider_idempotency_id", UUID.class)
                    .toString())
            .isEqualTo(hosting.lastFirstInvoiceConfirmationIdempotencyKey());

        assertThat(
                https
                    .request("POST", path + "/renew", issued.compact(), losingRenewal)
                    .statusCode())
            .isEqualTo(409);
        assertThat(consumptionCount(hosting, losingEvidenceId)).isZero();

        assertThat(
                https
                    .request("POST", path + "/renew", issued.compact(), winningRenewal)
                    .statusCode())
            .isEqualTo(200);
        assertThat(hosting.firstInvoiceConfirmations()).isEqualTo(1);
        assertThat(count(hosting, "SELECT count(*) FROM subscription_first_invoice_authorizations"))
            .isOne();

        releaseConfirmationGate.releaseNow();
        assertThat(delayedOriginal.get(60, TimeUnit.SECONDS).statusCode()).isEqualTo(409);
        assertThat(consumptionCount(hosting, openingEvidence)).isZero();
        assertThat(consumptionCount(hosting, winnerEvidenceId)).isOne();
        assertThat(
                hosting
                    .dsl()
                    .fetchSingle(
                        "SELECT encode(request_payload, 'hex') AS request_payload_hex, request_digest, "
                            + "billing_context_digest, xmin::text AS row_xmin "
                            + "FROM account_recent_reauthentication_evidence WHERE evidence_id = ?",
                        openingEvidence))
            .isEqualTo(evidenceBefore);
        JsonNode after = JSON.readTree(https.request("GET", path, issued.compact(), null).body());
        assertThat(after.path("confirmationRequestId").asText())
            .isEqualTo(originalRequestId.toString());
        assertThat(after.path("authorizationVersion").asLong()).isEqualTo(1L);
        assertThat(hosting.firstInvoiceConfirmations()).isEqualTo(1);
        UUID expiredAuthorization = UUID.randomUUID();
        var expiredProof =
            https.request(
                "POST",
                path + "/reauthenticate",
                issued.compact(),
                billingReauthenticationRequest(
                    tuple,
                    UUID.randomUUID(),
                    expiredAuthorization,
                    originalRequestId,
                    1L,
                    "FOLLOWUP_RENEWAL",
                    issued.account().getEmail()));
        assertThat(expiredProof.statusCode()).isEqualTo(200);
        UUID expiredEvidence =
            UUID.fromString(JSON.readTree(expiredProof.body()).path("evidenceId").asText());
        var expiredEvidenceBefore =
            hosting
                .dsl()
                .fetchSingle(
                    "SELECT encode(request_payload, 'hex') AS request_payload_hex, request_digest, "
                        + "billing_context_digest, xmin::text AS row_xmin "
                        + "FROM account_recent_reauthentication_evidence WHERE evidence_id = ?",
                    expiredEvidence);
        hosting.clock().advance(Duration.ofMinutes(6));
        Map<String, Object> expiredRenewal =
            Map.of(
                "requestId",
                originalRequestId,
                "authorizationRequestId",
                expiredAuthorization,
                "expectedRowVersion",
                tuple.path("expectedRowVersion").asLong(),
                "expectedAuthorizationVersion",
                1L,
                "reauthenticationEvidenceId",
                expiredEvidence,
                "acceptedTupleDigest",
                tuple.path("tupleDigest").asText(),
                "acceptCharge",
                true);
        assertThat(
                https
                    .request("POST", path + "/renew", issued.compact(), expiredRenewal)
                    .statusCode())
            .isEqualTo(409);
        assertThat(consumptionCount(hosting, expiredEvidence)).isZero();
        assertThat(count(hosting, "SELECT count(*) FROM subscription_first_invoice_authorizations"))
            .isOne();
        assertThat(
                hosting
                    .dsl()
                    .fetchSingle(
                        "SELECT encode(request_payload, 'hex') AS request_payload_hex, request_digest, "
                            + "billing_context_digest, xmin::text AS row_xmin "
                            + "FROM account_recent_reauthentication_evidence WHERE evidence_id = ?",
                        expiredEvidence))
            .isEqualTo(expiredEvidenceBefore);
        assertNoReceipt(hosting);
      }
    }
  }

  private static long consumptionCount(
      CanonicalHostingSubscriptionTestFixture.Fixture hosting, UUID evidenceId) {
    return hosting
        .dsl()
        .fetchSingle(
            "SELECT count(*) FROM account_recent_reauthentication_consumptions WHERE evidence_id = ?",
            evidenceId)
        .get(0, Long.class);
  }

  private static long count(CanonicalHostingSubscriptionTestFixture.Fixture hosting, String sql) {
    return hosting.dsl().fetchSingle(sql).get(0, Long.class);
  }

  private static CompletableFuture<HttpResponse<String>> asyncRequest(
      HttpsReceiver https,
      String method,
      String path,
      String bearer,
      Object body,
      CountDownLatch start,
      TestRequestExecutor requestExecutor) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            if (start != null && !start.await(30, TimeUnit.SECONDS)) {
              throw new IllegalStateException("Concurrent HTTP request barrier did not release");
            }
            return https.request(method, path, bearer, body);
          } catch (Exception failure) {
            throw new java.util.concurrent.CompletionException(failure);
          }
        },
        requestExecutor.executor());
  }

  private static void assertNoReceipt(CanonicalHostingSubscriptionTestFixture.Fixture hosting) {
    assertThat(
            hosting
                .dsl()
                .fetchSingle("SELECT count(*) FROM subscription_lifecycle_receipts")
                .get(0, Long.class))
        .isZero();
    assertThat(hosting.intents().findCurrentByTenant(hosting.tenantId()).orElseThrow().status())
        .isEqualTo("provisioning");
  }

  private static Map<String, Object> billingReauthenticationRequest(
      JsonNode tuple,
      UUID requestId,
      UUID authorizationRequestId,
      UUID originalRequestId,
      long expectedAuthorizationVersion,
      String authorizationPurpose,
      String email) {
    return Map.of(
        "requestId",
        requestId,
        "confirmationRequestId",
        authorizationRequestId,
        "billingBinding",
        Map.of(
            "originalRequestId",
            originalRequestId,
            "expectedRowVersion",
            tuple.path("expectedRowVersion").asLong(),
            "expectedAuthorizationVersion",
            expectedAuthorizationVersion,
            "acceptedTupleDigest",
            tuple.path("tupleDigest").asText(),
            "invoiceExpiresAt",
            tuple.path("expiresAt").asText(),
            "authorizationPurpose",
            authorizationPurpose),
        "email",
        email,
        "secret",
        PASSWORD);
  }

  /** Explicit test-only mount for the deliberately unregistered production owner. */
  private static final class HttpsReceiver implements AutoCloseable {
    private final Tomcat tomcat;
    private final GenericWebApplicationContext web;
    private final HttpClient client;
    private final URI origin;

    private HttpsReceiver(Path directory, AccountHostingFirstInvoiceController controller)
        throws Exception {
      this(directory, controller, new OwnerEntryGate[0]);
    }

    private HttpsReceiver(
        Path directory, AccountHostingFirstInvoiceController controller, OwnerEntryGate... gates)
        throws Exception {
      Path tls = Files.createDirectory(directory.resolve("https"));
      var pki =
          LoopbackMtlsTestPki.create(
              tls,
              "checkout-test-ca",
              new LoopbackMtlsTestPki.IdentitySpec(
                  "checkout", "spiffe://test-only/account-checkout", true));
      var identity = pki.identity("checkout");
      var material = pki.clientProperties(tls, identity);
      tomcat = new Tomcat();
      tomcat.setBaseDir(Files.createDirectory(directory.resolve("tomcat")).toString());
      tomcat.setPort(0);
      var connector = tomcat.getConnector();
      connector.setScheme("https");
      connector.setSecure(true);
      connector.setProperty("address", "127.0.0.1");
      var protocol = (AbstractHttp11Protocol<?>) connector.getProtocolHandler();
      protocol.setSSLEnabled(true);
      var ssl = new SSLHostConfig();
      var certificate = new SSLHostConfigCertificate(ssl, SSLHostConfigCertificate.Type.RSA);
      certificate.setCertificateFile(material.getCertChain());
      certificate.setCertificateKeyFile(material.getPrivateKey());
      certificate.setCertificateChainFile(material.getCaCert());
      ssl.addCertificate(certificate);
      protocol.addSslHostConfig(ssl);
      var context =
          tomcat.addContext("", Files.createDirectory(directory.resolve("web-root")).toString());
      web = new GenericWebApplicationContext();
      web.setServletContext(context.getServletContext());
      org.springframework.context.annotation.AnnotationConfigUtils
          .registerAnnotationConfigProcessors(web);
      web.registerBean(AccountHostingFirstInvoiceController.class, () -> controller);
      web.registerBean(ExplicitOwnerMvc.class, () -> new ExplicitOwnerMvc(gates));
      web.refresh();
      Tomcat.addServlet(context, "checkout", new DispatcherServlet(web));
      context.addServletMappingDecoded("/", "checkout");
      tomcat.start();
      origin = URI.create("https://localhost:" + connector.getLocalPort());
      SSLContext trust = SSLContext.getInstance("TLS");
      trust.init(null, pki.trustManagerFactory().getTrustManagers(), null);
      client =
          HttpClient.newBuilder().sslContext(trust).connectTimeout(Duration.ofSeconds(3)).build();
    }

    private HttpResponse<String> request(String method, String path, String bearer, Object body)
        throws Exception {
      var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(90));
      if (bearer != null) request.header("Authorization", "Bearer " + bearer);
      if (body != null) request.header("Content-Type", "application/json");
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() throws Exception {
      client.close();
      tomcat.stop();
      tomcat.destroy();
      web.close();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ExplicitOwnerMvc extends WebMvcConfigurationSupport {
    private final OwnerEntryGate[] gates;

    ExplicitOwnerMvc(OwnerEntryGate... gates) {
      this.gates = gates;
    }

    @Override
    protected void addInterceptors(InterceptorRegistry registry) {
      if (gates.length == 0) return;
      registry.addInterceptor(
          new HandlerInterceptor() {
            @Override
            public boolean preHandle(
                jakarta.servlet.http.HttpServletRequest request,
                jakarta.servlet.http.HttpServletResponse response,
                Object handler)
                throws Exception {
              if ("POST".equals(request.getMethod())) {
                for (OwnerEntryGate gate : gates) {
                  if (gate.armed && request.getRequestURI().endsWith(gate.endpointSuffix)) {
                    gate.arrived.countDown();
                    if (!gate.release.await(60, TimeUnit.SECONDS)) {
                      throw new IllegalStateException("Test owner-entry gate did not release");
                    }
                    break;
                  }
                }
              }
              return true;
            }
          });
    }

    @Override
    protected RequestMappingHandlerMapping createRequestMappingHandlerMapping() {
      return new RequestMappingHandlerMapping() {
        @Override
        protected boolean isHandler(Class<?> beanType) {
          return beanType == AccountHostingFirstInvoiceController.class;
        }
      };
    }
  }

  private static final class OwnerEntryGate implements AutoCloseable {
    private final String endpointSuffix;
    private final CountDownLatch arrived;
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile boolean armed;

    private OwnerEntryGate(String endpointSuffix) {
      this(endpointSuffix, 1, true);
    }

    private OwnerEntryGate(String endpointSuffix, int arrivals) {
      this(endpointSuffix, arrivals, true);
    }

    private OwnerEntryGate(String endpointSuffix, int arrivals, boolean armed) {
      this.endpointSuffix = endpointSuffix;
      this.arrived = new CountDownLatch(arrivals);
      this.armed = armed;
    }

    private void arm() {
      armed = true;
    }

    private void releaseNow() {
      release.countDown();
    }

    @Override
    public void close() {
      releaseNow();
    }
  }

  private static final class TestRequestExecutor implements AutoCloseable {
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    private ExecutorService executor() {
      return executor;
    }

    @Override
    public void close() {
      executor.shutdown();
      try {
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
      } catch (InterruptedException interrupted) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }
}
