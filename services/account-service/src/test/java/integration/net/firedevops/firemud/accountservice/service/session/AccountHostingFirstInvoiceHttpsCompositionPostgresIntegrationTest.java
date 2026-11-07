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
      try (var https =
          new HttpsReceiver(temporary, new AccountHostingFirstInvoiceController(payments))) {
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
        var proof =
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
        hosting.firstInvoiceConfirmationStatus("requires_action");
        var handoff = https.request("POST", path + "/confirm", issued.compact(), consent);
        assertThat(handoff.statusCode()).isEqualTo(200);
        assertThat(handoff.headers().firstValue("cache-control")).contains("no-store");
        assertThat(JSON.readTree(handoff.body()).path("status").asText())
            .isEqualTo("requires_action");
        assertThat(JSON.readTree(handoff.body()).path("clientSecret").asText())
            .startsWith(hosting.firstInvoice().paymentIntentId() + "_secret_");
        assertThat(hosting.firstInvoiceConfirmations()).isEqualTo(1);
        assertThat(https.request("POST", path + "/confirm", issued.compact(), consent).statusCode())
            .isEqualTo(200);
        assertThat(hosting.firstInvoiceConfirmations()).isEqualTo(1);
        assertNoReceipt(hosting);
        UUID authorization = UUID.randomUUID();
        var renewedProof =
            https.request(
                "POST",
                path + "/reauthenticate",
                issued.compact(),
                Map.of(
                    "requestId",
                    UUID.randomUUID(),
                    "confirmationRequestId",
                    authorization,
                    "email",
                    issued.account().getEmail(),
                    "secret",
                    PASSWORD));
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

  /** Explicit test-only mount for the deliberately unregistered production owner. */
  private static final class HttpsReceiver implements AutoCloseable {
    private final Tomcat tomcat;
    private final GenericWebApplicationContext web;
    private final HttpClient client;
    private final URI origin;

    private HttpsReceiver(Path directory, AccountHostingFirstInvoiceController controller)
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
      web.registerBean(ExplicitOwnerMvc.class);
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
      var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(10));
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
}
