package net.firedevops.firemud.gamelogic.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeTerminal;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationReadClient;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationReadEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicPublicationSourceReadBinding;
import net.firedevops.firemud.common.gamelogic.GameplayAbilitySchemaProjection;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadClient;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameLogicGameplayRuleIntakePostgresIntegrationTest {
  private static final String NAMESPACE = "test";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void upgradesActualV1AndSagaParentWithoutChangingHistoryOrRetainedSagaData(
      @TempDir Path temporary) throws Exception {
    String schema = "gl_" + UUID.randomUUID().toString().replace("-", "");
    String historyTable = "flyway_schema_history_" + schema;
    var dataSource = new DriverManagerDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    dataSource.setSchema(schema);
    Path parentMigrationDirectory = Files.createDirectory(temporary.resolve("installed-parent"));
    var v1Resource =
        Objects.requireNonNull(
            getClass().getClassLoader().getResourceAsStream("db/migration/V1__init.sql"));
    try (v1Resource) {
      Files.copy(v1Resource, parentMigrationDirectory.resolve("V1__init.sql"));
    }

    org.flywaydb.core.Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .table(historyTable)
        .locations("filesystem:" + parentMigrationDirectory, "classpath:db/migration/saga")
        .load()
        .migrate();

    var dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var installedParents = migrationHistory(dsl, historyTable);
    assertThat(installedParents)
        .extracting(MigrationHistory::version)
        .containsExactly("1", "1000", "1001", "1002");
    assertThat(installedParents)
        .extracting(MigrationHistory::script)
        .containsExactly(
            "V1__init.sql",
            "V1000__create_saga_schema.sql",
            "V1001__saga_instance_table.sql",
            "V1002__saga_step_table.sql");
    assertThat(installedParents)
        .allSatisfy(
            row -> {
              assertThat(row.checksum()).isNotNull();
              assertThat(row.success()).isTrue();
            });
    long intakeTablesBeforeUpgrade =
        Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = ? AND table_name = 'game_logic_gameplay_rule_intake_terminal'",
                    schema))
            .get(0, Long.class);
    assertThat(intakeTablesBeforeUpgrade).isZero();
    // V1 is empty; retain an actual shared Saga row to detect changes to the installed parent.
    var insertedSaga =
        Objects.requireNonNull(
            dsl.fetchOne(
                "INSERT INTO "
                    + schema
                    + ".saga_instance (saga_name, state) VALUES (?, ?) RETURNING id",
                "retained-parent-saga",
                "RUNNING"));
    long sagaId = insertedSaga.get("id", Long.class);
    var retainedSagaBefore = sagaSnapshot(dsl, schema, sagaId);

    org.flywaydb.core.Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .table(historyTable)
        .locations("classpath:db/migration", "classpath:db/migration/saga")
        .load()
        .migrate();

    var upgradedHistory = migrationHistory(dsl, historyTable);
    assertThat(upgradedHistory)
        .extracting(MigrationHistory::version)
        .containsExactly("1", "1000", "1001", "1002", "1003");
    assertThat(upgradedHistory.subList(0, installedParents.size()))
        .containsExactlyElementsOf(installedParents);
    var installedIntake = upgradedHistory.getLast();
    assertThat(installedIntake.script())
        .isEqualTo("V1003__immutable_gameplay_rule_source_intake.sql");
    assertThat(installedIntake.installedRank())
        .isGreaterThan(installedParents.getLast().installedRank());
    assertThat(installedIntake.checksum()).isNotNull();
    assertThat(installedIntake.success()).isTrue();
    assertThat(sagaSnapshot(dsl, schema, sagaId)).isEqualTo(retainedSagaBefore);
    long intakeTablesAfterUpgrade =
        Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = ? AND table_name = 'game_logic_gameplay_rule_intake_terminal'",
                    schema))
            .get(0, Long.class);
    assertThat(intakeTablesAfterUpgrade).isEqualTo(1L);
    assertThat(dsl.fetchCount(DSL.table("game_logic_gameplay_rule_intake_terminal"))).isZero();
  }

  private static List<MigrationHistory> migrationHistory(DSLContext dsl, String historyTable) {
    return dsl.fetch(
            "SELECT installed_rank, version, script, checksum, success FROM "
                + historyTable
                + " WHERE version IS NOT NULL ORDER BY installed_rank")
        .map(
            row ->
                new MigrationHistory(
                    row.get("installed_rank", Integer.class),
                    row.get("version", String.class),
                    row.get("script", String.class),
                    row.get("checksum", Integer.class),
                    row.get("success", Boolean.class)));
  }

  private static SagaRowSnapshot sagaSnapshot(DSLContext dsl, String schema, long sagaId) {
    var row =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT encode(convert_to(to_jsonb(saga_instance)::text, 'UTF8'), 'hex') AS row_bytes, "
                    + "xmin::text AS xmin FROM "
                    + schema
                    + ".saga_instance WHERE id = ?",
                sagaId));
    return new SagaRowSnapshot(row.get("row_bytes", String.class), row.get("xmin", String.class));
  }

  @Test
  void exactConcurrentRetriesRetainOneImmutableOriginalTerminalAndReadItWithoutOwnerReads()
      throws Exception {
    var fixture = fixture();
    var authorization = authorization();
    var source = authorization.source();
    var bothSourceReads = new CountDownLatch(2);
    var accountReads = new AtomicInteger();
    var gameDesignReads = new AtomicInteger();
    var accountReader = mock(GameLogicIntakeAuthorizationReadClient.class);
    var gameDesignReader = mock(GameplayRuleSourceReadClient.class);
    when(accountReader.read(any(GameLogicIntakeAuthorizationReadEvidence.Request.class)))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              accountReads.incrementAndGet();
              return new GameLogicIntakeAuthorizationReadEvidence(invocation.getArgument(0));
            });
    when(gameDesignReader.read(any(GameplayRuleSourceReadEvidence.Request.class)))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              gameDesignReads.incrementAndGet();
              var sourceRequest = invocation.<GameplayRuleSourceReadEvidence.Request>getArgument(0);
              assertThat(sourceRequest.proof())
                  .isInstanceOf(GameplayRuleSourceReadEvidence.Finalized.class);
              assertThat(sourceRequest.proof().canonicalBytes())
                  .containsExactly(authorization.canonicalBytes());
              bothSourceReads.countDown();
              if (!bothSourceReads.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent source-read barrier timed out");
              }
              return new GameplayRuleSourceReadEvidence(invocation.getArgument(0), source);
            });
    var first = fixture.service(accountReader, gameDesignReader);
    var second = fixture.service(accountReader, gameDesignReader);
    byte[] originalTerminalBytes;

    try (var executor = Executors.newFixedThreadPool(2)) {
      var left = executor.submit(() -> asGameDesign(() -> first.retain(authorization)));
      var right = executor.submit(() -> asGameDesign(() -> second.retain(authorization)));
      var retained = left.get(15, TimeUnit.SECONDS);
      var retry = right.get(15, TimeUnit.SECONDS);
      originalTerminalBytes = retained.canonicalBytes();
      assertThat(retained.canonicalBytes()).containsExactly(retry.canonicalBytes());
    }

    assertThat(accountReads).hasValue(2);
    assertThat(gameDesignReads).hasValue(2);
    assertThat(fixture.dsl.fetchCount(DSL.table("game_logic_gameplay_rule_intake_terminal")))
        .isEqualTo(1);
    var stored = fixture.repository.findTerminal(authorization.operationId()).orElseThrow();
    assertThat(stored.authorizationBytes()).containsExactly(authorization.canonicalBytes());
    assertThat(stored.selectedSourceBytes()).containsExactly(source.canonicalBytes());
    assertThat(stored.manifestBytes())
        .containsExactly(source.manifest().canonicalJson().getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "UPDATE game_logic_gameplay_rule_intake_terminal SET outcome = 'ABORTED' WHERE operation_id = ?",
                    authorization.operationId()))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "DELETE FROM game_logic_gameplay_rule_intake_terminal WHERE operation_id = ?",
                    authorization.operationId()))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () -> fixture.dsl.execute("TRUNCATE game_logic_gameplay_rule_intake_terminal"))
        .isInstanceOf(RuntimeException.class);
    assertThat(fixture.dsl.fetchCount(DSL.table("game_logic_gameplay_rule_intake_terminal")))
        .isEqualTo(1);

    var unavailableAccount = mock(GameLogicIntakeAuthorizationReadClient.class);
    var unavailableDesign = mock(GameplayRuleSourceReadClient.class);
    when(unavailableAccount.read(any(GameLogicIntakeAuthorizationReadEvidence.Request.class)))
        .thenThrow(new AssertionError("Stored terminal retry must not reread Account"));
    when(unavailableDesign.read(any(GameplayRuleSourceReadEvidence.Request.class)))
        .thenThrow(new AssertionError("Stored terminal retry must not reread latest GD source"));
    var restarted = fixture.service(unavailableAccount, unavailableDesign);
    var recovered = asGameDesign(() -> restarted.retain(authorization));
    assertThat(recovered.canonicalBytes()).containsExactly(originalTerminalBytes);
  }

  @Test
  void explicitAbortWinsBeforeRetentionAndPreventsLaterSourceReadsAndCommit() throws Exception {
    var fixture = fixture();
    var authorization = authorization();
    var sourceReadEntered = new CountDownLatch(1);
    var allowSourceRead = new CountDownLatch(1);
    var accountReader = mock(GameLogicIntakeAuthorizationReadClient.class);
    var gameDesignReader = mock(GameplayRuleSourceReadClient.class);
    when(accountReader.read(any(GameLogicIntakeAuthorizationReadEvidence.Request.class)))
        .thenAnswer(
            invocation -> new GameLogicIntakeAuthorizationReadEvidence(invocation.getArgument(0)));
    when(gameDesignReader.read(any(GameplayRuleSourceReadEvidence.Request.class)))
        .thenAnswer(
            invocation -> {
              sourceReadEntered.countDown();
              if (!allowSourceRead.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Source-read barrier timed out");
              }
              return new GameplayRuleSourceReadEvidence(
                  invocation.getArgument(0), authorization.source());
            });
    var service = fixture.service(accountReader, gameDesignReader);

    try (var executor = Executors.newSingleThreadExecutor()) {
      var retention = executor.submit(() -> asGameDesign(() -> service.retain(authorization)));
      assertThat(sourceReadEntered.await(5, TimeUnit.SECONDS)).isTrue();
      var aborted = asGameDesign(() -> service.abort(authorization));
      allowSourceRead.countDown();
      var observed = retention.get(15, TimeUnit.SECONDS);
      assertThat(aborted.outcome()).isEqualTo(GameLogicGameplayRuleIntakeTerminal.Outcome.ABORTED);
      assertThat(observed.canonicalBytes()).containsExactly(aborted.canonicalBytes());
    } finally {
      allowSourceRead.countDown();
    }

    assertThat(fixture.dsl.fetchCount(DSL.table("game_logic_gameplay_rule_intake_terminal")))
        .isEqualTo(1);
    assertThat(fixture.repository.findTerminal(authorization.operationId()).orElseThrow().outcome())
        .isEqualTo(GameLogicGameplayRuleIntakeTerminal.Outcome.ABORTED);
  }

  @Test
  void publicationReadUsesCommittedTerminalAfterUpstreamReadersAreUnavailableAndNeverAttestsAbort()
      throws Exception {
    var fixture = fixture();
    var authorization = authorization();
    // These upstream collaborators are fixture stubs; this definition proves GL's committed local
    // readback, not genuine cross-owner Account or Game Design producer issuance.
    var accountReader = mock(GameLogicIntakeAuthorizationReadClient.class);
    var gameDesignReader = mock(GameplayRuleSourceReadClient.class);
    when(accountReader.read(any(GameLogicIntakeAuthorizationReadEvidence.Request.class)))
        .thenAnswer(
            invocation -> new GameLogicIntakeAuthorizationReadEvidence(invocation.getArgument(0)));
    when(gameDesignReader.read(any(GameplayRuleSourceReadEvidence.Request.class)))
        .thenAnswer(
            invocation ->
                new GameplayRuleSourceReadEvidence(
                    invocation.getArgument(0), authorization.source()));

    var retained =
        asGameDesign(() -> fixture.service(accountReader, gameDesignReader).retain(authorization));
    var unavailableAccount = mock(GameLogicIntakeAuthorizationReadClient.class);
    var unavailableDesign = mock(GameplayRuleSourceReadClient.class);
    when(unavailableAccount.read(any(GameLogicIntakeAuthorizationReadEvidence.Request.class)))
        .thenThrow(new AssertionError("Exact retained retry must not reread Account"));
    when(unavailableDesign.read(any(GameplayRuleSourceReadEvidence.Request.class)))
        .thenThrow(new AssertionError("Exact retained retry must not reread Game Design"));

    var exactRetry =
        asGameDesign(
            () -> fixture.service(unavailableAccount, unavailableDesign).retain(authorization));
    assertThat(exactRetry.canonicalBytes()).containsExactly(retained.canonicalBytes());
    verify(unavailableAccount, never())
        .read(any(GameLogicIntakeAuthorizationReadEvidence.Request.class));
    verify(unavailableDesign, never()).read(any(GameplayRuleSourceReadEvidence.Request.class));

    var publicationReader =
        new GameLogicPublicationSourceReadService(fixture.repository, NAMESPACE);
    var publicationBinding = publicationBinding(authorization);
    var publicationResult = asGameDesign(() -> publicationReader.read(publicationBinding));
    var publicationRetry = asGameDesign(() -> publicationReader.read(publicationBinding));
    assertThat(publicationResult.binding().canonicalBytes())
        .containsExactly(publicationBinding.canonicalBytes());
    assertThat(publicationResult.terminalBytes()).containsExactly(retained.canonicalBytes());
    assertThat(publicationRetry.terminalBytes()).containsExactly(publicationResult.terminalBytes());
    assertThat(publicationResult.manifestDigest())
        .isEqualTo(authorization.source().manifest().digest());
    assertThat(publicationResult.abilitySchemaDigest())
        .isEqualTo(GameplayAbilitySchemaProjection.digest(authorization.source().manifest()));
    assertThat(
            fixture
                .repository
                .findTerminal(authorization.operationId())
                .orElseThrow()
                .canonicalBytes())
        .containsExactly(retained.canonicalBytes());

    var changedOperationId =
        copyAuthorization(
            authorization,
            UUID.randomUUID(),
            authorization.fenceId(),
            authorization.intakeRequestId(),
            authorization.source());
    assertReadDenied(
        Status.Code.FAILED_PRECONDITION,
        () -> asGameDesign(() -> publicationReader.read(publicationBinding(changedOperationId))));

    var changedOperation =
        copyAuthorization(
            authorization,
            authorization.operationId(),
            authorization.fenceId(),
            UUID.randomUUID(),
            authorization.source());
    assertReadDenied(
        Status.Code.ALREADY_EXISTS,
        () -> asGameDesign(() -> publicationReader.read(publicationBinding(changedOperation))));

    var changedFence =
        copyAuthorization(
            authorization,
            authorization.operationId(),
            UUID.randomUUID(),
            authorization.intakeRequestId(),
            authorization.source());
    assertReadDenied(
        Status.Code.ALREADY_EXISTS,
        () -> asGameDesign(() -> publicationReader.read(publicationBinding(changedFence))));

    var originalCommit = authorization.source().binding();
    var changedCommit =
        DraftCommitBinding.create(
            originalCommit.target(),
            originalCommit.requestId(),
            UUID.randomUUID(),
            originalCommit.baseCommitId(),
            originalCommit.revisions(),
            originalCommit.affectedUnits());
    var changedCommitAuthorization =
        copyAuthorization(
            authorization,
            authorization.operationId(),
            authorization.fenceId(),
            authorization.intakeRequestId(),
            source(changedCommit));
    assertReadDenied(
        Status.Code.ALREADY_EXISTS,
        () ->
            asGameDesign(
                () -> publicationReader.read(publicationBinding(changedCommitAuthorization))));

    var abortedAuthorization = authorization();
    var aborted =
        asGameDesign(
            () -> fixture.service(accountReader, gameDesignReader).abort(abortedAuthorization));
    assertThat(aborted.outcome()).isEqualTo(GameLogicGameplayRuleIntakeTerminal.Outcome.ABORTED);
    assertReadDenied(
        Status.Code.FAILED_PRECONDITION,
        () -> asGameDesign(() -> publicationReader.read(publicationBinding(abortedAuthorization))));
  }

  private static Fixture fixture() {
    String schema = "gl_intake_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource = new DriverManagerDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    dataSource.setSchema(schema);
    org.flywaydb.core.Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .table("flyway_schema_history")
        .locations("classpath:db/migration")
        .load()
        .migrate();
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var transactionManager = new DataSourceTransactionManager(dataSource);
    return new Fixture(dsl, transactionManager, new GameLogicGameplayRuleIntakeRepository(dsl));
  }

  private static <T> T asGameDesign(java.util.concurrent.Callable<T> action) throws Exception {
    var peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/test/sa/game-design-service", NAMESPACE, "game-design-service");
    Context previous = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).attach();
    try {
      return action.call();
    } finally {
      Context.current().detach(previous);
    }
  }

  private static GameLogicIntakeAuthorizationBinding authorization() {
    var actor = UUID.randomUUID();
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.randomUUID(), UUID.randomUUID(), 1, "private", 2, "private", "NEW_GAME_ROW");
    var binding =
        DraftCommitBinding.create(
            target,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "genesis",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "{}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "GAMEPLAY_RULE_SET",
                    target.canonicalVersionId().toString(),
                    "GAMEPLAY_RULE_SET",
                    "effective",
                    "0")));
    var source =
        new GameplayRuleSelectedSource(
            GameplayRuleManifest.canonical(
                Map.of(
                    "schema", "game-design-gameplay-rule-source-snapshot/v1",
                    "bindingJson", binding.canonicalJson(),
                    "bindingDigest", binding.digest(),
                    "sourceEpoch", "1",
                    "inheritedCommitId", "",
                    "genesisReceiptId", UUID.randomUUID().toString(),
                    "manifestJson", GameplayRuleManifest.explicitEmpty().canonicalJson(),
                    "entries", List.of())));
    var accountSource =
        new DraftAuthorizationFenceBinding.SourceEvidence(
            DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
            actor.toString(),
            "1",
            "1",
            null,
            null,
            new byte[] {4, 5, 6});
    return new GameLogicIntakeAuthorizationBinding(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        actor,
        source,
        List.of(accountSource));
  }

  private static GameLogicIntakeAuthorizationBinding copyAuthorization(
      GameLogicIntakeAuthorizationBinding original,
      UUID operationId,
      UUID fenceId,
      UUID intakeRequestId,
      GameplayRuleSelectedSource source) {
    return new GameLogicIntakeAuthorizationBinding(
        operationId,
        fenceId,
        intakeRequestId,
        original.actorAccountId(),
        source,
        original.sources());
  }

  private static GameplayRuleSelectedSource source(DraftCommitBinding binding) {
    return new GameplayRuleSelectedSource(
        GameplayRuleManifest.canonical(
            Map.of(
                "schema", "game-design-gameplay-rule-source-snapshot/v1",
                "bindingJson", binding.canonicalJson(),
                "bindingDigest", binding.digest(),
                "sourceEpoch", "1",
                "inheritedCommitId", "",
                "genesisReceiptId", UUID.randomUUID().toString(),
                "manifestJson", GameplayRuleManifest.explicitEmpty().canonicalJson(),
                "entries", List.of())));
  }

  private static GameLogicPublicationSourceReadBinding publicationBinding(
      GameLogicIntakeAuthorizationBinding authorization) {
    var target = authorization.source().binding().target();
    return new GameLogicPublicationSourceReadBinding(
        PublicationDigestRequestBinding.full(
            authorization.tenantId().toString(),
            Long.toString(target.gameDesignVersionRowId()),
            "publication-read"),
        authorization);
  }

  private static void assertReadDenied(
      Status.Code expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
  }

  private record MigrationHistory(
      Integer installedRank, String version, String script, Integer checksum, Boolean success) {}

  private record SagaRowSnapshot(String rowBytes, String xmin) {}

  private record Fixture(
      DSLContext dsl,
      DataSourceTransactionManager transactionManager,
      GameLogicGameplayRuleIntakeRepository repository) {
    private GameLogicGameplayRuleIntakeService service(
        GameLogicIntakeAuthorizationReadClient accountReader,
        GameplayRuleSourceReadClient gameDesignReader) {
      return new GameLogicGameplayRuleIntakeService(
          repository, transactionManager, accountReader, gameDesignReader, NAMESPACE);
    }
  }
}
