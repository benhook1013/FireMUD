package integration.net.firedevops.firemud.gamedesign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.impl.AuthoredWorldVersionStateService;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultExecuteListener;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL owner proof for source-qualified, current Game Design version-state reads. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = GameDesignServiceApplication.class,
    properties = {
      "spring.profiles.active=test",
      "firemud.auth.jwt-secret=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "firemud.grpc.plaintext=true",
      "firemud.grpc.workload-namespace=authored-version-state-test",
      "spring.grpc.server.port=0",
      "asset.store.endpoint=http://localhost:9000",
      "asset.store.bucket=test-bucket",
      "asset.store.region=us-east-1",
      "asset.store.access-key=test-access-key",
      "asset.store.secret-key=test-secret-key"
    })
@Import(NoGrpcServerTestConfiguration.class)
class AuthoredWorldVersionStateIntegrationTest {
  private static final String NAMESPACE = "authored-version-state-test";
  private static final org.jooq.Table<?> VERSION = DSL.table(DSL.name("version"));
  private static final org.jooq.Field<Long> VERSION_ID = DSL.field(DSL.name("id"), Long.class);
  private static final org.jooq.Field<String> VERSION_STATE =
      DSL.field(DSL.name("version_state"), String.class);
  private static final org.jooq.Field<Long> VERSION_STATE_EPOCH =
      DSL.field(DSL.name("version_state_epoch"), Long.class);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "game_design_service");
  }

  @Autowired private AuthoredWorldVersionStateService service;
  @Autowired private GameRepository gameRepository;
  @Autowired private GameAuthoredWorldSourceRepository sourceRepository;
  @Autowired private VersionRepository versionRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DSLContext dsl;
  @Autowired private javax.sql.DataSource dataSource;

  @Test
  void readsCompletePersistedSourceAndCurrentVersionWithoutMutatingOwnerRows() {
    Fixture fixture = fixture("positive");
    Map<String, String> before = rowVersions(fixture);

    AuthoredWorldVersionStateEvidence first = service.read(request(fixture, UUID.randomUUID()));

    assertThat(first.request().canonicalTenantId()).isEqualTo(fixture.canonicalTenantId());
    assertThat(first.request().canonicalTenantId().toString())
        .isNotEqualTo(fixture.privateTenantKey());
    assertThat(first.sourceEvidence()).isEqualTo(fixture.source());
    assertThat(first.versionState())
        .isEqualTo(
            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                .VERSION_LIFECYCLE_STATE_DRAFT);
    assertThat(first.versionStateEpoch()).isEqualTo(1L);
    assertThat(rowVersions(fixture)).isEqualTo(before);

    Version changed = fixture.version();
    changed.setVersionState(VersionLifecycleState.RETIRED);
    changed.setVersionStateEpoch(2L);
    new TransactionTemplate(transactionManager).execute(status -> versionRepository.save(changed));

    AuthoredWorldVersionStateEvidence current = service.read(request(fixture, UUID.randomUUID()));
    assertThat(current.sourceEvidence()).isEqualTo(first.sourceEvidence());
    assertThat(current.versionState())
        .isEqualTo(
            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                .VERSION_LIFECYCLE_STATE_RETIRED);
    assertThat(current.versionStateEpoch()).isEqualTo(2L);
    Map<String, String> afterStateChange = rowVersions(fixture);
    assertThat(afterStateChange).containsEntry("game", before.get("game"));
    assertThat(afterStateChange).containsEntry("binding", before.get("binding"));
    assertThat(afterStateChange).containsEntry("source", before.get("source"));
    assertThat(afterStateChange.get("version")).isNotEqualTo(before.get("version"));
    assertThat(rowVersions(fixture)).isEqualTo(afterStateChange);
  }

  @Test
  void rejectsStaleSourceDigestAndARealVersionOwnedByAnotherGame() {
    Fixture sourceFixture = fixture("ownership-source");
    Fixture otherFixture = fixture("ownership-other");
    String staleDigest = "sha256:" + "f".repeat(64);
    Map<String, String> sourceBefore = rowVersions(sourceFixture);
    Map<String, String> otherBefore = rowVersions(otherFixture);

    assertThatThrownBy(() -> service.read(request(sourceFixture, UUID.randomUUID(), staleDigest)))
        .isInstanceOf(GameAuthoredWorldSourceRepository.InvalidSourceEvidenceException.class)
        .hasMessageContaining("digest");
    assertThatThrownBy(
            () ->
                service.read(
                    request(
                        UUID.randomUUID(),
                        otherFixture.canonicalTenantId(),
                        otherFixture.source().worldSlug(),
                        sourceFixture.source().operationId(),
                        sourceFixture.source().evidenceDigest(),
                        sourceFixture.version().getId())))
        .isInstanceOf(GameAuthoredWorldSourceRepository.InvalidSourceEvidenceException.class)
        .hasMessageContaining("exact requested scope");
    assertThatThrownBy(
            () ->
                service.read(
                    request(
                        sourceFixture,
                        UUID.randomUUID(),
                        sourceFixture.source().evidenceDigest(),
                        otherFixture.version().getId())))
        .isInstanceOf(GameAuthoredWorldSourceRepository.InvalidSourceEvidenceException.class)
        .hasMessageContaining("not owned by the exact Game Design source game");
    assertThat(rowVersions(sourceFixture)).isEqualTo(sourceBefore);
    assertThat(rowVersions(otherFixture)).isEqualTo(otherBefore);
  }

  @Test
  void freshOwnerSnapshotDoesNotJoinAnAmbientRepeatableReadSnapshot() {
    Fixture fixture = fixture("ambient-snapshot");
    TransactionTemplate outer = new TransactionTemplate(transactionManager);
    outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    AuthoredWorldVersionStateEvidence result =
        outer.execute(
            status -> {
              assertThat(
                      dsl.select(VERSION_STATE)
                          .from(VERSION)
                          .where(VERSION_ID.eq(fixture.version().getId()))
                          .fetchOne(VERSION_STATE))
                  .isEqualTo("DRAFT");
              updateVersionInRequiresNew(fixture, "PUBLISHED", 2L);
              AuthoredWorldVersionStateEvidence fresh =
                  service.read(request(fixture, UUID.randomUUID()));
              assertThat(
                      dsl.select(VERSION_STATE)
                          .from(VERSION)
                          .where(VERSION_ID.eq(fixture.version().getId()))
                          .fetchOne(VERSION_STATE))
                  .isEqualTo("DRAFT");
              return fresh;
            });

    assertThat(result).isNotNull();
    assertThat(result.versionState())
        .isEqualTo(
            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                .VERSION_LIFECYCLE_STATE_PUBLISHED);
    assertThat(result.versionStateEpoch()).isEqualTo(2L);
  }

  @Test
  void versionChangeDuringOwnerReadRemainsOneRepeatableReadSnapshot() throws Exception {
    Fixture fixture = fixture("concurrent-snapshot");
    CountDownLatch versionQueryReached = new CountDownLatch(1);
    CountDownLatch versionQueryReleased = new CountDownLatch(1);
    AtomicBoolean interlockEnabled = new AtomicBoolean(true);
    DefaultExecuteListener interlock =
        new DefaultExecuteListener() {
          @Override
          public void executeStart(ExecuteContext context) {
            String sql = context.sql();
            if (interlockEnabled.get()
                && sql != null
                && sql.toLowerCase(Locale.ROOT).contains("from \"version\"")) {
              versionQueryReached.countDown();
              await(versionQueryReleased);
            }
          }
        };
    DefaultConfiguration configuration = new DefaultConfiguration();
    configuration.set(new TransactionAwareDataSourceProxy(dataSource));
    configuration.set(SQLDialect.POSTGRES);
    configuration.set(new DefaultExecuteListenerProvider(interlock));
    GameAuthoredWorldSourceRepository interlockedRepository =
        new GameAuthoredWorldSourceRepository(DSL.using(configuration));
    AuthoredWorldVersionStateService interlockedService =
        new AuthoredWorldVersionStateService(transactionManager, interlockedRepository);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<AuthoredWorldVersionStateEvidence> read =
          executor.submit(() -> interlockedService.read(request(fixture, UUID.randomUUID())));
      assertThat(versionQueryReached.await(10, TimeUnit.SECONDS)).isTrue();
      updateVersionInRequiresNew(fixture, "PUBLISHED", 2L);
      interlockEnabled.set(false);
      versionQueryReleased.countDown();

      AuthoredWorldVersionStateEvidence snapshot = read.get(15, TimeUnit.SECONDS);
      assertThat(snapshot.versionState())
          .isEqualTo(
              net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                  .VERSION_LIFECYCLE_STATE_DRAFT);
      assertThat(snapshot.versionStateEpoch()).isEqualTo(1L);
      AuthoredWorldVersionStateEvidence nextRead =
          service.read(request(fixture, UUID.randomUUID()));
      assertThat(nextRead.versionState())
          .isEqualTo(
              net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                  .VERSION_LIFECYCLE_STATE_PUBLISHED);
      assertThat(nextRead.versionStateEpoch()).isEqualTo(2L);
    } finally {
      interlockEnabled.set(false);
      versionQueryReleased.countDown();
      executor.shutdownNow();
    }
  }

  private Fixture fixture(String label) {
    String privateTenantKey = "version-state-" + UUID.randomUUID().toString().substring(0, 8);
    Game game = new Game();
    game.setTenantId(privateTenantKey);
    game.setName("Synthetic " + label);
    game.setDescription("Synthetic source/version owner proof fixture");
    Game persistedGame =
        new TransactionTemplate(transactionManager).execute(status -> gameRepository.save(game));
    assertThat(persistedGame).isNotNull();

    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    AuthoredWorldSourceEvidence source =
        new TransactionTemplate(transactionManager)
            .execute(
                status ->
                    sourceRepository.register(
                        NAMESPACE,
                        UUID.randomUUID(),
                        persistedGame.getCanonicalTenantId(),
                        "tenant-" + suffix,
                        "world-" + suffix,
                        "Synthetic World 🐉"));
    assertThat(source).isNotNull();

    Version version = new Version();
    version.setTenantId(privateTenantKey);
    version.setVersionNumber(1);
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    version.setNotes("synthetic source-qualified version-state proof fixture");
    Version persistedVersion =
        new TransactionTemplate(transactionManager)
            .execute(status -> versionRepository.save(version));
    assertThat(persistedVersion).isNotNull();
    return new Fixture(
        persistedGame.getCanonicalTenantId(), privateTenantKey, source, persistedVersion);
  }

  private AuthoredWorldVersionStateEvidence.Request request(Fixture fixture, UUID readRequestId) {
    return request(
        fixture, readRequestId, fixture.source().evidenceDigest(), fixture.version().getId());
  }

  private AuthoredWorldVersionStateEvidence.Request request(
      Fixture fixture, UUID readRequestId, String sourceDigest) {
    return request(fixture, readRequestId, sourceDigest, fixture.version().getId());
  }

  private AuthoredWorldVersionStateEvidence.Request request(
      Fixture fixture, UUID readRequestId, String sourceDigest, long versionId) {
    return request(
        readRequestId,
        fixture.canonicalTenantId(),
        fixture.source().worldSlug(),
        fixture.source().operationId(),
        sourceDigest,
        versionId);
  }

  private AuthoredWorldVersionStateEvidence.Request request(
      UUID readRequestId,
      UUID canonicalTenantId,
      String worldSlug,
      UUID sourceOperationId,
      String sourceDigest,
      long versionId) {
    return new AuthoredWorldVersionStateEvidence.Request(
        1,
        NAMESPACE,
        readRequestId,
        canonicalTenantId,
        worldSlug,
        sourceOperationId,
        sourceDigest,
        versionId);
  }

  private Map<String, String> rowVersions(Fixture fixture) {
    return Map.of(
        "game",
        xmin(
            "SELECT xmin::text AS xmin FROM game WHERE canonical_tenant_id = ?",
            fixture.canonicalTenantId()),
        "binding",
        xmin(
            "SELECT xmin::text AS xmin FROM game_design_tenant_slug_binding "
                + "WHERE target_namespace = ? AND canonical_tenant_id = ?",
            NAMESPACE,
            fixture.canonicalTenantId()),
        "source",
        xmin(
            "SELECT xmin::text AS xmin FROM game_design_authored_world_source_operations "
                + "WHERE operation_id = ?",
            fixture.source().operationId()),
        "version",
        xmin("SELECT xmin::text AS xmin FROM version WHERE id = ?", fixture.version().getId()));
  }

  private String xmin(String sql, Object... arguments) {
    var rows = dsl.fetch(sql, arguments);
    assertThat(rows).hasSize(1);
    return rows.getFirst().get("xmin", String.class);
  }

  private void updateVersionInRequiresNew(Fixture fixture, String state, long epoch) {
    TransactionTemplate update = new TransactionTemplate(transactionManager);
    update.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    update.execute(
        status ->
            dsl.update(VERSION)
                .set(VERSION_STATE, state)
                .set(VERSION_STATE_EPOCH, epoch)
                .where(VERSION_ID.eq(fixture.version().getId()))
                .execute());
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out at version-state snapshot interlock");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted at version-state snapshot interlock", exception);
    }
  }

  private record Fixture(
      UUID canonicalTenantId,
      String privateTenantKey,
      AuthoredWorldSourceEvidence source,
      Version version) {}
}
