package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.Allocation;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.IdempotencyConflictException;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.InitializationMode;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.ReadState;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.Request;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.Snapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.SourceUnavailableException;
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

/**
 * Physical initial allocation/read proof using actual GS source/catalog/preparation owners and SQL.
 *
 * <p>The existing canonical publication fixture stipulates upstream Account/World/Game Design
 * authority; this does not execute those producers or establish caller authorization, participant
 * manifests, isolation, source delivery, registration or playtest admission. Fixture-policy source
 * rows are never accepted by this primitive.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionInitialPlaytestLifecyclePostgresIntegrationTest {
  private static final String NAMESPACE = "initial-playtest-proof";
  private static final String TABLE = "game_session_initial_playtest_preparations";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @BeforeAll
  static void awaitPostgresReady() {
    PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(postgres);
  }

  @Test
  void allocatesFreshNonAdmissibleIdentityFromExactRetainedCanonicalSourceAndReplaysOriginal() {
    Context context = context(true);
    UUID legacyNamespace = UUID.randomUUID();
    context
        .dsl()
        .execute(
            "INSERT INTO gameplay_tenant_shared_playable_state_namespace (tenant_id, playable_state_namespace_id) VALUES (99174, ?)",
            legacyNamespace);
    var retainedBefore = context.retainedRows();
    Request request =
        context.request(
            UUID.randomUUID(),
            "private-playtest",
            context
                .now()
                .plusSeconds(3600)
                .with(java.time.temporal.ChronoField.NANO_OF_SECOND, 123456789));
    Allocation allocated = context.prepare(request);
    assertThat(allocated.replayed()).isFalse();
    var preparation = allocated.preparation();
    assertThat(preparation.request()).isEqualTo(request);
    assertThat(preparation.requestDigest()).isEqualTo(request.requestDigest());
    byte[] retainedRequestBytes =
        Objects.requireNonNull(
                context
                    .dsl()
                    .fetchOne(
                        "SELECT request_preimage FROM " + TABLE + " WHERE request_id = ?",
                        request.requestId()))
            .get("request_preimage", byte[].class);
    // The 46-byte schema marker has a four-byte big-endian length, not decimal-colon framing.
    assertThat(retainedRequestBytes)
        .startsWith(new byte[] {0, 0, 0, 46})
        .containsExactly(request.canonicalPreimage());
    assertThat(preparation.playtestLifecycleId()).isNotEqualTo(new UUID(0, 0));
    assertThat(preparation.playableStateNamespaceId())
        .isNotEqualTo(new UUID(0, 0))
        .isNotEqualTo(preparation.playtestLifecycleId())
        .isNotEqualTo(context.catalog().playableStateNamespaceId())
        .isNotEqualTo(legacyNamespace);
    assertThat(preparation.playtestStateGeneration()).isEqualTo(1);
    assertThat(preparation.preparationState()).isEqualTo("PREPARING");
    assertThat(preparation.source().intakeOperationId())
        .isEqualTo(context.catalog().sourceIntakeReceipt().operationId());
    assertThat(preparation.source().sourceOperationId())
        .isEqualTo(context.catalog().sourceIntakeReceipt().source().operationId());
    assertThat(preparation.source().sourceEvidenceDigest())
        .isEqualTo(context.catalog().sourceIntakeReceipt().source().evidenceDigest());
    assertThat(preparation.source().canonicalVersionId())
        .isEqualTo(context.published().target().canonicalVersionId());
    assertThat(preparation.source().publishedReleaseBundleDigest())
        .isEqualTo(context.published().publishedReleaseBundleDigest());
    assertThat(context.prepare(request)).isEqualTo(new Allocation(preparation, true));
    var snapshot = context.read(request, preparation.playtestLifecycleId());
    assertThat(snapshot.preparation()).isEqualTo(preparation);
    assertThat(snapshot.state()).isEqualTo(ReadState.INCOMPLETE);
    assertThat(snapshot.admissionAllowed()).isFalse();
    assertThat(context.count()).isEqualTo(1);
    assertThat(context.retainedRows()).usingRecursiveComparison().isEqualTo(retainedBefore);
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
    assertThat(context.dsl().fetchCount(DSL.table("game_instances"))).isZero();
  }

  @Test
  void changedIntentUnderOneRequestConflictsBeforeSourceLookupOrAllocation() {
    Context context = context(true);
    Request original =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    Allocation allocated = context.prepare(original);
    for (Request changed :
        List.of(
            context.request(
                original.requestId(), "another-private-realm", original.forkExpiresAt()),
            context.request(
                original.requestId(), original.realmSlug(), original.forkExpiresAt().plusNanos(1)),
            new Request(
                original.requestId(),
                NAMESPACE,
                original.tenantId(),
                "another-world",
                original.realmSlug(),
                original.sourceCatalogRealmId(),
                original.sourceCatalogRequestId(),
                1,
                original.sourceIntakeOperationId(),
                original.canonicalVersionId(),
                original.publishedReleaseBundleRef(),
                original.publishedReleaseBundleDigest(),
                InitializationMode.FRESH,
                original.forkExpiresAt()),
            new Request(
                original.requestId(),
                NAMESPACE,
                UUID.randomUUID(),
                original.worldSlug(),
                original.realmSlug(),
                original.sourceCatalogRealmId(),
                original.sourceCatalogRequestId(),
                1,
                original.sourceIntakeOperationId(),
                original.canonicalVersionId(),
                original.publishedReleaseBundleRef(),
                original.publishedReleaseBundleDigest(),
                InitializationMode.FRESH,
                original.forkExpiresAt()),
            new Request(
                original.requestId(),
                NAMESPACE,
                original.tenantId(),
                original.worldSlug(),
                original.realmSlug(),
                original.sourceCatalogRealmId(),
                original.sourceCatalogRequestId(),
                1,
                original.sourceIntakeOperationId(),
                UUID.randomUUID(),
                original.publishedReleaseBundleRef(),
                original.publishedReleaseBundleDigest(),
                InitializationMode.FRESH,
                original.forkExpiresAt()))) {
      assertThatThrownBy(() -> context.prepare(changed))
          .isInstanceOf(IdempotencyConflictException.class);
    }
    assertThat(context.prepare(original).preparation()).isEqualTo(allocated.preparation());
    assertThat(context.count()).isEqualTo(1);
  }

  @Test
  void missingOrMismatchedRetainedSourceAndBuildNeverAllocate() {
    Context missing = context(false);
    assertThatThrownBy(
            () ->
                missing.prepare(
                    missing.request(
                        UUID.randomUUID(), "private-playtest", missing.now().plusSeconds(300))))
        .isInstanceOf(SourceUnavailableException.class);
    assertThat(missing.count()).isZero();
    Context context = context(true);
    Request original =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(300));
    for (Request changed :
        List.of(
            new Request(
                UUID.randomUUID(),
                NAMESPACE,
                original.tenantId(),
                original.worldSlug(),
                original.realmSlug(),
                UUID.randomUUID(),
                original.sourceCatalogRequestId(),
                1,
                original.sourceIntakeOperationId(),
                original.canonicalVersionId(),
                original.publishedReleaseBundleRef(),
                original.publishedReleaseBundleDigest(),
                InitializationMode.FRESH,
                original.forkExpiresAt()),
            new Request(
                UUID.randomUUID(),
                NAMESPACE,
                original.tenantId(),
                original.worldSlug(),
                original.realmSlug(),
                original.sourceCatalogRealmId(),
                original.sourceCatalogRequestId(),
                1,
                UUID.randomUUID(),
                original.canonicalVersionId(),
                original.publishedReleaseBundleRef(),
                original.publishedReleaseBundleDigest(),
                InitializationMode.FRESH,
                original.forkExpiresAt()),
            new Request(
                UUID.randomUUID(),
                NAMESPACE,
                original.tenantId(),
                original.worldSlug(),
                original.realmSlug(),
                original.sourceCatalogRealmId(),
                original.sourceCatalogRequestId(),
                1,
                original.sourceIntakeOperationId(),
                UUID.randomUUID(),
                original.publishedReleaseBundleRef(),
                original.publishedReleaseBundleDigest(),
                InitializationMode.FRESH,
                original.forkExpiresAt()),
            new Request(
                UUID.randomUUID(),
                NAMESPACE,
                original.tenantId(),
                original.worldSlug(),
                original.realmSlug(),
                original.sourceCatalogRealmId(),
                original.sourceCatalogRequestId(),
                1,
                original.sourceIntakeOperationId(),
                original.canonicalVersionId(),
                original.publishedReleaseBundleRef(),
                "sha256:" + "a".repeat(64),
                InitializationMode.FRESH,
                original.forkExpiresAt()),
            context.request(
                UUID.randomUUID(), context.catalog().realmSlug(), original.forkExpiresAt()))) {
      assertThatThrownBy(() -> context.prepare(changed))
          .isInstanceOf(SourceUnavailableException.class);
    }
    assertThat(context.count()).isZero();
  }

  @Test
  void concurrentExactRequestAllocatesOnceAndNewRequestsForSameRealmGetDistinctIdentities()
      throws Exception {
    Context context = context(true);
    Request request =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch begin = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () -> {
                ready.countDown();
                await(begin);
                return context.prepare(request);
              });
      var second =
          executor.submit(
              () -> {
                ready.countDown();
                await(begin);
                return context.prepare(request);
              });
      assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
      begin.countDown();
      Allocation left = first.get(60, TimeUnit.SECONDS);
      Allocation right = second.get(60, TimeUnit.SECONDS);
      assertThat(left.preparation()).isEqualTo(right.preparation());
      assertThat(List.of(left.replayed(), right.replayed())).containsExactlyInAnyOrder(false, true);
      assertThat(context.count()).isEqualTo(1);
      Request newFirst =
          context.request(UUID.randomUUID(), request.realmSlug(), request.forkExpiresAt());
      Request newSecond =
          context.request(UUID.randomUUID(), request.realmSlug(), request.forkExpiresAt());
      var third = executor.submit(() -> context.prepare(newFirst));
      var fourth = executor.submit(() -> context.prepare(newSecond));
      var another = third.get(60, TimeUnit.SECONDS).preparation();
      var other = fourth.get(60, TimeUnit.SECONDS).preparation();
      assertThat(another.playtestLifecycleId())
          .isNotEqualTo(left.preparation().playtestLifecycleId())
          .isNotEqualTo(other.playtestLifecycleId());
      assertThat(another.playableStateNamespaceId())
          .isNotEqualTo(left.preparation().playableStateNamespaceId())
          .isNotEqualTo(other.playableStateNamespaceId());
      assertThat(context.read(request, left.preparation().playtestLifecycleId()).preparation())
          .isEqualTo(left.preparation());
      assertThat(context.count()).isEqualTo(3);
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
  void exactReadRejectsWrongLifecycleAndReportsRealExpiryWithoutChangingOriginal() {
    Context context = context(true);
    Request request =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(5));
    Allocation allocation = context.prepare(request);
    var original = allocation.preparation();
    assertThat(context.read(request, original.playtestLifecycleId()).state())
        .isEqualTo(ReadState.INCOMPLETE);
    Optional<Snapshot> wrongLifecycle =
        context
            .transaction()
            .execute(
                status ->
                    context
                        .repository()
                        .readExact(
                            NAMESPACE,
                            request.tenantId(),
                            request.worldSlug(),
                            request.realmSlug(),
                            UUID.randomUUID()));
    Optional<Snapshot> wrongRealm =
        context
            .transaction()
            .execute(
                status ->
                    context
                        .repository()
                        .readExact(
                            NAMESPACE,
                            request.tenantId(),
                            request.worldSlug(),
                            "wrong-realm",
                            original.playtestLifecycleId()));
    Optional<Snapshot> wrongTenant =
        context
            .transaction()
            .execute(
                status ->
                    context
                        .repository()
                        .readExact(
                            NAMESPACE,
                            UUID.randomUUID(),
                            request.worldSlug(),
                            request.realmSlug(),
                            original.playtestLifecycleId()));
    Optional<Snapshot> wrongWorld =
        context
            .transaction()
            .execute(
                status ->
                    context
                        .repository()
                        .readExact(
                            NAMESPACE,
                            request.tenantId(),
                            "wrong-world",
                            request.realmSlug(),
                            original.playtestLifecycleId()));
    assertThat(wrongLifecycle).isEmpty();
    assertThat(wrongRealm).isEmpty();
    assertThat(wrongTenant).isEmpty();
    assertThat(wrongWorld).isEmpty();
    awaitExpiry(context, request.forkExpiresAt());
    var expired = context.read(request, original.playtestLifecycleId());
    assertThat(expired.observedAt()).isAfterOrEqualTo(request.forkExpiresAt());
    assertThat(expired.state()).isEqualTo(ReadState.EXPIRED);
    assertThat(expired.admissionAllowed()).isFalse();
    assertThat(expired.preparation()).isEqualTo(original);
    assertThat(context.prepare(request)).isEqualTo(new Allocation(original, true));
    var reuse =
        context
            .prepare(
                context.request(
                    UUID.randomUUID(), request.realmSlug(), context.now().plusSeconds(3600)))
            .preparation();
    assertThat(reuse.playtestLifecycleId()).isNotEqualTo(original.playtestLifecycleId());
    assertThat(reuse.playableStateNamespaceId()).isNotEqualTo(original.playableStateNamespaceId());
  }

  @Test
  void rollbackExpiredFinalCommitAndImmutableHistoryViolationNeverPublishAuthority() {
    Context context = context(true);
    Request request =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .executeWithoutResult(
                        status -> {
                          context.repository().prepare(request);
                          throw new IllegalStateException("Controlled coordinator refusal");
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Controlled coordinator refusal");
    assertThat(context.count()).isZero();
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .executeWithoutResult(
                        status -> {
                          Request shortRequest =
                              context.request(
                                  UUID.randomUUID(),
                                  "private-playtest",
                                  context.now().plusSeconds(5));
                          context.repository().prepare(shortRequest);
                          awaitExpiry(context, shortRequest.forkExpiresAt());
                        }))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("expired before final preparation commit");
    assertThat(context.count()).isZero();
    var committed = context.prepare(request);
    for (String sql :
        List.of(
            "UPDATE " + TABLE + " SET preparation_state = 'OPEN'",
            "UPDATE " + TABLE + " SET playtest_state_generation = 2",
            "UPDATE " + TABLE + " SET fork_expiry_second = fork_expiry_second + 1",
            "DELETE FROM " + TABLE,
            "TRUNCATE " + TABLE)) {
      assertThatThrownBy(
              () ->
                  context.transaction().executeWithoutResult(status -> context.dsl().execute(sql)))
          .as(sql)
          .isInstanceOf(RuntimeException.class);
    }
    assertThat(context.count()).isEqualTo(1);
    assertThat(context.prepare(request).preparation()).isEqualTo(committed.preparation());
    assertThat(context.dsl().fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
  }

  @Test
  void unsupportedInitializationAndMissingOwnerTransactionFailBeforeAllocation() {
    Context context = context(true);
    Request request =
        context.request(UUID.randomUUID(), "private-playtest", context.now().plusSeconds(3600));
    for (InitializationMode mode :
        List.of(InitializationMode.SEEDED, InitializationMode.SNAPSHOT)) {
      assertThatThrownBy(
              () ->
                  new Request(
                      request.requestId(),
                      NAMESPACE,
                      request.tenantId(),
                      request.worldSlug(),
                      request.realmSlug(),
                      request.sourceCatalogRealmId(),
                      request.sourceCatalogRequestId(),
                      1,
                      request.sourceIntakeOperationId(),
                      request.canonicalVersionId(),
                      request.publishedReleaseBundleRef(),
                      request.publishedReleaseBundleDigest(),
                      mode,
                      request.forkExpiresAt()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Only initial FRESH");
    }
    assertThatThrownBy(() -> context.repository().prepare(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("owner transaction");
    assertThat(context.count()).isZero();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS))
        throw new AssertionError("Concurrent allocation did not start");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

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

  private Context context(boolean withSource) {
    String schema = "initial_playtest_" + UUID.randomUUID().toString().replace("-", "");
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
            "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize())
        .load()
        .migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    var repository = new GameSessionInitialPlaytestLifecycleRepository(dsl);
    if (!withSource) return new Context(dsl, transaction, repository, null, null);
    // These upstream receipts are explicitly stipulated. Every retained GS row is created through
    // its actual source, fresh association and closed Human canonical catalog owner.
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
    var sourceOwner = new GameSessionAuthoredWorldSourceRepository(dsl);
    var intake =
        Objects.requireNonNull(
            transaction.execute(status -> sourceOwner.register(UUID.randomUUID(), evidence)));
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
    var published =
        CanonicalHumanPublicationOwnerTestFixtures.publishedSet(
            catalogRequest,
            evidence,
            version,
            72L,
            "controlled-publish-" + UUID.randomUUID(),
            "release-bundle:" + version,
            "generation-1",
            3L);
    var catalogs = new GameSessionCanonicalRealmCatalogRepository(dsl);
    var preparedCatalog = catalogs.prepareInitialPublicProduction(catalogRequest, published);
    var catalog =
        Objects.requireNonNull(
            transaction.execute(status -> catalogs.createInitialPublicProduction(preparedCatalog)));
    return new Context(dsl, transaction, repository, catalog, published);
  }

  private record Context(
      DSLContext dsl,
      TransactionTemplate transaction,
      GameSessionInitialPlaytestLifecycleRepository repository,
      CanonicalRealmCatalogSnapshot catalog,
      RealmPolicyPublishedEvidence.PublishedSet published) {
    Instant now() {
      return Objects.requireNonNull(dsl.fetchOne("SELECT clock_timestamp() AS now"))
          .get("now", OffsetDateTime.class)
          .toInstant();
    }

    Request request(UUID request, String realm, Instant expiry) {
      if (catalog == null)
        return new Request(
            request,
            NAMESPACE,
            UUID.randomUUID(),
            "missing-world",
            realm,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "missing-release",
            "sha256:" + "b".repeat(64),
            InitializationMode.FRESH,
            expiry);
      return new Request(
          request,
          NAMESPACE,
          catalog.tenantId(),
          catalog.worldSlug(),
          realm,
          catalog.realmId(),
          catalog.creationRequestId(),
          catalog.catalogRevision(),
          catalog.sourceIntakeReceipt().operationId(),
          published.target().canonicalVersionId(),
          published.publishedReleaseBundleRef(),
          published.publishedReleaseBundleDigest(),
          InitializationMode.FRESH,
          expiry);
    }

    Allocation prepare(Request request) {
      return Objects.requireNonNull(transaction.execute(status -> repository.prepare(request)));
    }

    GameSessionInitialPlaytestLifecycleRepository.Snapshot read(Request request, UUID lifecycle) {
      return Objects.requireNonNull(
          transaction.execute(
              status ->
                  repository
                      .readExact(
                          NAMESPACE,
                          request.tenantId(),
                          request.worldSlug(),
                          request.realmSlug(),
                          lifecycle)
                      .orElseThrow()));
    }

    int count() {
      return dsl.fetchCount(DSL.table(TABLE));
    }

    List<Map<String, Object>> retainedRows() {
      return dsl.fetch(
              "SELECT 'catalog' AS source, realm_id::TEXT AS id, xmin::TEXT AS version FROM game_session_canonical_realm_catalog "
                  + "UNION ALL SELECT 'namespace', playable_state_namespace_id::TEXT, xmin::TEXT FROM gameplay_tenant_shared_playable_state_namespace ORDER BY 1, 2")
          .intoMaps();
    }
  }
}
