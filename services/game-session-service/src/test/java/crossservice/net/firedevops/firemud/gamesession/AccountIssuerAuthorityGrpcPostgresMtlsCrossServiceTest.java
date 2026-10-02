package crossservice.net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityGrpcService;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.gamesession.client.AccountIssuerAuthorityClient;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL-backed Account issuer readback composed with the physical Game Session mTLS client.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountIssuerAuthorityGrpcPostgresMtlsCrossServiceTest {
  private static final String NAMESPACE = "test";
  private static final String ISSUER_ID = "https://account.example.test/issuer";
  private static final String STREAM_KEY = "account:auth-authority:v1:issuer/" + ISSUER_ID;
  private static final String BOOTSTRAP_READ_REQUEST_ID = "11111111-1111-4111-8111-111111111111";
  private static final String CURRENT_READ_REQUEST_ID = "22222222-2222-4222-8222-222222222222";
  private static final String HISTORICAL_READ_REQUEST_ID = "33333333-3333-4333-8333-333333333333";

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @TempDir private Path tempDir;

  private final Map<String, Path> copiedCertificates = new HashMap<>();

  @Test
  void postgresIssuerSourceReturnsBootstrapCurrentAndHistoricalEvidenceOverSocketMtls()
      throws Exception {
    AccountFixture account = newAccountFixture();
    account.seedIssuer();
    AccountIssuerAuthorityEventProducer producer = account.producer();
    Server server = startServer(producer);

    try (AccountIssuerAuthorityClient client = newClient(server.getPort(), "game-session")) {
      client.init();

      AccountIssuerAuthorityClient.SourceReadback bootstrap =
          client.readCurrent(BOOTSTRAP_READ_REQUEST_ID);
      assertThat(bootstrap.requestId()).isEqualTo(BOOTSTRAP_READ_REQUEST_ID);
      assertThat(bootstrap.targetNamespace()).isEqualTo(NAMESPACE);
      assertThat(bootstrap.sourceSnapshot().issuerId()).isEqualTo(ISSUER_ID);
      assertThat(bootstrap.sourceSnapshot().sourceScope()).isEqualTo("issuer/" + ISSUER_ID);
      assertThat(bootstrap.sourceSnapshot().outboxStreamKey()).isEqualTo(STREAM_KEY);
      assertThat(bootstrap.sourceSnapshot().issuerAuthGeneration()).isEqualTo("1");
      assertThat(bootstrap.sourceSnapshot().sourceVersion()).isEqualTo("1");
      assertThat(bootstrap.sourceSnapshot().outboxSequence()).isEqualTo("0");
      assertThat(bootstrap.sourceSnapshot().latestEvent()).isEmpty();
      assertThat(bootstrap.requestedEvent()).isEmpty();

      IssuerGenerationAuthorityEvent first = producer.advance(ISSUER_ID, UUID.randomUUID(), 1L, 1L);
      IssuerGenerationAuthorityEvent second =
          producer.advance(ISSUER_ID, UUID.randomUUID(), 2L, 2L);

      AccountIssuerAuthorityClient.SourceReadback current =
          client.readCurrent(CURRENT_READ_REQUEST_ID);
      AccountIssuerAuthorityClient.SourceReadback historical =
          client.readCommittedEvent(HISTORICAL_READ_REQUEST_ID, "1");

      assertThat(current.requestId()).isEqualTo(CURRENT_READ_REQUEST_ID);
      assertThat(current.targetNamespace()).isEqualTo(NAMESPACE);
      assertThat(current.sourceSnapshot().issuerId()).isEqualTo(ISSUER_ID);
      assertThat(current.sourceSnapshot().sourceScope()).isEqualTo("issuer/" + ISSUER_ID);
      assertThat(current.sourceSnapshot().outboxStreamKey()).isEqualTo(STREAM_KEY);
      assertThat(current.sourceSnapshot().issuerAuthGeneration()).isEqualTo("3");
      assertThat(current.sourceSnapshot().sourceVersion()).isEqualTo("3");
      assertThat(current.sourceSnapshot().outboxSequence()).isEqualTo("2");
      assertThat(current.sourceSnapshot().latestEvent()).isPresent();
      assertSameEvent(second, current.sourceSnapshot().latestEvent().orElseThrow());
      assertThat(current.requestedEvent()).isEmpty();

      assertThat(historical.requestId()).isEqualTo(HISTORICAL_READ_REQUEST_ID);
      assertThat(historical.targetNamespace()).isEqualTo(NAMESPACE);
      assertThat(historical.sourceSnapshot().issuerId()).isEqualTo(ISSUER_ID);
      assertThat(historical.sourceSnapshot().sourceScope()).isEqualTo("issuer/" + ISSUER_ID);
      assertThat(historical.sourceSnapshot().outboxStreamKey()).isEqualTo(STREAM_KEY);
      assertThat(historical.sourceSnapshot().issuerAuthGeneration()).isEqualTo("3");
      assertThat(historical.sourceSnapshot().sourceVersion()).isEqualTo("3");
      assertThat(historical.sourceSnapshot().outboxSequence()).isEqualTo("2");
      assertThat(historical.sourceSnapshot().latestEvent()).isPresent();
      assertSameEvent(second, historical.sourceSnapshot().latestEvent().orElseThrow());
      assertThat(historical.requestedEvent()).isPresent();
      assertSameEvent(first, historical.requestedEvent().orElseThrow());
    } finally {
      stop(server);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"wrong-service", "wrong-namespace"})
  void trustedButWrongGameSessionIdentityIsDeniedWithoutChangingPostgresSource(
      String clientIdentity) throws Exception {
    AccountFixture account = newAccountFixture();
    account.seedIssuer();
    AccountIssuerAuthorityEventProducer producer = account.producer();
    Server server = startServer(producer);

    try (AccountIssuerAuthorityClient client = newClient(server.getPort(), clientIdentity)) {
      client.init();
      IssuerAuthoritySnapshot before = producer.readCurrent(ISSUER_ID);

      assertThatThrownBy(() -> client.readCurrent(CURRENT_READ_REQUEST_ID))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));

      assertThat(producer.readCurrent(ISSUER_ID)).isEqualTo(before);
    } finally {
      stop(server);
    }
  }

  private AccountFixture newAccountFixture() {
    String schema = "issuer_authority_grpc_proof_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
    dataSource.setUrl(postgres.getJdbcUrl() + separator + "currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("filesystem:" + accountMigrationDirectory())
        .load()
        .migrate();

    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(transactionDsl);
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(transactionDsl);
    return new AccountFixture(generations, outbox, transactionDsl, transactionManager);
  }

  private static Path accountMigrationDirectory() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      Path accountMigrations =
          current.resolve("services/account-service/src/main/resources/db/migration");
      if (Files.isRegularFile(current.resolve("settings.gradle.kts"))
          && Files.isDirectory(accountMigrations)) {
        return accountMigrations;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Unable to locate the Account service migration directory");
  }

  private Server startServer(AccountIssuerAuthorityEventProducer producer) throws Exception {
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.forServer(
                    certificate("account.crt").toFile(), certificate("account.key").toFile())
                .trustManager(certificate("ca.crt").toFile())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(
            ServerInterceptors.intercept(
                new AccountIssuerAuthorityGrpcService(producer, NAMESPACE),
                new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private AccountIssuerAuthorityClient newClient(int port, String clientIdentity) throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + port);
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(certificate(clientIdentity + ".crt").toString());
    tls.setPrivateKey(certificate(clientIdentity + ".key").toString());
    tls.setCaCert(certificate("ca.crt").toString());
    return new AccountIssuerAuthorityClient(
        endpoints,
        tls,
        new GrpcChannelFactory(),
        BlockingGrpcStubCustomizer.noop(),
        NAMESPACE,
        ISSUER_ID);
  }

  private Path certificate(String name) throws IOException {
    Path existing = copiedCertificates.get(name);
    if (existing != null) {
      return existing;
    }

    String resourcePath = "/certs/issuer-authority/" + name;
    try (InputStream resource =
        AccountIssuerAuthorityGrpcPostgresMtlsCrossServiceTest.class.getResourceAsStream(
            resourcePath)) {
      if (resource == null) {
        throw new IllegalStateException("Missing issuer-authority TLS fixture: " + resourcePath);
      }
      Path copied = tempDir.resolve(name);
      Files.copy(resource, copied);
      copiedCertificates.put(name, copied);
      return copied;
    }
  }

  private void assertSameEvent(
      IssuerGenerationAuthorityEvent expected, IssuerGenerationAuthorityEvent actual) {
    assertThat(actual.canonicalJson()).isEqualTo(expected.canonicalJson());
    assertThat(actual.eventDigest()).isEqualTo(expected.eventDigest());
  }

  private static void stop(Server server) throws InterruptedException {
    server.shutdownNow();
    server.awaitTermination(2, TimeUnit.SECONDS);
  }

  private record AccountFixture(
      AccountAuthorityGenerationRepository generations,
      AccountAuthorityOutboxRepository outbox,
      DSLContext transactionDsl,
      PlatformTransactionManager transactionManager) {
    private void seedIssuer() {
      new TransactionTemplate(transactionManager)
          .execute(status -> generations.initializeIssuerIfAbsent(ISSUER_ID));
    }

    private AccountIssuerAuthorityEventProducer producer() {
      return new AccountIssuerAuthorityEventProducer(
          ISSUER_ID, generations, outbox, transactionDsl, transactionManager);
    }
  }
}
