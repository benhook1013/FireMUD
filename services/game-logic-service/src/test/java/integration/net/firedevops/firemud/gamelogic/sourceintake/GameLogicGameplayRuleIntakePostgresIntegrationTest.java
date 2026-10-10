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
import java.util.List;
import java.util.Map;
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
