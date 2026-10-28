package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.protobuf.ByteString;
import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionRequest;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerReservationFenceEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayAccountInitialBarrierRedisScriptContribution;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Uses actual SQL intent/target reads with a controlled local signer.
 *
 * <p>The controlled key/channel proves only the verifier's local current-observation algorithm. It
 * is not operational attestor custody or evidence that a real deployment has this incarnation.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayProtectedIncarnationReceiverPostgresIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String KEY_ID = "controlled-attestor-1";
  private static final String ATTESTOR_BINDING = "controlled-local-attestor-binding";
  private static final String ACCEPTED_ATTESTATION_DIGEST = "sha256:" + "a".repeat(64);
  private static final String ACL_DIGEST = "sha256:" + "b".repeat(64);
  private static final String CONFIG_DIGEST = "sha256:" + "c".repeat(64);
  private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  @Test
  void localDescriptorFailuresRetainNeverDispatchedOriginalForLaterValidPreparation()
      throws Exception {
    try (Fixture.InitialFixture fixture = Fixture.InitialFixture.open();
        InitialRedisFixture physical = InitialRedisFixture.open();
        var login = fixture.originalLogin()) {
      var snapshot = fixture.initialRepository.readCurrent(fixture.original.requestId());
      String retained = fixture.initialTargetPostimage();
      String key =
          CanonicalGameplayAccountInitialBarrierKey.forTarget(
              fixture.tenantTags.assignmentForInitialBarrierTarget(fixture.original.requestId()),
              snapshot.initializerId(),
              fixture.original.requestId());
      RedisClient undispatched = mock(RedisClient.class);
      RedisScriptCatalog catalog = mock(RedisScriptCatalog.class);
      when(catalog.require(CanonicalGameplayAccountInitialBarrierRedisScriptContribution.SCRIPT_ID))
          .thenThrow(new IllegalStateException("missing local descriptor"))
          .thenReturn(mock(RedisScriptDescriptor.class));
      var owner = fixture.initialOwner(undispatched, fixture.initialRepository, catalog);
      for (int failure = 0; failure < 2; failure++) {
        assertThat(
                owner.initializeOnce(
                    fixture.original, login, fixture.tenantId, fixture.namespaceId))
            .isEqualTo(
                new CanonicalGameplayAccountInitialOpenProof.Refused(
                    CanonicalGameplayAccountInitialOpenProof.Reason.TARGET_NEVER_DISPATCHED));
        assertThat(fixture.initialRepository.readCurrent(fixture.original.requestId()).state())
            .isEqualTo(
                CanonicalGameplayAccountInitialBarrierInitializerRepository.State.NEVER_DISPATCHED);
        assertThat(fixture.initialTargetPostimage()).isEqualTo(retained);
        assertThat(physical.connection.sync().exists(key)).isZero();
      }
      org.mockito.Mockito.verifyNoInteractions(undispatched);
      var validOwner = fixture.initialOwner(physical.ownerClient(), fixture.initialRepository);
      assertThat(
              validOwner.initializeOnce(
                  fixture.original, login, fixture.tenantId, fixture.namespaceId))
          .isInstanceOf(CanonicalGameplayAccountInitialOpenProof.CurrentOpen.class);
      assertThat(
              fixture.initialRepository.readCurrent(fixture.original.requestId()).initializerId())
          .isEqualTo(snapshot.initializerId());
      physical.connection.sync().del(key);
    }
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void remotePreflightFailuresRetainNeverDispatchedOriginalForValidRetry() throws Exception {
    try (Fixture.InitialFixture fixture = Fixture.InitialFixture.open();
        InitialRedisFixture physical = InitialRedisFixture.open();
        var login = fixture.originalLogin();
        var loadConnection = physical.ownerClient.connect(ByteArrayCodec.INSTANCE)) {
      var snapshot = fixture.initialRepository.readCurrent(fixture.original.requestId());
      String retained = fixture.initialTargetPostimage();
      String key =
          CanonicalGameplayAccountInitialBarrierKey.forTarget(
              fixture.tenantTags.assignmentForInitialBarrierTarget(fixture.original.requestId()),
              snapshot.initializerId(),
              fixture.original.requestId());
      RedisClient connectFailure = mock(RedisClient.class);
      when(connectFailure.connect(any(ByteArrayCodec.class)))
          .thenThrow(new IllegalStateException("controlled connect failure"));
      RedisClient loadFailure = mock(RedisClient.class);
      StatefulRedisConnection<byte[], byte[]> connection = spy(loadConnection);
      RedisAsyncCommands<byte[], byte[]> commands = spy(loadConnection.async());
      doReturn(commands).when(connection).async();
      doThrow(new IllegalStateException("controlled SCRIPT LOAD failure"))
          .when(commands)
          .scriptLoad(any(byte[].class));
      when(loadFailure.connect(any(ByteArrayCodec.class))).thenReturn(connection);
      // The actual administrator connection reports principal 'default', which must be refused.
      for (RedisClient failingClient :
          List.of(connectFailure, physical.administrator, loadFailure)) {
        var owner = fixture.initialOwner(failingClient, fixture.initialRepository);
        assertThat(
                owner.initializeOnce(
                    fixture.original, login, fixture.tenantId, fixture.namespaceId))
            .isEqualTo(
                new CanonicalGameplayAccountInitialOpenProof.Refused(
                    CanonicalGameplayAccountInitialOpenProof.Reason.TARGET_NEVER_DISPATCHED));
        assertThat(fixture.initialTargetPostimage()).isEqualTo(retained);
        assertThat(fixture.initialRepository.readCurrent(fixture.original.requestId()).state())
            .isEqualTo(
                CanonicalGameplayAccountInitialBarrierInitializerRepository.State.NEVER_DISPATCHED);
        assertThat(physical.connection.sync().exists(key)).isZero();
      }
      assertThat(
              fixture
                  .initialOwner(physical.ownerClient, fixture.initialRepository)
                  .initializeOnce(fixture.original, login, fixture.tenantId, fixture.namespaceId))
          .isInstanceOf(CanonicalGameplayAccountInitialOpenProof.CurrentOpen.class);
      assertThat(
              fixture.initialRepository.readCurrent(fixture.original.requestId()).initializerId())
          .isEqualTo(snapshot.initializerId());
      physical.connection.sync().del(key);
    }
  }

  @Test
  void lostDispatchClaimReplyLeavesMissingAccountOpenUncertainWithoutAnotherCreate()
      throws Exception {
    String sensitiveMessage = "secret-dispatch-claim-reply-token-and-source-93746";
    Logger logger =
        (Logger) LoggerFactory.getLogger(CanonicalGameplayAccountInitialBarrierRedisOwner.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try (Fixture.InitialFixture fixture = Fixture.InitialFixture.open();
        InitialRedisFixture physical = InitialRedisFixture.open();
        var login = fixture.originalLogin()) {
      var repository = spy(fixture.initialRepository);
      org.mockito.Mockito.doAnswer(
              invocation -> {
                invocation.callRealMethod();
                throw new IllegalStateException(sensitiveMessage);
              })
          .when(repository)
          .recordDispatchPossible(any(), any());
      var owner = fixture.initialOwner(physical.ownerClient, repository);
      assertThat(
              owner.initializeOnce(fixture.original, login, fixture.tenantId, fixture.namespaceId))
          .isInstanceOf(CanonicalGameplayAccountInitialOpenProof.Refused.class);
      var snapshot = fixture.initialRepository.readCurrent(fixture.original.requestId());
      assertThat(snapshot.state())
          .isEqualTo(
              CanonicalGameplayAccountInitialBarrierInitializerRepository.State.DISPATCH_POSSIBLE);
      String retained = fixture.initialTargetPostimage();
      String key =
          CanonicalGameplayAccountInitialBarrierKey.forTarget(
              fixture.tenantTags.assignmentForInitialBarrierTarget(fixture.original.requestId()),
              snapshot.initializerId(),
              fixture.original.requestId());
      assertThat(physical.connection.sync().exists(key)).isZero();
      var retry = fixture.initialOwner(physical.ownerClient, fixture.initialRepository);
      assertThat(
              retry.initializeOnce(fixture.original, login, fixture.tenantId, fixture.namespaceId))
          .isInstanceOf(CanonicalGameplayAccountInitialOpenProof.Refused.class);
      assertThat(fixture.initialTargetPostimage()).isEqualTo(retained);
      assertThat(physical.connection.sync().exists(key)).isZero();
      org.mockito.Mockito.verify(repository).recordDispatchPossible(any(), any());
      assertThat(appender.list).hasSize(1);
      ILoggingEvent event = appender.list.getFirst();
      assertThat(event.getLevel()).isEqualTo(Level.WARN);
      assertThat(event.getFormattedMessage())
          .isEqualTo(
              "Initial Account OPEN create attempt did not complete originalRequestId="
                  + snapshot.originalRequestId()
                  + " initializerId="
                  + snapshot.initializerId()
                  + " failureType="
                  + IllegalStateException.class.getName())
          .doesNotContain(sensitiveMessage);
      assertThat(event.getArgumentArray())
          .containsExactly(
              snapshot.originalRequestId(),
              snapshot.initializerId(),
              IllegalStateException.class.getName());
      assertThat(event.getThrowableProxy()).isNull();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void abandonedNeverDispatchedOriginalPreventsFreshInitialForLaterLogin() throws Exception {
    try (Fixture.InitialFixture fixture = Fixture.InitialFixture.open()) {
      String initializer =
          fixture
              .dsl
              .fetchSingle(
                  "SELECT to_jsonb(i)::text FROM"
                      + " game_session_canonical_initial_account_barrier_initializer i")
              .get(0, String.class);
      String target = fixture.initialTargetPostimage();
      fixture.dsl.execute(
          "UPDATE game_session_initial_gameplay_login_journal"
              + " SET abandoned = TRUE, abandoned_at = clock_timestamp() WHERE request_id = ?",
          fixture.original.requestId());
      String abandonedJournal =
          fixture
              .dsl
              .fetchSingle(
                  "SELECT to_jsonb(j)::text FROM game_session_initial_gameplay_login_journal j"
                      + " WHERE request_id = ?",
                  fixture.original.requestId())
              .get(0, String.class);
      var later =
          new InitialGameplayLoginAttemptCoordinates(UUID.randomUUID(), UUID.randomUUID(), 18L);
      try (var login =
          Fixture.mockInitialLoginResult(later, fixture.accountId, UUID.randomUUID())) {
        fixture.dsl.execute(
            "INSERT INTO game_session_initial_gameplay_login_journal"
                + " (request_id, caller_context_id, expected_attempt_fence) VALUES (?, ?, ?)",
            later.requestId(),
            later.callerContextId(),
            later.expectedAttemptFence());
        fixture.dsl.execute(
            "UPDATE game_session_initial_gameplay_login_journal"
                + " SET verified_token_jti = ?, verified_token_sha256 = ?,"
                + " verified_token_generation = 1, verified_issuance_fence = 1"
                + " WHERE request_id = ?",
            login.evidence().tokenJti(),
            login.evidence().tokenSha256(),
            later.requestId());
        assertThatThrownBy(
                () ->
                    fixture.initialRepository.beginOrRead(
                        later, login, fixture.tenantId, fixture.namespaceId))
            .hasStackTraceContaining("uq_gs_initial_account_barrier_never_retarget");
      }
      assertThat(
              fixture
                  .dsl
                  .fetchSingle(
                      "SELECT to_jsonb(i)::text FROM"
                          + " game_session_canonical_initial_account_barrier_initializer i")
                  .get(0, String.class))
          .isEqualTo(initializer);
      assertThat(fixture.initialTargetPostimage()).isEqualTo(target);
      assertThat(
              fixture
                  .dsl
                  .fetchSingle(
                      "SELECT to_jsonb(j)::text FROM game_session_initial_gameplay_login_journal j"
                          + " WHERE request_id = ?",
                      fixture.original.requestId())
                  .get(0, String.class))
          .isEqualTo(abandonedJournal);
      assertThat(
              fixture
                  .dsl
                  .fetchSingle(
                      "SELECT target_state FROM game_session_canonical_initial_account_barrier_target"
                          + " WHERE original_request_id = ?",
                      fixture.original.requestId())
                  .get(0, String.class))
          .isEqualTo("NEVER_DISPATCHED");
    }
  }

  @Test
  void initialTagAssignmentAndDispatchSerializeInventoryBeforeControlInBothOrders()
      throws Exception {
    for (boolean tagFirst : List.of(true, false)) {
      try (Fixture.InitialFixture fixture = Fixture.InitialFixture.open();
          var holder = Fixture.dataSource(fixture.schema).getConnection();
          var tagConnection = Fixture.dataSource(fixture.schema).getConnection();
          var dispatchConnection = Fixture.dataSource(fixture.schema).getConnection()) {
        var observation =
            fixture
                .receiver(new ControlledSigner(fixture.keyPair))
                .observeForInitialAccountBarrier(fixture.original.requestId());
        DSLContext holderDsl = DSL.using(holder, SQLDialect.POSTGRES);
        DSLContext tagDsl = DSL.using(tagConnection, SQLDialect.POSTGRES);
        DSLContext dispatchDsl = DSL.using(dispatchConnection, SQLDialect.POSTGRES);
        int holderPid =
            java.util.Objects.requireNonNull(
                holderDsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
        int tagPid =
            java.util.Objects.requireNonNull(
                tagDsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
        int dispatchPid =
            java.util.Objects.requireNonNull(
                dispatchDsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
        var tags =
            new CanonicalGameplayTenantGameplayTagRepository(
                tagDsl, fixture.targetWorkloadNamespace, 1);
        var repository =
            new CanonicalGameplayAccountInitialBarrierInitializerRepository(
                dispatchDsl, Clock.fixed(NOW, ZoneOffset.UTC));
        java.util.concurrent.Callable<Object> tag =
            () -> tags.assignmentForInitialBarrierTarget(fixture.original.requestId());
        java.util.concurrent.Callable<Object> dispatch =
            () -> repository.recordDispatchPossible(fixture.original.requestId(), observation);
        holder.setAutoCommit(false);
        holderDsl.fetchSingle(
            "SELECT account_id FROM game_session_canonical_account_coverage_control"
                + " WHERE account_id = ? FOR UPDATE",
            fixture.accountId);
        try (var executor = Executors.newFixedThreadPool(2)) {
          try {
            var first = executor.submit(tagFirst ? tag : dispatch);
            int firstPid = tagFirst ? tagPid : dispatchPid;
            int secondPid = tagFirst ? dispatchPid : tagPid;
            awaitInitialBackendBlockedBy(fixture.dsl, firstPid, holderPid);
            var second = executor.submit(tagFirst ? dispatch : tag);
            awaitInitialBackendBlockedBy(fixture.dsl, secondPid, firstPid);
            // Neither waiter may own target rows before it owns account control.
            holderDsl.fetchSingle(
                "SELECT initializer_id FROM game_session_canonical_initial_account_barrier_initializer"
                    + " WHERE original_request_id = ? FOR UPDATE NOWAIT",
                fixture.original.requestId());
            holder.rollback();
            assertThat(first.get(5L, TimeUnit.SECONDS)).isNotNull();
            assertThat(second.get(5L, TimeUnit.SECONDS)).isNotNull();
          } finally {
            holder.rollback();
          }
        }
        assertThat(fixture.initialRepository.readCurrent(fixture.original.requestId()).state())
            .isEqualTo(
                CanonicalGameplayAccountInitialBarrierInitializerRepository.State
                    .DISPATCH_POSSIBLE);
      }
    }
  }

  private static void awaitInitialBackendBlockedBy(
      DSLContext observer, int waitingPid, int holderPid) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    boolean blocked = false;
    while (System.nanoTime() < deadline) {
      blocked =
          Boolean.TRUE.equals(
              observer
                  .fetchSingle("SELECT ? = ANY(pg_blocking_pids(?))", holderPid, waitingPid)
                  .get(0, Boolean.class));
      if (blocked) break;
      TimeUnit.MILLISECONDS.sleep(10L);
    }
    assertThat(blocked)
        .as("PostgreSQL backend %s waits on exact holder %s", waitingPid, holderPid)
        .isTrue();
  }

  @Test
  void initialOwnerComposesSqlSignedObservationAndRedisWithoutRecreatingResetOpen()
      throws Exception {
    for (BigInteger epoch : List.of(BigInteger.ZERO, BigInteger.valueOf(8))) {
      try (Fixture.InitialFixture fixture = Fixture.InitialFixture.open(epoch);
          InitialRedisFixture physical = InitialRedisFixture.open();
          CanonicalGameplayLoginResult login = fixture.originalLogin()) {
        var owner = fixture.initialOwner(physical.ownerClient(), fixture.initialRepository);
        var initialized =
            owner.initializeOnce(fixture.original, login, fixture.tenantId, fixture.namespaceId);
        assertThat(initialized)
            .isInstanceOf(CanonicalGameplayAccountInitialOpenProof.CurrentOpen.class);
        var opened = (CanonicalGameplayAccountInitialOpenProof.CurrentOpen) initialized;
        assertThat(opened.sourceControlEpoch()).isEqualTo(epoch);
        assertThat(opened.exactFields()).hasSize(9);
        assertThat(physical.connection().sync().hgetall(opened.key()))
            .isEqualTo(opened.exactFields());
        String retained = fixture.initialTargetPostimage();

        var replay = owner.verifyCurrentOpen(fixture.original.requestId());
        assertThat(replay).isInstanceOf(CanonicalGameplayAccountInitialOpenProof.CurrentOpen.class);
        assertThat(((CanonicalGameplayAccountInitialOpenProof.CurrentOpen) replay).exactFields())
            .isEqualTo(opened.exactFields());
        assertThat(fixture.initialTargetPostimage()).isEqualTo(retained);

        fixture.dsl.execute(
            "UPDATE game_session_initial_gameplay_login_journal"
                + " SET abandoned = true, abandoned_at = CURRENT_TIMESTAMP"
                + " WHERE request_id = ?",
            fixture.original.requestId());
        // The installed target is storage evidence independent of a later caller. The old LOGIN
        // cannot create anything, while another binding may verify the same current target.
        assertThat(
                owner.verifyCurrentOpenForTarget(
                    fixture.accountId, fixture.tenantId, fixture.namespaceId))
            .isInstanceOf(CanonicalGameplayAccountInitialOpenProof.CurrentOpen.class);
        assertThat(
                owner.initializeOnce(
                    fixture.original, login, fixture.tenantId, fixture.namespaceId))
            .isInstanceOf(CanonicalGameplayAccountInitialOpenProof.Refused.class);
        assertThat(physical.connection().sync().hgetall(opened.key()))
            .isEqualTo(opened.exactFields());
        assertThat(fixture.initialTargetPostimage()).isEqualTo(retained);

        physical.connection().sync().del(opened.key());
        assertThat(owner.verifyCurrentOpen(fixture.original.requestId()))
            .isInstanceOf(CanonicalGameplayAccountInitialOpenProof.Refused.class);
        assertThat(
                owner.initializeOnce(
                    fixture.original, login, fixture.tenantId, fixture.namespaceId))
            .isInstanceOf(CanonicalGameplayAccountInitialOpenProof.Refused.class);
        assertThat(physical.connection().sync().exists(opened.key())).isZero();
        assertThat(fixture.initialTargetPostimage()).isEqualTo(retained);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"INVENTORY", "CONTROL"})
  void initialOpenReadbackExpiredDuringSqlLockWaitCannotCompleteOriginal(String heldLock)
      throws Exception {
    AtomicLong trustedMillis = new AtomicLong(NOW.toEpochMilli());
    Clock trustedClock = mock(Clock.class);
    when(trustedClock.millis()).thenAnswer(ignored -> trustedMillis.get());
    try (Fixture.InitialFixture fixture = Fixture.InitialFixture.open();
        InitialRedisFixture physical = InitialRedisFixture.open();
        var login = fixture.originalLogin();
        var holder = Fixture.dataSource(fixture.schema).getConnection();
        var acceptanceConnection = Fixture.dataSource(fixture.schema).getConnection()) {
      var repository =
          spy(
              new CanonicalGameplayAccountInitialBarrierInitializerRepository(
                  fixture.dsl, trustedClock));
      AtomicReference<CanonicalGameplayAccountInitialBarrierRedisOwner.Readback> captured =
          new AtomicReference<>();
      org.mockito.Mockito.doAnswer(
              invocation -> {
                captured.set(invocation.getArgument(1));
                throw new IllegalStateException("controlled capture before SQL completion");
              })
          .when(repository)
          .acceptOpenReadback(eq(fixture.original.requestId()), any());
      var owner =
          fixture.initialOwner(
              physical.ownerClient(),
              repository,
              RedisScriptCatalog.fromContributions(
                  List.of(new CanonicalGameplayAccountInitialBarrierRedisScriptContribution())),
              trustedClock);
      assertThat(
              owner.initializeOnce(fixture.original, login, fixture.tenantId, fixture.namespaceId))
          .isInstanceOf(CanonicalGameplayAccountInitialOpenProof.Refused.class);
      var readback = java.util.Objects.requireNonNull(captured.get());
      assertThat(trustedMillis.get())
          .isGreaterThanOrEqualTo(
              Math.max(
                  readback.beforeIncarnation().issuedAtEpochMillis(),
                  readback.afterIncarnation().issuedAtEpochMillis()))
          .isLessThan(
              Math.min(
                  readback.beforeIncarnation().expiresAtEpochMillis(),
                  readback.afterIncarnation().expiresAtEpochMillis()));
      assertThat(fixture.initialRepository.readCurrent(fixture.original.requestId()).state())
          .isEqualTo(
              CanonicalGameplayAccountInitialBarrierInitializerRepository.State.DISPATCH_POSSIBLE);
      String retainedTarget = fixture.initialTargetPostimage();
      Map<String, String> retainedPhysical = physical.connection().sync().hgetall(readback.key());
      assertThat(retainedPhysical).isEqualTo(readback.barrierFields());
      DSLContext holderDsl = DSL.using(holder, SQLDialect.POSTGRES);
      DSLContext acceptanceDsl = DSL.using(acceptanceConnection, SQLDialect.POSTGRES);
      int holderPid =
          java.util.Objects.requireNonNull(
              holderDsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
      int acceptancePid =
          java.util.Objects.requireNonNull(
              acceptanceDsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
      var acceptanceRepository =
          new CanonicalGameplayAccountInitialBarrierInitializerRepository(
              acceptanceDsl, trustedClock);
      holder.setAutoCommit(false);
      if ("INVENTORY".equals(heldLock)) {
        holderDsl.fetchSingle(
            "SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock"
                + " WHERE singleton_id = 1 FOR UPDATE");
      } else {
        holderDsl.fetchSingle(
            "SELECT account_id FROM game_session_canonical_account_coverage_control"
                + " WHERE account_id = ? FOR UPDATE",
            fixture.accountId);
      }
      try (var executor = Executors.newSingleThreadExecutor()) {
        try {
          var acceptance =
              executor.submit(
                  () ->
                      acceptanceRepository.acceptOpenReadback(
                          fixture.original.requestId(), readback));
          awaitInitialBackendBlockedBy(fixture.dsl, acceptancePid, holderPid);
          trustedMillis.set(
              Math.max(
                      readback.beforeIncarnation().expiresAtEpochMillis(),
                      readback.afterIncarnation().expiresAtEpochMillis())
                  + 1L);
          holder.rollback();
          assertThatThrownBy(() -> acceptance.get(5L, TimeUnit.SECONDS))
              .hasStackTraceContaining(
                  "INITIAL protected incarnation observation expired before SQL completion");
        } finally {
          holder.rollback();
        }
      }
      assertThat(fixture.initialRepository.readCurrent(fixture.original.requestId()).state())
          .isEqualTo(
              CanonicalGameplayAccountInitialBarrierInitializerRepository.State.DISPATCH_POSSIBLE);
      assertThat(fixture.initialTargetPostimage()).isEqualTo(retainedTarget);
      assertThat(physical.connection().sync().hgetall(readback.key())).isEqualTo(retainedPhysical);
      physical.connection().sync().del(readback.key());
    }
  }

  @Test
  void initialOwnerRecoversLostSqlReceiptFromExactPhysicalOpenWithoutAnotherCreate()
      throws Exception {
    try (Fixture.InitialFixture fixture = Fixture.InitialFixture.open();
        InitialRedisFixture physical = InitialRedisFixture.open();
        CanonicalGameplayLoginResult login = fixture.originalLogin()) {
      var repository = spy(fixture.initialRepository);
      // Controlled receipt loss after the real SQL dispatch and real Redis write/readback.
      doThrow(new IllegalStateException("Controlled loss of first SQL receipt"))
          .doCallRealMethod()
          .when(repository)
          .acceptOpenReadback(eq(fixture.original.requestId()), any());
      var owner = fixture.initialOwner(physical.ownerClient(), repository);
      assertThat(
              owner.initializeOnce(fixture.original, login, fixture.tenantId, fixture.namespaceId))
          .isInstanceOf(CanonicalGameplayAccountInitialOpenProof.Refused.class);
      assertThat(repository.readCurrent(fixture.original.requestId()).state())
          .isEqualTo(
              CanonicalGameplayAccountInitialBarrierInitializerRepository.State.DISPATCH_POSSIBLE);
      String key =
          CanonicalGameplayAccountInitialBarrierKey.forTarget(
              fixture.tenantTags.assignmentForInitialBarrierTarget(fixture.original.requestId()),
              repository.readCurrent(fixture.original.requestId()).initializerId(),
              fixture.original.requestId());
      Map<String, String> firstPostimage = physical.connection().sync().hgetall(key);

      var recovered =
          owner.initializeOnce(fixture.original, login, fixture.tenantId, fixture.namespaceId);
      assertThat(recovered)
          .isInstanceOf(CanonicalGameplayAccountInitialOpenProof.CurrentOpen.class);
      assertThat(((CanonicalGameplayAccountInitialOpenProof.CurrentOpen) recovered).key())
          .isEqualTo(key);
      assertThat(physical.connection().sync().hgetall(key)).isEqualTo(firstPostimage);
      assertThat(repository.readCurrent(fixture.original.requestId()).state())
          .isEqualTo(
              CanonicalGameplayAccountInitialBarrierInitializerRepository.State.OPEN_INSTALLED);
      String retained = fixture.initialTargetPostimage();
      assertThat(owner.verifyCurrentOpen(fixture.original.requestId()))
          .isInstanceOf(CanonicalGameplayAccountInitialOpenProof.CurrentOpen.class);
      assertThat(fixture.initialTargetPostimage()).isEqualTo(retained);
    }
  }

  private record InitialRedisFixture(
      RedisClient administrator,
      StatefulRedisConnection<String, String> connection,
      RedisClient ownerClient)
      implements AutoCloseable {
    static InitialRedisFixture open() {
      String principal = "gamesession_coord_app";
      String password = UUID.randomUUID().toString();
      RedisClient administrator =
          RedisClient.create(RedisURI.create(redis.getHost(), redis.getMappedPort(6379)));
      StatefulRedisConnection<String, String> connection = administrator.connect();
      // This container is owned solely by this test class; each case removes only its own key.
      connection
          .sync()
          .aclSetuser(
              principal,
              new AclSetuserArgs()
                  .reset()
                  .on()
                  .addPassword(password)
                  .keyPattern("session:game:*")
                  .addCommand(CommandType.HELLO)
                  .addCommand(CommandType.PING)
                  .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
                  .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
                  .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
                  .addCommand(CommandType.EVALSHA)
                  .addCommand(CommandType.TYPE)
                  .addCommand(CommandType.PTTL)
                  .addCommand(CommandType.HLEN)
                  .addCommand(CommandType.HGETALL)
                  .addCommand(CommandType.HSET));
      RedisClient owner =
          RedisClient.create(
              RedisURI.Builder.redis(redis.getHost(), redis.getMappedPort(6379))
                  .withAuthentication(principal, password)
                  .build());
      return new InitialRedisFixture(administrator, connection, owner);
    }

    @Override
    public void close() {
      connection.close();
      ownerClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
      administrator.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }
  }

  @Test
  void verifiesOnlySignedExactCurrentTargetAndRejectsReplayAndMismatches() throws Exception {
    try (Fixture fixture = Fixture.open()) {
      ControlledSigner channel = new ControlledSigner(fixture.keyPair());
      var receiver = fixture.receiver(channel);

      var verified = receiver.observeForFenceTarget(fixture.intent().operationId(), 0L);

      assertThat(verified.operationId()).isEqualTo(fixture.intent().operationId());
      assertThat(verified.accountId()).isEqualTo(fixture.intent().accountId());
      assertThat(verified.tenantId()).isEqualTo(fixture.targetTenantId());
      assertThat(verified.playableStateNamespaceId()).isEqualTo(fixture.targetNamespaceId());
      assertThat(verified.plannedAccountAdmissionFence())
          .isEqualTo(fixture.intent().plannedAccountAdmissionFence());
      assertThat(verified.operationFence()).isEqualTo(fixture.intent().operationFence().toString());
      assertThat(verified.cohortDigest()).isEqualTo(fixture.intent().cohortDigest());
      assertThat(verified.barrierTargetDigest()).isEqualTo(fixture.intent().barrierTargetDigest());
      assertThat(verified.acceptedAttestationDigest()).isEqualTo(ACCEPTED_ATTESTATION_DIGEST);
      assertThat(verified.evidence()).isEqualTo(fixture.expectedEvidence());
      assertThat(verified.expiresAtEpochMillis()).isEqualTo(NOW.toEpochMilli() + 30_000L);
      assertThat(verified.payloadDigest()).startsWith("sha256:");
      assertThat(verified.incarnationDigest()).startsWith("sha256:");

      var repeated = receiver.observeForFenceTarget(fixture.intent().operationId(), 0L);
      assertThat(repeated.observationNonce()).isNotEqualTo(verified.observationNonce());
      assertThat(repeated.payloadDigest()).isNotEqualTo(verified.payloadDigest());
      assertThat(repeated.incarnationDigest()).isEqualTo(verified.incarnationDigest());

      assertThat(
              java.lang.reflect.Modifier.isPrivate(
                  CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation.class
                      .getDeclaredConstructors()[0].getModifiers()))
          .isTrue();

      var restartedReceiver = fixture.receiver(request -> channel.lastSigned());
      assertThatThrownBy(
              () -> restartedReceiver.observeForFenceTarget(fixture.intent().operationId(), 0L))
          .isInstanceOf(
              CanonicalGameplayProtectedIncarnationReceiver.ObservationRejectedException.class);

      for (Mutation mutation :
          List.of(
              Mutation.WRONG_NONCE,
              Mutation.WRONG_OPERATION_ID,
              Mutation.WRONG_OPERATION_FENCE,
              Mutation.WRONG_STORAGE_GENERATION,
              Mutation.FOREIGN_ACCOUNT,
              Mutation.FOREIGN_TENANT,
              Mutation.FOREIGN_NAMESPACE,
              Mutation.EXPIRED_REQUEST_WINDOW,
              Mutation.FUTURE_OBSERVATION_TIME,
              Mutation.UNKNOWN_FIELD,
              Mutation.TOO_MANY_NODES,
              Mutation.TRAILING_TOKEN,
              Mutation.NON_CANONICAL_BYTES,
              Mutation.OVERSIZED,
              Mutation.WRONG_KEY_ID,
              Mutation.BAD_SIGNATURE)) {
        ControlledSigner invalid = new ControlledSigner(fixture.keyPair(), mutation, null);
        assertThatThrownBy(
                () ->
                    fixture
                        .receiver(invalid)
                        .observeForFenceTarget(fixture.intent().operationId(), 0L))
            .isInstanceOf(
                CanonicalGameplayProtectedIncarnationReceiver.ObservationRejectedException.class);
      }

      assertThatThrownBy(
              () ->
                  fixture.receiver(null).observeForFenceTarget(fixture.intent().operationId(), 0L))
          .isInstanceOf(
              CanonicalGameplayProtectedIncarnationReceiver.ObservationUnavailableException.class);

      var missingPinnedKeys = fixture.configuration(Map.of(), fixture.expectedEvidence());
      assertThatThrownBy(
              () ->
                  fixture
                      .receiver(channel, missingPinnedKeys)
                      .observeForFenceTarget(fixture.intent().operationId(), 0L))
          .isInstanceOf(
              CanonicalGameplayProtectedIncarnationReceiver.ObservationUnavailableException.class);

      assertThatThrownBy(
              () ->
                  fixture.configuration(
                      Map.of(
                          KEY_ID,
                          (java.security.interfaces.RSAPublicKey) fixture.keyPair().getPublic()),
                      null))
          .isInstanceOf(
              CanonicalGameplayProtectedIncarnationReceiver.ObservationUnavailableException.class);
    }
  }

  @Test
  void changedTrustedStorageGenerationChangesIncarnationDigestInControlledFixture()
      throws Exception {
    try (Fixture fixture = Fixture.open()) {
      // Both signed observations use this same SQL fixture. The local controlled signer does not
      // establish custody of the actual target storage generation.
      var trustedKeys =
          Map.of(KEY_ID, (java.security.interfaces.RSAPublicKey) fixture.keyPair().getPublic());
      var original = fixture.expectedEvidence();
      var changedEvidence =
          new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
              original.deploymentIdentity(),
              original.deploymentGeneration(),
              original.canonicalNodeSet(),
              original.redisBuildIdentity(),
              original.luaScriptRegistryVersion(),
              original.luaScriptRegistryDigest(),
              original.startupState(),
              original.bootGeneration(),
              original.startupGeneration(),
              "storage-controlled-fixture-8",
              original.aofGeneration());

      var originalVerified =
          fixture
              .receiver(new ControlledSigner(fixture.keyPair()))
              .observeForFenceTarget(fixture.intent().operationId(), 0L);
      var changedVerified =
          fixture
              .receiver(
                  new ControlledSigner(fixture.keyPair()),
                  fixture.configuration(trustedKeys, changedEvidence))
              .observeForFenceTarget(fixture.intent().operationId(), 0L);

      assertThat(changedVerified.evidence()).isEqualTo(changedEvidence);
      assertThat(changedVerified.operationFence()).isEqualTo(originalVerified.operationFence());
      assertThat(changedVerified.barrierTargetDigest())
          .isEqualTo(originalVerified.barrierTargetDigest());
      assertThat(changedVerified.incarnationDigest())
          .isNotEqualTo(originalVerified.incarnationDigest());
    }
  }

  @Test
  void changedCurrentSqlContextAfterSourceCallCannotProduceVerifiedEvidence() throws Exception {
    try (Fixture fixture = Fixture.open()) {
      ControlledSigner channel =
          new ControlledSigner(fixture.keyPair(), Mutation.NONE, fixture.dsl());
      assertThatThrownBy(
              () ->
                  fixture
                      .receiver(channel)
                      .observeForFenceTarget(fixture.intent().operationId(), 0L))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
      assertThat(channel.lastSigned()).isNotNull();
    }
  }

  @Test
  void initialArmVerifiesExactOriginalAndNonzeroNoActiveEpochWithSameTrustedVerifier()
      throws Exception {
    try (Fixture.InitialFixture fixture = Fixture.InitialFixture.open()) {
      ControlledSigner channel = new ControlledSigner(fixture.keyPair());
      var receiver = fixture.receiver(channel);

      var verified = receiver.observeForInitialAccountBarrier(fixture.original().requestId());

      assertThat(verified.ownerKind())
          .isEqualTo(
              CanonicalGameplayProtectedIncarnationReceiver.OwnerKind.INITIAL_ACCOUNT_BARRIER);
      assertThat(verified.requestId()).isEqualTo(fixture.original().requestId());
      assertThat(verified.originalCallerContextId())
          .isEqualTo(fixture.original().callerContextId());
      assertThat(verified.originalAttemptFence()).isEqualTo(17L);
      assertThat(verified.accountId()).isEqualTo(fixture.accountId());
      assertThat(verified.cohortDigest()).isNull();
      assertThat(verified.sourceControlState()).isEqualTo("NO_ACTIVE_ACCOUNT_WIDE_FLOW");
      assertThat(verified.sourceControlEpoch()).isEqualTo("8");
      assertThat(verified.acceptedAttestationDigest()).isEqualTo(ACCEPTED_ATTESTATION_DIGEST);
      assertThat(verified.evidence()).isEqualTo(fixture.expectedEvidence());

      var repeated = receiver.observeForInitialAccountBarrier(fixture.original().requestId());
      assertThat(repeated.observationNonce()).isNotEqualTo(verified.observationNonce());
      assertThat(repeated.incarnationDigest()).isEqualTo(verified.incarnationDigest());

      for (Mutation mutation :
          List.of(Mutation.WRONG_INITIAL_SOURCE_EPOCH, Mutation.WRONG_INITIAL_ATTEMPT)) {
        assertThatThrownBy(
                () ->
                    fixture
                        .receiver(new ControlledSigner(fixture.keyPair(), mutation, null))
                        .observeForInitialAccountBarrier(fixture.original().requestId()))
            .isInstanceOf(
                CanonicalGameplayProtectedIncarnationReceiver.ObservationRejectedException.class);
      }
    }
  }

  @Test
  void changedNoActiveEpochAfterInitialObservationCannotProduceVerifiedEvidence() throws Exception {
    try (Fixture.InitialFixture fixture = Fixture.InitialFixture.open()) {
      ControlledSigner channel =
          new ControlledSigner(fixture.keyPair(), Mutation.NONE, fixture.dsl());

      assertThatThrownBy(
              () ->
                  fixture
                      .receiver(channel)
                      .observeForInitialAccountBarrier(fixture.original().requestId()))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
      assertThat(channel.lastSigned()).isNotNull();
    }
  }

  private enum Mutation {
    NONE,
    WRONG_NONCE,
    WRONG_OPERATION_ID,
    WRONG_OPERATION_FENCE,
    WRONG_INITIAL_SOURCE_EPOCH,
    WRONG_INITIAL_ATTEMPT,
    WRONG_STORAGE_GENERATION,
    FOREIGN_ACCOUNT,
    FOREIGN_TENANT,
    FOREIGN_NAMESPACE,
    EXPIRED_REQUEST_WINDOW,
    FUTURE_OBSERVATION_TIME,
    UNKNOWN_FIELD,
    TOO_MANY_NODES,
    TRAILING_TOKEN,
    NON_CANONICAL_BYTES,
    OVERSIZED,
    WRONG_KEY_ID,
    BAD_SIGNATURE
  }

  private static final class ControlledSigner
      implements CanonicalGameplayProtectedIncarnationReceiver.ProtectedObservationChannel {
    private final KeyPair keyPair;
    private final Mutation mutation;
    private final DSLContext contextMutationDsl;
    private CanonicalGameplayProtectedIncarnationReceiver.SignedObservation lastSigned;

    private ControlledSigner(KeyPair keyPair) {
      this(keyPair, Mutation.NONE, null);
    }

    private ControlledSigner(KeyPair keyPair, Mutation mutation, DSLContext contextMutationDsl) {
      this.keyPair = keyPair;
      this.mutation = mutation;
      this.contextMutationDsl = contextMutationDsl;
    }

    @Override
    public CanonicalGameplayProtectedIncarnationReceiver.SignedObservation observe(
        CanonicalGameplayProtectedIncarnationReceiver.ObservationRequest request) {
      if (contextMutationDsl != null) {
        if (request.context().ownerKind()
            == CanonicalGameplayProtectedIncarnationReceiver.OwnerKind.INITIAL_ACCOUNT_BARRIER) {
          contextMutationDsl.execute(
              "UPDATE game_session_canonical_account_coverage_control"
                  + " SET last_operation_fence = 9 WHERE account_id = ?",
              request.context().accountId());
        } else {
          contextMutationDsl.execute(
              "UPDATE game_session_canonical_account_admission_fence_intent"
                  + " SET cohort_digest = ? WHERE operation_id = ?",
              "sha256:" + "f".repeat(64),
              request.context().operationId());
        }
      }
      Map<String, Object> root = payload(request);
      switch (mutation) {
        case WRONG_NONCE -> root.put("observationNonce", "A".repeat(43));
        case WRONG_OPERATION_ID -> {
          String field =
              request.context().ownerKind()
                      == CanonicalGameplayProtectedIncarnationReceiver.OwnerKind
                          .INITIAL_ACCOUNT_BARRIER
                  ? "initializerId"
                  : "operationId";
          asMap(root.get("owner")).put(field, UUID.randomUUID().toString());
        }
        case WRONG_OPERATION_FENCE -> {
          String field =
              request.context().ownerKind()
                      == CanonicalGameplayProtectedIncarnationReceiver.OwnerKind
                          .INITIAL_ACCOUNT_BARRIER
                  ? "sourceControlEpoch"
                  : "operationFence";
          asMap(root.get("owner")).put(field, "999999");
        }
        case WRONG_INITIAL_SOURCE_EPOCH -> asMap(root.get("owner")).put("sourceControlEpoch", "9");
        case WRONG_INITIAL_ATTEMPT -> asMap(root.get("owner")).put("originalAttemptFence", 18L);
        case WRONG_STORAGE_GENERATION ->
            asMap(root.get("targetDeploymentEvidence"))
                .put("storageGeneration", "storage-untrusted-response");
        case FOREIGN_ACCOUNT ->
            asMap(root.get("owner")).put("accountId", UUID.randomUUID().toString());
        case FOREIGN_TENANT ->
            asMap(root.get("target")).put("tenantId", UUID.randomUUID().toString());
        case FOREIGN_NAMESPACE ->
            asMap(root.get("target")).put("playableStateNamespaceId", UUID.randomUUID().toString());
        case EXPIRED_REQUEST_WINDOW ->
            root.put("observationExpiresAtEpochMillis", request.issuedAtEpochMillis());
        case FUTURE_OBSERVATION_TIME ->
            root.put("observationObservedAtEpochMillis", request.issuedAtEpochMillis() + 1_000L);
        case UNKNOWN_FIELD -> root.put("unexpected", "field");
        case TOO_MANY_NODES ->
            asMap(root.get("targetDeploymentEvidence"))
                .put("canonicalNodeSet", List.of("node-a", "node-b", "node-c"));
        default -> {
          // The other mutations are applied to the bytes or detached signature below.
        }
      }
      try {
        byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(root));
        byte[] payload;
        if (mutation == Mutation.NON_CANONICAL_BYTES) {
          payload =
              ByteBuffer.allocate(canonical.length + 1).put(canonical).put((byte) ' ').array();
        } else if (mutation == Mutation.TRAILING_TOKEN) {
          byte[] suffix = " {}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          payload =
              ByteBuffer.allocate(canonical.length + suffix.length)
                  .put(canonical)
                  .put(suffix)
                  .array();
        } else if (mutation == Mutation.OVERSIZED) {
          asMap(root.get("owner")).put("padding", "x".repeat(17_000));
          byte[] oversized = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(root));
          payload = oversized;
        } else {
          payload = canonical;
        }
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(canonical);
        byte[] signature = signer.sign();
        if (mutation == Mutation.BAD_SIGNATURE) {
          signature[signature.length - 1] ^= 1;
        }
        String responseKeyId =
            mutation == Mutation.WRONG_KEY_ID ? "unconfigured-attestor-key" : KEY_ID;
        lastSigned =
            new CanonicalGameplayProtectedIncarnationReceiver.SignedObservation(
                responseKeyId, payload, signature);
        return lastSigned;
      } catch (Exception failure) {
        throw new IllegalStateException("Controlled test signer failed", failure);
      }
    }

    private CanonicalGameplayProtectedIncarnationReceiver.SignedObservation lastSigned() {
      return lastSigned;
    }
  }

  private static Map<String, Object> payload(
      CanonicalGameplayProtectedIncarnationReceiver.ObservationRequest request) {
    var context = request.context();
    var evidence = request.expectedEvidence();
    Map<String, Object> owner = new LinkedHashMap<>();
    boolean initial =
        context.ownerKind()
            == CanonicalGameplayProtectedIncarnationReceiver.OwnerKind.INITIAL_ACCOUNT_BARRIER;
    if (initial) {
      owner.put("ownerKind", context.ownerKind().name());
      owner.put("initializerId", context.operationId().toString());
      owner.put("originalRequestId", context.requestId().toString());
      owner.put("originalCallerContextId", context.originalCallerContextId().toString());
      owner.put("originalAttemptFence", context.originalAttemptFence());
      owner.put("accountId", context.accountId().toString());
      owner.put("sourceControlState", context.sourceControlState());
      owner.put("sourceControlEpoch", context.sourceControlEpoch());
      owner.put("inventorySnapshotRevision", context.inventorySnapshotRevision());
      owner.put("accountLoginEvidenceDigest", context.accountLoginEvidenceDigest());
      owner.put("sourceControlDigest", context.sourceControlDigest());
      owner.put("targetDigest", context.barrierTargetDigest());
    } else {
      owner.put("operationId", context.operationId().toString());
      owner.put("requestId", context.requestId().toString());
      owner.put("accountId", context.accountId().toString());
      owner.put("operationFence", context.operationFence());
      owner.put("plannedAccountAdmissionFence", context.plannedAccountAdmissionFence().toString());
      owner.put("inventorySnapshotRevision", context.inventorySnapshotRevision());
      owner.put("cohortDigest", context.cohortDigest());
      owner.put("barrierTargetDigest", context.barrierTargetDigest());
    }

    Map<String, Object> target = new LinkedHashMap<>();
    target.put("targetOrdinal", context.targetOrdinal());
    target.put("tenantId", context.tenantId().toString());
    target.put("playableStateNamespaceId", context.playableStateNamespaceId().toString());
    target.put("tenantGameplayTag", context.tenantGameplayTag());
    target.put("workloadNamespace", context.workloadNamespace());
    target.put("allocationVersion", context.allocationVersion());

    Map<String, Object> targetEvidence = new LinkedHashMap<>();
    targetEvidence.put("deploymentIdentity", evidence.deploymentIdentity());
    targetEvidence.put("deploymentGeneration", evidence.deploymentGeneration());
    targetEvidence.put("canonicalNodeSet", evidence.canonicalNodeSet());
    targetEvidence.put("redisBuildIdentity", evidence.redisBuildIdentity());
    targetEvidence.put("luaScriptRegistryVersion", evidence.luaScriptRegistryVersion());
    targetEvidence.put("luaScriptRegistryDigest", evidence.luaScriptRegistryDigest());
    targetEvidence.put("startupState", evidence.startupState());
    targetEvidence.put("bootGeneration", evidence.bootGeneration());
    targetEvidence.put("startupGeneration", evidence.startupGeneration());
    targetEvidence.put("storageGeneration", evidence.storageGeneration());
    targetEvidence.put("aofGeneration", evidence.aofGeneration());

    Map<String, Object> root = new LinkedHashMap<>();
    root.put(
        "schema",
        initial
            ? "firemud.gs.protected-initial-account-barrier-read/v1"
            : "firemud.gs.protected-incarnation-read/v1");
    root.put("attestorBinding", request.attestorBinding());
    root.put("attestorKeyId", KEY_ID);
    root.put("acceptedAttestationDigest", request.acceptedAttestationDigest());
    root.put("observationNonce", request.nonce());
    root.put("observationIssuedAtEpochMillis", request.issuedAtEpochMillis());
    root.put("observationObservedAtEpochMillis", request.issuedAtEpochMillis());
    root.put("observationExpiresAtEpochMillis", request.expiresAtEpochMillis());
    root.put("owner", owner);
    root.put("target", target);
    root.put("targetDeploymentEvidence", targetEvidence);
    root.put("coordOpsRoAclDigest", request.coordOpsRoAclDigest());
    root.put("coordRedisConfigObservedDigest", request.coordRedisConfigObservedDigest());
    return root;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return (Map<String, Object>) value;
  }

  private static final class Fixture implements AutoCloseable {
    private final String schema;
    private final DSLContext dsl;
    private final CanonicalGameplayAccountAdmissionFenceIntent intent;
    private final UUID targetTenantId;
    private final UUID targetNamespaceId;
    private final String targetWorkloadNamespace;
    private final KeyPair keyPair;
    private final CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
        expectedEvidence;

    private Fixture(
        String schema,
        DSLContext dsl,
        CanonicalGameplayAccountAdmissionFenceIntent intent,
        UUID targetTenantId,
        UUID targetNamespaceId,
        String targetWorkloadNamespace,
        KeyPair keyPair,
        CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
            expectedEvidence) {
      this.schema = schema;
      this.dsl = dsl;
      this.intent = intent;
      this.targetTenantId = targetTenantId;
      this.targetNamespaceId = targetNamespaceId;
      this.targetWorkloadNamespace = targetWorkloadNamespace;
      this.keyPair = keyPair;
      this.expectedEvidence = expectedEvidence;
    }

    private static Fixture open() throws Exception {
      String schema = "gs_incarnation_" + UUID.randomUUID().toString().replace("-", "");
      DriverManagerDataSource dataSource = dataSource(schema);
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history")
          .locations(MIGRATION_LOCATION)
          .load()
          .migrate();
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      UUID tenantId = UUID.randomUUID();
      var runtime =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(dataSource, tenantId);
      CanonicalGameplayBindingInventoryRepositoryIntegrationTest
          .seedSyntheticVerifiedLegacyDisposition(dsl);

      UUID accountId = UUID.randomUUID();
      UUID transitionId = UUID.randomUUID();
      UUID characterId = UUID.randomUUID();
      String sessionId = UUID.randomUUID().toString();
      CanonicalGameplayBindingIdentity identity =
          new CanonicalGameplayBindingIdentity(
              accountId,
              runtime.canonicalTenantId(),
              runtime.playableStateNamespaceId(),
              characterId,
              "SHARED",
              runtime.gameInstanceUuid(),
              runtime.gameInstanceId(),
              sessionId,
              UUID.randomUUID(),
              BigInteger.ONE,
              GameSessionAccountDelegationProfile.ISSUER,
              BigInteger.ONE,
              BigInteger.ONE,
              BigInteger.valueOf(16),
              BigInteger.valueOf(32));
      CanonicalIssuerReservationFenceEvidence reservation =
          new CanonicalIssuerReservationFenceEvidence(
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              BigInteger.ONE,
              BigInteger.ONE,
              BigInteger.ONE);
      var inventory = new CanonicalGameplayBindingInventoryRepository(dsl);
      inventory.prepare(
          new CanonicalGameplayBindingTransitionRequest(
              transitionId, UUID.randomUUID(), identity, reservation, null, null));
      CanonicalGameplayAccountAdmissionFenceIntent intent =
          new CanonicalGameplayAccountAdmissionBarrierRepository(dsl)
              .beginFenceIntent(accountId, UUID.randomUUID());

      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair keyPair = generator.generateKeyPair();
      var expectedEvidence =
          new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
              "controlled-target-deployment",
              "generation-7",
              List.of("redis-node-a"),
              "redis-test-build",
              "registry-test-v1",
              "sha256:" + "d".repeat(64),
              "READY",
              "boot-7",
              "startup-7",
              "storage-7",
              "aof-7");
      return new Fixture(
          schema,
          dsl,
          intent,
          runtime.canonicalTenantId(),
          runtime.playableStateNamespaceId(),
          runtime.targetNamespace(),
          keyPair,
          expectedEvidence);
    }

    private CanonicalGameplayProtectedIncarnationReceiver receiver(
        CanonicalGameplayProtectedIncarnationReceiver.ProtectedObservationChannel channel) {
      return receiver(
          channel,
          configuration(
              Map.of(KEY_ID, (java.security.interfaces.RSAPublicKey) keyPair.getPublic()),
              expectedEvidence));
    }

    private CanonicalGameplayProtectedIncarnationReceiver receiver(
        CanonicalGameplayProtectedIncarnationReceiver.ProtectedObservationChannel channel,
        CanonicalGameplayProtectedIncarnationReceiver.TrustedConfiguration configuration) {
      return new CanonicalGameplayProtectedIncarnationReceiver(
          new CanonicalGameplayAccountAdmissionBarrierRepository(dsl),
          new CanonicalGameplayTenantGameplayTagRepository(dsl, targetWorkloadNamespace, 1),
          channel,
          configuration,
          Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CanonicalGameplayProtectedIncarnationReceiver.TrustedConfiguration configuration(
        Map<String, java.security.interfaces.RSAPublicKey> keys,
        CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence evidence) {
      return new CanonicalGameplayProtectedIncarnationReceiver.TrustedConfiguration(
          ATTESTOR_BINDING,
          ACCEPTED_ATTESTATION_DIGEST,
          keys,
          evidence,
          ACL_DIGEST,
          CONFIG_DIGEST,
          16 * 1024,
          1,
          Duration.ofSeconds(30));
    }

    private CanonicalGameplayAccountAdmissionFenceIntent intent() {
      return intent;
    }

    private UUID targetTenantId() {
      return targetTenantId;
    }

    private UUID targetNamespaceId() {
      return targetNamespaceId;
    }

    private KeyPair keyPair() {
      return keyPair;
    }

    private DSLContext dsl() {
      return dsl;
    }

    private CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
        expectedEvidence() {
      return expectedEvidence;
    }

    @Override
    public void close() {
      dropSchema(schema);
    }

    private static final class InitialFixture implements AutoCloseable {
      private final String schema;
      private final DSLContext dsl;
      private final InitialGameplayLoginAttemptCoordinates original;
      private final UUID accountId;
      private final UUID tenantId;
      private final UUID namespaceId;
      private final String targetWorkloadNamespace;
      private final KeyPair keyPair;
      private final CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
          expectedEvidence;
      private final CanonicalGameplayAccountInitialBarrierInitializerRepository initialRepository;
      private final CanonicalGameplayTenantGameplayTagRepository tenantTags;

      private InitialFixture(
          String schema,
          DSLContext dsl,
          InitialGameplayLoginAttemptCoordinates original,
          UUID accountId,
          UUID tenantId,
          UUID namespaceId,
          String targetWorkloadNamespace,
          KeyPair keyPair,
          CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
              expectedEvidence,
          CanonicalGameplayAccountInitialBarrierInitializerRepository initialRepository,
          CanonicalGameplayTenantGameplayTagRepository tenantTags) {
        this.schema = schema;
        this.dsl = dsl;
        this.original = original;
        this.accountId = accountId;
        this.tenantId = tenantId;
        this.namespaceId = namespaceId;
        this.targetWorkloadNamespace = targetWorkloadNamespace;
        this.keyPair = keyPair;
        this.expectedEvidence = expectedEvidence;
        this.initialRepository = initialRepository;
        this.tenantTags = tenantTags;
      }

      private static InitialFixture open() throws Exception {
        return open(BigInteger.valueOf(8));
      }

      private static InitialFixture open(BigInteger sourceControlEpoch) throws Exception {
        String schema = "gs_initial_incarnation_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource dataSource = Fixture.dataSource(schema);
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history")
            .locations(MIGRATION_LOCATION)
            .load()
            .migrate();
        DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
        UUID tenantId = UUID.randomUUID();
        var runtime =
            CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(dataSource, tenantId);
        CanonicalGameplayBindingInventoryRepositoryIntegrationTest
            .seedSyntheticVerifiedLegacyDisposition(dsl);

        UUID accountId = UUID.randomUUID();
        UUID transitionId = UUID.randomUUID();
        UUID characterId = UUID.randomUUID();
        var identity =
            new CanonicalGameplayBindingIdentity(
                accountId,
                runtime.canonicalTenantId(),
                runtime.playableStateNamespaceId(),
                characterId,
                "SHARED",
                runtime.gameInstanceUuid(),
                runtime.gameInstanceId(),
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                BigInteger.ONE,
                GameSessionAccountDelegationProfile.ISSUER,
                BigInteger.ONE,
                BigInteger.ONE,
                BigInteger.valueOf(16),
                BigInteger.valueOf(32));
        var reservation =
            new CanonicalIssuerReservationFenceEvidence(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigInteger.ONE,
                BigInteger.ONE,
                BigInteger.ONE);
        new CanonicalGameplayBindingInventoryRepository(dsl)
            .prepare(
                new CanonicalGameplayBindingTransitionRequest(
                    transitionId, UUID.randomUUID(), identity, reservation, null, null));
        dsl.execute(
            "UPDATE game_session_canonical_account_coverage_control"
                + " SET last_operation_fence = ? WHERE account_id = ?",
            sourceControlEpoch,
            accountId);

        InitialGameplayLoginAttemptCoordinates original =
            new InitialGameplayLoginAttemptCoordinates(UUID.randomUUID(), UUID.randomUUID(), 17L);
        var accountLogin = mockInitialLoginResult(original, accountId, UUID.randomUUID());
        dsl.execute(
            "INSERT INTO game_session_initial_gameplay_login_journal"
                + " (request_id, caller_context_id, expected_attempt_fence) VALUES (?, ?, ?)",
            original.requestId(),
            original.callerContextId(),
            original.expectedAttemptFence());
        dsl.execute(
            "UPDATE game_session_initial_gameplay_login_journal"
                + " SET verified_token_jti = ?, verified_token_sha256 = ?,"
                + " verified_token_generation = 1, verified_issuance_fence = 1"
                + " WHERE request_id = ?",
            accountLogin.evidence().tokenJti(),
            accountLogin.evidence().tokenSha256(),
            original.requestId());
        CanonicalGameplayAccountInitialBarrierInitializerRepository initialRepository =
            new CanonicalGameplayAccountInitialBarrierInitializerRepository(dsl);
        initialRepository.beginOrRead(
            original,
            accountLogin,
            runtime.canonicalTenantId(),
            runtime.playableStateNamespaceId());
        accountLogin.close();

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        var expectedEvidence = controlledExpectedEvidence();
        var tenantTags =
            new CanonicalGameplayTenantGameplayTagRepository(dsl, runtime.targetNamespace(), 1);
        return new InitialFixture(
            schema,
            dsl,
            original,
            accountId,
            runtime.canonicalTenantId(),
            runtime.playableStateNamespaceId(),
            runtime.targetNamespace(),
            keyPair,
            expectedEvidence,
            initialRepository,
            tenantTags);
      }

      private CanonicalGameplayProtectedIncarnationReceiver receiver(
          CanonicalGameplayProtectedIncarnationReceiver.ProtectedObservationChannel channel) {
        return receiver(
            channel,
            new CanonicalGameplayProtectedIncarnationReceiver.TrustedConfiguration(
                ATTESTOR_BINDING,
                ACCEPTED_ATTESTATION_DIGEST,
                Map.of(KEY_ID, (java.security.interfaces.RSAPublicKey) keyPair.getPublic()),
                expectedEvidence,
                ACL_DIGEST,
                CONFIG_DIGEST,
                16 * 1024,
                1,
                Duration.ofSeconds(30)));
      }

      private CanonicalGameplayProtectedIncarnationReceiver receiver(
          CanonicalGameplayProtectedIncarnationReceiver.ProtectedObservationChannel channel,
          CanonicalGameplayProtectedIncarnationReceiver.TrustedConfiguration configuration) {
        return new CanonicalGameplayProtectedIncarnationReceiver(
            initialRepository,
            tenantTags,
            channel,
            configuration,
            Clock.fixed(NOW, ZoneOffset.UTC));
      }

      private CanonicalGameplayLoginResult originalLogin() {
        UUID tokenJti =
            dsl.fetchSingle(
                    "SELECT verified_token_jti FROM game_session_initial_gameplay_login_journal"
                        + " WHERE request_id = ?",
                    original.requestId())
                .get(0, UUID.class);
        return mockInitialLoginResult(original, accountId, tokenJti);
      }

      private String initialTargetPostimage() {
        return dsl.fetchSingle(
                "SELECT to_jsonb(t)::text FROM game_session_canonical_initial_account_barrier_target t"
                    + " WHERE original_request_id = ?",
                original.requestId())
            .get(0, String.class);
      }

      private CanonicalGameplayAccountInitialBarrierRedisOwner initialOwner(
          RedisClient client,
          CanonicalGameplayAccountInitialBarrierInitializerRepository repository) {
        return initialOwner(
            client,
            repository,
            RedisScriptCatalog.fromContributions(
                List.of(new CanonicalGameplayAccountInitialBarrierRedisScriptContribution())));
      }

      private CanonicalGameplayAccountInitialBarrierRedisOwner initialOwner(
          RedisClient client,
          CanonicalGameplayAccountInitialBarrierInitializerRepository repository,
          RedisScriptCatalog catalog) {
        return initialOwner(client, repository, catalog, Clock.systemUTC());
      }

      private CanonicalGameplayAccountInitialBarrierRedisOwner initialOwner(
          RedisClient client,
          CanonicalGameplayAccountInitialBarrierInitializerRepository repository,
          RedisScriptCatalog catalog,
          Clock trustedClock) {
        var configuration =
            new CanonicalGameplayProtectedIncarnationReceiver.TrustedConfiguration(
                ATTESTOR_BINDING,
                ACCEPTED_ATTESTATION_DIGEST,
                Map.of(KEY_ID, (java.security.interfaces.RSAPublicKey) keyPair.getPublic()),
                expectedEvidence,
                ACL_DIGEST,
                CONFIG_DIGEST,
                16 * 1024,
                1,
                Duration.ofSeconds(30));
        var receiver =
            new CanonicalGameplayProtectedIncarnationReceiver(
                repository, tenantTags, new ControlledSigner(keyPair), configuration, trustedClock);
        return new CanonicalGameplayAccountInitialBarrierRedisOwner(
            client, catalog, repository, tenantTags, receiver);
      }

      private InitialGameplayLoginAttemptCoordinates original() {
        return original;
      }

      private UUID accountId() {
        return accountId;
      }

      private KeyPair keyPair() {
        return keyPair;
      }

      private DSLContext dsl() {
        return dsl;
      }

      private CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
          expectedEvidence() {
        return expectedEvidence;
      }

      @Override
      public void close() {
        try (var connection =
                java.sql.DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var statement = connection.createStatement()) {
          statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } catch (Exception failure) {
          throw new IllegalStateException("Could not dispose INITIAL observation schema", failure);
        }
      }
    }

    private static CanonicalGameplayLoginResult mockInitialLoginResult(
        InitialGameplayLoginAttemptCoordinates original, UUID accountId, UUID tokenJti) {
      var evidence =
          new CanonicalGameplayLoginResult.NonSecretEvidence(
              accountId,
              original.requestId(),
              original.callerContextId(),
              tokenJti,
              "e".repeat(64),
              GameSessionAccountDelegationProfile.PROFILE,
              NOW.getEpochSecond(),
              NOW.getEpochSecond(),
              NOW.plusSeconds(120).getEpochSecond(),
              "1",
              "1",
              ByteString.copyFromUtf8("{}"),
              ByteString.copyFromUtf8("[]"),
              ByteString.copyFromUtf8("[]"));
      CanonicalGameplayLoginResult result = mock(CanonicalGameplayLoginResult.class);
      when(result.evidence()).thenReturn(evidence);
      return result;
    }

    private static CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
        controlledExpectedEvidence() {
      return new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
          "controlled-target-deployment",
          "generation-7",
          List.of("redis-node-a"),
          "redis-test-build",
          "registry-test-v1",
          "sha256:" + "d".repeat(64),
          "READY",
          "boot-7",
          "startup-7",
          "storage-7",
          "aof-7");
    }

    private static DriverManagerDataSource dataSource(String schema) {
      DriverManagerDataSource dataSource = new DriverManagerDataSource();
      dataSource.setDriverClassName("org.postgresql.Driver");
      String jdbcUrl = postgres.getJdbcUrl();
      String separator = jdbcUrl.contains("?") ? "&" : "?";
      dataSource.setUrl(jdbcUrl + separator + "currentSchema=" + schema);
      dataSource.setUsername(postgres.getUsername());
      dataSource.setPassword(postgres.getPassword());
      return dataSource;
    }

    private static void dropSchema(String schema) {
      try (var connection =
              java.sql.DriverManager.getConnection(
                  postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
          var statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      } catch (Exception failure) {
        throw new IllegalStateException(
            "Could not dispose protected incarnation test schema", failure);
      }
    }
  }
}
