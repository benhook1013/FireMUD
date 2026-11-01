package integration.net.firedevops.firemud.gamesession.service.directtext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.GameplayLoginSourceContext;
import net.firedevops.firemud.account.v1.GameplayLoginTransportClass;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeResponse;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipResponse;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldSelectionService;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog.RealmView;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog.WorldView;
import net.firedevops.firemud.gamesession.command.text.ObservedDirectTextTransport;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof.Outcome;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.repository.DirectTextAuthenticatedLobbyRepository;
import net.firedevops.firemud.gamesession.repository.InitialGameplayLoginCoordinationJournalRepository;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyCustody;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyJoin;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyJoin.State;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyOwner;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyScope;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Native PostgreSQL migration/owner proof with controlled RSA Account responses, guarded catalog
 * reads and custody fixtures. No live ingress, protected production DB delivery, PKI, hidden
 * rebind, registration, cleanup, enrollment, activation, actor selection or gameplay admission is
 * proved.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class DirectTextAuthenticatedLobbyIntegrationTest {
  private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
  private static final Clock VERIFY_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final UUID ACCOUNT = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final String PARENT = "game_session_initial_gameplay_login_journal";
  private static final String SCOPE = DirectTextAuthenticatedLobbyRepository.SCOPE;
  private static final String CURRENT = DirectTextAuthenticatedLobbyRepository.CURRENT;
  private static final String JOIN = DirectTextAuthenticatedLobbyRepository.JOIN;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static KeyPair keys;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private String schema;
  private DriverManagerDataSource source;
  private DSLContext dsl;
  private DataSourceTransactionManager manager;
  private TransactionTemplate tx;
  private InitialGameplayLoginCoordinationJournalRepository journal;
  private DirectTextAuthenticatedLobbyRepository repository;
  private DirectTextAuthenticatedLobbyOwner owner;
  private DirectTextWorldSelectionService selection;
  private AccountClient account;
  private CanonicalGameplayLoginResult verified;
  private InitialGameplayLoginAttemptCoordinates original;
  private ControlledCustody custody;
  private final MutableClock clock = new MutableClock();
  private CanonicalPlayableTarget target;
  private String parentBeforeMigration;

  @BeforeAll
  static void signingAndPostgres() throws Exception {
    PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(postgres);
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    keys = generator.generateKeyPair();
  }

  @BeforeEach
  void migratePopulatedOriginalThenComposeInactiveOwner() throws Exception {
    schema = "gs_direct_text_lobby_" + UUID.randomUUID().toString().replace("-", "");
    source = new DriverManagerDataSource();
    source.setDriverClassName("org.postgresql.Driver");
    source.setUrl(postgres.getJdbcUrl());
    source.setUsername(postgres.getUsername());
    source.setPassword(postgres.getPassword());
    source.setSchema(schema);
    migration("1051.1").migrate();
    dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    manager = new DataSourceTransactionManager(source);
    tx = new TransactionTemplate(manager);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(15);
    journal = new InitialGameplayLoginCoordinationJournalRepository(dsl);
    original = new InitialGameplayLoginAttemptCoordinates(UUID.randomUUID(), UUID.randomUUID(), 7);
    verified = verified(original, ACCOUNT, UUID.randomUUID(), "5");
    tx.executeWithoutResult(
        status -> {
          journal.reserve(original);
          journal.recordVerifiedResult(original, verified);
        });
    parentBeforeMigration = parentJson();
    migration(null).migrate();
    repository = new DirectTextAuthenticatedLobbyRepository(dsl);
    account = mock(AccountClient.class);
    target = target();
    var routes = mock(CanonicalPlayerRouteReadService.class);
    when(routes.readVisiblePublicProductionTargets()).thenReturn(List.of(target));
    when(routes.readCurrentTarget(target)).thenReturn(target);
    var world =
        new WorldView(
            target.worldSlug(),
            target.worldDisplayName(),
            List.of(
                new RealmView(
                    target.realmSlug(),
                    target.realmDisplayName(),
                    target.gameSessionTenantId(),
                    target.gameInstanceId(),
                    target.pointerVersion(),
                    true,
                    true,
                    false,
                    target.playableStateScope(),
                    target.characterCreationPolicy(),
                    target.catalogRevision(),
                    target.realmId(),
                    target.playableStateNamespaceId())));
    selection =
        new DirectTextWorldSelectionService(
            GameplayWorldCatalog.forWorldSupplier(() -> List.of(world)), routes, clock);
    custody =
        new ControlledCustody(
            original, verified, new ObservedDirectTextTransport("00042", UUID.randomUUID()));
    when(account.issueDirectTextConnectScope(any(), any()))
        .thenAnswer(
            call ->
                IssueDirectTextConnectScopeResponse.newBuilder()
                    .setConnectScopeId("opaque-" + UUID.randomUUID())
                    .setEvaluatedAt(NOW.minusSeconds(1).toString())
                    .setConnectScopeExpiresAt(NOW.plusSeconds(60).toString())
                    .build());
    owner = owner(repository, manager);
  }

  private Flyway migration(String targetVersion) {
    var config =
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .locations(
                "filesystem:"
                    + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize());
    if (targetVersion != null) config.target(targetVersion);
    return config.load();
  }

  @AfterEach
  void closeOnlyOwnedResultAndSchema() throws Exception {
    if (verified != null) verified.close();
    if (schema != null) {
      try (var connection =
              DriverManager.getConnection(
                  postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
          var statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
      }
    }
  }

  @Test
  void forwardMigrationPreservesPopulatedMinimalParentAndFullExactNonsecretScope() {
    assertThat(parentJson()).isEqualTo(parentBeforeMigration);
    var scope = realms();
    var stored = repository.readCommittedScope(scope);
    assertThat(stored.encode()).containsExactly(scope.encode());
    assertThat(stored.publicTarget()).isEqualTo(target);
    assertThat(stored.evidence()).isEqualTo(verified.evidence());
    assertThat(stored.transport()).isEqualTo(custody.transport);
    assertThat(stored.issued().evaluatedAt()).isEqualTo(NOW.minusSeconds(1).toString());
    assertThat(stored.issued().connectScopeExpiresAt()).isEqualTo(NOW.plusSeconds(60).toString());
    assertThat(new String(stored.encode(), StandardCharsets.UTF_8))
        .doesNotContain("fixture-transient-secret", "eyJhbGci", "authToken", "password");
    assertThat(parentJson()).isEqualTo(parentBeforeMigration);
    reject("DELETE FROM " + PARENT + " WHERE request_id = ?", original.requestId());
  }

  @Test
  void actualOwnerReadsCommittedExactRequestBeforeAccountAndRetainsPositiveReply() {
    var scope = realms();
    when(account.joinPublicProductionMembership(any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              var row =
                  dsl.fetchSingle(
                      "SELECT * FROM " + JOIN + " WHERE scope_record_id = ?", scope.recordId());
              assertThat(row.get("state", String.class)).isEqualTo("DISPATCH_POSSIBLE");
              var request =
                  DirectTextAuthenticatedLobbyJoin.decodeRequest(
                      row.get("request_bytes", byte[].class));
              assertThat(request.getPlayerContext())
                  .isEqualTo(
                      call.getArgument(
                          0, net.firedevops.firemud.shared.v1.PlayerExecutionContext.class));
              assertThat(request.getPlayerContext().getAccountId()).isEqualTo(ACCOUNT.toString());
              assertThat(request.getPlayerContext().getSessionId())
                  .isEqualTo(custody.transport.transportSessionId());
              assertThat(request.getConnectScopeId()).isEqualTo(scope.issued().connectScopeId());
              assertThat(request.getConnectScopeId()).isEqualTo(call.getArgument(1, String.class));
              assertThat(request.getRequestId()).isEqualTo(call.getArgument(2, String.class));
              assertThat(call.getArgument(3, Instant.class))
                  .isEqualTo(scope.issued().connectScopeExpiresAtInstant());
              return positive();
            });
    var completed = owner.join(original, verified, custody);
    assertThat(completed.status())
        .isEqualTo(DirectTextAuthenticatedLobbyOwner.Status.COMPLETED_CURRENT_NON_ADMITTING);
    assertThat(completed.currentReply()).contains(positive());
    var continuation = owner(repository, manager).join(original, verified, custody);
    assertThat(continuation.requestId()).isEqualTo(completed.requestId());
    verify(account, times(1)).joinPublicProductionMembership(any(), any(), any(), any());
  }

  @Test
  void independentConnectionsAllocateOneFrozenWinnerAndOnlyOneDispatchClaim() throws Exception {
    realms();
    var gate = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var one =
          executor.submit(
              () -> {
                await(gate);
                return freeze();
              });
      var two =
          executor.submit(
              () -> {
                await(gate);
                return freeze();
              });
      gate.countDown();
      var first = one.get(10, TimeUnit.SECONDS);
      var second = two.get(10, TimeUnit.SECONDS);
      assertThat(second.request()).isEqualTo(first.request());
      assertThat(first.requestId())
          .isNotIn(
              original.requestId(),
              original.callerContextId(),
              first.scope().recordId(),
              custody.transport.proxyConnectionId(),
              verified.evidence().tokenJti());
      var a = executor.submit(() -> claim(first));
      var b = executor.submit(() -> claim(second));
      assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
      assertThat(repository.readCommittedJoin(first).state()).isEqualTo(State.DISPATCH_POSSIBLE);
      assertThat(dsl.fetchCount(DSL.table(JOIN))).isEqualTo(1);
    } finally {
      gate.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void actualParentAbandonmentAndDispatchClaimSerializeInBothOrders(boolean abandonmentFirst)
      throws Exception {
    realms();
    var frozen = freeze();
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var followerStarted = new CountDownLatch(1);
    var followerPid = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var winner =
          executor.submit(
              () ->
                  tx.execute(
                      status -> {
                        repository.verifyOriginal(original, verified);
                        if (abandonmentFirst) journal.abandon(original);
                        else
                          assertThat(
                                  repository.claimDispatch(
                                      frozen, verified, custody.transport, clock))
                              .isTrue();
                        locked.countDown();
                        await(release);
                        return true;
                      }));
      assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
      var follower =
          executor.submit(
              () -> {
                try {
                  return tx.execute(
                      status -> {
                        followerPid.set(
                            dsl.fetchSingle("SELECT pg_backend_pid() AS pid")
                                .get("pid", Integer.class));
                        followerStarted.countDown();
                        if (abandonmentFirst)
                          return repository.claimDispatch(
                              frozen, verified, custody.transport, clock);
                        journal.abandon(original);
                        return true;
                      });
                } catch (IllegalStateException denied) {
                  return false;
                }
              });
      assertThat(followerStarted.await(5, TimeUnit.SECONDS)).isTrue();
      awaitActualLockWait(followerPid.get());
      assertThat(follower.isDone()).isFalse();
      release.countDown();
      assertThat(winner.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(follower.get(10, TimeUnit.SECONDS)).isEqualTo(!abandonmentFirst);
      var retained = repository.readCommittedJoin(frozen);
      assertThat(retained.state())
          .isEqualTo(abandonmentFirst ? State.NEVER_DISPATCHED : State.DISPATCH_POSSIBLE);
      assertThat(journal.readCommitted(original).orElseThrow().abandoned()).isTrue();
      assertThatThrownBy(() -> claim(frozen)).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(this::realms).isInstanceOf(IllegalStateException.class);
      verify(account, times(1)).issueDirectTextConnectScope(any(), any());
      if (!abandonmentFirst) {
        tx.executeWithoutResult(
            status -> repository.recordPositiveReply(retained, verified, positive()));
        assertThat(repository.readCommittedJoin(retained).reply()).contains(positive());
      }
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void freshExplicitRealmsPreservesOlderUnknownAndForbidsStaleDispatch() {
    var older = realms();
    when(account.joinPublicProductionMembership(any(), any(), any(), any())).thenReturn(unknown());
    var first = owner.join(original, verified, custody);
    var oldOriginal =
        dsl.fetchSingle(
                "SELECT request_bytes FROM " + JOIN + " WHERE scope_record_id = ?",
                older.recordId())
            .get("request_bytes", byte[].class);
    var newer = realms();
    assertThat(newer.recordId()).isNotEqualTo(older.recordId());
    assertThat(repository.readCommittedScope(older).encode()).containsExactly(older.encode());
    assertThat(
            dsl.fetchSingle(
                    "SELECT request_bytes FROM " + JOIN + " WHERE scope_record_id = ?",
                    older.recordId())
                .get("request_bytes", byte[].class))
        .containsExactly(oldOriginal);
    var oldJoin =
        new DirectTextAuthenticatedLobbyJoin(
            older,
            DirectTextAuthenticatedLobbyJoin.decodeRequest(oldOriginal),
            State.DISPATCH_POSSIBLE,
            Optional.empty());
    assertThatThrownBy(() -> claim(oldJoin)).isInstanceOf(IllegalStateException.class);
    var second = owner.join(original, verified, custody);
    assertThat(second.requestId()).isNotEqualTo(first.requestId());
    assertThat(dsl.fetchCount(DSL.table(SCOPE))).isEqualTo(2);
    assertThat(dsl.fetchCount(DSL.table(JOIN))).isEqualTo(2);
    assertThat(parentJson()).isEqualTo(parentBeforeMigration);
  }

  @Test
  void lostReplyLeavesOriginalUnresolvedAcrossOwnerReplacementAndExpiryOrResetDoesNotEraseIt() {
    realms();
    when(account.joinPublicProductionMembership(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("Controlled lost reply"));
    assertThatThrownBy(() -> owner.join(original, verified, custody))
        .isInstanceOf(IllegalStateException.class);
    var continued = owner(repository, manager).join(original, verified, custody);
    assertThat(continued.status())
        .isEqualTo(DirectTextAuthenticatedLobbyOwner.Status.RETAINED_UNRESOLVED);
    var before = joinJson();
    clock.now = NOW.plusSeconds(60);
    assertThatThrownBy(() -> owner.join(original, verified, custody))
        .isInstanceOf(IllegalArgumentException.class);
    clock.now = NOW;
    custody.reset();
    assertThatThrownBy(() -> owner.join(original, verified, custody))
        .isInstanceOf(IllegalStateException.class);
    assertThat(joinJson()).isEqualTo(before);
    verify(account, times(1)).joinPublicProductionMembership(any(), any(), any(), any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void lostCommittedFreezeOrDispatchResponseRetainsSameOriginalWithoutAutomaticReissue(
      boolean loseFreeze) {
    realms();
    when(account.joinPublicProductionMembership(any(), any(), any(), any())).thenReturn(positive());
    var loseCommit = new AtomicBoolean();
    var instrumented = spy(repository);
    if (loseFreeze) {
      doAnswer(
              call -> {
                Object value = call.callRealMethod();
                loseCommit.set(true);
                return value;
              })
          .when(instrumented)
          .freezeCurrentJoin(any(), any(), any(), any());
    } else {
      doAnswer(
              call -> {
                Object value = call.callRealMethod();
                loseCommit.set(true);
                return value;
              })
          .when(instrumented)
          .claimDispatch(any(), any(), any(), any());
    }
    var losingManager =
        new PlatformTransactionManager() {
          @Override
          public TransactionStatus getTransaction(TransactionDefinition definition) {
            return manager.getTransaction(definition);
          }

          @Override
          public void commit(TransactionStatus status) {
            manager.commit(status);
            if (loseCommit.getAndSet(false))
              throw new IllegalStateException("Controlled committed owner reply loss");
          }

          @Override
          public void rollback(TransactionStatus status) {
            manager.rollback(status);
          }
        };
    assertThatThrownBy(() -> owner(instrumented, losingManager).join(original, verified, custody))
        .isInstanceOf(IllegalStateException.class);
    UUID retainedId =
        dsl.fetchSingle("SELECT join_request_id FROM " + JOIN).get("join_request_id", UUID.class);
    verify(account, times(0)).joinPublicProductionMembership(any(), any(), any(), any());
    var continued = owner(repository, manager).join(original, verified, custody);
    assertThat(continued.requestId()).isEqualTo(retainedId);
    assertThat(continued.status())
        .isEqualTo(
            loseFreeze
                ? DirectTextAuthenticatedLobbyOwner.Status.COMPLETED_CURRENT_NON_ADMITTING
                : DirectTextAuthenticatedLobbyOwner.Status.RETAINED_UNRESOLVED);
    verify(account, times(loseFreeze ? 1 : 0))
        .joinPublicProductionMembership(any(), any(), any(), any());
    assertThat(dsl.fetchCount(DSL.table(JOIN))).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void lostResultPersistenceOutcomeNeverRearmsOrOverwrites(boolean committedBeforeLoss) {
    realms();
    when(account.joinPublicProductionMembership(any(), any(), any(), any())).thenReturn(positive());
    var loseAfterCommit = new AtomicBoolean();
    var instrumented = spy(repository);
    doAnswer(
            call -> {
              call.callRealMethod();
              if (committedBeforeLoss) loseAfterCommit.set(true);
              else throw new IllegalStateException("Controlled result transaction rollback");
              return null;
            })
        .when(instrumented)
        .recordPositiveReply(any(), any(), any());
    var losingManager =
        new PlatformTransactionManager() {
          @Override
          public TransactionStatus getTransaction(TransactionDefinition definition) {
            return manager.getTransaction(definition);
          }

          @Override
          public void commit(TransactionStatus status) {
            manager.commit(status);
            if (loseAfterCommit.getAndSet(false))
              throw new IllegalStateException("Controlled committed reply loss");
          }

          @Override
          public void rollback(TransactionStatus status) {
            manager.rollback(status);
          }
        };
    assertThatThrownBy(() -> owner(instrumented, losingManager).join(original, verified, custody))
        .isInstanceOf(IllegalStateException.class);
    var continued = owner(repository, manager).join(original, verified, custody);
    assertThat(continued.status())
        .isEqualTo(
            committedBeforeLoss
                ? DirectTextAuthenticatedLobbyOwner.Status.COMPLETED_CURRENT_NON_ADMITTING
                : DirectTextAuthenticatedLobbyOwner.Status.RETAINED_UNRESOLVED);
    verify(account, times(1)).joinPublicProductionMembership(any(), any(), any(), any());
    assertThat(dsl.fetchCount(DSL.table(JOIN))).isEqualTo(1);
  }

  @Test
  void positiveInflightResultAfterAbandonmentAndTransportResetIsHistoricalOnly() {
    var scope = realms();
    when(account.joinPublicProductionMembership(any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              tx.executeWithoutResult(status -> journal.abandon(original));
              custody.resetAfterCurrentCallback();
              return positive();
            });
    var observed = owner.join(original, verified, custody);
    assertThat(observed.status())
        .isEqualTo(DirectTextAuthenticatedLobbyOwner.Status.HISTORICAL_ONLY);
    assertThat(observed.currentReply()).isEmpty();
    byte[] reply =
        dsl.fetchSingle(
                "SELECT reply_bytes FROM " + JOIN + " WHERE scope_record_id = ?", scope.recordId())
            .get("reply_bytes", byte[].class);
    assertThat(DirectTextAuthenticatedLobbyJoin.decodeReply(scope, reply)).isEqualTo(positive());
  }

  @Test
  void delayedPositiveResultDoesNotMoveANewerCurrentScope() {
    var old = realms();
    when(account.joinPublicProductionMembership(any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              var newer = realms();
              assertThat(newer.recordId()).isNotEqualTo(old.recordId());
              return positive();
            });
    var observed = owner.join(original, verified, custody);
    assertThat(observed.status())
        .isEqualTo(DirectTextAuthenticatedLobbyOwner.Status.HISTORICAL_ONLY);
    assertThat(observed.currentReply()).isEmpty();
    var currentId =
        dsl.fetchSingle("SELECT scope_record_id FROM " + CURRENT)
            .get("scope_record_id", UUID.class);
    assertThat(currentId).isNotEqualTo(old.recordId());
    assertThat(
            dsl.fetchSingle(
                    "SELECT state FROM " + JOIN + " WHERE scope_record_id = ?", old.recordId())
                .get("state", String.class))
        .isEqualTo("COMPLETED");
  }

  @Test
  void currentVerifiedIdentityAndCompleteWitnessAreRequiredNotMerelyMatchingSqlOrFlags()
      throws Exception {
    realms();
    try (var differentAccount =
            verified(original, UUID.randomUUID(), verified.evidence().tokenJti(), "5");
        var differentWitness = verified(original, ACCOUNT, UUID.randomUUID(), "6")) {
      assertThatThrownBy(
              () ->
                  tx.execute(
                      status ->
                          repository.current(original, differentAccount, custody.transport, clock)))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(
              () ->
                  tx.execute(
                      status ->
                          repository.current(original, differentWitness, custody.transport, clock)))
          .isInstanceOf(IllegalStateException.class);
    }
    var wrongFence =
        new InitialGameplayLoginAttemptCoordinates(
            original.requestId(), original.callerContextId(), 8);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(status -> repository.verifyOriginal(wrongFence, verified)))
        .isInstanceOf(IllegalStateException.class);
    var missing =
        new InitialGameplayLoginAttemptCoordinates(UUID.randomUUID(), UUID.randomUUID(), 1);
    try (var result = verified(missing, ACCOUNT, UUID.randomUUID(), "1")) {
      assertThatThrownBy(
              () -> tx.executeWithoutResult(status -> repository.verifyOriginal(missing, result)))
          .isInstanceOf(IllegalStateException.class);
      tx.executeWithoutResult(status -> journal.reserve(missing));
      assertThatThrownBy(
              () -> tx.executeWithoutResult(status -> repository.verifyOriginal(missing, result)))
          .isInstanceOf(IllegalStateException.class);
    }
    verified.close();
    assertThatThrownBy(() -> owner.join(original, verified, custody))
        .isInstanceOf(IllegalStateException.class);
    assertThat(dsl.fetchCount(DSL.table(JOIN))).isZero();
  }

  @Test
  void directSqlProtectsEveryCompanionAndRefusesInvalidIdentityTransitionsAndPointerRegression() {
    var oldScope = realms();
    var frozen = freeze();
    reject(
        "UPDATE " + JOIN + " SET state = 'COMPLETED', reply_bytes = ?", positive().toByteArray());
    reject("UPDATE " + JOIN + " SET join_request_id = ?", original.requestId());
    reject("UPDATE " + JOIN + " SET request_bytes = ?", new byte[] {1});
    assertThat(claim(frozen)).isTrue();
    reject("UPDATE " + JOIN + " SET state = 'NEVER_DISPATCHED'");
    tx.executeWithoutResult(status -> repository.recordPositiveReply(frozen, verified, positive()));
    reject(
        "UPDATE " + JOIN + " SET reply_bytes = ?",
        positive().toBuilder().setReplayed(true).build().toByteArray());
    reject("UPDATE " + SCOPE + " SET account_id = ?", UUID.randomUUID());
    reject("UPDATE " + SCOPE + " SET scope_bytes = ?", new byte[] {1});
    realms();
    reject(
        "UPDATE "
            + CURRENT
            + " SET scope_record_id = ?, scope_sequence = (SELECT scope_sequence FROM "
            + SCOPE
            + " WHERE scope_record_id = ?)",
        oldScope.recordId(),
        oldScope.recordId());
    for (String table : List.of(JOIN, CURRENT, SCOPE)) {
      assertThatThrownBy(() -> dsl.execute("DELETE FROM " + table))
          .hasMessageContaining("retention is held")
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(() -> dsl.execute("TRUNCATE " + table + " CASCADE"))
          .hasMessageContaining("retention is held")
          .isInstanceOf(DataAccessException.class);
    }
    assertThat(dsl.fetchCount(DSL.table(SCOPE))).isEqualTo(2);
    assertThat(dsl.fetchCount(DSL.table(JOIN))).isEqualTo(1);
  }

  @Test
  void nonDurableUnjoinedOrAmbientTransactionsCannotSupplyReadbackOrDispatchEvidence() {
    realms();
    assertThatThrownBy(this::freezeWithoutTransaction).isInstanceOf(IllegalStateException.class);
    var frozen = freeze();
    assertThatThrownBy(() -> tx.execute(status -> repository.readCommittedJoin(frozen)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                tx.execute(
                    status -> {
                      dsl.execute("SET LOCAL synchronous_commit = 'off'");
                      return repository.claimDispatch(frozen, verified, custody.transport, clock);
                    }))
        .isInstanceOf(IllegalStateException.class);
    var unjoined =
        new DirectTextAuthenticatedLobbyRepository(DSL.using(source, SQLDialect.POSTGRES));
    assertThatThrownBy(
            () ->
                tx.execute(
                    status -> unjoined.claimDispatch(frozen, verified, custody.transport, clock)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(repository.readCommittedJoin(frozen).state()).isEqualTo(State.NEVER_DISPATCHED);
  }

  private DirectTextAuthenticatedLobbyOwner owner(
      DirectTextAuthenticatedLobbyRepository repo, PlatformTransactionManager transactions) {
    return new DirectTextAuthenticatedLobbyOwner(repo, selection, account, transactions, clock);
  }

  private DirectTextAuthenticatedLobbyScope realms() {
    return owner.realms(original, verified, custody, selection.browse().orElseThrow(), "1");
  }

  private DirectTextAuthenticatedLobbyJoin freeze() {
    return tx.execute(status -> freezeWithoutTransaction());
  }

  private DirectTextAuthenticatedLobbyJoin freezeWithoutTransaction() {
    return repository.freezeCurrentJoin(original, verified, custody.transport, clock);
  }

  private boolean claim(DirectTextAuthenticatedLobbyJoin frozen) {
    return Boolean.TRUE.equals(
        tx.execute(status -> repository.claimDispatch(frozen, verified, custody.transport, clock)));
  }

  private String parentJson() {
    return dsl.fetchSingle(
            "SELECT to_jsonb(p)::text AS value FROM " + PARENT + " p WHERE request_id = ?",
            original.requestId())
        .get("value", String.class);
  }

  private String joinJson() {
    return dsl.fetchSingle("SELECT to_jsonb(j)::text AS value FROM " + JOIN + " j")
        .get("value", String.class);
  }

  private void reject(String sql, Object... values) {
    assertThatThrownBy(() -> dsl.execute(sql, values))
        .isInstanceOfSatisfying(
            DataAccessException.class,
            failure -> assertThat(failure.sqlState()).isEqualTo("23514"));
  }

  private void awaitActualLockWait(int pid) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (Boolean.TRUE.equals(
          dsl.fetchSingle("SELECT cardinality(pg_blocking_pids(?)) > 0 AS waiting", pid)
              .get("waiting", Boolean.class))) return;
      Thread.onSpinWait();
    }
    throw new IllegalStateException(
        "Independent original owner did not reach a PostgreSQL lock wait");
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS))
        throw new IllegalStateException("Bounded lobby fixture timed out");
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Lobby fixture interrupted", failure);
    }
  }

  private static JoinPublicProductionMembershipResponse positive() {
    return JoinPublicProductionMembershipResponse.newBuilder()
        .setSuccess(true)
        .setOutcomeCode("JOINED")
        .setAccountId(ACCOUNT.toString())
        .setTenantId("101")
        .setMembershipId("90")
        .setMembershipVersion(3)
        .setMembershipAuthorityGeneration(1)
        .build();
  }

  private static JoinPublicProductionMembershipResponse unknown() {
    return JoinPublicProductionMembershipResponse.newBuilder()
        .setOutcomeCode("AUTH_UNAVAILABLE")
        .setError(ErrorDetail.newBuilder().setCode("AUTH_UNAVAILABLE"))
        .build();
  }

  private static CanonicalPlayableTarget target() {
    return new CanonicalPlayableTarget(
        "gameplay",
        "studio",
        UUID.randomUUID(),
        "forest",
        "Forest",
        UUID.randomUUID(),
        "public",
        "Live",
        101,
        202,
        UUID.randomUUID(),
        "SHARED",
        UUID.randomUUID(),
        UUID.randomUUID(),
        303,
        1,
        7,
        "d".repeat(64),
        82,
        "original-open",
        "a".repeat(64),
        OriginKind.NO_PRIOR_POINTER,
        null,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "sha256:" + "c".repeat(64),
        27,
        "sha256:" + "f".repeat(64),
        Outcome.COMMITTED,
        false,
        NOW.minusSeconds(1),
        "ALLOW_NEW");
  }

  /** Controlled protected-call exclusion only; no claim of a real TCP/private-result producer. */
  private static final class ControlledCustody implements DirectTextAuthenticatedLobbyCustody {
    private final InitialGameplayLoginAttemptCoordinates original;
    private final CanonicalGameplayLoginResult result;
    private final ObservedDirectTextTransport transport;
    private boolean live = true;
    private boolean resetAfterCallback;

    ControlledCustody(
        InitialGameplayLoginAttemptCoordinates original,
        CanonicalGameplayLoginResult result,
        ObservedDirectTextTransport transport) {
      this.original = original;
      this.result = result;
      this.transport = transport;
    }

    synchronized void reset() {
      live = false;
    }

    synchronized void resetAfterCurrentCallback() {
      resetAfterCallback = true;
    }

    @Override
    public synchronized <T> T withCurrent(
        InitialGameplayLoginAttemptCoordinates coordinates,
        CanonicalGameplayLoginResult borrowed,
        Function<ObservedDirectTextTransport, T> action) {
      if (!live || !original.equals(coordinates) || borrowed != result)
        throw new IllegalStateException("Controlled custody unavailable");
      try {
        return action.apply(transport);
      } finally {
        if (resetAfterCallback) live = false;
      }
    }
  }

  private static final class MutableClock extends Clock {
    private Instant now = NOW;

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  /** Real signature/aggregate validation with locally controlled keys, not real Account custody. */
  private static CanonicalGameplayLoginResult verified(
      InitialGameplayLoginAttemptCoordinates original, UUID accountId, UUID jti, String fence)
      throws Exception {
    long now = NOW.getEpochSecond();
    var claims =
        new LinkedHashMap<String, Object>(
            Map.ofEntries(
                Map.entry("iss", GameSessionAccountDelegationProfile.ISSUER),
                Map.entry("aud", GameSessionAccountDelegationProfile.AUDIENCE),
                Map.entry("sub", accountId.toString()),
                Map.entry("accountId", accountId.toString()),
                Map.entry("jti", jti.toString()),
                Map.entry("iat", now - 2),
                Map.entry("nbf", now - 2),
                Map.entry("exp", now + 120),
                Map.entry("tokenGeneration", "1"),
                Map.entry("issuanceFence", fence),
                Map.entry("membershipVersion", Map.of()),
                Map.entry(
                    "authorityTuple", GameSessionAccountDelegationProfile.authorityTuple(1, 1))));
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String input =
        encoder.encodeToString(
                "{\"alg\":\"RS256\",\"kid\":\"test-key\",\"typ\":\"JWT\"}"
                    .getBytes(StandardCharsets.UTF_8))
            + "."
            + encoder.encodeToString(JSON.writeValueAsBytes(claims));
    var signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(keys.getPrivate());
    signer.update(input.getBytes(StandardCharsets.US_ASCII));
    String token = input + "." + encoder.encodeToString(signer.sign());
    var request =
        AuthenticateRequest.newBuilder()
            .setRequestId(original.requestId().toString())
            .setEmail("player@example.test")
            .setPassword("fixture-transient-secret")
            .setSourceContext(
                GameplayLoginSourceContext.newBuilder()
                    .setCallerContextId(original.callerContextId().toString())
                    .setCanonicalClientAddress("203.0.113.10")
                    .setTransportClass(
                        GameplayLoginTransportClass
                            .GAMEPLAY_LOGIN_TRANSPORT_CLASS_TRUSTED_TCP_PROXY))
            .build();
    var response =
        AuthenticateResponse.newBuilder()
            .setAuthToken(token)
            .setAccountId(accountId.toString())
            .setRequestId(original.requestId().toString())
            .setCallerContextId(original.callerContextId().toString())
            .setTokenJti(jti.toString())
            .setTokenSha256(
                HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(StandardCharsets.US_ASCII))))
            .setTokenProfile(GameSessionAccountDelegationProfile.PROFILE)
            .setIssuedAtEpochSeconds(now - 2)
            .setNotBeforeEpochSeconds(now - 2)
            .setExpiresAtEpochSeconds(now + 120)
            .setTokenGeneration("1")
            .setIssuanceFence(fence)
            .setAuthorityTupleCanonicalJson(canonical(claims.get("authorityTuple")))
            .setOutboxCheckpointsCanonicalJson(
                canonical(
                    List.of(
                        Map.of(
                            "outboxStreamKey",
                            "account:auth-authority:v1:account/" + accountId,
                            "outboxSequence",
                            "0"),
                        Map.of(
                            "outboxStreamKey",
                            "account:auth-authority:v1:issuer/firemud-account-service",
                            "outboxSequence",
                            "0"))))
            .setOutboxSourceEventEvidenceCanonicalJson(canonical(List.of()))
            .build();
    var pin =
        new AccountPublicJwksCache.SourceIdentity(
            "test",
            "cluster-a",
            "11111111-1111-4111-8111-111111111111",
            "test",
            "22222222-2222-4222-8222-222222222222",
            "33333333-3333-4333-8333-333333333333",
            "binding-7",
            "https://kubernetes.example:6443",
            "a".repeat(64));
    var key = (RSAPublicKey) keys.getPublic();
    String jwks =
        JSON.writeValueAsString(
            Map.of(
                "keys",
                List.of(
                    Map.of(
                        "kty",
                        "RSA",
                        "use",
                        "sig",
                        "alg",
                        "RS256",
                        "kid",
                        "test-key",
                        "n",
                        unsigned(key.getModulus().toByteArray()),
                        "e",
                        unsigned(key.getPublicExponent().toByteArray())))));
    var cache =
        new AccountPublicJwksCache(
            () ->
                new AccountPublicJwksCache.PublicJwksSnapshot(
                    pin, jwks.getBytes(StandardCharsets.UTF_8)),
            pin,
            VERIFY_CLOCK,
            Duration.ofSeconds(30));
    return CanonicalGameplayLoginResult.fromAuthenticatedResponse(
        response, request, new AccountAsymmetricJwtVerifier(cache, VERIFY_CLOCK), VERIFY_CLOCK);
  }

  private static ByteString canonical(Object value) throws Exception {
    return ByteString.copyFrom(
        Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value)));
  }

  private static String unsigned(byte[] bytes) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            bytes.length > 1 && bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
  }
}
