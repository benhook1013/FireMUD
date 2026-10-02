package crossservice.net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.stub.AbstractStub;
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
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.account.v1.AcknowledgeIssuerProjectionForRuntimeRequest;
import net.firedevops.firemud.account.v1.CaptureIssuerProjectionForRuntimeRequest;
import net.firedevops.firemud.account.v1.IssuerAuthorityServiceGrpc;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionAcknowledgmentRepository;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionAcknowledgmentRepository.Acknowledgment;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionReconciliationRepository;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityGrpcService;
import net.firedevops.firemud.accountservice.service.AccountIssuerProjectionAcknowledgmentService;
import net.firedevops.firemud.accountservice.service.AccountIssuerProjectionReconciliationService;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import net.firedevops.firemud.common.account.authority.IssuerProjectionInstallationAcknowledgmentDigestV1;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.gamesession.client.AccountIssuerAuthorityClient;
import net.firedevops.firemud.gamesession.client.AccountIssuerAuthorityClient.InstallationAcknowledgmentReceipt;
import net.firedevops.firemud.gamesession.client.AccountIssuerAuthorityClient.ProjectionCaptureReceipt;
import net.firedevops.firemud.gamesession.service.IssuerAuthorityProjectionRedisContract;
import net.firedevops.firemud.gamesession.service.IssuerProjectionReconciliationInstaller;
import net.firedevops.firemud.gamesession.service.RedisIssuerAuthorityProjectionStore;
import net.firedevops.firemud.gamesession.service.RedisIssuerAuthorityProjectionStore.ApplyResult;
import net.firedevops.firemud.gamesession.service.RedisIssuerAuthorityProjectionStore.ProjectionSnapshot;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL-backed Account issuer handoffs composed with physical Game Session mTLS and Redis. */
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
  private static final String RECONCILE_GAP_REQUEST_ID = "77777777-7777-4777-8777-777777777777";
  private static final String RECONCILE_BASELINE_REQUEST_ID =
      "77777777-0000-4777-8777-777777777777";
  private static final String RECONCILE_ZERO_REQUEST_ID = "88888888-8888-4888-8888-888888888888";
  private static final String RECONCILE_RETRY_REQUEST_ID = "99999999-9999-4999-8999-999999999999";
  private static final String RECONCILE_CONCURRENT_REQUEST_ID =
      "aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa";
  private static final String RECONCILE_STALE_REQUEST_ID = "bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb";
  private static final String RECONCILE_RACE_REQUEST_ID = "cccccccc-3333-4333-8333-cccccccccccc";
  private static final String RECONCILE_REDIS_REFUSAL_REQUEST_ID =
      "dddddddd-4444-4444-8444-dddddddddddd";
  private static final String ORDINARY_EVENT_READ_REQUEST_ID =
      "eeeeeeee-5555-4555-8555-eeeeeeeeeeee";
  private static final String ACK_ZERO_REQUEST_ID = "12121212-1212-4121-8121-121212121212";
  private static final String ACK_POSITIVE_REQUEST_ID = "13131313-1313-4131-8131-131313131313";
  private static final String ACK_RETRY_REQUEST_ID = "14141414-1414-4141-8141-141414141414";
  private static final String ACK_CONCURRENT_REQUEST_ID = "15151515-1515-4151-8151-151515151515";
  private static final String ACK_STALE_REQUEST_ID = "16161616-1616-4161-8161-161616161616";
  private static final String ACK_MUTATION_REQUEST_ID = "17171717-1717-4171-8171-171717171717";
  private static final String ACK_WRONG_SERVER_REQUEST_ID = "18181818-1818-4181-8181-181818181818";
  private static final String COORD_PASSWORD = "issuer-reconciliation-proof-secret";
  private static final String APPLIED_AT = "2026-10-03T09:30:00Z";
  private static final ObjectMapper JSON = new ObjectMapper();
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
    Server server =
        startServer(producer, account.captureService(), account.acknowledgmentService());

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
    Server server =
        startServer(producer, account.captureService(), account.acknowledgmentService());

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
    Server server =
        startServer(producer, account.captureService(), account.acknowledgmentService());
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
  void postgresZeroAndPositiveInstallationsReceiveFullDurableAcknowledgmentsOverSocketMtls()
      throws Exception {
    AccountFixture account = newAccountFixture();
    account.seedIssuer();
    AccountIssuerAuthorityEventProducer producer = account.producer();

    try (InstallationFixture fixture = newInstallationFixture(account, producer)) {
      var zeroInstallation =
          fixture.installer().install(ACK_ZERO_REQUEST_ID, APPLIED_AT).receipt().orElseThrow();
      DatabaseState zeroInstalledState = new DatabaseState(1L, 0L, 0L, 1L);
      assertDatabaseState(account, zeroInstalledState);
      assertThat(account.acknowledgmentCount()).isZero();
      IssuerAuthoritySnapshot zeroSourceBeforeAck = producer.readCurrent(ISSUER_ID);

      InstallationAcknowledgmentReceipt zeroAcknowledgment =
          fixture.client().acknowledgeInstallation(zeroInstallation);
      assertSameSourceSnapshot(zeroSourceBeforeAck, producer.readCurrent(ISSUER_ID));
      assertAcknowledgmentBindings(zeroAcknowledgment, zeroInstallation);
      assertThat(
              JSON.readTree(zeroAcknowledgment.installedProjectionJson())
                  .has("lastAppliedSourceEventId"))
          .isFalse();
      assertDatabaseState(account, zeroInstalledState);
      assertThat(account.acknowledgmentCount()).isEqualTo(1L);
      assertStoredAcknowledgment(account, zeroAcknowledgment, zeroInstallation);

      IssuerGenerationAuthorityEvent positiveEvent = advance(producer, 1L);
      var positiveInstallation =
          fixture
              .installer()
              .install(ACK_POSITIVE_REQUEST_ID, "2026-10-03T09:31:00Z")
              .receipt()
              .orElseThrow();
      DatabaseState positiveInstalledState = new DatabaseState(1L, 1L, 1L, 2L);
      assertThat(positiveInstallation.capturedSource().issuerAuthGeneration()).isEqualTo("2");
      assertThat(positiveInstallation.capturedSource().sourceVersion()).isEqualTo("2");
      assertThat(positiveInstallation.capturedSource().outboxSequence()).isEqualTo("1");
      assertThat(positiveInstallation.capturedSource().latestEvent()).isPresent();
      assertSameEvent(
          positiveEvent, positiveInstallation.capturedSource().latestEvent().orElseThrow());
      assertDatabaseState(account, positiveInstalledState);
      IssuerAuthoritySnapshot positiveSourceBeforeAck = producer.readCurrent(ISSUER_ID);

      InstallationAcknowledgmentReceipt positiveAcknowledgment =
          fixture.client().acknowledgeInstallation(positiveInstallation);
      assertSameSourceSnapshot(positiveSourceBeforeAck, producer.readCurrent(ISSUER_ID));
      assertAcknowledgmentBindings(positiveAcknowledgment, positiveInstallation);
      JsonNode positiveJson = JSON.readTree(positiveAcknowledgment.installedProjectionJson());
      assertThat(positiveJson.path("lastAppliedIssuerGeneration").asText()).isEqualTo("2");
      assertThat(positiveJson.path("lastAppliedSourceOutboxSequence").asText()).isEqualTo("1");
      assertThat(positiveJson.path("lastAppliedSourceEventId").asText())
          .isEqualTo(positiveEvent.eventId());
      assertThat(positiveJson.path("lastAppliedSourceEventDigest").asText())
          .isEqualTo(positiveEvent.eventDigest());
      assertDatabaseState(account, positiveInstalledState);
      assertThat(account.acknowledgmentCount()).isEqualTo(2L);
      assertStoredAcknowledgment(account, positiveAcknowledgment, positiveInstallation);
      assertStoredEventBytes(account, 1L, positiveEvent.canonicalJsonUtf8());
    }
  }

  @Test
  void exactAcknowledgmentRetryReturnsOriginalAfterDiscardedResponseAndLaterSourceProgress()
      throws Exception {
    AccountFixture account = newAccountFixture();
    account.seedIssuer();
    AccountIssuerAuthorityEventProducer producer = account.producer();
    advance(producer, 1L);

    try (InstallationFixture fixture = newInstallationFixture(account, producer)) {
      var installation =
          fixture.installer().install(ACK_RETRY_REQUEST_ID, APPLIED_AT).receipt().orElseThrow();
      DatabaseState installedState = new DatabaseState(1L, 1L, 1L, 1L);
      assertDatabaseState(account, installedState);
      assertThat(account.acknowledgmentCount()).isZero();
      IssuerAuthoritySnapshot sourceBeforeDiscardedAck = producer.readCurrent(ISSUER_ID);

      // Discard the successful typed response as a lost-response simulation. This models the
      // caller losing its result without simulating a network outage or process restart.
      fixture.client().acknowledgeInstallation(installation);
      assertSameSourceSnapshot(sourceBeforeDiscardedAck, producer.readCurrent(ISSUER_ID));
      StoredAcknowledgment original = account.readAcknowledgment(installation.operationId());
      assertDatabaseState(account, installedState);
      assertThat(account.acknowledgmentCount()).isEqualTo(1L);

      IssuerGenerationAuthorityEvent laterEvent = advance(producer, 2L);
      DatabaseState afterSourceProgress = new DatabaseState(1L, 1L, 2L, 1L);
      assertDatabaseState(account, afterSourceProgress);
      IssuerAuthoritySnapshot sourceBeforeRetry = producer.readCurrent(ISSUER_ID);
      assertStoredEventBytes(
          account,
          1L,
          installation.capturedSource().latestEvent().orElseThrow().canonicalJsonUtf8());
      assertStoredEventBytes(account, 2L, laterEvent.canonicalJsonUtf8());

      InstallationAcknowledgmentReceipt retry =
          fixture.client().acknowledgeInstallation(installation);
      assertAcknowledgmentBindings(retry, installation);
      assertThat(retry.acknowledgmentId()).isEqualTo(original.acknowledgmentId());
      assertThat(retry.installedProjectionJson().getBytes(StandardCharsets.UTF_8))
          .containsExactly(original.installedProjectionJson());
      assertThat(retry.requestDigest()).isEqualTo(original.requestDigest());
      assertThat(JSON.readTree(retry.installedProjectionJson()).path("appliedAt").asText())
          .isEqualTo(APPLIED_AT);
      assertDatabaseState(account, afterSourceProgress);
      assertSameSourceSnapshot(sourceBeforeRetry, producer.readCurrent(ISSUER_ID));
      assertThat(account.acknowledgmentCount()).isEqualTo(1L);
      assertStoredAcknowledgment(account, retry, installation);
      assertThat(producer.readCurrent(ISSUER_ID).outboxSequence()).isEqualTo(2L);
    }
  }

  @Test
  void concurrentExactAcknowledgmentsReturnOneV44ResultOverSeparateSocketClients()
      throws Exception {
    AccountFixture account = newAccountFixture();
    account.seedIssuer();
    AccountIssuerAuthorityEventProducer producer = account.producer();
    advance(producer, 1L);

    try (InstallationFixture fixture = newInstallationFixture(account, producer);
        AccountIssuerAuthorityClient secondClient =
            newClient(fixture.server().getPort(), "game-session")) {
      secondClient.init();
      var installation =
          fixture
              .installer()
              .install(ACK_CONCURRENT_REQUEST_ID, APPLIED_AT)
              .receipt()
              .orElseThrow();
      assertDatabaseState(account, new DatabaseState(1L, 1L, 1L, 1L));
      assertThat(account.acknowledgmentCount()).isZero();
      IssuerAuthoritySnapshot sourceBeforeAck = producer.readCurrent(ISSUER_ID);
      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<InstallationAcknowledgmentReceipt> first =
            executor.submit(
                () -> concurrentAcknowledgment(fixture.client(), installation, ready, start));
        Future<InstallationAcknowledgmentReceipt> second =
            executor.submit(
                () -> concurrentAcknowledgment(secondClient, installation, ready, start));

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        InstallationAcknowledgmentReceipt firstResult = first.get(15, TimeUnit.SECONDS);
        InstallationAcknowledgmentReceipt secondResult = second.get(15, TimeUnit.SECONDS);
        assertSameSourceSnapshot(sourceBeforeAck, producer.readCurrent(ISSUER_ID));

        assertAcknowledgmentBindings(firstResult, installation);
        assertAcknowledgmentBindings(secondResult, installation);
        assertThat(secondResult.acknowledgmentId()).isEqualTo(firstResult.acknowledgmentId());
        assertThat(secondResult.requestDigest()).isEqualTo(firstResult.requestDigest());
        assertDatabaseState(account, new DatabaseState(1L, 1L, 1L, 1L));
        assertThat(account.acknowledgmentCount()).isEqualTo(1L);
        assertStoredAcknowledgment(account, firstResult, installation);
      } finally {
        executor.shutdownNow();
      }
    }
  }

  @Test
  void firstAcknowledgmentOfCaptureThatIsStaleAfterSourceAdvanceIsDeniedWithoutAckRow()
      throws Exception {
    AccountFixture account = newAccountFixture();
    account.seedIssuer();
    AccountIssuerAuthorityEventProducer producer = account.producer();
    advance(producer, 1L);

    try (InstallationFixture fixture = newInstallationFixture(account, producer)) {
      var installation =
          fixture.installer().install(ACK_STALE_REQUEST_ID, APPLIED_AT).receipt().orElseThrow();
      assertDatabaseState(account, new DatabaseState(1L, 1L, 1L, 1L));
      IssuerGenerationAuthorityEvent currentEvent = advance(producer, 2L);
      DatabaseState advancedState = new DatabaseState(1L, 1L, 2L, 1L);
      IssuerAuthoritySnapshot sourceBeforeDeniedAck = producer.readCurrent(ISSUER_ID);

      assertThatThrownBy(() -> fixture.client().acknowledgeInstallation(installation))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.FAILED_PRECONDITION));

      assertDatabaseState(account, advancedState);
      assertSameSourceSnapshot(sourceBeforeDeniedAck, producer.readCurrent(ISSUER_ID));
      assertThat(account.acknowledgmentCount()).isZero();
      assertThat(account.readAcknowledgmentOptional(installation.operationId())).isEmpty();
      assertThat(producer.readCurrent(ISSUER_ID).outboxSequence()).isEqualTo(2L);
      assertStoredEventBytes(
          account,
          1L,
          installation.capturedSource().latestEvent().orElseThrow().canonicalJsonUtf8());
      assertStoredEventBytes(account, 2L, currentEvent.canonicalJsonUtf8());
    }
  }

  @Test
  void rawSamePeerAcknowledgmentRejectsChangedProjectionBytesAndCaptureBinding() throws Exception {
    AccountFixture account = newAccountFixture();
    account.seedIssuer();
    AccountIssuerAuthorityEventProducer producer = account.producer();

    try (InstallationFixture fixture = newInstallationFixture(account, producer)) {
      var installation =
          fixture.installer().install(ACK_MUTATION_REQUEST_ID, APPLIED_AT).receipt().orElseThrow();
      DatabaseState committedState = new DatabaseState(1L, 0L, 0L, 1L);
      assertDatabaseState(account, committedState);
      assertThat(account.acknowledgmentCount()).isZero();
      IssuerAuthoritySnapshot sourceBeforeAcceptedAck = producer.readCurrent(ISSUER_ID);
      InstallationAcknowledgmentReceipt accepted =
          fixture.client().acknowledgeInstallation(installation);
      assertSameSourceSnapshot(sourceBeforeAcceptedAck, producer.readCurrent(ISSUER_ID));
      assertDatabaseState(account, committedState);
      assertThat(account.acknowledgmentCount()).isEqualTo(1L);
      IssuerAuthoritySnapshot sourceBeforeRawDenials = producer.readCurrent(ISSUER_ID);
      ManagedChannel channel = newRawClientChannel(fixture.server().getPort(), "game-session");
      try {
        String changedProjection =
            installation
                .projectionSnapshot()
                .json()
                .replace(
                    "\"appliedAt\":\"" + APPLIED_AT + "\"",
                    "\"appliedAt\": \"" + APPLIED_AT + "\"");
        assertThat(changedProjection).isNotEqualTo(installation.projectionSnapshot().json());
        assertRawAcknowledgmentDenied(
            channel,
            rawAcknowledgmentRequest(installation, changedProjection, installation.requestDigest()),
            Status.Code.ALREADY_EXISTS);
        assertRawAcknowledgmentDenied(
            channel,
            rawAcknowledgmentRequest(
                installation, installation.projectionSnapshot().json(), "f".repeat(64)),
            Status.Code.ALREADY_EXISTS);
      } finally {
        shutdown(channel);
      }

      assertDatabaseState(account, committedState);
      assertSameSourceSnapshot(sourceBeforeRawDenials, producer.readCurrent(ISSUER_ID));
      assertThat(account.acknowledgmentCount()).isEqualTo(1L);
      assertStoredAcknowledgment(account, accepted, installation);
    }
  }

  @ParameterizedTest(name = "acknowledgment server {0}")
  @ValueSource(strings = {"game-session", "wrong-account-namespace"})
  void wrongAccountServerIdentityCannotReturnTypedInstallationAcknowledgment(String serverIdentity)
      throws Exception {
    AccountFixture account = newAccountFixture();
    account.seedIssuer();
    AccountIssuerAuthorityEventProducer producer = account.producer();

    try (InstallationFixture fixture = newInstallationFixture(account, producer)) {
      var installation =
          fixture
              .installer()
              .install(ACK_WRONG_SERVER_REQUEST_ID, APPLIED_AT)
              .receipt()
              .orElseThrow();
      DatabaseState installedState = new DatabaseState(1L, 0L, 0L, 1L);
      assertDatabaseState(account, installedState);
      assertThat(account.acknowledgmentCount()).isZero();
      IssuerAuthoritySnapshot sourceBeforeWrongServerAck = producer.readCurrent(ISSUER_ID);
      Server wrongServer =
          startServer(
              serverIdentity, producer, account.captureService(), account.acknowledgmentService());
      try (AccountIssuerAuthorityClient wrongServerClient =
          newClient(wrongServer.getPort(), "game-session")) {
        wrongServerClient.init();
        assertThatThrownBy(() -> wrongServerClient.acknowledgeInstallation(installation))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(Status.fromThrowable(failure).getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
        assertSameSourceSnapshot(sourceBeforeWrongServerAck, producer.readCurrent(ISSUER_ID));
        assertDatabaseState(account, installedState);
      } finally {
        stop(wrongServer);
      }

      // The request may have committed at the owner despite rejection of its server identity;
      // retrying through the exact Account identity returns the same private typed result.
      InstallationAcknowledgmentReceipt accepted =
          fixture.client().acknowledgeInstallation(installation);
      assertSameSourceSnapshot(sourceBeforeWrongServerAck, producer.readCurrent(ISSUER_ID));
      assertAcknowledgmentBindings(accepted, installation);
      assertDatabaseState(account, new DatabaseState(1L, 0L, 0L, 1L));
      assertThat(account.acknowledgmentCount()).isEqualTo(1L);
      assertStoredAcknowledgment(account, accepted, installation);
    }
  }

  @Test
  void postgresCaptureRejectsMalformedAndMissingSourceRequestsWithoutSqlMutation()
      throws Exception {
    AccountFixture account = newAccountFixture();
    AccountIssuerAuthorityEventProducer producer = account.producer();
    Server server =
        startServer(producer, account.captureService(), account.acknowledgmentService());

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
    Server server =
        startServer(producer, account.captureService(), account.acknowledgmentService());

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
    Server server =
        startServer(producer, account.captureService(), account.acknowledgmentService());

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

  @Test
  void capturedLatestCheckpointInstallsAcrossMissedEventsThenOrdinaryDeliveryIsContiguous()
      throws Exception {
    AccountFixture account = newAccountFixture();
    account.seedIssuer();
    AccountIssuerAuthorityEventProducer producer = account.producer();
    advance(producer, 1L);

    try (InstallationFixture fixture = newInstallationFixture(account, producer)) {
      IssuerProjectionReconciliationInstaller.Result baseline =
          fixture.installer().install(RECONCILE_BASELINE_REQUEST_ID, "2026-10-03T09:29:00Z");
      assertThat(baseline.outcome())
          .isEqualTo(IssuerProjectionReconciliationInstaller.Outcome.INSTALLED);
      assertThat(baseline.receipt().orElseThrow().capturedSource().outboxSequence()).isEqualTo("1");

      advance(producer, 2L);
      advance(producer, 3L);
      IssuerProjectionReconciliationInstaller.Result installed =
          fixture.installer().install(RECONCILE_GAP_REQUEST_ID, APPLIED_AT);

      assertThat(installed.outcome())
          .isEqualTo(IssuerProjectionReconciliationInstaller.Outcome.INSTALLED);
      var receipt = installed.receipt().orElseThrow();
      assertThat(receipt.requestId()).isEqualTo(UUID.fromString(RECONCILE_GAP_REQUEST_ID));
      assertThat(receipt.capturedSource().issuerAuthGeneration()).isEqualTo("4");
      assertThat(receipt.capturedSource().sourceVersion()).isEqualTo("4");
      assertThat(receipt.capturedSource().outboxSequence()).isEqualTo("3");
      assertThat(receipt.capturedSource().latestEvent()).isPresent();

      ProjectionSnapshot installedProjection = receipt.projectionSnapshot();
      JsonNode installedJson = JSON.readTree(installedProjection.json());
      assertThat(installedJson.path("lastAppliedIssuerGeneration").asText()).isEqualTo("4");
      assertThat(installedJson.path("lastAppliedSourceOutboxSequence").asText()).isEqualTo("3");
      assertThat(installedJson.path("lastAppliedSourceEventId").asText())
          .isEqualTo(receipt.capturedSource().latestEvent().orElseThrow().eventId());
      assertThat(installedJson.path("lastAppliedSourceEventDigest").asText())
          .isEqualTo(receipt.capturedSource().latestEvent().orElseThrow().eventDigest());
      assertThat(installedJson.path("appliedAt").asText()).isEqualTo(APPLIED_AT);
      assertRedisExact(fixture.redis(), installedProjection);
      assertDatabaseState(account, new DatabaseState(1L, 1L, 3L, 2L));

      IssuerGenerationAuthorityEvent fourth = advance(producer, 4L);
      AccountIssuerAuthorityClient.SourceReadback delivered =
          fixture.client().readCommittedEvent(ORDINARY_EVENT_READ_REQUEST_ID, "4");
      assertThat(delivered.sourceSnapshot().outboxSequence()).isEqualTo("4");
      assertSameEvent(fourth, delivered.requestedEvent().orElseThrow());

      ApplyResult ordinaryEvent = fixture.redis().store().apply(delivered, "2026-10-03T09:31:00Z");
      assertThat(ordinaryEvent.outcome())
          .isEqualTo(RedisIssuerAuthorityProjectionStore.Outcome.APPLIED);
      JsonNode advancedJson = JSON.readTree(ordinaryEvent.snapshot().orElseThrow().json());
      assertThat(advancedJson.path("lastAppliedSourceOutboxSequence").asText()).isEqualTo("4");
      assertThat(advancedJson.path("lastAppliedIssuerGeneration").asText()).isEqualTo("5");
      assertRedisExact(fixture.redis(), ordinaryEvent.snapshot().orElseThrow());
      assertDatabaseState(account, new DatabaseState(1L, 1L, 4L, 2L));
    }
  }

  @Test
  void zeroCheckpointInstallsWithoutEventEvidenceThenAcceptsSequenceOne() throws Exception {
    AccountFixture account = newAccountFixture();
    account.seedIssuer();
    AccountIssuerAuthorityEventProducer producer = account.producer();

    try (InstallationFixture fixture = newInstallationFixture(account, producer)) {
      IssuerProjectionReconciliationInstaller.Result installed =
          fixture.installer().install(RECONCILE_ZERO_REQUEST_ID, APPLIED_AT);

      assertThat(installed.outcome())
          .isEqualTo(IssuerProjectionReconciliationInstaller.Outcome.INSTALLED);
      var receipt = installed.receipt().orElseThrow();
      assertThat(receipt.capturedSource().issuerAuthGeneration()).isEqualTo("1");
      assertThat(receipt.capturedSource().sourceVersion()).isEqualTo("1");
      assertThat(receipt.capturedSource().outboxSequence()).isEqualTo("0");
      assertThat(receipt.capturedSource().latestEvent()).isEmpty();
      JsonNode zeroJson = JSON.readTree(receipt.projectionSnapshot().json());
      assertThat(zeroJson.path("lastAppliedIssuerGeneration").asText()).isEqualTo("1");
      assertThat(zeroJson.path("lastAppliedSourceOutboxSequence").asText()).isEqualTo("0");
      assertThat(zeroJson.has("lastAppliedSourceEventId")).isFalse();
      assertThat(zeroJson.has("lastAppliedSourceEventDigest")).isFalse();
      assertRedisExact(fixture.redis(), receipt.projectionSnapshot());
      assertDatabaseState(account, new DatabaseState(1L, 0L, 0L, 1L));

      IssuerGenerationAuthorityEvent first = advance(producer, 1L);
      AccountIssuerAuthorityClient.SourceReadback delivered =
          fixture.client().readCommittedEvent(ORDINARY_EVENT_READ_REQUEST_ID, "1");
      assertThat(delivered.sourceSnapshot().outboxSequence()).isEqualTo("1");
      assertSameEvent(first, delivered.requestedEvent().orElseThrow());

      ApplyResult ordinaryEvent = fixture.redis().store().apply(delivered, "2026-10-03T09:31:00Z");
      assertThat(ordinaryEvent.outcome())
          .isEqualTo(RedisIssuerAuthorityProjectionStore.Outcome.APPLIED);
      JsonNode firstJson = JSON.readTree(ordinaryEvent.snapshot().orElseThrow().json());
      assertThat(firstJson.path("lastAppliedSourceOutboxSequence").asText()).isEqualTo("1");
      assertThat(firstJson.path("lastAppliedSourceEventId").asText()).isEqualTo(first.eventId());
      assertThat(firstJson.path("lastAppliedSourceEventDigest").asText())
          .isEqualTo(first.eventDigest());
      assertRedisExact(fixture.redis(), ordinaryEvent.snapshot().orElseThrow());
      assertDatabaseState(account, new DatabaseState(1L, 1L, 1L, 1L));
    }
  }

  @Test
  void exactInstallRetryPreservesOriginalReceiptProjectionBytesAndAppliedAt() throws Exception {
    AccountFixture account = newAccountFixture();
    account.seedIssuer();
    AccountIssuerAuthorityEventProducer producer = account.producer();
    advance(producer, 1L);

    try (InstallationFixture fixture = newInstallationFixture(account, producer)) {
      IssuerProjectionReconciliationInstaller.Result first =
          fixture.installer().install(RECONCILE_RETRY_REQUEST_ID, APPLIED_AT);
      assertThat(first.outcome())
          .isEqualTo(IssuerProjectionReconciliationInstaller.Outcome.INSTALLED);
      var originalReceipt = first.receipt().orElseThrow();
      RedisValue originalRedis = fixture.redis().read(originalReceipt.projectionSnapshot().key());
      DatabaseState committedState = new DatabaseState(1L, 1L, 1L, 1L);
      assertDatabaseState(account, committedState);

      IssuerProjectionReconciliationInstaller.Result retry =
          fixture.installer().install(RECONCILE_RETRY_REQUEST_ID, "2026-10-03T09:32:00Z");
      assertThat(retry.outcome())
          .isEqualTo(IssuerProjectionReconciliationInstaller.Outcome.REPLAYED);
      var retriedReceipt = retry.receipt().orElseThrow();
      assertThat(retriedReceipt.operationId()).isEqualTo(originalReceipt.operationId());
      assertThat(retriedReceipt.requestId()).isEqualTo(originalReceipt.requestId());
      assertThat(retriedReceipt.requestDigest()).isEqualTo(originalReceipt.requestDigest());
      assertThat(retriedReceipt.capturedSource().outboxSequence())
          .isEqualTo(originalReceipt.capturedSource().outboxSequence());
      assertThat(retriedReceipt.projectionSnapshot())
          .isEqualTo(originalReceipt.projectionSnapshot());
      RedisValue retriedRedis = fixture.redis().read(originalReceipt.projectionSnapshot().key());
      assertThat(retriedRedis.bytes()).containsExactly(originalRedis.bytes());
      assertThat(retriedRedis.ttlMillis()).isEqualTo(originalRedis.ttlMillis());
      assertThat(originalRedis.ttlMillis()).isEqualTo(-1L);
      assertThat(JSON.readTree(originalRedis.bytes()).path("appliedAt").asText())
          .isEqualTo(APPLIED_AT);
      assertDatabaseState(account, committedState);
    }
  }

  @Test
  void concurrentExactInstallersDoNotDuplicateAccountSourceOrCaptureRows() throws Exception {
    AccountFixture account = newAccountFixture();
    account.seedIssuer();
    AccountIssuerAuthorityEventProducer producer = account.producer();
    advance(producer, 1L);

    try (InstallationFixture fixture = newInstallationFixture(account, producer)) {
      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<IssuerProjectionReconciliationInstaller.Result> first =
            executor.submit(() -> concurrentInstall(fixture.installer(), ready, start));
        Future<IssuerProjectionReconciliationInstaller.Result> second =
            executor.submit(() -> concurrentInstall(fixture.installer(), ready, start));

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        var firstResult = first.get(15, TimeUnit.SECONDS);
        var secondResult = second.get(15, TimeUnit.SECONDS);
        var results = java.util.List.of(firstResult, secondResult);

        assertThat(results.stream().filter(result -> result.receipt().isPresent()).count())
            .isBetween(1L, 2L);
        assertThat(
                results.stream()
                    .filter(
                        result ->
                            result.outcome()
                                == IssuerProjectionReconciliationInstaller.Outcome.INSTALLED)
                    .count())
            .isEqualTo(1L);
        assertThat(results)
            .allSatisfy(
                result ->
                    assertThat(result.outcome())
                        .isIn(
                            IssuerProjectionReconciliationInstaller.Outcome.INSTALLED,
                            IssuerProjectionReconciliationInstaller.Outcome.REPLAYED,
                            IssuerProjectionReconciliationInstaller.Outcome.QUARANTINED));
        var receipts = results.stream().flatMap(result -> result.receipt().stream()).toList();
        assertThat(
                receipts.stream()
                    .map(IssuerProjectionReconciliationInstaller.InstallationReceipt::operationId)
                    .distinct())
            .hasSize(1);
        assertThat(
                receipts.stream()
                    .map(IssuerProjectionReconciliationInstaller.InstallationReceipt::requestId)
                    .distinct())
            .containsExactly(UUID.fromString(RECONCILE_CONCURRENT_REQUEST_ID));
        assertThat(
                receipts.stream()
                    .map(IssuerProjectionReconciliationInstaller.InstallationReceipt::requestDigest)
                    .distinct())
            .hasSize(1);
        assertDatabaseState(account, new DatabaseState(1L, 1L, 1L, 1L));

        ProjectionSnapshot installedProjection = receipts.get(0).projectionSnapshot();
        for (IssuerProjectionReconciliationInstaller.InstallationReceipt receipt : receipts) {
          assertThat(receipt.projectionSnapshot()).isEqualTo(installedProjection);
        }
        assertRedisExact(fixture.redis(), installedProjection);
      } finally {
        executor.shutdownNow();
      }
    }
  }

  @Test
  void capturedSourceThatAdvancesBeforeStoreWriteReturnsNoVerifiedReceipt() throws Exception {
    AccountFixture account = newAccountFixture();
    account.seedIssuer();
    AccountIssuerAuthorityEventProducer producer = account.producer();
    advance(producer, 1L);

    try (InstallationFixture fixture = newInstallationFixture(account, producer)) {
      ProjectionCaptureReceipt captured =
          fixture.client().captureProjection(RECONCILE_STALE_REQUEST_ID);
      assertThat(captured.capturedSource().outboxSequence()).isEqualTo("1");
      advance(producer, 2L);
      DatabaseState sourceAfterAdvance = new DatabaseState(1L, 1L, 2L, 1L);

      IssuerProjectionReconciliationInstaller.Result stale =
          fixture.installer().install(RECONCILE_STALE_REQUEST_ID, APPLIED_AT);
      assertThat(stale.outcome())
          .isEqualTo(IssuerProjectionReconciliationInstaller.Outcome.STALE_SOURCE);
      assertThat(stale.receipt()).isEmpty();
      assertThat(fixture.redis().read(projectionKey()).bytes()).isNull();
      assertThat(fixture.redis().read(projectionKey()).ttlMillis()).isEqualTo(-2L);
      assertDatabaseState(account, sourceAfterAdvance);

      ProjectionCaptureReceipt exactRetry =
          fixture.client().captureProjection(RECONCILE_STALE_REQUEST_ID);
      assertThat(exactRetry.operationUUID()).isEqualTo(captured.operationUUID());
      assertThat(exactRetry.requestDigest()).isEqualTo(captured.requestDigest());
      assertThat(exactRetry.capturedSource().outboxSequence()).isEqualTo("1");
      assertDatabaseState(account, sourceAfterAdvance);
    }
  }

  @Test
  void sourceAdvanceBetweenRedisWriteAndFinalAccountReadbackWithholdsReceipt() throws Exception {
    AccountFixture account = newAccountFixture();
    account.seedIssuer();
    AccountIssuerAuthorityEventProducer producer = account.producer();
    advance(producer, 1L);
    AtomicInteger currentReads = new AtomicInteger();
    BlockingGrpcStubCustomizer sourceAdvanceBeforeSecondRead =
        interceptorCustomizer(
            new ClientInterceptor() {
              @Override
              public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> interceptCall(
                  MethodDescriptor<RequestT, ResponseT> method,
                  CallOptions callOptions,
                  Channel next) {
                ClientCall<RequestT, ResponseT> call = next.newCall(method, callOptions);
                return new ForwardingClientCall.SimpleForwardingClientCall<>(call) {
                  @Override
                  public void start(
                      ClientCall.Listener<ResponseT> responseListener, Metadata headers) {
                    if ("account.v1.IssuerAuthorityService/ReadIssuerAuthorityForRuntime"
                            .equals(method.getFullMethodName())
                        && currentReads.incrementAndGet() == 2) {
                      advance(producer, 2L);
                    }
                    super.start(responseListener, headers);
                  }
                };
              }
            });

    try (InstallationFixture fixture =
        newInstallationFixture(account, producer, COORD_PASSWORD, sourceAdvanceBeforeSecondRead)) {
      IssuerProjectionReconciliationInstaller.Result stale =
          fixture.installer().install(RECONCILE_RACE_REQUEST_ID, APPLIED_AT);

      assertThat(currentReads.get()).isEqualTo(2);
      assertThat(stale.outcome())
          .isEqualTo(IssuerProjectionReconciliationInstaller.Outcome.STALE_SOURCE);
      assertThat(stale.receipt()).isEmpty();
      JsonNode retainedProjection = JSON.readTree(fixture.redis().read(projectionKey()).bytes());
      assertThat(retainedProjection.path("lastAppliedSourceOutboxSequence").asText())
          .isEqualTo("1");
      assertThat(retainedProjection.path("lastAppliedIssuerGeneration").asText()).isEqualTo("2");
      assertThat(retainedProjection.path("appliedAt").asText()).isEqualTo(APPLIED_AT);
      assertThat(fixture.redis().read(projectionKey()).ttlMillis()).isEqualTo(-1L);
      assertDatabaseState(account, new DatabaseState(1L, 1L, 2L, 1L));
      assertThat(producer.readCurrent(ISSUER_ID).outboxSequence()).isEqualTo(2L);

      ProjectionCaptureReceipt original =
          fixture.client().captureProjection(RECONCILE_RACE_REQUEST_ID);
      assertThat(original.capturedSource().outboxSequence()).isEqualTo("1");
      assertDatabaseState(account, new DatabaseState(1L, 1L, 2L, 1L));
    }
  }

  @Test
  void redisAuthenticationRefusalLeavesCapturedAccountReceiptAndSourceUntouched() throws Exception {
    AccountFixture account = newAccountFixture();
    account.seedIssuer();
    AccountIssuerAuthorityEventProducer producer = account.producer();
    advance(producer, 1L);
    IssuerAuthoritySnapshot sourceBeforeRefusal = producer.readCurrent(ISSUER_ID);

    try (InstallationFixture fixture =
        newInstallationFixture(
            account, producer, "wrong-issuer-proof-password", BlockingGrpcStubCustomizer.noop())) {
      assertThatThrownBy(
              () -> fixture.installer().install(RECONCILE_REDIS_REFUSAL_REQUEST_ID, APPLIED_AT))
          .satisfies(failure -> assertThat(exceptionMessageChain(failure)).contains("WRONGPASS"));
      DatabaseState capturedSource = new DatabaseState(1L, 1L, 1L, 1L);
      assertDatabaseState(account, capturedSource);
      IssuerAuthoritySnapshot sourceAfterRefusal = producer.readCurrent(ISSUER_ID);
      assertThat(sourceAfterRefusal.issuerId()).isEqualTo(sourceBeforeRefusal.issuerId());
      assertThat(sourceAfterRefusal.issuerAuthGeneration())
          .isEqualTo(sourceBeforeRefusal.issuerAuthGeneration());
      assertThat(sourceAfterRefusal.sourceVersion()).isEqualTo(sourceBeforeRefusal.sourceVersion());
      assertThat(sourceAfterRefusal.outboxStreamKey())
          .isEqualTo(sourceBeforeRefusal.outboxStreamKey());
      assertThat(sourceAfterRefusal.outboxSequence())
          .isEqualTo(sourceBeforeRefusal.outboxSequence());
      assertThat(sourceBeforeRefusal.latestEvent()).isPresent();
      assertThat(sourceAfterRefusal.latestEvent()).isPresent();
      assertSameEvent(
          sourceBeforeRefusal.latestEvent().orElseThrow(),
          sourceAfterRefusal.latestEvent().orElseThrow());

      ProjectionCaptureReceipt retained =
          fixture.client().captureProjection(RECONCILE_REDIS_REFUSAL_REQUEST_ID);
      assertThat(retained.capturedSource().outboxSequence()).isEqualTo("1");
      StoredReceipt storedReceipt =
          account.readReceipt(UUID.fromString(RECONCILE_REDIS_REFUSAL_REQUEST_ID));
      assertThat(retained.operationUUID()).isEqualTo(storedReceipt.operationId());
      assertThat(retained.requestDigest())
          .isEqualTo(HexFormat.of().formatHex(storedReceipt.requestDigest()));
      assertThat(fixture.redis().read(projectionKey()).bytes()).isNull();
      assertThat(fixture.redis().read(projectionKey()).ttlMillis()).isEqualTo(-2L);
      assertDatabaseState(account, capturedSource);
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

  private InstallationFixture newInstallationFixture(
      AccountFixture account, AccountIssuerAuthorityEventProducer producer) throws Exception {
    return newInstallationFixture(
        account, producer, COORD_PASSWORD, BlockingGrpcStubCustomizer.noop());
  }

  private InstallationFixture newInstallationFixture(
      AccountFixture account,
      AccountIssuerAuthorityEventProducer producer,
      String redisPassword,
      BlockingGrpcStubCustomizer stubCustomizer)
      throws Exception {
    RedisFixture redis = newRedisFixture(redisPassword);
    Server server = null;
    AccountIssuerAuthorityClient client = null;
    try {
      server = startServer(producer, account.captureService(), account.acknowledgmentService());
      client = newClient(server.getPort(), "game-session", stubCustomizer);
      client.init();
      return new InstallationFixture(
          server,
          client,
          redis,
          new IssuerProjectionReconciliationInstaller(client, redis.store()));
    } catch (Exception failure) {
      if (client != null) {
        client.close();
      }
      if (server != null) {
        stop(server);
      }
      redis.close();
      throw failure;
    }
  }

  private RedisFixture newRedisFixture(String password) {
    GenericContainer<?> redisContainer =
        new GenericContainer<>("redis:7.2-alpine")
            .withExposedPorts(6379)
            .withCommand(
                "redis-server",
                "--save",
                "",
                "--appendonly",
                "no",
                "--user",
                "gamesession_coord_app",
                "on",
                ">" + COORD_PASSWORD,
                "~session:game:auth:issuer-generation:v1:*",
                "+get",
                "+set",
                "+pttl",
                "+evalsha",
                "+script|load");
    redisContainer.start();
    RedisIssuerAuthorityProjectionStore store =
        new RedisIssuerAuthorityProjectionStore(
            NAMESPACE,
            new RedisIssuerAuthorityProjectionStore.CoordinationEndpoint(
                redisContainer.getHost(),
                redisContainer.getMappedPort(6379),
                "gamesession_coord_app",
                password),
            new RedisIssuerAuthorityProjectionStore.CacheRateLimitEndpoint(
                "redis-cache.test.invalid", 6380));
    LettuceConnectionFactory adminConnectionFactory = null;
    try {
      store.init();
      adminConnectionFactory =
          new LettuceConnectionFactory(
              new RedisStandaloneConfiguration(
                  redisContainer.getHost(), redisContainer.getMappedPort(6379)));
      adminConnectionFactory.afterPropertiesSet();
      StringRedisTemplate adminTemplate = new StringRedisTemplate(adminConnectionFactory);
      adminTemplate.afterPropertiesSet();
      return new RedisFixture(redisContainer, store, adminConnectionFactory, adminTemplate);
    } catch (RuntimeException failure) {
      store.close();
      if (adminConnectionFactory != null) {
        adminConnectionFactory.destroy();
      }
      redisContainer.stop();
      throw failure;
    }
  }

  private BlockingGrpcStubCustomizer interceptorCustomizer(ClientInterceptor interceptor) {
    return new BlockingGrpcStubCustomizer() {
      @Override
      public <T extends AbstractStub<T>> T customize(T stub) {
        return stub.withInterceptors(interceptor);
      }
    };
  }

  private IssuerGenerationAuthorityEvent advance(
      AccountIssuerAuthorityEventProducer producer, long expectedVersion) {
    return producer.advance(ISSUER_ID, UUID.randomUUID(), expectedVersion, expectedVersion);
  }

  private IssuerProjectionReconciliationInstaller.Result concurrentInstall(
      IssuerProjectionReconciliationInstaller installer, CountDownLatch ready, CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Concurrent installer did not reach the start gate");
    }
    return installer.install(RECONCILE_CONCURRENT_REQUEST_ID, APPLIED_AT);
  }

  private static String projectionKey() {
    return IssuerAuthorityProjectionRedisContract.keyForIssuer(ISSUER_ID);
  }

  private void assertRedisExact(RedisFixture redis, ProjectionSnapshot expected) {
    RedisValue actual = redis.read(expected.key());
    assertThat(actual.bytes()).containsExactly(expected.json().getBytes(StandardCharsets.UTF_8));
    assertThat(actual.ttlMillis()).isEqualTo(-1L);
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
      AccountIssuerProjectionReconciliationService captureService,
      AccountIssuerProjectionAcknowledgmentService acknowledgmentService)
      throws Exception {
    return startServer("account", producer, captureService, acknowledgmentService);
  }

  private Server startServer(
      String serverIdentity,
      AccountIssuerAuthorityEventProducer producer,
      AccountIssuerProjectionReconciliationService captureService,
      AccountIssuerProjectionAcknowledgmentService acknowledgmentService)
      throws Exception {
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .sslContext(
            GrpcSslContexts.forServer(
                    certificate(serverIdentity + ".crt").toFile(),
                    certificate(serverIdentity + ".key").toFile())
                .trustManager(certificate("ca.crt").toFile())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .addService(
            ServerInterceptors.intercept(
                new AccountIssuerAuthorityGrpcService(
                    producer, captureService, acknowledgmentService, NAMESPACE),
                new GrpcPeerIdentityInterceptor()))
        .build()
        .start();
  }

  private AccountIssuerAuthorityClient newClient(int port, String clientIdentity) throws Exception {
    return newClient(port, clientIdentity, BlockingGrpcStubCustomizer.noop());
  }

  private AccountIssuerAuthorityClient newClient(
      int port, String clientIdentity, BlockingGrpcStubCustomizer stubCustomizer) throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + port);
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(certificate(clientIdentity + ".crt").toString());
    tls.setPrivateKey(certificate(clientIdentity + ".key").toString());
    tls.setCaCert(certificate("ca.crt").toString());
    return new AccountIssuerAuthorityClient(
        endpoints, tls, new GrpcChannelFactory(), stubCustomizer, NAMESPACE, ISSUER_ID);
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
    assertThat(actual.canonicalJsonUtf8()).containsExactly(expected.canonicalJsonUtf8());
    assertThat(actual.eventDigest()).isEqualTo(expected.eventDigest());
  }

  private void assertSameSourceSnapshot(
      IssuerAuthoritySnapshot expected, IssuerAuthoritySnapshot actual) {
    assertThat(actual.issuerId()).isEqualTo(expected.issuerId());
    assertThat(actual.issuerAuthGeneration()).isEqualTo(expected.issuerAuthGeneration());
    assertThat(actual.sourceVersion()).isEqualTo(expected.sourceVersion());
    assertThat(actual.outboxStreamKey()).isEqualTo(expected.outboxStreamKey());
    assertThat(actual.outboxSequence()).isEqualTo(expected.outboxSequence());
    assertThat(actual.latestEvent().isPresent()).isEqualTo(expected.latestEvent().isPresent());
    if (expected.latestEvent().isPresent()) {
      assertSameEvent(expected.latestEvent().orElseThrow(), actual.latestEvent().orElseThrow());
    }
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

  private void assertAcknowledgmentBindings(
      InstallationAcknowledgmentReceipt acknowledgment,
      IssuerProjectionReconciliationInstaller.InstallationReceipt installation) {
    assertThat(acknowledgment.acknowledgmentId()).isNotNull();
    assertThat(acknowledgment.acknowledgmentId()).isNotEqualTo(new UUID(0L, 0L));
    assertThat(acknowledgment.captureOperationId()).isEqualTo(installation.operationId());
    assertThat(acknowledgment.captureRequestId()).isEqualTo(installation.requestId());
    assertThat(acknowledgment.issuerId()).isEqualTo(ISSUER_ID);
    assertThat(acknowledgment.targetNamespace()).isEqualTo(NAMESPACE);
    assertThat(acknowledgment.callerWorkloadIdentity()).isEqualTo(GAME_SESSION_PEER_URI);
    assertThat(acknowledgment.projectionKey()).isEqualTo(projectionKey());
    assertThat(acknowledgment.captureRequestDigestVersion()).isEqualTo(1);
    assertThat(acknowledgment.captureRequestDigest()).isEqualTo(installation.requestDigest());
    assertThat(acknowledgment.requestDigestVersion())
        .isEqualTo(IssuerProjectionInstallationAcknowledgmentDigestV1.VERSION);
    assertThat(acknowledgment.requestDigest())
        .isEqualTo(
            Acknowledgment.requestDigestFor(
                ISSUER_ID,
                GAME_SESSION_PEER_URI,
                projectionKey(),
                installation.operationId(),
                installation.requestId(),
                1,
                installation.requestDigest(),
                installation.projectionSnapshot().json()));
    assertThat(acknowledgment.installedProjectionJson())
        .isEqualTo(installation.projectionSnapshot().json());
    assertThat(acknowledgment.installedProjectionSha256())
        .isEqualTo(
            HexFormat.of()
                .formatHex(
                    sha256(
                        installation
                            .projectionSnapshot()
                            .json()
                            .getBytes(StandardCharsets.UTF_8))));
  }

  private void assertStoredAcknowledgment(
      AccountFixture account,
      InstallationAcknowledgmentReceipt acknowledgment,
      IssuerProjectionReconciliationInstaller.InstallationReceipt installation) {
    StoredAcknowledgment stored = account.readAcknowledgment(installation.operationId());
    assertThat(stored.acknowledgmentId()).isEqualTo(acknowledgment.acknowledgmentId());
    assertThat(stored.captureOperationId()).isEqualTo(acknowledgment.captureOperationId());
    assertThat(stored.captureRequestId()).isEqualTo(acknowledgment.captureRequestId());
    assertThat(stored.issuerId()).isEqualTo(acknowledgment.issuerId());
    assertThat(stored.callerWorkloadIdentity()).isEqualTo(acknowledgment.callerWorkloadIdentity());
    assertThat(stored.projectionKey()).isEqualTo(acknowledgment.projectionKey());
    assertThat(stored.captureRequestDigestVersion())
        .isEqualTo(acknowledgment.captureRequestDigestVersion());
    assertThat(stored.captureRequestDigest()).isEqualTo(acknowledgment.captureRequestDigest());
    assertThat(stored.requestDigestVersion()).isEqualTo(acknowledgment.requestDigestVersion());
    assertThat(stored.requestDigest()).isEqualTo(acknowledgment.requestDigest());
    assertThat(stored.installedProjectionJson())
        .containsExactly(acknowledgment.installedProjectionJson().getBytes(StandardCharsets.UTF_8));
    assertThat(stored.installedProjectionSha256())
        .isEqualTo(acknowledgment.installedProjectionSha256());
  }

  private AcknowledgeIssuerProjectionForRuntimeRequest rawAcknowledgmentRequest(
      IssuerProjectionReconciliationInstaller.InstallationReceipt installation,
      String installedProjectionJson,
      String captureRequestDigest) {
    return AcknowledgeIssuerProjectionForRuntimeRequest.newBuilder()
        .setIssuerId(ISSUER_ID)
        .setCaptureOperationId(installation.operationId().toString())
        .setCaptureRequestId(installation.requestId().toString())
        .setCaptureRequestDigestVersion(1)
        .setCaptureRequestDigest(captureRequestDigest)
        .setProjectionKey(projectionKey())
        .setInstalledProjectionJson(installedProjectionJson)
        .build();
  }

  private void assertRawAcknowledgmentDenied(
      ManagedChannel channel,
      AcknowledgeIssuerProjectionForRuntimeRequest request,
      Status.Code expectedCode) {
    assertThatThrownBy(
            () ->
                IssuerAuthorityServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .acknowledgeIssuerProjectionForRuntime(request))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expectedCode));
  }

  private InstallationAcknowledgmentReceipt concurrentAcknowledgment(
      AccountIssuerAuthorityClient client,
      IssuerProjectionReconciliationInstaller.InstallationReceipt installation,
      CountDownLatch ready,
      CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException(
          "Concurrent acknowledgment client did not reach the start gate");
    }
    return client.acknowledgeInstallation(installation);
  }

  private static byte[] sha256(byte[] value) {
    try {
      return java.security.MessageDigest.getInstance("SHA-256").digest(value);
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static String exceptionMessageChain(Throwable failure) {
    StringBuilder messages = new StringBuilder();
    Throwable current = failure;
    while (current != null) {
      if (current.getMessage() != null) {
        messages.append(current.getMessage()).append('\n');
      }
      current = current.getCause();
    }
    return messages.toString();
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

    private AccountIssuerProjectionAcknowledgmentService acknowledgmentService() {
      return new AccountIssuerProjectionAcknowledgmentService(
          ISSUER_ID,
          GAME_SESSION_PEER_URI,
          captureService(),
          new AccountIssuerProjectionAcknowledgmentRepository(transactionDsl),
          transactionManager);
    }

    private long acknowledgmentCount() {
      return count(
          "SELECT COUNT(*) FROM account_issuer_projection_installation_acknowledgments "
              + "WHERE issuer_id = ?",
          ISSUER_ID);
    }

    private java.util.Optional<StoredAcknowledgment> readAcknowledgmentOptional(
        UUID captureOperationId) {
      org.jooq.Record row =
          transactionDsl.fetchOne(
              "SELECT acknowledgment_id, capture_operation_id, capture_request_id, issuer_id, "
                  + "caller_workload_identity, projection_key, capture_request_digest_version, "
                  + "encode(capture_request_digest, 'hex') AS capture_request_digest, "
                  + "request_digest_version, encode(request_digest, 'hex') AS request_digest, "
                  + "installed_projection_json, "
                  + "encode(installed_projection_sha256, 'hex') AS installed_projection_sha256 "
                  + "FROM account_issuer_projection_installation_acknowledgments "
                  + "WHERE capture_operation_id = ?",
              captureOperationId);
      if (row == null) {
        return java.util.Optional.empty();
      }
      return java.util.Optional.of(
          new StoredAcknowledgment(
              row.get("acknowledgment_id", UUID.class),
              row.get("capture_operation_id", UUID.class),
              row.get("capture_request_id", UUID.class),
              row.get("issuer_id", String.class),
              row.get("caller_workload_identity", String.class),
              row.get("projection_key", String.class),
              row.get("capture_request_digest_version", Integer.class),
              row.get("capture_request_digest", String.class),
              row.get("request_digest_version", Integer.class),
              row.get("request_digest", String.class),
              row.get("installed_projection_json", byte[].class),
              row.get("installed_projection_sha256", String.class)));
    }

    private StoredAcknowledgment readAcknowledgment(UUID captureOperationId) {
      return readAcknowledgmentOptional(captureOperationId)
          .orElseThrow(
              () -> new IllegalStateException("Expected durable issuer acknowledgment is absent"));
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

  private record StoredAcknowledgment(
      UUID acknowledgmentId,
      UUID captureOperationId,
      UUID captureRequestId,
      String issuerId,
      String callerWorkloadIdentity,
      String projectionKey,
      int captureRequestDigestVersion,
      String captureRequestDigest,
      int requestDigestVersion,
      String requestDigest,
      byte[] installedProjectionJson,
      String installedProjectionSha256) {}

  private record RedisValue(byte[] bytes, long ttlMillis) {}

  private record InstallationFixture(
      Server server,
      AccountIssuerAuthorityClient client,
      RedisFixture redis,
      IssuerProjectionReconciliationInstaller installer)
      implements AutoCloseable {
    @Override
    public void close() throws Exception {
      try {
        client.close();
      } finally {
        try {
          redis.close();
        } finally {
          stop(server);
        }
      }
    }
  }

  private record RedisFixture(
      GenericContainer<?> container,
      RedisIssuerAuthorityProjectionStore store,
      LettuceConnectionFactory adminConnectionFactory,
      StringRedisTemplate adminTemplate)
      implements AutoCloseable {
    private RedisValue read(String key) {
      RedisValue value =
          adminTemplate.execute(
              (RedisCallback<RedisValue>)
                  connection -> {
                    byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                    byte[] bytes = connection.stringCommands().get(keyBytes);
                    long ttlMillis = bytes == null ? -2L : connection.keyCommands().pTtl(keyBytes);
                    return new RedisValue(bytes, ttlMillis);
                  });
      return Objects.requireNonNull(value, "Redis test read returned no result");
    }

    @Override
    public void close() {
      store.close();
      adminConnectionFactory.destroy();
      container.stop();
    }
  }
}
