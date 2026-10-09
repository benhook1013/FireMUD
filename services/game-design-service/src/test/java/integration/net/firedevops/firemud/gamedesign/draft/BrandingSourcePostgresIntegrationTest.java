package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.GameAsset;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.AssetSource;
import net.firedevops.firemud.gamedesign.publication.BrandingSource;
import net.firedevops.firemud.gamedesign.publication.BrandingSourceRepository;
import net.firedevops.firemud.gamedesign.publication.CommandSource;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.GameplayRuleSource;
import net.firedevops.firemud.gamedesign.repository.GameAssetRepository;
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

/** Physical storage proof only; Account creator admission is not simulated by this fixture. */
@Testcontainers(disabledWithoutDocker = true)
class BrandingSourcePostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void actualFreshGenesisMixedSourceApplicationAndRestartReadbackPreserveOriginalHistory()
      throws Exception {
    var f = fixture(null);
    var logo = asset(f, "logo.png");
    var ordinary = asset(f, "ordinary.png");
    // Borrow only typed policy input; the fixture is not Account publication authorization.
    var policy =
        net.firedevops.firemud.gamedesign.publication.IsolatedPublicationOperationFixtures.fresh(
                f.target)
            .account()
            .input()
            .selection()
            .selectedCommit()
            .revisions()
            .stream()
            .map(DraftCommitBinding.RevisionPayload::payload)
            .filter(p -> p.contains("REALM_ENTRY_POLICY"))
            .findFirst()
            .orElseThrow();
    var first =
        binding(
            f,
            "0",
            BrandingSource.SCOPE,
            BrandingSource.upsertPayload(
                logo.getId().toString(),
                "logo.png",
                BrandingSource.Role.LOGO,
                BrandingSource.Requiredness.REQUIRED),
            AssetSource.upsertPayload(
                ordinary.getId().toString(), "ordinary.png", AssetSource.Requiredness.REQUIRED),
            GameplayRuleSource.upsertPayload(
                new GameplayRuleManifest.AdmissionTag("branding-proof")),
            CommandSource.deletePayload("absent"),
            policy);
    apply(f, first);
    var synchronizedSources =
        new GameDesignSourceRepository(f.dsl)
            .readSynchronized(f.target, first.commitId())
            .orElseThrow();
    var selected = synchronizedSources.branding().orElseThrow();
    assertThat(selected.items()).hasSize(1);
    assertThat(selected.items().getFirst().reference().sourceBinding()).isEqualTo(first);
    assertThat(selected.roleDeclarations())
        .containsEntry("LOGO", "PRESENT")
        .containsEntry("THEME", "EMPTY");
    assertThat(
            synchronizedSources
                .gameplay()
                .manifest()
                .families()
                .get(GameplayRuleManifest.Family.ADMISSION_TAGS))
        .containsExactly(new GameplayRuleManifest.AdmissionTag("branding-proof"));
    assertThat(new BrandingSourceRepository(f.dsl).readSnapshot(f.target, UUID.randomUUID()))
        .isEmpty();
    assertThatThrownBy(
            () ->
                f.dsl.execute(
                    "UPDATE game_assets SET data = ? WHERE id = ?", new byte[] {9}, logo.getId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThatThrownBy(() -> f.dsl.execute("DELETE FROM game_design_branding_source_revision"))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);

    var sibling = binding(f, "1", CommandSource.SCOPE, CommandSource.deletePayload("absent"));
    apply(f, sibling);
    var inherited =
        new GameDesignSourceRepository(f.dsl)
            .readSynchronized(f.target, sibling.commitId())
            .orElseThrow()
            .branding()
            .orElseThrow();
    assertThat(inherited.items()).isEqualTo(selected.items());
    assertThat(inherited.sourceEpoch()).isEqualTo("1");
    assertThat(inherited.binding()).isEqualTo(sibling);
    var deleted =
        binding(
            f,
            "1",
            BrandingSource.SCOPE,
            BrandingSource.deletePayload("logo.png", BrandingSource.Role.LOGO));
    apply(f, deleted);
    assertThat(
            new BrandingSourceRepository(f.dsl)
                .readSnapshot(f.target, deleted.commitId())
                .orElseThrow()
                .roleDeclarations()
                .values())
        .containsOnly("EMPTY");
    assertThat(
            new BrandingSourceRepository(f.dsl)
                .readSnapshot(f.target, first.commitId())
                .orElseThrow())
        .isEqualTo(selected);
  }

  @Test
  void retainedVersionCannotAcquireMissingBrandingGenesis() {
    var f = fixture("58");
    var original = binding(f, "0", CommandSource.SCOPE, CommandSource.deletePayload("historical"));
    f.tx.executeWithoutResult(
        ignored -> {
          var coordinator = new DraftCommitCoordinatorRepository(f.dsl);
          coordinator.claim(original);
          coordinator.claimApplicationSlot(original);
          coordinator.markOwnerInProgress(
              original, DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE);
          var commands =
              new net.firedevops.firemud.gamedesign.publication.CommandSourceRepository(f.dsl);
          var applied = commands.apply(original).orElseThrow();
          var out = new java.io.ByteArrayOutputStream();
          net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
              out, "game-design-control-plane-command-application/v1");
          net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
              out, original.canonicalBytes());
          net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
              out, applied.canonicalBytes());
          var outcome =
              new DraftCommitCoordinatorRepository.OwnerOutcome(
                  DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                  DraftCommitCoordinatorRepository.OwnerStatus.APPLIED,
                  original.commitId(),
                  original.digest(),
                  applied.resultIdentity(),
                  out.toByteArray(),
                  List.of(applied.commandAppliedEpoch()));
          coordinator.recordOwnerOutcome(original, outcome);
          coordinator.advanceVisibilityFence(
              original,
              new DraftCommitCoordinatorRepository.CoordinatorProof(original, List.of(outcome)));
          commands.captureSynchronized(original);
          new net.firedevops.firemud.gamedesign.publication.RealmPolicySourceRepository(f.dsl)
              .captureSynchronized(original);
          new net.firedevops.firemud.gamedesign.publication.AssetSourceRepository(f.dsl)
              .captureSynchronized(original);
          new net.firedevops.firemud.gamedesign.publication.GameplayRuleSourceRepository(f.dsl)
              .captureSynchronized(original);
          coordinator.releaseApplicationSlot(original);
        });
    var originalBytes =
        f.dsl
            .fetchSingle(
                "SELECT result_bytes FROM game_design_draft_commit_owner_result WHERE canonical_version_id = ?",
                f.target.canonicalVersionId())
            .get(0, byte[].class);
    var originalSources =
        List.of("command_source", "realm_policy", "asset_source", "gameplay_rule");
    var sourceJson =
        originalSources.stream()
            .map(
                name ->
                    f.dsl
                        .fetchSingle(
                            "SELECT snapshot_json FROM game_design_"
                                + name
                                + "_snapshot WHERE canonical_version_id = ?",
                            f.target.canonicalVersionId())
                        .get(0, String.class))
            .toList();
    var fenceJson =
        f.dsl
            .fetchSingle(
                "SELECT to_jsonb(f)::text FROM game_design_draft_commit_visibility_fence f WHERE canonical_version_id = ?",
                f.target.canonicalVersionId())
            .get(0, String.class);
    f.migrate();
    var historical =
        new GameDesignSourceRepository(f.dsl)
            .readSynchronized(f.target, original.commitId())
            .orElseThrow();
    assertThat(historical.branding()).isEmpty();
    assertThat(historical.command().binding()).isEqualTo(original);
    assertThat(
            List.of(
                historical.command().canonicalJson(),
                historical.policy().canonicalJson(),
                historical.asset().canonicalJson(),
                historical.gameplay().canonicalJson()))
        .isEqualTo(sourceJson);
    assertThat(
            f.dsl
                .fetchSingle(
                    "SELECT to_jsonb(f)::text FROM game_design_draft_commit_visibility_fence f WHERE canonical_version_id = ?",
                    f.target.canonicalVersionId())
                .get(0, String.class))
        .isEqualTo(fenceJson);
    assertThat(
            f.dsl
                .fetchSingle(
                    "SELECT result_bytes FROM game_design_draft_commit_owner_result WHERE canonical_version_id = ?",
                    f.target.canonicalVersionId())
                .get(0, byte[].class))
        .isEqualTo(originalBytes);
    assertThatThrownBy(
            () ->
                f.tx.execute(
                    ignored -> new GameDesignSourceRepository(f.dsl).captureSynchronized(original)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAME_DESIGN_SOURCE_GENESIS_INCOMPLETE");
    assertThat(new BrandingSourceRepository(f.dsl).readGenesis(f.target)).isEmpty();
    assertThatThrownBy(
            () ->
                f.tx.execute(
                    ignored -> new BrandingSourceRepository(f.dsl).enrollFreshDraft(f.target)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("BRANDING_SOURCE_FRESH_INSERT_UNAVAILABLE");
    assertThatThrownBy(() -> new GameDesignSourceRepository(f.dsl).readGenesis(f.target))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAME_DESIGN_SOURCE_GENESIS_INCOMPLETE");
  }

  @Test
  void wrongTenantSourceRowCannotWriteOrExposeSnapshot() {
    var f = fixture(null);
    var foreignGame = new Game();
    foreignGame.setTenantId(UUID.randomUUID().toString());
    foreignGame.setName("Foreign asset owner");
    var foreign =
        Objects.requireNonNull(
            f.tx.execute(ignored -> new GameRepository(f.dsl).save(foreignGame)));
    var source = new GameAsset();
    source.setTenantId(foreign.getTenantId());
    source.setFileName("foreign.png");
    source.setContentType("image/png");
    source.setData(new byte[] {1});
    var foreignAsset =
        Objects.requireNonNull(
            f.tx.execute(ignored -> new GameAssetRepository(f.dsl).save(source)));
    var payload =
        BrandingSource.upsertPayload(
            foreignAsset.getId().toString(),
            "foreign.png",
            BrandingSource.Role.FAVICON,
            BrandingSource.Requiredness.REQUIRED);
    var binding = binding(f, "0", BrandingSource.SCOPE, payload);
    assertThatThrownBy(() -> apply(f, binding))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("BRANDING_SOURCE_ROW_UNAVAILABLE");
    assertThat(new BrandingSourceRepository(f.dsl).readSnapshot(f.target, binding.commitId()))
        .isEmpty();
  }

  private void apply(Fixture f, DraftCommitBinding binding) {
    f.tx.executeWithoutResult(
        ignored -> {
          var coordinator = new DraftCommitCoordinatorRepository(f.dsl);
          coordinator.claim(binding);
          coordinator.claimApplicationSlot(binding);
          coordinator.markOwnerInProgress(
              binding, DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE);
          var sources = new GameDesignSourceRepository(f.dsl);
          var applied = sources.apply(binding);
          coordinator.advanceSourceVisibilityFence(
              binding,
              new DraftCommitCoordinatorRepository.CoordinatorProof(
                  binding, List.of(applied.ownerOutcome())));
          sources.captureSynchronized(binding);
          coordinator.releaseApplicationSlot(binding);
        });
  }

  private DraftCommitBinding binding(
      Fixture f, String epoch, String primaryScope, String... payloads) {
    var revisions = new ArrayList<DraftCommitBinding.RevisionPayload>();
    var scopes = new java.util.TreeSet<String>();
    scopes.add(primaryScope);
    for (int i = 0; i < payloads.length; i++) {
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              Integer.toString(i),
              UUID.randomUUID(),
              DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
              payloads[i]));
      if (payloads[i].contains("\"revisionKind\":\"ASSET_REFERENCE\""))
        scopes.add(AssetSource.SCOPE);
      if (payloads[i].contains("\"revisionKind\":\"GAMEPLAY_RULE\""))
        scopes.add(GameplayRuleSource.SCOPE);
      if (payloads[i].contains("\"revisionKind\":\"COMMAND_DEFINITION\""))
        scopes.add(CommandSource.SCOPE);
      if (payloads[i].contains("\"revisionKind\":\"REALM_ENTRY_POLICY\""))
        scopes.add(net.firedevops.firemud.gamedesign.publication.RealmPolicySource.SCOPE);
    }
    var units =
        scopes.stream()
            .map(
                scope ->
                    new DraftCommitBinding.AffectedUnit(
                        DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                        scope,
                        f.target.canonicalVersionId().toString(),
                        scope,
                        "effective",
                        scope.equals(primaryScope) ? epoch : "0"))
            .toList();
    return DraftCommitBinding.create(
        f.target, UUID.randomUUID(), UUID.randomUUID(), "base-commit-0", revisions, units);
  }

  private GameAsset asset(Fixture f, String name) {
    var asset = new GameAsset();
    asset.setTenantId(f.target.gameDesignVersionTenantKey());
    asset.setFileName(name);
    asset.setContentType("image/png");
    asset.setData(name.getBytes(StandardCharsets.UTF_8));
    return Objects.requireNonNull(
        f.tx.execute(ignored -> new GameAssetRepository(f.dsl).save(asset)));
  }

  private Fixture fixture(String maximumMigration) {
    var schema = "branding_source_" + UUID.randomUUID().toString().replace("-", "");
    var data =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    data.setSchema(schema);
    var config =
        Flyway.configure()
            .dataSource(data)
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history_game_design_service")
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (maximumMigration != null) config.target(maximumMigration);
    config.load().migrate();
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(data));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var game = new Game();
    game.setTenantId(UUID.randomUUID().toString());
    game.setName("Branding source storage fixture");
    var savedGame =
        Objects.requireNonNull(tx.execute(ignored -> new GameRepository(dsl).save(game)));
    var version = new Version();
    version.setTenantId(savedGame.getTenantId());
    version.setVersionNumber(1);
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    var saved =
        Objects.requireNonNull(
            tx.execute(
                ignored -> {
                  if (maximumMigration == null) return new VersionRepository(dsl).save(version);
                  // Retained fixture intentionally uses the pre-branding producer, in its actual
                  // insert transaction.
                  long id =
                      dsl.fetchSingle(
                              "INSERT INTO version (tenant_id, canonical_tenant_id, canonical_version_id, identity_source_game_row_id, identity_source_game_tenant_key, identity_source_provenance_kind, version_number, version_state, version_state_epoch, is_script_only, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'NEW_GAME_ROW', 1, 'DRAFT', 1, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id",
                              savedGame.getTenantId(),
                              savedGame.getCanonicalTenantId(),
                              UUID.randomUUID(),
                              savedGame.getId(),
                              savedGame.getTenantId())
                          .get(0, Long.class);
                  var retained = new VersionRepository(dsl).findById(id).orElseThrow();
                  var oldTarget =
                      new DraftCommitBinding.TargetProof(
                          retained.getCanonicalTenantId(),
                          retained.getCanonicalVersionId(),
                          id,
                          retained.getTenantId(),
                          retained.getIdentitySourceGameRowId(),
                          retained.getIdentitySourceGameTenantKey(),
                          retained.getIdentitySourceProvenanceKind());
                  var policy =
                      new net.firedevops.firemud.gamedesign.publication.RealmPolicySourceRepository(
                              dsl)
                          .recordFreshGenesis(oldTarget);
                  new net.firedevops.firemud.gamedesign.publication.CommandSourceRepository(dsl)
                      .enrollNewDraftGenesis(
                          new CommandSource.NewDraftGenesisReceipt(
                              oldTarget, policy.receiptId(), policy.creationTransactionId()));
                  new net.firedevops.firemud.gamedesign.publication.AssetSourceRepository(dsl)
                      .enrollFreshDraft(oldTarget);
                  new net.firedevops.firemud.gamedesign.publication.GameplayRuleSourceRepository(
                          dsl)
                      .enrollFreshDraft(oldTarget);
                  return retained;
                }));
    var target =
        new DraftCommitBinding.TargetProof(
            saved.getCanonicalTenantId(),
            saved.getCanonicalVersionId(),
            saved.getId(),
            saved.getTenantId(),
            saved.getIdentitySourceGameRowId(),
            saved.getIdentitySourceGameTenantKey(),
            saved.getIdentitySourceProvenanceKind());
    return new Fixture(dsl, tx, target, data, schema);
  }

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate tx,
      DraftCommitBinding.TargetProof target,
      DriverManagerDataSource data,
      String schema) {
    void migrate() {
      Flyway.configure()
          .dataSource(data)
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history_game_design_service")
          .placeholders(Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .load()
          .migrate();
    }
  }
}
