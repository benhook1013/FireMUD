package crossservice.net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.ManagedChannel;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.CaptureIssuerProjectionForRuntimeRequest;
import net.firedevops.firemud.account.v1.IssuerAuthorityServiceGrpc;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionReconciliationRepository;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityGrpcService;
import net.firedevops.firemud.accountservice.service.AccountIssuerProjectionReconciliationService;
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
 * PostgreSQL-backed Account issuer readback and capture composed with physical Game Session mTLS.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountIssuerAuthorityGrpcPostgresMtlsCrossServiceTest {
  private static final String NAMESPACE = "test";
  private static final String ISSUER_ID = "https://account.example.test/issuer";
  private static final String STREAM_KEY = "account:auth-authority:v1:issuer/" + ISSUER_ID;
  private static final String BOOTSTRAP_READ_REQUEST_ID = "11111111-1111-4111-8111-111111111111";
  private static final String CURRENT_READ_REQUEST_ID = "22222222-2222-4222-8222-222222222222";
  private static final String HISTORICAL_READ_REQUEST_ID = "33333333-3333-4333-8333-333333333333";
  private static final String ZERO_CAPTURE_REQUEST_ID = "44444444-4444-4444-8444-444444444444";
  private static final String POSITIVE_CAPTURE_REQUEST_ID = "55555555-5555-4555-8555-555555555555";
  private static final String FIRST_ADVANCE_REQUEST_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String SECOND_ADVANCE_REQUEST_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  private static final String NO_SOURCE_CAPTURE_REQUEST_ID = "66666666-6666-4666-8666-666666666666";
  private static final String GAME_SESSION_PEER_URI =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service";

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
    Server server = startServer(producer, account.captureService());

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

  @Test
  void postgresCapturePreservesZeroAndOriginalPositiveReceiptAfterLaterSourceAdvance()
      throws Exception {
    AccountFixture account = newAccountFixture();
    account.seedIssuer();
    AccountIssuerAuthorityEventProducer producer = account.producer();
    Server server = startServer(producer, account.captureService());

    try (AccountIssuerAuthorityClient client = newClient(server.getPort(), "game-session")) {
      client.init();

      AccountIssuerAuthorityClient.ProjectionCaptureReceipt zero =
          client.captureProjection(ZERO_CAPTURE_REQUEST_ID);
      assertCaptureBindings(zero, ZERO_CAPTURE_REQUEST_ID);
      assertThat(zero.capturedSource().issuerAuthGeneration()).isEqualTo("1");
      assertThat(zero.capturedSource().sourceVersion()).isEqualTo("1");
      assertThat(zero.capturedSource().outboxSequence()).isEqualTo("0");
      assertThat(zero.capturedSource().latestEvent()).isEmpty();
      assertDatabaseState(account, new DatabaseState(1L, 0L, 0L, 1L));

      IssuerGenerationAuthorityEvent first =
          producer.advance(ISSUER_ID, UUID.fromString(FIRST_ADVANCE_REQUEST_ID), 1L, 1L);
      assertDatabaseState(account, new DatabaseState(1L, 1L, 1L, 1L));

      // Discard the successful socket acknowledgement to model a committed capture whose caller
      // did not retain the response. This is not a simulated network outage.
      client.captureProjection(POSITIVE_CAPTURE_REQUEST_ID);
      assertDatabaseState(account, new DatabaseState(1L, 1L, 1L, 2L));
      StoredReceipt committedPositive =
          account.readReceipt(UUID.fromString(POSITIVE_CAPTURE_REQUEST_ID));
      assertThat(committedPositive.outboxSequence()).isEqualTo(1L);
      assertThat(committedPositive.eventPayload()).containsExactly(first.canonicalJsonUtf8());
      assertStoredEventBytes(account, 1L, committedPositive.eventPayload());

      IssuerGenerationAuthorityEvent second =
          producer.advance(ISSUER_ID, UUID.fromString(SECOND_ADVANCE_REQUEST_ID), 2L, 2L);
      assertDatabaseState(account, new DatabaseState(1L, 1L, 2L, 2L));

      AccountIssuerAuthorityClient.ProjectionCaptureReceipt retry =
          client.captureProjection(POSITIVE_CAPTURE_REQUEST_ID);
      assertCaptureBindings(retry, POSITIVE_CAPTURE_REQUEST_ID);
      assertThat(retry.operationUUID()).isEqualTo(committedPositive.operationId());
      assertThat(HexFormat.of().formatHex(committedPositive.requestDigest()))
          .isEqualTo(retry.requestDigest());
      assertThat(retry.capturedSource().issuerAuthGeneration()).isEqualTo("2");
      assertThat(retry.capturedSource().sourceVersion()).isEqualTo("2");
      assertThat(retry.capturedSource().outboxSequence()).isEqualTo("1");
      assertThat(retry.capturedSource().latestEvent()).isPresent();
      assertThat(retry.capturedSource().latestEvent().orElseThrow().canonicalJson())
          .isEqualTo(new String(committedPositive.eventPayload(), StandardCharsets.UTF_8));
      assertThat(retry.capturedSource().latestEvent().orElseThrow().canonicalJsonUtf8())
          .containsExactly(committedPositive.eventPayload());

      IssuerAuthoritySnapshot current = producer.readCurrent(ISSUER_ID);
      assertThat(current.outboxSequence()).isEqualTo(2L);
      assertThat(current.latestEvent()).isPresent();
      assertSameEvent(second, current.latestEvent().orElseThrow());
      assertStoredEventBytes(account, 2L, second.canonicalJsonUtf8());
      assertDatabaseState(account, new DatabaseState(1L, 1L, 2L, 2L));
    } finally {
      stop(server);
    }
  }

  @Test
  void concurrentExactPostgresCapturesFromSeparateSocketClientsReturnOneDurableReceipt()
      throws Exception {
    AccountFixture account = newAccountFixture();
    account.seedIssuer();
    AccountIssuerAuthorityEventProducer producer = account.producer();
    Server server = startServer(producer, account.captureService());
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try (AccountIssuerAuthorityClient firstClient = newClient(server.getPort(), "game-session");
        AccountIssuerAuthorityClient secondClient = newClient(server.getPort(), "game-session")) {
      firstClient.init();
      secondClient.init();

      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch start = new CountDownLatch(1);
      Future<AccountIssuerAuthorityClient.ProjectionCaptureReceipt> first =
          executor.submit(
              () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException(
                      "First capture client did not reach the start gate");
                }
                return firstClient.captureProjection(POSITIVE_CAPTURE_REQUEST_ID);
              });
      Future<AccountIssuerAuthorityClient.ProjectionCaptureReceipt> second =
          executor.submit(
              () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException(
                      "Second capture client did not reach the start gate");
                }
                return secondClient.captureProjection(POSITIVE_CAPTURE_REQUEST_ID);
              });

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      AccountIssuerAuthorityClient.ProjectionCaptureReceipt firstReceipt =
          first.get(10, TimeUnit.SECONDS);
      AccountIssuerAuthorityClient.ProjectionCaptureReceipt secondReceipt =
          second.get(10, TimeUnit.SECONDS);

      assertCaptureBindings(firstReceipt, POSITIVE_CAPTURE_REQUEST_ID);
      assertSameCaptureReceipt(firstReceipt, secondReceipt);
      assertThat(firstReceipt.capturedSource().issuerAuthGeneration()).isEqualTo("1");
      assertThat(firstReceipt.capturedSource().sourceVersion()).isEqualTo("1");
      assertThat(firstReceipt.capturedSource().outboxSequence()).isEqualTo("0");
      assertThat(firstReceipt.capturedSource().latestEvent()).isEmpty();
      assertDatabaseState(account, new DatabaseState(1L, 0L, 0L, 1L));
      assertThat(producer.readCurrent(ISSUER_ID).outboxSequence()).isZero();
    } finally {
      executor.shutdownNow();
      stop(server);
    }
  }

  @Test
  void postgresCaptureRejectsMalformedAndMissingSourceRequestsWithoutSqlMutation()
      throws Exception {
    AccountFixture account = newAccountFixture();
    AccountIssuerAuthorityEventProducer producer = account.producer();
    Server server = startServer(producer, account.captureService());

    try {
      assertDatabaseState(account, new DatabaseState(0L, 0L, 0L, 0L));
      ManagedChannel rawChannel = newRawClientChannel(server.getPort(), "game-session");
      try {
        CaptureIssuerProjectionForRuntimeRequest malformedRequest =
            CaptureIssuerProjectionForRuntimeRequest.newBuilder()
                .setIssuerId(ISSUER_ID)
                .setRequestId("not-a-canonical-uuid")
                .build();
        assertThatThrownBy(
                () ->
                    IssuerAuthorityServiceGrpc.newBlockingStub(rawChannel)
                        .withDeadlineAfter(5, TimeUnit.SECONDS)
                        .captureIssuerProjectionForRuntime(malformedRequest))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
      } finally {
        shutdown(rawChannel);
      }

      try (AccountIssuerAuthorityClient client = newClient(server.getPort(), "game-session")) {
        client.init();
        assertThatThrownBy(() -> client.captureProjection(NO_SOURCE_CAPTURE_REQUEST_ID))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .isEqualTo(Status.Code.FAILED_PRECONDITION));
      }

      assertDatabaseState(account, new DatabaseState(0L, 0L, 0L, 0L));
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
    Server server = startServer(producer, account.captureService());

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

  @ParameterizedTest(name = "capture postgres caller {0}")
  @ValueSource(strings = {"wrong-service", "wrong-namespace"})
  void postgresCaptureRejectsWrongGameSessionIdentityWithoutChangingAnyOwnerRows(
      String clientIdentity) throws Exception {
    AccountFixture account = newAccountFixture();
    account.seedIssuer();
    AccountIssuerAuthorityEventProducer producer = account.producer();
    IssuerAuthoritySnapshot before = producer.readCurrent(ISSUER_ID);
    Server server = startServer(producer, account.captureService());

    try (AccountIssuerAuthorityClient client = newClient(server.getPort(), clientIdentity)) {
      client.init();
      assertDatabaseState(account, new DatabaseState(1L, 0L, 0L, 0L));

      assertThatThrownBy(() -> client.captureProjection(POSITIVE_CAPTURE_REQUEST_ID))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.PERMISSION_DENIED));

      assertThat(producer.readCurrent(ISSUER_ID)).isEqualTo(before);
      assertDatabaseState(account, new DatabaseState(1L, 0L, 0L, 0L));
    } finally {
      stop(server);
    }
  }

  private AccountFixture newAccountFixture() {
    String schema = "issuer_authority_grpc_proof_" + UUID.randomUUID().toString().replace("-", "");
    if (schema.length() >= 63) {
      throw new IllegalStateException("Issuer proof schema must remain shorter than 63 bytes");
    }
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

  private Server startServer(
      AccountIssuerAuthorityEventProducer producer,
      AccountIssuerProjectionReconciliationService captureService)
      throws Exception {
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.forServer(
                    certificate("account.crt").toFile(), certificate("account.key").toFile())
                .trustManager(certificate("ca.crt").toFile())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(
            ServerInterceptors.intercept(
                new AccountIssuerAuthorityGrpcService(producer, captureService, NAMESPACE),
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

  private ManagedChannel newRawClientChannel(int port, String clientIdentity) throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(certificate(clientIdentity + ".crt").toString());
    tls.setPrivateKey(certificate(clientIdentity + ".key").toString());
    tls.setCaCert(certificate("ca.crt").toString());
    return new GrpcChannelFactory().buildChannel("localhost:" + port, 6565, tls, false);
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

  private void assertCaptureBindings(
      AccountIssuerAuthorityClient.ProjectionCaptureReceipt receipt, String requestId) {
    UUID requestUUID = UUID.fromString(requestId);
    String projectionKey = "session:game:auth:issuer-generation:v1:" + ISSUER_ID;
    assertThat(receipt.operationUUID()).isNotNull();
    assertThat(receipt.operationUUID()).isNotEqualTo(new UUID(0L, 0L));
    assertThat(receipt.requestUUID()).isEqualTo(requestUUID);
    assertThat(receipt.issuerId()).isEqualTo(ISSUER_ID);
    assertThat(receipt.callerWorkloadIdentity()).isEqualTo(GAME_SESSION_PEER_URI);
    assertThat(receipt.projectionKey()).isEqualTo(projectionKey);
    assertThat(receipt.requestDigestVersion()).isEqualTo(1);
    assertThat(receipt.capturedSource().issuerId()).isEqualTo(ISSUER_ID);
    assertThat(receipt.capturedSource().sourceScope()).isEqualTo("issuer/" + ISSUER_ID);
    assertThat(receipt.capturedSource().outboxStreamKey()).isEqualTo(STREAM_KEY);
    assertThat(receipt.requestDigest())
        .isEqualTo(
            AccountIssuerProjectionReconciliationRepository.Receipt.requestDigestFor(
                ISSUER_ID, GAME_SESSION_PEER_URI, projectionKey, requestUUID));
  }

  private void assertSameCaptureReceipt(
      AccountIssuerAuthorityClient.ProjectionCaptureReceipt first,
      AccountIssuerAuthorityClient.ProjectionCaptureReceipt second) {
    assertThat(second.operationUUID()).isEqualTo(first.operationUUID());
    assertThat(second.requestUUID()).isEqualTo(first.requestUUID());
    assertThat(second.issuerId()).isEqualTo(first.issuerId());
    assertThat(second.callerWorkloadIdentity()).isEqualTo(first.callerWorkloadIdentity());
    assertThat(second.projectionKey()).isEqualTo(first.projectionKey());
    assertThat(second.requestDigestVersion()).isEqualTo(first.requestDigestVersion());
    assertThat(second.requestDigest()).isEqualTo(first.requestDigest());
    assertThat(second.capturedSource().issuerAuthGeneration())
        .isEqualTo(first.capturedSource().issuerAuthGeneration());
    assertThat(second.capturedSource().sourceVersion())
        .isEqualTo(first.capturedSource().sourceVersion());
    assertThat(second.capturedSource().outboxSequence())
        .isEqualTo(first.capturedSource().outboxSequence());
    assertThat(
            second
                .capturedSource()
                .latestEvent()
                .map(IssuerGenerationAuthorityEvent::canonicalJson))
        .isEqualTo(
            first
                .capturedSource()
                .latestEvent()
                .map(IssuerGenerationAuthorityEvent::canonicalJson));
  }

  private void assertStoredEventBytes(AccountFixture account, long sequence, byte[] expected) {
    byte[] outboxPayload =
        account
            .transactionDsl()
            .resultQuery(
                "SELECT payload FROM account_authority_outbox_events "
                    + "WHERE outbox_stream_key = ? AND outbox_sequence = ?",
                STREAM_KEY,
                sequence)
            .fetchOne(0, byte[].class);
    assertThat(outboxPayload).containsExactly(expected);
  }

  private void assertDatabaseState(AccountFixture account, DatabaseState expected) {
    assertThat(account.databaseState()).isEqualTo(expected);
  }

  private static void shutdown(ManagedChannel channel) throws InterruptedException {
    channel.shutdownNow();
    channel.awaitTermination(2, TimeUnit.SECONDS);
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

    private AccountIssuerProjectionReconciliationService captureService() {
      return new AccountIssuerProjectionReconciliationService(
          ISSUER_ID,
          GAME_SESSION_PEER_URI,
          producer(),
          new AccountIssuerProjectionReconciliationRepository(transactionDsl),
          transactionManager);
    }

    private DatabaseState databaseState() {
      return new DatabaseState(
          count(
              "SELECT COUNT(*) FROM account_authority_generations "
                  + "WHERE scope_kind = 'ISSUER' AND issuer_id = ?",
              ISSUER_ID),
          count(
              "SELECT COUNT(*) FROM account_authority_outbox_streams "
                  + "WHERE outbox_stream_key = ?",
              STREAM_KEY),
          count(
              "SELECT COUNT(*) FROM account_authority_outbox_events "
                  + "WHERE outbox_stream_key = ?",
              STREAM_KEY),
          count(
              "SELECT COUNT(*) FROM account_issuer_projection_reconciliation_receipts "
                  + "WHERE issuer_id = ?",
              ISSUER_ID));
    }

    private StoredReceipt readReceipt(UUID requestId) {
      org.jooq.Record row =
          transactionDsl.fetchOne(
              "SELECT operation_id, request_digest, outbox_sequence, event_payload "
                  + "FROM account_issuer_projection_reconciliation_receipts "
                  + "WHERE issuer_id = ? AND request_id = ?",
              ISSUER_ID,
              requestId);
      if (row == null) {
        throw new IllegalStateException("Expected committed issuer capture receipt is absent");
      }
      byte[] requestDigest = row.get("request_digest", byte[].class);
      byte[] eventPayload = row.get("event_payload", byte[].class);
      if (requestDigest == null || eventPayload == null) {
        throw new IllegalStateException("Expected positive issuer capture evidence is absent");
      }
      return new StoredReceipt(
          row.get("operation_id", UUID.class),
          row.get("outbox_sequence", Long.class),
          requestDigest,
          eventPayload);
    }

    private long count(String sql, Object... bindings) {
      return Objects.requireNonNull(
          transactionDsl.resultQuery(sql, bindings).fetchOne(0, Long.class),
          "Issuer proof row count query returned no value");
    }
  }

  private record DatabaseState(
      long sourceRows, long outboxStreamRows, long outboxEventRows, long receiptRows) {}

  private record StoredReceipt(
      UUID operationId, long outboxSequence, byte[] requestDigest, byte[] eventPayload) {}
}
