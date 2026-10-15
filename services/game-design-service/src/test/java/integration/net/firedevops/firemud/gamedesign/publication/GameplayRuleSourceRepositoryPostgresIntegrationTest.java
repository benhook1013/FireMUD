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
import net.firedevops.firemud.common.gamedesign.GameplayRuleSource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Family;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import net.firedevops.firemud.gamedesign.draft.GameDesignReviewedBaseRepository;
import net.firedevops.firemud.gamedesign.draft.IsolatedPublicationOwnerSetup;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.GameplayRuleSnapshot;
import net.firedevops.firemud.gamedesign.publication.GameplayRuleSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
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
        .locations("classpath:db/migration")
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

    DraftCommitBinding binding(String epoch, String base, String payload) {
      return DraftCommitBinding.create(
          target,
          UUID.randomUUID(),
          UUID.randomUUID(),
          base,
          List.of(
              new DraftCommitBinding.RevisionPayload(
                  "0", UUID.randomUUID(), Owner.GAME_DESIGN_CONTROL_PLANE, payload)),
          List.of(
              new DraftCommitBinding.AffectedUnit(
                  Owner.GAME_DESIGN_CONTROL_PLANE,
                  GameplayRuleSource.SCOPE,
                  target.canonicalVersionId().toString(),
                  GameplayRuleSource.SCOPE,
                  GameplayRuleSource.SCOPE_ID,
                  epoch)));
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
