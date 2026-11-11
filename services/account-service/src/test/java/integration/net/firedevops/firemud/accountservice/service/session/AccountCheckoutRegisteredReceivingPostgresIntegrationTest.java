package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import integration.net.firedevops.firemud.accountservice.repository.CanonicalHostingSubscriptionTestFixture;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import net.firedevops.firemud.accountservice.config.AccountCheckoutReceivingConfiguration;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.config.PaymentProperties;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingPlanCatalog;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecyclePolicy;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountPlatformRestrictionOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.impl.AccountControlUiAuthenticationGrpcService;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.config.CommonSecurityServletAutoConfiguration;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.GrpcAuthProperties;
import net.firedevops.firemud.common.security.HttpAuthProperties;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.TestContainerImages;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.jooq.DSLContext;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registered Gateway HTTPS -> actual mTLS login -> registered Account HTTPS/owners -> physical
 * PostgreSQL/Coordination. Signer platform sources, custody, policy and SDK transport are
 * explicitly controlled upstream inputs. This supplies no live payment, privacy, PKI or deployment
 * qualification.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountCheckoutRegisteredReceivingPostgresIntegrationTest {
  private static final String NAMESPACE = "firemud-prod";
  private static final String PASSWORD = "test-only-registered-checkout-password";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("checkout-receiving-primary")
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
              "checkout-receiving-primary",
              "6379");

  @TempDir Path temporary;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void registeredChainAuthenticatesRealOwnerAndCommitsOnlyExactSignedProviderSuccess(
      boolean preRowRenewal)
      throws Exception {
    try (var upstream =
        new AccountControlUiOriginalOrderFixture(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            redis.getHost(),
            redis.getMappedPort(6379),
            temporary)) {
      var account = upstream.prepareReceivingAccount(PASSWORD);
      var otherAccount = upstream.prepareReceivingAccount(PASSWORD);
      var source = upstream.receivingSources();
      var hosting =
          CanonicalHostingSubscriptionTestFixture.createAwaitingPayment(
              Objects.requireNonNull(source.manager.getDataSource()), account, NAMESPACE);
      assertThat(
              source
                  .dsl
                  .fetchSingle(
                      "SELECT count(*) FROM account_control_ui_issuance_operations WHERE account_uuid = ?",
                      account.getAccountUuid())
                  .get(0, Long.class))
          .isZero();
      try (var receiving = new RegisteredChain(temporary, upstream, hosting)) {
        var login =
            Map.of(
                "requestId",
                UUID.randomUUID(),
                "callerContextId",
                UUID.randomUUID(),
                "email",
                account.getEmail(),
                "secret",
                PASSWORD);
        var denied = new HashMap<String, Object>(login);
        denied.put("requestId", UUID.randomUUID());
        denied.put("secret", "wrong-secret");
        assertThat(receiving.request("POST", "/api/account/auth/login", null, denied).statusCode())
            .isEqualTo(401);
        var signedIn = receiving.request("POST", "/api/account/auth/login", null, login);
        assertThat(signedIn.statusCode()).isEqualTo(200);
        assertThat(signedIn.headers().firstValue("cache-control")).contains("no-store");
        var data = JSON.readTree(signedIn.body()).path("data");
        assertThat(data.path("accountId").asText()).isEqualTo(account.getAccountUuid().toString());
        String bearer = data.path("authToken").asText();
        assertThat(
                JSON.readTree(
                        receiving.request("POST", "/api/account/auth/login", null, login).body())
                    .path("data")
                    .path("authToken")
                    .asText())
            .isEqualTo(bearer);
        assertThat(
                source
                    .dsl
                    .fetchSingle(
                        "SELECT count(*) FROM account_tenant_membership WHERE account_id = ?",
                        account.getId())
                    .get(0, Long.class))
            .isZero();
        String path =
            "/api/account/billing/hosting/subscriptions/"
                + hosting.creation().subscriptionId()
                + "/first-invoice";
        assertThat(receiving.request("GET", path, null, null).statusCode()).isEqualTo(401);
        assertThat(receiving.request("GET", path, "forged.hmac.token", null).statusCode())
            .isEqualTo(401);
        var owner = receiving.request("GET", path, bearer, null);
        assertThat(owner.statusCode()).isEqualTo(200);
        JsonNode tuple = JSON.readTree(owner.body());
        assertThat(tuple.path("amount").asText()).isEqualTo("2400");
        var otherLogin =
            receiving.request(
                "POST",
                "/api/account/auth/login",
                null,
                Map.of(
                    "requestId",
                    UUID.randomUUID(),
                    "callerContextId",
                    UUID.randomUUID(),
                    "email",
                    otherAccount.getEmail(),
                    "secret",
                    PASSWORD));
        assertThat(otherLogin.statusCode()).isEqualTo(200);
        String otherBearer =
            JSON.readTree(otherLogin.body()).path("data").path("authToken").asText();
        var wrongOwner = receiving.request("GET", path, otherBearer, null);
        assertThat(wrongOwner.statusCode()).isEqualTo(409);
        assertThat(wrongOwner.body())
            .doesNotContain(tuple.path("invoiceId").asText(), "clientSecret", PASSWORD);
        assertThat(hosting.firstInvoiceConfirmations()).isZero();
        UUID confirmation = UUID.randomUUID();
        var proof =
            receiving.request(
                "POST",
                path + "/reauthenticate",
                bearer,
                billingReauthentication(
                    tuple, confirmation, confirmation, "INITIAL_CONFIRMATION", account.getEmail()));
        assertThat(proof.statusCode()).isEqualTo(200);
        var consent = new HashMap<String, Object>();
        consent.put("requestId", confirmation);
        consent.put("expectedRowVersion", tuple.path("expectedRowVersion").asLong());
        consent.put(
            "reauthenticationEvidenceId", JSON.readTree(proof.body()).path("evidenceId").asText());
        consent.put("acceptedTupleDigest", tuple.path("tupleDigest").asText());
        consent.put("acceptCharge", false);
        assertThat(receiving.request("POST", path + "/confirm", bearer, consent).statusCode())
            .isEqualTo(400);
        assertThat(hosting.firstInvoiceConfirmations()).isZero();
        consent.put("acceptCharge", true);
        String authorizationPath = path + "/confirm";
        Map<String, Object> authorization = consent;
        if (preRowRenewal) {
          assertThat(
                  JSON.readTree(receiving.request("GET", path, bearer, null).body())
                      .path("confirmationRequestId")
                      .isNull())
              .isTrue();
          UUID renewal = UUID.randomUUID();
          var renewalProof =
              receiving.request(
                  "POST",
                  path + "/reauthenticate",
                  bearer,
                  billingReauthentication(
                      tuple, confirmation, renewal, "PRE_ROW_RENEWAL", account.getEmail()));
          assertThat(renewalProof.statusCode()).isEqualTo(200);
          authorizationPath = path + "/renew";
          authorization = new HashMap<>(consent);
          authorization.put("authorizationRequestId", renewal);
          authorization.put("expectedAuthorizationVersion", 0L);
          authorization.put(
              "reauthenticationEvidenceId",
              JSON.readTree(renewalProof.body()).path("evidenceId").asText());
        }
        hosting.firstInvoiceConfirmationStatus("requires_action");
        var handoff = receiving.request("POST", authorizationPath, bearer, authorization);
        assertThat(handoff.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(handoff.body()).path("status").asText())
            .isEqualTo("requires_action");
        assertThat(receiving.request("POST", authorizationPath, bearer, authorization).statusCode())
            .isEqualTo(200);
        if (preRowRenewal) {
          assertThat(receiving.request("POST", path + "/confirm", bearer, consent).statusCode())
              .isEqualTo(409);
        }
        assertThat(hosting.firstInvoiceConfirmations()).isOne();
        assertThat(
                JSON.readTree(receiving.request("GET", path, bearer, null).body())
                    .path("state")
                    .asText())
            .isEqualTo("AWAITING_PAYMENT");
        hosting.firstInvoiceStatus("succeeded");
        hosting.providerStatus("active");
        assertThat(
                JSON.readTree(receiving.request("GET", path, bearer, null).body())
                    .path("state")
                    .asText())
            .isEqualTo("AWAITING_PAYMENT");
        assertThat(receiving.rawWebhook(hosting.signedSuccessPayload(), null).statusCode())
            .isEqualTo(400);
        // Let the original controlled ten-second provider-dispatch lease expire without issuing
        // repeated reconciliation commands or changing the real receiving clock.
        Thread.sleep(Duration.ofSeconds(11));
        // The controlled provider event follows real owner consent. Advance only the provider
        // fixture clock; receiving keeps its genuine clock and unchanged consent/lease guards.
        hosting.clock().advance(Duration.between(hosting.clock().instant(), Instant.now()));
        var success = hosting.signedSuccess();
        var consentedAt =
            source
                .dsl
                .fetchSingle(
                    "SELECT consented_at FROM subscription_first_invoice_confirmations WHERE request_id = ?",
                    confirmation)
                .get(0, OffsetDateTime.class);
        assertThat(JSON.readTree(success.payload()).path("created").asLong())
            .isGreaterThanOrEqualTo(consentedAt.toEpochSecond());
        var completion = receiving.rawWebhook(success.payload(), success.signature());
        assertThat(completion.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(completion.body()).path("state").asText()).isEqualTo("COMMITTED");
        assertThat(hosting.firstInvoiceConfirmations()).isOne();
        assertThat(
                JSON.readTree(receiving.request("GET", path, bearer, null).body())
                    .path("state")
                    .asText())
            .isEqualTo("COMMITTED");
        assertThat(receiving.rawWebhook(success.payload(), success.signature()).statusCode())
            .isEqualTo(200);
        assertThat(
                source
                    .dsl
                    .fetchSingle(
                        "SELECT count(*) FROM subscription_first_invoice_completions WHERE subscription_id = ?",
                        hosting.creation().subscriptionId())
                    .get(0, Long.class))
            .isOne();
        var admin =
            io.lettuce.core.RedisClient.create(
                io.lettuce.core.RedisURI.Builder.redis(redis.getHost(), redis.getMappedPort(6379))
                    .build());
        try (var connection = admin.connect(io.lettuce.core.codec.ByteArrayCodec.INSTANCE)) {
          String tokenHash =
              AccountControlUiIssuanceRepository.hash(
                  bearer.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
          connection
              .sync()
              .del(
                  ("session:auth:token:" + tokenHash)
                      .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } finally {
          admin.shutdown();
        }
        assertThat(receiving.request("GET", path, bearer, null).statusCode()).isEqualTo(401);
        assertThat(hosting.firstInvoiceConfirmations()).isOne();
        org.mockito.Mockito.verify(receiving.rateLimitRedis, org.mockito.Mockito.atLeastOnce())
            .execute(
                org.mockito.ArgumentMatchers.same(receiving.rateLimitScript),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq(List.of("20", "40", "", "1")));
      }
    }
  }

  private static Map<String, Object> billingReauthentication(
      JsonNode tuple, UUID original, UUID authorization, String purpose, String email) {
    return Map.of(
        "requestId", UUID.randomUUID(),
        "confirmationRequestId", authorization,
        "billingBinding", Map.of(
            "originalRequestId", original,
            "expectedRowVersion", tuple.path("expectedRowVersion").asLong(),
            "expectedAuthorizationVersion", tuple.path("authorizationVersion").asLong(),
            "acceptedTupleDigest", tuple.path("tupleDigest").asText(),
            "invoiceExpiresAt", tuple.path("expiresAt").asText(),
            "authorizationPurpose", purpose),
        "email", email,
        "secret", PASSWORD);
  }

  private static final class RegisteredChain implements AutoCloseable {
    private GenericWebApplicationContext account;
    private Tomcat tomcat;
    private Server grpc;
    private AnnotationConfigApplicationContext gateway;
    private DisposableServer gatewayHttps;
    private HttpClient browser;
    private URI origin;
    private ReactiveStringRedisTemplate rateLimitRedis;
    private RedisScript<List<Long>> rateLimitScript;

    RegisteredChain(
        Path directory,
        AccountControlUiOriginalOrderFixture upstream,
        CanonicalHostingSubscriptionTestFixture.Fixture hosting)
        throws Exception {
      var f = upstream.receivingSources();
      var signer = upstream.signerFixture;
      try {
        Path tls = Files.createDirectory(directory.resolve("registered-tls"));
        var pki =
            LoopbackMtlsTestPki.create(
                tls,
                "registered-checkout-ca",
                new LoopbackMtlsTestPki.IdentitySpec(
                    "account", "spiffe://firemud/ns/" + NAMESPACE + "/sa/account-service", true),
                new LoopbackMtlsTestPki.IdentitySpec(
                    "gateway-client",
                    "spiffe://firemud/ns/" + NAMESPACE + "/sa/spring-cloud-gateway",
                    false),
                new LoopbackMtlsTestPki.IdentitySpec(
                    "gateway-https",
                    "spiffe://firemud/ns/" + NAMESPACE + "/sa/spring-cloud-gateway",
                    true));
        var accountTls = pki.clientProperties(tls, pki.identity("account"));
        var gatewayTls = pki.clientProperties(tls, pki.identity("gateway-client"));
        var gatewayHttpsTls = pki.clientProperties(tls, pki.identity("gateway-https"));
        tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createDirectory(directory.resolve("registered-tomcat")).toString());
        tomcat.setPort(0);
        var connector = tomcat.getConnector();
        connector.setScheme("https");
        connector.setSecure(true);
        connector.setProperty("address", "127.0.0.1");
        var protocol = (AbstractHttp11Protocol<?>) connector.getProtocolHandler();
        protocol.setSSLEnabled(true);
        var ssl = new SSLHostConfig();
        var certificate = new SSLHostConfigCertificate(ssl, SSLHostConfigCertificate.Type.RSA);
        certificate.setCertificateFile(accountTls.getCertChain());
        certificate.setCertificateKeyFile(accountTls.getPrivateKey());
        certificate.setCertificateChainFile(accountTls.getCaCert());
        ssl.addCertificate(certificate);
        protocol.addSslHostConfig(ssl);
        var servlet =
            tomcat.addContext(
                "", Files.createDirectory(directory.resolve("registered-web")).toString());
        account = new GenericWebApplicationContext();
        account.setServletContext(servlet.getServletContext());
        account.getEnvironment().setActiveProfiles("isolated-checkout-receiving");
        account
            .getEnvironment()
            .getPropertySources()
            .addFirst(
                new MapPropertySource(
                    "explicit-isolated-inputs",
                    Map.of(
                        "firemud.account.checkout-receiving.enabled",
                        "true",
                        "firemud.grpc.workload-namespace",
                        NAMESPACE,
                        "firemud.account.public-jwks.api-binding-path",
                        "/etc/firemud/account-jwt-api/binding.json",
                        "firemud.account.checkout-receiving.private-signer-root",
                        signer.privateRoot.toString(),
                        "firemud.account.checkout-receiving.public-signer-root",
                        signer.publicRoot.toString(),
                        "firemud.account.checkout-receiving.control-ui-keyring-root",
                        directory.resolve("custody").toString(),
                        "firemud.auth.http.enabled",
                        "true")));
        AnnotationConfigUtils.registerAnnotationConfigProcessors(account);
        account.registerBean(DSLContext.class, () -> f.dsl);
        account.registerBean(
            "transactionManager", PlatformTransactionManager.class, () -> f.manager);
        account.registerBean(AccountRepository.class, () -> f.accounts);
        var generations = new AccountAuthorityGenerationRepository(f.dsl);
        account.registerBean(AccountAuthorityGenerationRepository.class, () -> generations);
        account.registerBean(
            AccountAuthoritySourceEvidenceRepository.class,
            () ->
                new AccountAuthoritySourceEvidenceRepository(
                    f.dsl, generations, new AccountAuthorityOutboxRepository(f.dsl)));
        account.registerBean(
            AccountPlatformRestrictionOperationRepository.class, () -> f.restrictionOperations);
        account.registerBean(AccountServiceImpl.class, () -> f.primary);
        account.registerBean(AccountJwtSignerDesiredStateRepository.class, () -> signer.desired);
        account.registerBean(AccountJwtJwksPublicationRepository.class, () -> signer.publication);
        account.registerBean(AccountJwtSignerMaterializerTrustBinding.class, () -> signer.trust);
        account.registerBean(
            AccountCheckoutReceivingConfiguration.COORDINATION_URI,
            io.lettuce.core.RedisURI.class,
            upstream::receivingCoordinationUri);
        account.registerBean(
            AccountCheckoutReceivingConfiguration.ACKNOWLEDGEMENTS,
            AccountCoordinationPinnedConnectionProvider.AcknowledgementRequirements.class,
            () ->
                new AccountCoordinationPinnedConnectionProvider.AcknowledgementRequirements(
                    1, 1, 5000));
        account.registerBean(
            AccountCheckoutReceivingConfiguration.STRIPE,
            com.stripe.StripeClient.class,
            hosting::receivingClient);
        account.registerBean(
            AccountHostingPlanCatalog.class, () -> new AccountHostingPlanCatalog(List.of()));
        account.registerBean(
            AccountHostingSubscriptionLifecycleRepository.ReconciliationLimits.class,
            () ->
                new AccountHostingSubscriptionLifecycleRepository.ReconciliationLimits(
                    4, 3, Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofHours(1)));
        account.registerBean(
            AccountHostingSubscriptionLifecyclePolicy.class,
            () ->
                new AccountHostingSubscriptionLifecyclePolicy(
                    Duration.ofSeconds(15),
                    new AccountHostingSubscriptionLifecyclePolicy.RecoveryTiming(
                        Duration.ofSeconds(60), Duration.ofSeconds(120))));
        var paymentProperties = new PaymentProperties();
        paymentProperties.setHostingWebhookSecret(hosting.receivingWebhookSecret());
        account.registerBean(PaymentProperties.class, () -> paymentProperties);
        account.registerBean(
            "jwtUtil",
            JwtUtil.class,
            () -> new JwtUtil("test-only-legacy-secret-at-least-thirty-two-bytes", 300_000L));
        var http = new HttpAuthProperties();
        http.setEnabled(true);
        account.registerBean(HttpAuthProperties.class, () -> http);
        account.registerBean(GrpcAuthProperties.class, GrpcAuthProperties::new);
        account.registerBean(ReceiverMvc.class);
        account.registerBean(AccountCheckoutReceivingConfiguration.class);
        // Only the external platform API/configmap inputs are controlled. Production source
        // factories,
        // trusted-source verification, signer receipts and all authentication/billing owners are
        // real.
        try (var api =
                mockConstruction(
                    AccountJwtJwksApiBinding.class,
                    (mock, ignored) ->
                        when(mock.current()).thenAnswer(call -> signer.api.current()));
            var client =
                mockConstruction(
                    AccountJwtJwksConfigMapClient.class,
                    (mock, ignored) -> {
                      when(mock.identity()).thenAnswer(call -> signer.client.identity());
                      when(mock.observe()).thenAnswer(call -> signer.client.observe());
                    })) {
          account.refresh();
        }
        assertThat(account.getBeansOfType(AccountJwtJwksTrustedSource.class)).hasSize(1);
        assertThat(
                account.getBean(
                    net.firedevops.firemud.common.security.HttpJwtAuthInterceptor.class))
            .isInstanceOf(
                net.firedevops.firemud.accountservice.security.AccountCheckoutHttpAuthInterceptor
                    .class);
        assertThat(
                account.getBean(
                    net.firedevops.firemud.accountservice.authordraft
                        .AccountControlUiAccountAuthority.class))
            .isNotInstanceOf(
                net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority.class);
        Tomcat.addServlet(servlet, "account", new DispatcherServlet(account));
        servlet.addServletMappingDecoded("/", "account");
        tomcat.start();
        var grpcTls =
            GrpcSslContexts.forServer(
                    Path.of(accountTls.getCertChain()).toFile(),
                    Path.of(accountTls.getPrivateKey()).toFile())
                .trustManager(Path.of(accountTls.getCaCert()).toFile())
                .clientAuth(ClientAuth.REQUIRE)
                .build();
        grpc =
            NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .sslContext(grpcTls)
                .addService(
                    ServerInterceptors.intercept(
                        account.getBean(AccountControlUiAuthenticationGrpcService.class),
                        new GrpcPeerIdentityInterceptor(),
                        account.getBean(AuthTokenInterceptor.class)))
                .build()
                .start();
        gateway = new AnnotationConfigApplicationContext();
        gateway.getEnvironment().setActiveProfiles("isolated-checkout-receiving");
        gateway
            .getEnvironment()
            .getPropertySources()
            .addFirst(
                new MapPropertySource(
                    "isolated-gateway-inputs",
                    Map.of(
                        "firemud.account.checkout-receiving.enabled",
                        "true",
                        "firemud.account.checkout-receiving.workload-namespace",
                        NAMESPACE,
                        "firemud.account.checkout-receiving.account-https-base-url",
                        "https://localhost:" + connector.getLocalPort(),
                        "firemud.account.checkout-receiving.account-ca-cert-path",
                        accountTls.getCaCert())));
        var endpoints = new ServiceEndpointsProperties();
        endpoints.setAccountService("localhost:" + grpc.getPort());
        gateway.registerBean(ServiceEndpointsProperties.class, () -> endpoints);
        gateway.registerBean(CommonGrpcClientProperties.class, () -> gatewayTls);
        gateway.registerBean(GrpcChannelFactory.class, GrpcChannelFactory::new);
        var gatewayProperties = new org.springframework.cloud.gateway.config.GatewayProperties();
        var rateFilter = new org.springframework.cloud.gateway.filter.FilterDefinition();
        rateFilter.setName("RequestRateLimiter");
        rateFilter.setArgs(
            Map.of(
                "redis-rate-limiter.replenishRate",
                "20",
                "redis-rate-limiter.burstCapacity",
                "40"));
        gatewayProperties.setDefaultFilters(List.of(rateFilter));
        gateway.registerBean(
            org.springframework.cloud.gateway.config.GatewayProperties.class,
            () -> gatewayProperties);
        rateLimitScript = canonicalRateLimitScript();
        rateLimitRedis = org.mockito.Mockito.mock(ReactiveStringRedisTemplate.class);
        when(rateLimitRedis.execute(
                org.mockito.ArgumentMatchers.same(rateLimitScript),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq(List.of("20", "40", "", "1"))))
            .thenReturn(Flux.just(List.of(1L, 39L)));
        gateway.registerBean(ReactiveStringRedisTemplate.class, () -> rateLimitRedis);
        gateway.registerBean(
            RedisRateLimiter.REDIS_SCRIPT_NAME, RedisScript.class, () -> rateLimitScript);
        gateway.register(
            GatewayWebFlux.class,
            net.firedevops.firemud.springcloudgateway.config.AccountCheckoutReceivingConfiguration
                .class);
        // This non-Boot fixture supplies the same script resource/name as
        // GatewayRedisAutoConfiguration.
        // Only Redis execution is stipulated; the real configured adapter and exact-path filter
        // run.
        gateway.refresh();
        assertThat(gateway.getBean(RedisRateLimiter.REDIS_SCRIPT_NAME)).isSameAs(rateLimitScript);
        var httpHandler =
            new ReactorHttpHandlerAdapter(
                WebHttpHandlerBuilder.applicationContext(gateway).build());
        var gatewayServerTls =
            io.netty.handler.ssl.SslContextBuilder.forServer(
                    Path.of(gatewayHttpsTls.getCertChain()).toFile(),
                    Path.of(gatewayHttpsTls.getPrivateKey()).toFile())
                .build();
        gatewayHttps =
            HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .secure(spec -> spec.sslContext(gatewayServerTls))
                .handle(httpHandler)
                .bindNow(Duration.ofSeconds(10));
        origin = URI.create("https://localhost:" + gatewayHttps.port());
        var trust = SSLContext.getInstance("TLS");
        trust.init(null, pki.trustManagerFactory().getTrustManagers(), null);
        browser =
            HttpClient.newBuilder().sslContext(trust).connectTimeout(Duration.ofSeconds(3)).build();
      } catch (Exception | Error failure) {
        try {
          close();
        } catch (Exception cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RedisScript<List<Long>> canonicalRateLimitScript() {
      var script = new DefaultRedisScript<List<Long>>();
      script.setLocation(new ClassPathResource("META-INF/scripts/request_rate_limiter.lua"));
      script.setResultType((Class) List.class);
      return script;
    }

    HttpResponse<String> request(String method, String path, String bearer, Object body)
        throws Exception {
      var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(20));
      if (bearer != null) request.header("Authorization", "Bearer " + bearer);
      if (body != null) request.header("Content-Type", "application/json");
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
      return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> rawWebhook(String payload, String signature) throws Exception {
      var request =
          HttpRequest.newBuilder(origin.resolve("/api/account/billing/hosting/webhook"))
              .timeout(Duration.ofSeconds(20))
              .header("Content-Type", "application/json");
      if (signature != null) request.header("Stripe-Signature", signature);
      return browser.send(
          request.POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
          HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() throws Exception {
      if (browser != null) browser.close();
      if (gatewayHttps != null) gatewayHttps.disposeNow();
      if (gateway != null) gateway.close();
      if (grpc != null) {
        grpc.shutdownNow();
        grpc.awaitTermination(5, TimeUnit.SECONDS);
      }
      if (tomcat != null) {
        tomcat.stop();
        tomcat.destroy();
      }
      if (account != null) account.close();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @org.springframework.boot.autoconfigure.ImportAutoConfiguration(
      CommonSecurityServletAutoConfiguration.class)
  static class ReceiverMvc {}

  @Configuration(proxyBeanMethods = false)
  @EnableWebFlux
  static class GatewayWebFlux {}
}
