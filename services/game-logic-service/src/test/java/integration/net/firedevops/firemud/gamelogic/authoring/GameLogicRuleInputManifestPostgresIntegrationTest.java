package net.firedevops.firemud.gamelogic.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.gamelogic.service.GameLogicDraftDesignDigestService;
import net.firedevops.firemud.gamelogic.service.impl.GameLogicDraftDesignDigestServiceImpl;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL owner-storage proof. The full binding's other owner entries and the held authorization
 * are explicitly synthetic storage-test fixtures; they do not authenticate a producer or enable
 * publication.
 */
@Testcontainers(disabledWithoutDocker = true)
class GameLogicRuleInputManifestPostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static DSLContext dsl;
  private static PlatformTransactionManager transactionManager;
  private static GameLogicRuleInputManifestRepository repository;

  @BeforeAll
  static void initializeMigratedOwnerStorage() {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setURL(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .placeholders(Map.of("serviceSchema", "public"))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    transactionManager = new DataSourceTransactionManager(dataSource);
    dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    repository = new GameLogicRuleInputManifestRepository(dsl, transactionManager);
  }

  @Test
  void persistsExplicitEmptyManifestAndReadsBackExactBindingAndOwnerResult() {
    DraftCommitBinding binding =
        binding(randomTarget(), UUID.randomUUID(), UUID.randomUUID(), "root", "0");
    GameLogicRuleInputManifest expected =
        GameLogicRuleInputManifest.fromExplicitEmptyIntent(binding);

    GameLogicRuleInputManifest actual = serviceWithSyntheticStorageAuthority(-1).apply(binding);

    assertThat(actual).isEqualTo(expected);
    assertThat(actual.contentDigest())
        .isEqualTo("71b4da6a96f68a6f85d48731f5b4e448d05d21241ca75016ef0c9a0000c14f74");
    assertThat(actual.abilitySchemaDigest())
        .isEqualTo("a6c1b6d52654bce002ddb64de2d84853061aa87cb15407547038cc3d4e7cfbd4");
    assertThat(repository.read(expected.tenantId(), expected.versionId())).contains(expected);
    assertThat(countFor(expected.tenantId(), expected.versionId())).isEqualTo(1L);
    Record epochRecord =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT expected_epoch, aggregate_epoch_after, scope_epoch_after "
                    + "FROM game_logic_version_rule_input_manifest "
                    + "WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                expected.tenantId(),
                expected.versionId()),
            "inserted manifest epoch row must be readable");
    assertThat(epochRecord.get("aggregate_epoch_after", Long.class)).isEqualTo(1L);
  }

  @Test
  void exactRetryReturnsTheOriginalImmutableOwnerRow() {
    DraftCommitBinding binding =
        binding(randomTarget(), UUID.randomUUID(), UUID.randomUUID(), "root", "0");
    GameLogicRuleInputManifest first = serviceWithSyntheticStorageAuthority(-1).apply(binding);
    GameLogicRuleInputManifest replay = serviceWithSyntheticStorageAuthority(-1).apply(binding);

    assertThat(replay).isEqualTo(first);
    assertThat(countFor(first.tenantId(), first.versionId())).isEqualTo(1L);
  }

  @Test
  void changedBindingSourceOrRequestCannotReplaceTheOriginalVersionRow() {
    TargetProof originalTarget = randomTarget();
    UUID requestId = UUID.randomUUID();
    DraftCommitBinding original =
        binding(originalTarget, requestId, UUID.randomUUID(), "root", "0");
    GameLogicRuleInputManifest first = serviceWithSyntheticStorageAuthority(-1).apply(original);

    TargetProof substitutedSource =
        new TargetProof(
            originalTarget.canonicalTenantId(),
            originalTarget.canonicalVersionId(),
            originalTarget.gameDesignVersionRowId() + 1,
            originalTarget.gameDesignVersionTenantKey(),
            originalTarget.sourceGameRowId(),
            originalTarget.sourceGameTenantKey(),
            originalTarget.sourceProvenanceKind());
    DraftCommitBinding changed =
        binding(substitutedSource, requestId, UUID.randomUUID(), "different-base", "0");

    assertThatThrownBy(() -> serviceWithSyntheticStorageAuthority(-1).apply(changed))
        .isInstanceOf(GameLogicRuleInputManifestRepository.ManifestConflictException.class);
    assertThat(repository.read(first.tenantId(), first.versionId())).contains(first);
    assertThat(countFor(first.tenantId(), first.versionId())).isEqualTo(1L);
  }

  @Test
  void absentOwnerEvidenceRemainsAbsentAndProtectedDigestHandlerStaysDenied() {
    UUID tenantId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    assertThat(repository.read(tenantId, versionId)).isEmpty();
    assertThatThrownBy(
            () ->
                new GameLogicDraftDesignDigestServiceImpl()
                    .getDraftDesignDigest(tenantId.toString(), versionId.toString()))
        .isInstanceOf(GameLogicDraftDesignDigestService.UnsupportedDigestScopeException.class);
    assertThat(countFor(tenantId, versionId)).isZero();
  }

  @Test
  void defaultApplyServiceDeniesWithoutAuthenticatedCurrentSourceAndAccountVerifier() {
    DraftCommitBinding binding =
        binding(randomTarget(), UUID.randomUUID(), UUID.randomUUID(), "root", "0");
    GameLogicEmptyRuleInputManifestService service =
        new GameLogicEmptyRuleInputManifestService(repository);

    assertThatThrownBy(() -> service.apply(binding))
        .isInstanceOf(GameLogicEmptyRuleInputManifestService.ApplicationDeniedException.class);
    assertThat(
            repository.read(
                binding.target().canonicalTenantId(), binding.target().canonicalVersionId()))
        .isEmpty();
  }

  @Test
  void rollbackAfterInsertLeavesNoManifestOrEpochAdvance() {
    DraftCommitBinding binding =
        binding(randomTarget(), UUID.randomUUID(), UUID.randomUUID(), "root", "0");
    UUID tenantId = binding.target().canonicalTenantId();
    UUID versionId = binding.target().canonicalVersionId();

    assertThatThrownBy(() -> serviceWithSyntheticStorageAuthority(3).apply(binding))
        .isInstanceOf(SyntheticAuthorityLostException.class);

    assertThat(repository.read(tenantId, versionId)).isEmpty();
    assertThat(countFor(tenantId, versionId)).isZero();
  }

  @Test
  void concurrentFirstClaimsAtEpochZeroRetainOneOriginalResult() throws Exception {
    DraftCommitBinding binding =
        binding(randomTarget(), UUID.randomUUID(), UUID.randomUUID(), "root", "0");
    GameLogicRuleInputManifest expected =
        GameLogicRuleInputManifest.fromExplicitEmptyIntent(binding);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<GameLogicRuleInputManifest> first =
          executor.submit(
              () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Concurrent claim start timed out");
                }
                return serviceWithSyntheticStorageAuthority(-1).apply(binding);
              });
      Future<GameLogicRuleInputManifest> second =
          executor.submit(
              () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Concurrent claim start timed out");
                }
                return serviceWithSyntheticStorageAuthority(-1).apply(binding);
              });
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(expected);
      assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(expected);
    } finally {
      executor.shutdownNow();
    }
    assertThat(countFor(expected.tenantId(), expected.versionId())).isEqualTo(1L);
  }

  @Test
  void nonEmptyIntentExtraGameLogicUnitsAndNonzeroEpochAreRejected() {
    TargetProof target = randomTarget();
    UUID requestId = UUID.randomUUID();
    UUID commitId = UUID.randomUUID();

    DraftCommitBinding nonEmpty =
        binding(
            target,
            requestId,
            commitId,
            "root",
            "0",
            GameLogicRuleInputManifest.emptyIntentJson()
                .replace("\"ruleInputs\":[]", "\"ruleInputs\":[{}]"),
            1,
            1);
    assertThatThrownBy(() -> serviceWithSyntheticStorageAuthority(-1).apply(nonEmpty))
        .isInstanceOf(GameLogicRuleInputManifest.InvalidRuleManifestIntentException.class);

    DraftCommitBinding extraOwnerUnit =
        binding(target, UUID.randomUUID(), UUID.randomUUID(), "root", "0", 1, 2);
    assertThatThrownBy(() -> serviceWithSyntheticStorageAuthority(-1).apply(extraOwnerUnit))
        .isInstanceOf(GameLogicRuleInputManifest.InvalidRuleManifestIntentException.class);

    DraftCommitBinding extraOwnerRevision =
        binding(target, UUID.randomUUID(), UUID.randomUUID(), "root", "0", 2, 1);
    assertThatThrownBy(() -> serviceWithSyntheticStorageAuthority(-1).apply(extraOwnerRevision))
        .isInstanceOf(GameLogicRuleInputManifest.InvalidRuleManifestIntentException.class);

    DraftCommitBinding staleEpoch =
        binding(target, UUID.randomUUID(), UUID.randomUUID(), "root", "1");
    assertThatThrownBy(() -> serviceWithSyntheticStorageAuthority(-1).apply(staleEpoch))
        .isInstanceOf(GameLogicRuleInputManifest.InvalidRuleManifestIntentException.class);
    assertThat(repository.read(target.canonicalTenantId(), target.canonicalVersionId())).isEmpty();
  }

  @Test
  void fullDraftBindingCannotOmitTheGameLogicOwner() {
    TargetProof target = randomTarget();
    List<RevisionPayload> revisions = new ArrayList<>();
    List<AffectedUnit> units = new ArrayList<>();
    int order = 0;
    for (Owner owner : Owner.values()) {
      if (owner != Owner.GAME_LOGIC) {
        revisions.add(
            new RevisionPayload(
                Integer.toString(order++),
                UUID.randomUUID(),
                owner,
                "{\"syntheticStorageTestParticipant\":\"" + owner.name() + "\"}"));
        units.add(
            new AffectedUnit(
                owner,
                "SYNTHETIC_TEST_OWNER",
                target.canonicalVersionId().toString(),
                "VERSION",
                target.canonicalVersionId().toString(),
                "0"));
      }
    }

    DraftCommitBinding omittedGameLogic =
        DraftCommitBinding.create(
            target, UUID.randomUUID(), UUID.randomUUID(), "root", revisions, units);
    assertThatThrownBy(() -> serviceWithSyntheticStorageAuthority(-1).apply(omittedGameLogic))
        .isInstanceOf(GameLogicRuleInputManifest.InvalidRuleManifestIntentException.class);

    List<RevisionPayload> gameLogicRevisionWithoutUnit = new ArrayList<>(revisions);
    gameLogicRevisionWithoutUnit.add(
        new RevisionPayload(
            Integer.toString(order),
            UUID.randomUUID(),
            Owner.GAME_LOGIC,
            GameLogicRuleInputManifest.emptyIntentJson()));
    assertThatThrownBy(
            () ->
                DraftCommitBinding.create(
                    target,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "root",
                    gameLogicRevisionWithoutUnit,
                    units))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Required owner set must exactly match");

    List<AffectedUnit> gameLogicUnitWithoutRevision = new ArrayList<>(units);
    gameLogicUnitWithoutRevision.add(
        new AffectedUnit(
            Owner.GAME_LOGIC,
            "VERSION_RULE_MANIFEST",
            target.canonicalVersionId().toString(),
            "VERSION",
            target.canonicalVersionId().toString(),
            "0"));
    assertThatThrownBy(
            () ->
                DraftCommitBinding.create(
                    target,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "root",
                    revisions,
                    gameLogicUnitWithoutRevision))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Required owner set must exactly match");
  }

  @Test
  void databaseRejectsMutationOfAnAppliedManifest() {
    DraftCommitBinding binding =
        binding(randomTarget(), UUID.randomUUID(), UUID.randomUUID(), "root", "0");
    GameLogicRuleInputManifest applied = serviceWithSyntheticStorageAuthority(-1).apply(binding);

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE game_logic_version_rule_input_manifest SET owner_result_json = '{}' "
                        + "WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                    applied.tenantId(),
                    applied.versionId()))
        .hasMessageContaining("Game Logic Version rule manifests are immutable");
  }

  private static GameLogicEmptyRuleInputManifestService serviceWithSyntheticStorageAuthority(
      int failOnRequireHeldCall) {
    return new GameLogicEmptyRuleInputManifestService(
        repository, ignoredBinding -> new SyntheticTestAuthority(failOnRequireHeldCall));
  }

  private static DraftCommitBinding binding(
      TargetProof target,
      UUID requestId,
      UUID commitId,
      String baseCommitId,
      String expectedEpoch) {
    return binding(
        target,
        requestId,
        commitId,
        baseCommitId,
        expectedEpoch,
        GameLogicRuleInputManifest.emptyIntentJson(),
        1,
        1);
  }

  private static DraftCommitBinding binding(
      TargetProof target,
      UUID requestId,
      UUID commitId,
      String baseCommitId,
      String expectedEpoch,
      int gameLogicRevisionCount,
      int gameLogicUnitCount) {
    return binding(
        target,
        requestId,
        commitId,
        baseCommitId,
        expectedEpoch,
        GameLogicRuleInputManifest.emptyIntentJson(),
        gameLogicRevisionCount,
        gameLogicUnitCount);
  }

  private static DraftCommitBinding binding(
      TargetProof target,
      UUID requestId,
      UUID commitId,
      String baseCommitId,
      String expectedEpoch,
      String gameLogicIntent,
      int gameLogicRevisionCount,
      int gameLogicUnitCount) {
    List<RevisionPayload> revisions = new ArrayList<>();
    List<AffectedUnit> units = new ArrayList<>();
    int order = 0;
    for (Owner owner : Owner.values()) {
      int count = owner == Owner.GAME_LOGIC ? gameLogicRevisionCount : 1;
      for (int index = 0; index < count; index++) {
        String payload =
            owner == Owner.GAME_LOGIC
                ? gameLogicIntent
                : "{\"syntheticStorageTestParticipant\":\"" + owner.name() + "\"}";
        revisions.add(
            new RevisionPayload(Integer.toString(order++), UUID.randomUUID(), owner, payload));
      }

      count = owner == Owner.GAME_LOGIC ? gameLogicUnitCount : 1;
      for (int index = 0; index < count; index++) {
        String scopeId =
            owner == Owner.GAME_LOGIC && index > 0
                ? target.canonicalVersionId() + "-extra"
                : target.canonicalVersionId().toString();
        units.add(
            new AffectedUnit(
                owner,
                owner == Owner.GAME_LOGIC ? "VERSION_RULE_MANIFEST" : "SYNTHETIC_TEST_OWNER",
                target.canonicalVersionId().toString(),
                "VERSION",
                scopeId,
                owner == Owner.GAME_LOGIC ? expectedEpoch : "0"));
      }
    }
    return DraftCommitBinding.create(target, requestId, commitId, baseCommitId, revisions, units);
  }

  private static TargetProof randomTarget() {
    String privateTenantKey = UUID.randomUUID().toString();
    return new TargetProof(
        UUID.randomUUID(),
        UUID.randomUUID(),
        101L,
        privateTenantKey,
        202L,
        privateTenantKey,
        "NEW_GAME_ROW");
  }

  private static long countFor(UUID tenantId, UUID versionId) {
    Record countRecord =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT count(*) AS row_count FROM game_logic_version_rule_input_manifest "
                    + "WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                tenantId,
                versionId),
            "count query must return one row");
    return Objects.requireNonNull(
        countRecord.get("row_count", Long.class), "count query must return a value");
  }

  private static final class SyntheticTestAuthority
      implements GameLogicEmptyRuleInputManifestService.HeldCommitAuthority {
    private final int failOnRequireHeldCall;
    private final AtomicInteger requireHeldCalls = new AtomicInteger();

    private SyntheticTestAuthority(int failOnRequireHeldCall) {
      this.failOnRequireHeldCall = failOnRequireHeldCall;
    }

    @Override
    public void requireHeld() {
      if (requireHeldCalls.incrementAndGet() == failOnRequireHeldCall) {
        throw new SyntheticAuthorityLostException();
      }
    }

    @Override
    public void close() {}
  }

  private static final class SyntheticAuthorityLostException extends IllegalStateException {
    private SyntheticAuthorityLostException() {
      super("Synthetic storage-test authority was withdrawn");
    }
  }
}
