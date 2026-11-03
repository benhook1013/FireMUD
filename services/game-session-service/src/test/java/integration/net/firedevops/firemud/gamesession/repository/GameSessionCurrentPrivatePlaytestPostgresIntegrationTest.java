package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCurrentPrivatePlaytestRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCurrentPrivatePlaytestRepository.Current;
import net.firedevops.firemud.gamesession.repository.GameSessionCurrentPrivatePlaytestRepository.HeadRevisionConflictException;
import net.firedevops.firemud.gamesession.repository.GameSessionCurrentPrivatePlaytestRepository.IdempotencyConflictException;
import net.firedevops.firemud.gamesession.repository.GameSessionCurrentPrivatePlaytestRepository.Request;
import net.firedevops.firemud.gamesession.repository.GameSessionCurrentPrivatePlaytestRepository.Scope;
import net.firedevops.firemud.gamesession.repository.GameSessionCurrentPrivatePlaytestRepository.SelectionResult;
import net.firedevops.firemud.gamesession.repository.GameSessionCurrentPrivatePlaytestRepository.SourceUnavailableException;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.Preparation;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.ReadState;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Physical current-selection proof with actual initial-preparation/private-policy owners. The
 * established canonical fixture stipulates upstream Account/World/Game Design authority; these
 * tests establish neither workflow caller authorization nor cross-owner commit protection or
 * admission. No clock, immutable source or preparation row is rewritten.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionCurrentPrivatePlaytestPostgresIntegrationTest {
  private static final String NAMESPACE = "current-playtest-proof";
  private static final String HISTORY = "game_session_private_playtest_selections";
  private static final String HEAD = "game_session_current_private_playtests";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @BeforeAll
  static void awaitReady() {
    PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(postgres);
  }

  @Test
  void exactQualifiedSelectionAndReplacementReplayOriginalBytesWithoutReselectingNewest() {
    Context context = context(true);
    Preparation first = context.prepare(context.now().plusSeconds(3600));
    Request firstRequest = context.request(UUID.randomUUID(), first, null, null);
    var sourceRowsBefore = context.sourceRows();
    SelectionResult initial = context.select(firstRequest);
    assertThat(initial.replayed()).isFalse();
    assertThat(initial.receipt().headRevision()).isEqualTo(1);
    assertThat(initial.receipt().canonicalPreimage())
        .containsExactly(firstRequest.canonicalPreimage());
    byte[] persisted =
        Objects.requireNonNull(
                context
                    .dsl()
                    .fetchOne(
                        "SELECT request_preimage FROM " + HISTORY + " WHERE request_id = ?",
                        firstRequest.requestId()))
            .get("request_preimage", byte[].class);
    assertThat(persisted).containsExactly(firstRequest.canonicalPreimage());
    Current firstCurrent = context.current();
    assertThat(firstCurrent.selection()).isEqualTo(initial.receipt());
    assertThat(firstCurrent.selectedPrepSnapshot().preparation()).isEqualTo(first);
    assertThat(firstCurrent.selectedPrepSnapshot().state()).isEqualTo(ReadState.INCOMPLETE);
    assertThat(firstCurrent.admissionAllowed()).isFalse();
    assertThat(firstCurrent.selectedPrepSnapshot().admissionAllowed()).isFalse();
    assertThat(firstCurrent.selectedPrepSnapshot().preparation().source().targetPolicy())
        .isEqualTo(first.source().targetPolicy());
    Preparation second = context.prepare(context.now().plusSeconds(7200));
    Request replacement =
        context.request(UUID.randomUUID(), second, 1L, first.playtestLifecycleId());
    var replaced = context.select(replacement);
    assertThat(replaced.receipt().headRevision()).isEqualTo(2);
    assertThat(context.current().selectedPrepSnapshot().preparation()).isEqualTo(second);
    var beforeReplay = context.selectionRows();
    assertThat(context.select(firstRequest))
        .isEqualTo(new SelectionResult(initial.receipt(), true));
    assertThat(context.select(replacement))
        .isEqualTo(new SelectionResult(replaced.receipt(), true));
    assertThat(context.selectionRows()).usingRecursiveComparison().isEqualTo(beforeReplay);
    assertThat(context.current().selection().headRevision()).isEqualTo(2);
    assertThat(context.current().selectedPrepSnapshot().preparation().playtestStateGeneration())
        .isEqualTo(1);
    // The head revision is independent of each lifecycle's generation-one preparation.
    assertThat(context.sourceRows()).usingRecursiveComparison().isEqualTo(sourceRowsBefore);
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
    assertThat(context.dsl().fetchCount(DSL.table("game_instances"))).isZero();
  }

  @Test
  void changedRequestAndStaleRevisionOrCurrentLifecycleNeverAdvanceOrRecordAResult() {
    Context context = context(true);
    Preparation first = context.prepare(context.now().plusSeconds(3600));
    Preparation second = context.prepare(context.now().plusSeconds(3600));
    Request original = context.request(UUID.randomUUID(), first, null, null);
    context.select(original);
    var before = context.selectionRows();
    for (Request changed :
        List.of(
            context.request(original.requestId(), second, null, null),
            context.request(original.requestId(), first, 1L, first.playtestLifecycleId()),
            new Request(
                original.requestId(),
                new Scope(
                    NAMESPACE,
                    context.scope().tenantId(),
                    "another-world",
                    context.scope().realmSlug()),
                first.request().requestId(),
                first.playtestLifecycleId(),
                null,
                null))) {
      assertThatThrownBy(() -> context.select(changed))
          .isInstanceOf(IdempotencyConflictException.class);
    }
    for (Request stale :
        List.of(
            context.request(UUID.randomUUID(), second, null, null),
            context.request(UUID.randomUUID(), second, 2L, first.playtestLifecycleId()),
            context.request(UUID.randomUUID(), second, 1L, UUID.randomUUID()))) {
      assertThatThrownBy(() -> context.select(stale))
          .isInstanceOf(HeadRevisionConflictException.class);
    }
    assertThat(context.selectionRows()).usingRecursiveComparison().isEqualTo(before);
    assertThat(context.current().selection().headRevision()).isEqualTo(1);
    assertThat(context.current().selectedPrepSnapshot().preparation()).isEqualTo(first);
  }

  @Test
  void concurrentWritersOfSameExpectedHeadHaveOneWinnerAndOneNoMutationConflict() throws Exception {
    Context context = context(true);
    Preparation first = context.prepare(context.now().plusSeconds(3600));
    Preparation second = context.prepare(context.now().plusSeconds(3600));
    Preparation third = context.prepare(context.now().plusSeconds(3600));
    context.select(context.request(UUID.randomUUID(), first, null, null));
    Request leftRequest =
        context.request(UUID.randomUUID(), second, 1L, first.playtestLifecycleId());
    Request rightRequest =
        context.request(UUID.randomUUID(), third, 1L, first.playtestLifecycleId());
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch begin = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var left = executor.submit(() -> concurrentSelect(context, leftRequest, ready, begin));
      var right = executor.submit(() -> concurrentSelect(context, rightRequest, ready, begin));
      assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
      begin.countDown();
      var leftResult = left.get(60, TimeUnit.SECONDS);
      var rightResult = right.get(60, TimeUnit.SECONDS);
      assertThat(List.of(leftResult.applied(), rightResult.applied()))
          .containsExactlyInAnyOrder(true, false);
      UUID winningLifecycle =
          leftResult.applied() ? second.playtestLifecycleId() : third.playtestLifecycleId();
      assertThat(context.current().selection().playtestLifecycleId()).isEqualTo(winningLifecycle);
      assertThat(context.current().selection().headRevision()).isEqualTo(2);
      assertThat(context.count(HISTORY)).isEqualTo(2);
      assertThat(context.count(HEAD)).isEqualTo(1);
    } finally {
      begin.countDown();
      executor.shutdown();
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  @Test
  void absentPreparationWrongScopeAndAbsentPrivatePolicyCannotBecomeCurrent() {
    Context context = context(true);
    Optional<Current> initiallyAbsent =
        context
            .transaction()
            .execute(
                status ->
                    context
                        .repository()
                        .readCurrent(
                            NAMESPACE,
                            context.scope().tenantId(),
                            "source-world",
                            "private-playtest"));
    assertThat(initiallyAbsent).isEmpty();
    assertThatThrownBy(
            () ->
                context.select(
                    new Request(
                        UUID.randomUUID(),
                        context.scope(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        null)))
        .isInstanceOf(SourceUnavailableException.class);
    Preparation first = context.prepare(context.now().plusSeconds(3600));
    assertThatThrownBy(
            () ->
                context.select(
                    new Request(
                        UUID.randomUUID(),
                        new Scope(NAMESPACE, UUID.randomUUID(), "source-world", "private-playtest"),
                        first.request().requestId(),
                        first.playtestLifecycleId(),
                        null,
                        null)))
        .isInstanceOf(SourceUnavailableException.class);
    assertThatThrownBy(
            () ->
                context.select(
                    new Request(
                        UUID.randomUUID(),
                        context.scope(),
                        UUID.randomUUID(),
                        first.playtestLifecycleId(),
                        null,
                        null)))
        .isInstanceOf(SourceUnavailableException.class);
    assertThat(context.count(HISTORY)).isZero();
    assertThat(context.count(HEAD)).isZero();
    Context publicOnly = context(false);
    assertThatThrownBy(() -> publicOnly.prepare(publicOnly.now().plusSeconds(3600)))
        .isInstanceOf(
            GameSessionInitialPlaytestLifecycleRepository.SourceUnavailableException.class);
    assertThatThrownBy(
            () ->
                publicOnly.select(
                    new Request(
                        UUID.randomUUID(),
                        publicOnly.scope(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        null)))
        .isInstanceOf(SourceUnavailableException.class);
    assertThat(publicOnly.count(HEAD)).isZero();
    assertThat(publicOnly.count(HISTORY)).isZero();
  }

  @Test
  void expiredCurrentRemainsNonAdmissibleAndHistoricalReplayDoesNotReplaceIt() {
    Context context = context(true);
    Preparation first = context.prepare(context.now().plusSeconds(5));
    Request initial = context.request(UUID.randomUUID(), first, null, null);
    var selected = context.select(initial);
    awaitExpiry(context, first.request().forkExpiresAt());
    assertThat(context.current().selectedPrepSnapshot().state()).isEqualTo(ReadState.EXPIRED);
    assertThat(context.current().admissionAllowed()).isFalse();
    assertThat(context.select(initial)).isEqualTo(new SelectionResult(selected.receipt(), true));
    assertThatThrownBy(
            () ->
                context.select(
                    context.request(UUID.randomUUID(), first, 1L, first.playtestLifecycleId())))
        .isInstanceOf(SourceUnavailableException.class)
        .hasMessageContaining("expired");
    Preparation next = context.prepare(context.now().plusSeconds(3600));
    context.select(context.request(UUID.randomUUID(), next, 1L, first.playtestLifecycleId()));
    assertThat(context.select(initial)).isEqualTo(new SelectionResult(selected.receipt(), true));
    assertThat(context.current().selection().playtestLifecycleId())
        .isEqualTo(next.playtestLifecycleId());
    assertThat(context.count(HISTORY)).isEqualTo(2);
  }

  @Test
  void rollbackAndFinalCommitExpiryLeaveNeitherHeadNorReceiptAndCannotReserveRequestIdentity() {
    Context context = context(true);
    Preparation first = context.prepare(context.now().plusSeconds(3600));
    Request original = context.request(UUID.randomUUID(), first, null, null);
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .executeWithoutResult(
                        status -> {
                          context.repository().select(original);
                          throw new IllegalStateException("Controlled protected workflow refusal");
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Controlled protected workflow refusal");
    assertThat(context.count(HISTORY)).isZero();
    assertThat(context.count(HEAD)).isZero();
    Preparation shortPreparation = context.prepare(context.now().plusSeconds(5));
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .executeWithoutResult(
                        status -> {
                          context
                              .repository()
                              .select(
                                  context.request(UUID.randomUUID(), shortPreparation, null, null));
                          awaitExpiry(context, shortPreparation.request().forkExpiresAt());
                        }))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("expired before final commit");
    assertThat(context.count(HISTORY)).isZero();
    assertThat(context.count(HEAD)).isZero();
    assertThat(context.select(original).receipt().headRevision()).isEqualTo(1);
  }

  @Test
  void immutableHistoryScopeAndExactHeadReferenceGuardsRejectDirectViolations() {
    Context context = context(true);
    Preparation first = context.prepare(context.now().plusSeconds(3600));
    var selected = context.select(context.request(UUID.randomUUID(), first, null, null));
    for (String sql :
        List.of(
            "UPDATE " + HISTORY + " SET request_preimage = request_preimage",
            "DELETE FROM " + HISTORY,
            "TRUNCATE " + HISTORY + " CASCADE",
            "DELETE FROM " + HEAD,
            "TRUNCATE " + HEAD,
            "UPDATE " + HEAD + " SET realm_slug = 'another-realm'",
            "UPDATE " + HEAD + " SET head_revision = 3",
            "UPDATE " + HEAD + " SET head_revision = 2",
            "UPDATE " + HEAD + " SET playtest_lifecycle_id = gen_random_uuid()")) {
      assertThatThrownBy(
              () ->
                  context.transaction().executeWithoutResult(status -> context.dsl().execute(sql)))
          .as(sql)
          .isInstanceOf(RuntimeException.class);
    }
    assertThat(context.current().selection()).isEqualTo(selected.receipt());
    assertThat(context.count(HISTORY)).isEqualTo(1);
    assertThatThrownBy(
            () ->
                context
                    .repository()
                    .readCurrent(
                        NAMESPACE, context.scope().tenantId(), "source-world", "private-playtest"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("owner transaction");
  }

  private static Outcome concurrentSelect(
      Context context, Request request, CountDownLatch ready, CountDownLatch begin) {
    ready.countDown();
    try {
      if (!begin.await(30, TimeUnit.SECONDS))
        throw new AssertionError("Concurrent selection was not released");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
    try {
      context.select(request);
      return new Outcome(true);
    } catch (HeadRevisionConflictException conflict) {
      return new Outcome(false);
    }
  }

  private record Outcome(boolean applied) {}

  private static void awaitExpiry(Context context, Instant expiry) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      if (!context.now().isBefore(expiry)) return;
      try {
        Thread.sleep(20);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(interrupted);
      }
    }
    throw new AssertionError("Real database clock did not reach expiry");
  }

  private Context context(boolean privatePolicy) {
    String schema = "current_playtest_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .locations(
            "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize(),
            "classpath:db/migration/saga")
        .placeholders(Map.of("serviceSchema", schema))
        .load()
        .migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    UUID tenant = UUID.randomUUID();
    UUID registration = UUID.randomUUID();
    UUID sourceOperation = UUID.randomUUID();
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registration, tenant, "source-tenant", "source-world", "Source World");
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registration,
            sourceOperation,
            requestDigest,
            tenant,
            "source-tenant",
            "source-world",
            "Source World",
            731L,
            "controlled-source-game",
            "NEW_GAME_ROW");
    var evidence =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            registration,
            sourceOperation,
            requestDigest,
            tenant,
            "source-tenant",
            "source-world",
            "Source World",
            731L,
            "controlled-source-game",
            "NEW_GAME_ROW",
            evidenceDigest);
    var intake =
        Objects.requireNonNull(
            transaction.execute(
                status ->
                    new GameSessionAuthoredWorldSourceRepository(dsl)
                        .register(UUID.randomUUID(), evidence)));
    transaction.executeWithoutResult(
        status ->
            new GameSessionFreshTenantAssociationRepository(dsl)
                .associateFresh(
                    UUID.randomUUID(),
                    NAMESPACE,
                    intake,
                    CanonicalHumanPublicationOwnerTestFixtures.creationEvidence(evidence)));
    var catalogRequest =
        new CreateCanonicalRealmCatalogRequest(
            UUID.randomUUID(),
            NAMESPACE,
            tenant,
            intake.operationId(),
            "source-public",
            "Source Public",
            true,
            true,
            "SHARED",
            "PRESEEDED_ONLY",
            null,
            null);
    UUID version = UUID.randomUUID();
    List<RealmEntryPolicy> policies = privatePolicy ? List.of(privatePolicy()) : List.of();
    RealmPolicyPublishedEvidence.PublishedSet published =
        CanonicalHumanPublicationOwnerTestFixtures.publishedSet(
            catalogRequest,
            evidence,
            version,
            72L,
            "controlled-publish-" + UUID.randomUUID(),
            "release-bundle:" + version,
            "generation-1",
            3L,
            policies);
    var catalogs = new GameSessionCanonicalRealmCatalogRepository(dsl);
    var prepared = catalogs.prepareInitialPublicProduction(catalogRequest, published);
    var catalog =
        Objects.requireNonNull(
            transaction.execute(status -> catalogs.createInitialPublicProduction(prepared)));
    return new Context(
        dsl,
        transaction,
        new GameSessionCurrentPrivatePlaytestRepository(dsl),
        new GameSessionInitialPlaytestLifecycleRepository(dsl),
        catalog,
        published,
        new Scope(NAMESPACE, tenant, "source-world", "private-playtest"));
  }

  private static RealmEntryPolicy privatePolicy() {
    try {
      String json =
          new String(
              Rfc8785CanonicalJson.canonicalizeUtf8(
                  "{\"entryPolicy\":\"PRESEEDED_ONLY\",\"realmDisplayName\":\"Private Playtest\",\"realmSlug\":\"private-playtest\",\"publicProduction\":false,\"schemaVersion\":1,\"stateScope\":\"ISOLATED\",\"visible\":true,\"worldDisplayName\":\"Source World\",\"worldSlug\":\"source-world\"}"),
              StandardCharsets.UTF_8);
      return RealmEntryPolicy.parseCanonical(json, JsonMapper.builder().build());
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot encode controlled private policy", failure);
    }
  }

  private record Context(
      DSLContext dsl,
      TransactionTemplate transaction,
      GameSessionCurrentPrivatePlaytestRepository repository,
      GameSessionInitialPlaytestLifecycleRepository preparations,
      CanonicalRealmCatalogSnapshot catalog,
      RealmPolicyPublishedEvidence.PublishedSet published,
      Scope scope) {
    Instant now() {
      return Objects.requireNonNull(dsl.fetchOne("SELECT clock_timestamp() AS now"))
          .get("now", OffsetDateTime.class)
          .toInstant();
    }

    Preparation prepare(Instant expiry) {
      var request =
          new GameSessionInitialPlaytestLifecycleRepository.Request(
              UUID.randomUUID(),
              NAMESPACE,
              scope.tenantId(),
              scope.worldSlug(),
              scope.realmSlug(),
              catalog.realmId(),
              catalog.creationRequestId(),
              catalog.catalogRevision(),
              catalog.sourceIntakeReceipt().operationId(),
              published.target().canonicalVersionId(),
              published.publishedReleaseBundleRef(),
              published.publishedReleaseBundleDigest(),
              GameSessionInitialPlaytestLifecycleRepository.InitializationMode.FRESH,
              expiry);
      return Objects.requireNonNull(transaction.execute(status -> preparations.prepare(request)))
          .preparation();
    }

    Request request(
        UUID id, Preparation preparation, Long expectedRevision, UUID expectedLifecycle) {
      return new Request(
          id,
          scope,
          preparation.request().requestId(),
          preparation.playtestLifecycleId(),
          expectedRevision,
          expectedLifecycle);
    }

    SelectionResult select(Request request) {
      return Objects.requireNonNull(transaction.execute(status -> repository.select(request)));
    }

    Current current() {
      return Objects.requireNonNull(
          transaction.execute(
              status ->
                  repository
                      .readCurrent(
                          NAMESPACE, scope.tenantId(), scope.worldSlug(), scope.realmSlug())
                      .orElseThrow()));
    }

    int count(String table) {
      return dsl.fetchCount(DSL.table(table));
    }

    List<Map<String, Object>> selectionRows() {
      return dsl.fetch("SELECT * FROM " + HISTORY + " ORDER BY head_revision").intoMaps();
    }

    List<Map<String, Object>> sourceRows() {
      return dsl.fetch(
              "SELECT realm_id::TEXT AS identity, xmin::TEXT AS version FROM game_session_canonical_realm_catalog "
                  + "UNION ALL SELECT operation_id::TEXT, xmin::TEXT FROM game_session_authored_world_source_intake ORDER BY 1")
          .intoMaps();
    }
  }
}
