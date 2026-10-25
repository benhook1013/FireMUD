package integration.net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftBaseReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.AssetSource;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.gamedesign.GameplayRuleSource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Family;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import net.firedevops.firemud.gamedesign.draft.GameDesignReviewedBaseRepository;
import net.firedevops.firemud.gamedesign.draft.IsolatedPublicationOwnerSetup;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.GameAsset;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.GameplayRuleSnapshot;
import net.firedevops.firemud.gamedesign.publication.GameplayRuleSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameAssetRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual current-default source storage; creator/Account authority is explicitly isolated. */
@Testcontainers(disabledWithoutDocker = true)
class GameplayRuleSourceRepositoryPostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void actualGameplayApplicationStaysHiddenUntilCompleteSynchronizedFenceAndReplaysExactly() {
    var fixture = fixture();
    var binding = fixture.binding("0", fixture.genesisBase(), upsert("10"));
    fixture.stage(binding);
    var owner = new GameplayRuleSourceRepository(fixture.sql());
    assertThat(owner.readSnapshot(fixture.target(), binding.commitId())).isEmpty();
    var applied = fixture.write().execute(ignored -> fixture.sources().apply(binding));
    assertThat(applied.gameplay()).isPresent();
    fixture.synchronize(binding);
    var retained = owner.readSnapshot(fixture.target(), binding.commitId()).orElseThrow();
    assertThat(retained.entries()).hasSize(1);
    assertThat(retained.entries().getFirst().sourceBinding()).isEqualTo(binding);
    assertThat(retained.manifest().families().get(Family.STATS)).hasSize(1);
    var all =
        fixture.sources().readSynchronized(fixture.target(), binding.commitId()).orElseThrow();
    assertThat(all.gameplayEvidence()).contains(retained);
    assertThat(all.command().binding()).isEqualTo(binding);
    assertThat(all.policy().binding()).isEqualTo(binding);
    assertThat(all.asset().binding()).isEqualTo(binding);
    GameplayRuleSnapshot replayed =
        fixture.write().execute(ignored -> owner.captureSynchronized(binding));
    assertThat(replayed).isEqualTo(retained);
  }

  @Test
  void staleGameplayEpochCannotAlterPriorVisibleSourceOrRetainPartialApplication() {
    var fixture = fixture();
    var first = fixture.binding("0", fixture.genesisBase(), upsert("10"));
    fixture.stage(first);
    fixture.synchronize(first);
    var owner = new GameplayRuleSourceRepository(fixture.sql());
    var original = owner.readSnapshot(fixture.target(), first.commitId()).orElseThrow();
    var stale = fixture.binding("0", first.commitId().toString(), upsert("11"));
    assertThatThrownBy(() -> fixture.stage(stale)).isInstanceOf(RuntimeException.class);
    assertThat(owner.readSnapshot(fixture.target(), first.commitId())).contains(original);
    assertThat(owner.readSnapshot(fixture.target(), stale.commitId())).isEmpty();
    assertThat(fixture.sql().fetchCount(DSL.table("game_design_gameplay_rule_application")))
        .isEqualTo(1);
  }

  @Test
  void completeSnapshotsPreserveInheritedRuleProvenanceAndRequireExplicitDeletion() {
    var fixture = fixture();
    var first = fixture.binding("0", fixture.genesisBase(), upsert("10"));
    fixture.stage(first);
    fixture.synchronize(first);
    var second = fixture.binding("1", first.commitId().toString(), upsert("agility", "5"));
    fixture.stage(second);
    fixture.synchronize(second);
    var owner = new GameplayRuleSourceRepository(fixture.sql());
    var inherited = owner.readSnapshot(fixture.target(), second.commitId()).orElseThrow();
    assertThat(inherited.entries()).hasSize(2);
    assertThat(
            inherited.entries().stream()
                .filter(entry -> entry.definition().key().equals("strength"))
                .findFirst()
                .orElseThrow()
                .sourceBinding())
        .isEqualTo(first);
    var deletion =
        fixture.binding(
            "2",
            second.commitId().toString(),
            GameplayRuleSource.deletePayload(Family.STATS, "strength"));
    fixture.stage(deletion);
    fixture.synchronize(deletion);
    var current = owner.readSnapshot(fixture.target(), deletion.commitId()).orElseThrow();
    assertThat(current.entries()).hasSize(1);
    assertThat(current.entries().getFirst().definition().key()).isEqualTo("agility");
    assertThat(current.entries().getFirst().sourceBinding()).isEqualTo(second);
    assertThat(owner.readSnapshot(fixture.target(), first.commitId()).orElseThrow().entries())
        .hasSize(1);
  }

  @Test
  void rolledBackGameplayWriteLeavesFreshGenesisAndNoStagedOrVisibleHistory() {
    var fixture = fixture();
    var binding = fixture.binding("0", fixture.genesisBase(), upsert("10"));
    fixture
        .write()
        .executeWithoutResult(
            status -> {
              fixture.stageInTransaction(binding);
              status.setRollbackOnly();
            });
    var owner = new GameplayRuleSourceRepository(fixture.sql());
    assertThat(owner.readGenesis(fixture.target())).isPresent();
    assertThat(owner.readSnapshot(fixture.target(), binding.commitId())).isEmpty();
    assertThat(fixture.sql().fetchCount(DSL.table("game_design_gameplay_rule_application")))
        .isZero();
    assertThat(fixture.sql().fetchCount(DSL.table("game_design_gameplay_rule_revision"))).isZero();
  }

  @Test
  void nonemptyReferencedGameplayCatalogSharesAssetOutcomeAndRetainsInheritedSources() {
    var fixture = fixture();
    var asset =
        fixture
            .write()
            .execute(
                ignored -> {
                  var value = new GameAsset();
                  value.setTenantId(fixture.target().gameDesignVersionTenantKey());
                  value.setFileName("catalog-resource.png");
                  value.setContentType("image/png");
                  value.setData(
                      "actual catalog asset bytes"
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                  return new GameAssetRepository(fixture.sql()).save(value);
                });
    var payloads = new java.util.ArrayList<>(gameplayPayloads());
    payloads.add(
        AssetSource.upsertPayload(
            asset.getId().toString(), asset.getFileName(), AssetSource.Requiredness.REQUIRED));
    var binding = fixture.binding("0", fixture.genesisBase(), payloads.toArray(String[]::new));
    fixture.stage(binding);
    fixture.synchronize(binding);
    var owner = new GameplayRuleSourceRepository(fixture.sql());
    var snapshot = owner.requireCompleteForGameLogic(fixture.target(), binding.commitId());
    assertThat(snapshot.manifest().families()).hasSize(Family.values().length);
    assertThat(snapshot.manifest().families().get(Family.ACTIONS)).hasSize(1);
    assertThat(snapshot.manifest().families().get(Family.ABILITIES)).hasSize(1);
    assertThat(snapshot.entries())
        .allSatisfy(entry -> assertThat(entry.sourceBinding()).isEqualTo(binding));
    var outcome = fixture.write().execute(ignored -> fixture.sources().apply(binding));
    assertThat(outcome.asset()).isPresent();
    assertThat(outcome.gameplay().orElseThrow().snapshot().canonicalBytes())
        .containsExactly(snapshot.canonicalBytes());
    var independentlyRead =
        new GameplayRuleSourceRepository(fixture.sql())
            .requireCompleteForGameLogic(fixture.target(), binding.commitId());
    assertThat(independentlyRead.canonicalBytes()).containsExactly(snapshot.canonicalBytes());
    var disjoint =
        fixture.binding(
            "1", binding.commitId().toString(), AssetSource.deletePayload(asset.getFileName()));
    fixture.stage(disjoint);
    fixture.synchronize(disjoint);
    var inherited = owner.requireCompleteForGameLogic(fixture.target(), disjoint.commitId());
    assertThat(inherited.sourceEpoch()).isEqualTo("1");
    assertThat(inherited.inheritedCommitId()).isEqualTo(binding.commitId());
    assertThat(inherited.entries())
        .allSatisfy(entry -> assertThat(entry.sourceBinding()).isEqualTo(binding));
    assertThat(inherited.manifest().canonicalBytes())
        .containsExactly(snapshot.manifest().canonicalBytes());
    var deletion =
        fixture.binding(
            "1",
            disjoint.commitId().toString(),
            GameplayRuleSource.deletePayload(Family.ABILITIES, "focusAbility"),
            GameplayRuleSource.deletePayload(Family.ACTIONS, "focusAction"));
    fixture.stage(deletion);
    fixture.synchronize(deletion);
    var deleted = owner.requireCompleteForGameLogic(fixture.target(), deletion.commitId());
    assertThat(deleted.sourceEpoch()).isEqualTo("2");
    assertThat(deleted.manifest().families().get(Family.ACTIONS)).isEmpty();
    assertThat(deleted.manifest().families().get(Family.ABILITIES)).isEmpty();
    assertThat(
            owner.readSnapshot(fixture.target(), binding.commitId()).orElseThrow().canonicalBytes())
        .containsExactly(snapshot.canonicalBytes());
  }

  @Test
  void gameplayHandoffDeniesUnqualifiedLegacyEffectiveCommands() {
    var fixture = fixture();
    var command =
        "{\"schemaVersion\":1,\"commandId\":\"legacy\",\"semanticOwner\":\"GAME_LOGIC\","
            + "\"executionDiscipline\":\"DURABLE_GAMEPLAY\",\"stageRequirement\":\"GAMEPLAY\","
            + "\"promptPolicy\":\"WHEN_GAMEPLAY\",\"actionCategory\":\"GAMEPLAY\",\"historyRecordable\":true,"
            + "\"aliases\":[],\"actionTags\":[],\"effects\":[]}";
    var binding = fixture.binding("0", fixture.genesisBase(), CommandSource.upsertPayload(command));
    fixture.stage(binding);
    fixture.synchronize(binding);
    assertThat(
            new GameplayRuleSourceRepository(fixture.sql())
                .readSnapshot(binding.target(), binding.commitId()))
        .isPresent();
    assertThatThrownBy(
            () ->
                new GameplayRuleSourceRepository(fixture.sql())
                    .requireCompleteForGameLogic(binding.target(), binding.commitId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAMEPLAY_RULE_LEGACY_COMMAND_SOURCE_NOT_CONVERGED");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void gameplayHandoffDeniesGenericRuleEvenWhenTypedRevisionMatches(boolean legacyBeforeSnapshot) {
    var fixture = fixture();
    var binding =
        fixture.binding("0", fixture.genesisBase(), gameplayPayloads().toArray(String[]::new));
    var revision = binding.revisions().getFirst();
    if (!legacyBeforeSnapshot) {
      fixture.stage(binding);
      fixture.synchronize(binding);
    }
    fixture
        .sql()
        .execute(
            "INSERT INTO revision (tenant_id, version_id, author_account_id, revision_kind, logical_revision_id, data) "
                + "VALUES (?, ?, 1, 'GAMEPLAY_RULE', ?, CAST(? AS JSONB))",
            fixture.target().gameDesignVersionTenantKey(),
            fixture.target().gameDesignVersionRowId(),
            revision.revisionId().toString(),
            revision.payload());
    var retainedRows = fixture.sql().fetch("SELECT * FROM revision ORDER BY id");
    if (legacyBeforeSnapshot) {
      fixture.stage(binding);
      fixture.synchronize(binding);
    }
    var repository = new GameplayRuleSourceRepository(fixture.sql());
    var snapshot = repository.readSnapshot(binding.target(), binding.commitId()).orElseThrow();
    assertThat(snapshot.entries()).isNotEmpty();

    assertThatThrownBy(
            () -> repository.requireCompleteForGameLogic(binding.target(), binding.commitId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAMEPLAY_RULE_GENERIC_SOURCE_NOT_CONVERGED");
    assertThatThrownBy(
            () ->
                new GameplayRuleSourceRepository(fixture.sql())
                    .requireCompleteForGameLogic(binding.target(), binding.commitId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAMEPLAY_RULE_GENERIC_SOURCE_NOT_CONVERGED");
    assertThat(repository.readSnapshot(binding.target(), binding.commitId()).orElseThrow())
        .isEqualTo(snapshot);
    assertThat(fixture.sql().fetch("SELECT * FROM revision ORDER BY id")).isEqualTo(retainedRows);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void gameplayHandoffGenericRuleGuardUsesExactPrivateTenantAndVersion(boolean differentTenant) {
    var fixture = fixture();
    Version otherVersion =
        fixture
            .write()
            .execute(
                ignored -> {
                  var value = new Version();
                  value.setTenantId(fixture.target().gameDesignVersionTenantKey());
                  value.setVersionNumber(2);
                  return new VersionRepository(fixture.sql()).save(value);
                });
    String foreignTenant =
        fixture
            .write()
            .execute(
                ignored -> {
                  var value = new Game();
                  value.setTenantId("foreign-" + UUID.randomUUID().toString().substring(0, 8));
                  value.setName("Controlled foreign legacy history scope");
                  return new GameRepository(fixture.sql()).save(value).getTenantId();
                });
    var binding =
        fixture.binding("0", fixture.genesisBase(), gameplayPayloads().toArray(String[]::new));
    fixture.stage(binding);
    fixture.synchronize(binding);
    var repository = new GameplayRuleSourceRepository(fixture.sql());
    var snapshot = repository.requireCompleteForGameLogic(binding.target(), binding.commitId());
    // Generic history has no composite owner FK; scope both private keys even for retained rows.
    fixture
        .sql()
        .execute(
            "INSERT INTO revision (tenant_id, version_id, author_account_id, revision_kind, data) "
                + "VALUES (?, ?, 1, 'GAMEPLAY_RULE', '{}'::JSONB)",
            differentTenant ? foreignTenant : fixture.target().gameDesignVersionTenantKey(),
            differentTenant
                ? Long.valueOf(fixture.target().gameDesignVersionRowId())
                : otherVersion.getId());
    var retainedRows = fixture.sql().fetch("SELECT * FROM revision ORDER BY id");

    assertThat(repository.requireCompleteForGameLogic(binding.target(), binding.commitId()))
        .isEqualTo(snapshot);
    assertThat(fixture.sql().fetch("SELECT * FROM revision ORDER BY id")).isEqualTo(retainedRows);
  }

  @Test
  void gameplayHandoffCannotClaimEmptyWhileGenericRuleHistoryExists() {
    var fixture = fixture();
    var first = fixture.binding("0", fixture.genesisBase(), upsert("10"));
    fixture.stage(first);
    fixture.synchronize(first);
    var binding =
        fixture.binding(
            "1",
            first.commitId().toString(),
            GameplayRuleSource.deletePayload(Family.STATS, "strength"));
    fixture.stage(binding);
    fixture.synchronize(binding);
    var repository = new GameplayRuleSourceRepository(fixture.sql());
    assertThat(
            repository.requireCompleteForGameLogic(binding.target(), binding.commitId()).entries())
        .isEmpty();
    fixture
        .sql()
        .execute(
            "INSERT INTO revision (tenant_id, version_id, author_account_id, revision_kind, data) "
                + "VALUES (?, ?, 1, 'GAMEPLAY_RULE', '{}'::JSONB)",
            fixture.target().gameDesignVersionTenantKey(),
            fixture.target().gameDesignVersionRowId());
    var retainedRows = fixture.sql().fetch("SELECT * FROM revision ORDER BY id");

    assertThatThrownBy(
            () -> repository.requireCompleteForGameLogic(binding.target(), binding.commitId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAMEPLAY_RULE_GENERIC_SOURCE_NOT_CONVERGED");
    assertThat(fixture.sql().fetch("SELECT * FROM revision ORDER BY id")).isEqualTo(retainedRows);
  }

  private List<String> gameplayPayloads() {
    var action =
        new GameplayRuleManifest.Action(
            "focusAction",
            "Focus",
            List.of("canFocus"),
            List.of(),
            List.of(
                new GameplayRuleManifest.Cost(
                    "energy",
                    java.math.BigDecimal.ONE,
                    GameplayRuleManifest.CommitPolicy.ON_EXECUTION)),
            List.of(
                new GameplayRuleManifest.Cooldown(
                    "focusCooldown", 3L, GameplayRuleManifest.CommitPolicy.ON_EFFECT_SUCCESS)),
            List.of(new GameplayRuleManifest.EffectBinding("restore", "SOURCE")),
            List.of());
    List<GameplayRuleManifest.Definition> definitions =
        List.of(
            new GameplayRuleManifest.AdmissionTag("canFocus"),
            new GameplayRuleManifest.Resource(
                "energy",
                java.math.BigDecimal.TEN,
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.TEN,
                java.math.BigDecimal.valueOf(100),
                GameplayRuleManifest.Visibility.PUBLIC,
                List.of()),
            new GameplayRuleManifest.Effect(
                "restore",
                GameplayRuleManifest.Lifecycle.INSTANT,
                GameplayRuleManifest.EffectOperation.ADJUST_RESOURCE,
                "energy",
                null,
                java.math.BigDecimal.ONE,
                null,
                null),
            action,
            new GameplayRuleManifest.Ability("focusAbility", "Focus ability", "focusAction"));
    return definitions.stream().map(GameplayRuleSource::upsertPayload).toList();
  }

  private static String upsert(String value) {
    return upsert("strength", value);
  }

  private static String upsert(String key, String value) {
    return GameplayRuleSource.upsertPayload(
        new GameplayRuleManifest.Stat(
            key,
            GameplayRuleManifest.PrimitiveKind.NUMERIC,
            new BigDecimal(value),
            null,
            BigDecimal.ZERO,
            new BigDecimal("20"),
            GameplayRuleManifest.Visibility.PUBLIC,
            List.of()));
  }

  private static Fixture fixture() {
    var schema = "gd_gameplay_" + UUID.randomUUID().toString().replace("-", "");
    var data =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    data.setSchema(schema);
    Flyway.configure()
        .dataSource(data)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(java.util.Map.of("serviceSchema", schema))
        .locations(
            "filesystem:"
                + java.nio.file.Path.of("src/main/resources/db/migration")
                    .toAbsolutePath()
                    .normalize())
        .load()
        .migrate();
    var write = new TransactionTemplate(new DataSourceTransactionManager(data));
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var sql = DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES);
    var sources = new GameDesignSourceRepository(sql);
    var game =
        write.execute(
            ignored -> {
              var requested = new Game();
              requested.setTenantId("rule-" + UUID.randomUUID().toString().substring(0, 8));
              requested.setName("Isolated authored gameplay rules");
              return new GameRepository(sql).save(requested);
            });
    Version version =
        write.execute(
            ignored -> {
              var requested = new Version();
              requested.setTenantId(game.getTenantId());
              requested.setVersionNumber(1);
              return new VersionRepository(sql).save(requested);
            });
    var target =
        new TargetProof(
            version.getCanonicalTenantId(),
            version.getCanonicalVersionId(),
            version.getId(),
            version.getTenantId(),
            version.getIdentitySourceGameRowId(),
            version.getIdentitySourceGameTenantKey(),
            version.getIdentitySourceProvenanceKind());
    return new Fixture(sql, write, sources, target);
  }

  private record Fixture(
      DSLContext sql,
      TransactionTemplate write,
      GameDesignSourceRepository sources,
      TargetProof target) {
    String genesisBase() {
      return "genesis:" + sources.readGenesis(target).orElseThrow().policy().receiptId();
    }

    DraftCommitBinding binding(String epoch, String base, String... payloads) {
      var revisions = new java.util.ArrayList<DraftCommitBinding.RevisionPayload>();
      for (int index = 0; index < payloads.length; index++) {
        revisions.add(
            new DraftCommitBinding.RevisionPayload(
                Integer.toString(index),
                UUID.randomUUID(),
                Owner.GAME_DESIGN_CONTROL_PLANE,
                payloads[index]));
      }
      var units = new java.util.ArrayList<DraftCommitBinding.AffectedUnit>();
      for (String scope :
          List.of(GameplayRuleSource.SCOPE, CommandSource.SCOPE, AssetSource.SCOPE)) {
        String kind =
            scope.equals(GameplayRuleSource.SCOPE)
                ? "GAMEPLAY_RULE"
                : scope.equals(CommandSource.SCOPE) ? "COMMAND_DEFINITION" : "ASSET_REFERENCE";
        if (java.util.Arrays.stream(payloads).noneMatch(payload -> payload.contains(kind)))
          continue;
        String sourceEpoch = epoch;
        if (!scope.equals(GameplayRuleSource.SCOPE)) {
          String table =
              scope.equals(CommandSource.SCOPE)
                  ? "game_design_command_source_head"
                  : "game_design_asset_source_head";
          sourceEpoch =
              sql.fetchSingle(
                      "SELECT source_epoch FROM " + table + " WHERE canonical_version_id = ?",
                      target.canonicalVersionId())
                  .get(0, String.class);
        }
        units.add(
            new DraftCommitBinding.AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                scope,
                target.canonicalVersionId().toString(),
                scope,
                "effective",
                sourceEpoch));
      }
      return DraftCommitBinding.create(
          target, UUID.randomUUID(), UUID.randomUUID(), base, revisions, units);
    }

    void stage(DraftCommitBinding binding) {
      write.executeWithoutResult(ignored -> stageInTransaction(binding));
    }

    void stageInTransaction(DraftCommitBinding binding) {
      var reviewed =
          new GameDesignReviewedBaseRepository(sql)
              .resolve(target, DraftBaseReference.parse(binding.baseCommitId()));
      sources.claimReviewed(binding, reviewed);
      var coordinator = new DraftCommitCoordinatorRepository(sql);
      coordinator.claimApplicationSlot(binding);
      coordinator.markOwnerInProgress(binding, Owner.GAME_DESIGN_CONTROL_PLANE);
      sources.apply(binding);
    }

    void synchronize(DraftCommitBinding binding) {
      write.executeWithoutResult(
          ignored -> {
            var coordinator = new DraftCommitCoordinatorRepository(sql);
            var outcome =
                coordinator
                    .read(target, binding.requestId())
                    .orElseThrow()
                    .ownerStates()
                    .get(Owner.GAME_DESIGN_CONTROL_PLANE)
                    .outcome()
                    .orElseThrow();
            IsolatedPublicationOwnerSetup.advanceIsolatedSourceVisibility(
                sql, binding, List.of(outcome));
            coordinator.releaseApplicationSlot(binding);
          });
    }
  }
}
