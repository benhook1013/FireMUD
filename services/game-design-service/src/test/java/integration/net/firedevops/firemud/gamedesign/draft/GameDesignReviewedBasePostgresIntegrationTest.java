package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.common.authoring.DraftBaseReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.publication.AssetSnapshot;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyGenesis;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySource;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
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

/** PostgreSQL proof for Game Design's local review evidence guards. */
@Testcontainers(disabledWithoutDocker = true)
class GameDesignReviewedBasePostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void changedGenesisWitnessCannotBeClaimedOrReplaceRetainedV2Evidence() {
    Fixture fixture = fixture();
    var genesis = fixture.sources().readGenesis(fixture.target()).orElseThrow();
    var binding = policyBinding(fixture, genesis.policy().receiptId());
    var actual = fixture.resolve(binding);
    String changedWitness =
        new BigInteger(genesis.policy().creationTransactionId()).add(BigInteger.ONE).toString();
    var changed =
        GameDesignReviewedBaseEvidence.genesis(
            new GameDesignSourceRepository.Genesis(
                new RealmPolicyGenesis(
                    fixture.target(), genesis.policy().receiptId(), changedWitness),
                new CommandSource.NewDraftGenesisReceipt(
                    fixture.target(), genesis.command().receiptId(), changedWitness),
                Optional.of(
                    new AssetSnapshot.Genesis(
                        fixture.target(), genesis.asset().receiptId(), changedWitness))));
    assertThat(changed.reference()).isEqualTo(actual.reference());
    assertThat(changed.digest()).isNotEqualTo(actual.digest());

    assertThatThrownBy(() -> fixture.tx(() -> fixture.sources().claimReviewed(binding, changed)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("REVIEWED_BASE_OWNER_EVIDENCE_CONFLICT");
    assertThatThrownBy(
            () ->
                fixture.tx(
                    () -> {
                      fixture.coordinator().claim(binding);
                      return insertReview(fixture.dsl(), binding, changed);
                    }))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("Reviewed base bytes or digest differ from exact owner provenance");
    assertThat(fixture.coordinator().read(fixture.target(), binding.requestId())).isEmpty();
    assertThat(fixture.reviewed().read(binding)).isEmpty();

    var claim = fixture.tx(() -> fixture.sources().claimReviewed(binding, actual));
    assertThatThrownBy(
            () ->
                fixture.tx(
                    () ->
                        fixture
                            .dsl()
                            .execute(
                                "UPDATE game_design_reviewed_draft_base SET evidence_digest = ? WHERE request_id = ?",
                                changed.digest(),
                                binding.requestId())))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("Reviewed Draft base evidence is immutable");
    assertThatThrownBy(() -> fixture.tx(() -> fixture.sources().claimReviewed(binding, changed)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("REVIEWED_BASE_STORED_EVIDENCE_CONFLICT");
    assertThat(fixture.coordinator().read(fixture.target(), binding.requestId())).contains(claim);
    var retained = fixture.reviewed().requireCurrentRetained(binding);
    assertThat(retained.canonicalBytes()).containsExactly(actual.canonicalBytes());
    assertThat(retained.digest()).isEqualTo(actual.digest());
  }

  @Test
  void opaqueClaimRemainsUnreviewedAndCannotApplyOrCaptureSources() {
    Fixture fixture = fixture();
    var genesis = fixture.sources().readGenesis(fixture.target()).orElseThrow();
    var binding = policyBinding(fixture, genesis.policy().receiptId());
    var evidence = fixture.resolve(binding);
    var originalClaim = fixture.tx(() -> fixture.coordinator().claim(binding));

    assertThatThrownBy(() -> fixture.tx(() -> fixture.sources().claimReviewed(binding, evidence)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("REVIEWED_BASE_EXISTING_CLAIM_LACKS_EVIDENCE");
    assertThat(fixture.coordinator().read(fixture.target(), binding.requestId()))
        .contains(originalClaim);
    assertThat(fixture.reviewed().read(binding)).isEmpty();

    var slot = fixture.tx(() -> fixture.coordinator().claimApplicationSlot(binding));
    assertThatThrownBy(() -> fixture.tx(() -> insertReview(fixture.dsl(), binding, evidence)))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("Reviewed base requires exact new full Draft binding");
    fixture.tx(
        () -> fixture.coordinator().markOwnerInProgress(binding, Owner.GAME_DESIGN_CONTROL_PLANE));
    var dispatched =
        fixture.coordinator().read(fixture.target(), binding.requestId()).orElseThrow();

    assertThatThrownBy(() -> fixture.tx(() -> fixture.sources().apply(binding)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAME_DESIGN_SOURCE_REVIEWED_BASE_UNAVAILABLE");
    assertThatThrownBy(() -> fixture.tx(() -> fixture.sources().captureSynchronized(binding)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAME_DESIGN_SOURCE_REVIEWED_BASE_UNAVAILABLE");
    assertThat(fixture.coordinator().read(fixture.target(), binding.requestId()))
        .contains(dispatched);
    assertThat(fixture.coordinator().readApplicationSlot(fixture.target())).contains(slot);
    assertThat(fixture.coordinator().readVisibilityFence(fixture.target())).isEmpty();
    assertThat(fixture.reviewed().read(binding)).isEmpty();
    assertEmptyTables(
        fixture.dsl(),
        "game_design_realm_policy_application",
        "game_design_command_source_application",
        "game_design_realm_policy_snapshot",
        "game_design_command_source_snapshot");
  }

  @Test
  void unreviewedPublicationSelectionCannotFreezeOrReadSourceCaptures() {
    Fixture fixture = fixture();
    // Retain the actual local selection and operation using stipulated Account and World evidence.
    var operation =
        fixture.tx(
            () -> {
              try {
                return IsolatedPublicationOwnerSetup.retain(fixture.dsl(), fixture.target(), 1);
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            });
    var selected = operation.account().input().selection().selectedCommit();
    assertThat(fixture.coordinator().readVisibilityFence(fixture.target()).orElseThrow().commitId())
        .isEqualTo(selected.commitId());
    assertThat(fixture.reviewed().read(selected)).isEmpty();

    assertThatThrownBy(() -> fixture.tx(() -> fixture.sources().freeze(operation)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAME_DESIGN_SOURCE_REVIEWED_BASE_UNAVAILABLE");
    assertThatThrownBy(() -> fixture.sources().readCapture(operation))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GAME_DESIGN_SOURCE_REVIEWED_BASE_UNAVAILABLE");
    assertEmptyTables(
        fixture.dsl(),
        "game_design_realm_policy_capture",
        "game_design_command_source_capture",
        "game_design_asset_source_capture",
        "game_design_gameplay_rule_source_capture",
        "game_design_template_config_source_capture",
        "game_design_branding_source_capture");
  }

  private static void assertEmptyTables(DSLContext dsl, String... tables) {
    for (String table : tables) {
      assertThat(dsl.fetchCount(DSL.table(table))).as(table).isZero();
    }
  }

  private static int insertReview(
      DSLContext dsl, DraftCommitBinding binding, GameDesignReviewedBaseEvidence evidence) {
    return dsl.execute(
        "INSERT INTO game_design_reviewed_draft_base "
            + "(canonical_tenant_id, canonical_version_id, request_id, commit_id, input_digest, "
            + "binding_json, base_kind, base_identity, base_reference, evidence_schema, "
            + "evidence_bytes, evidence_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding.requestId(),
        binding.commitId(),
        binding.digest(),
        binding.canonicalJson(),
        evidence.reference().kind().name(),
        evidence.reference().identity(),
        binding.baseCommitId(),
        GameDesignReviewedBaseEvidence.SCHEMA,
        evidence.canonicalBytes(),
        evidence.digest());
  }

  private static DraftCommitBinding policyBinding(Fixture fixture, UUID receiptId) {
    String payload =
        """
        {"revisionKind":"REALM_ENTRY_POLICY","logicalRevisionId":"authored-main",
         "policy":{"schemaVersion":1,"worldSlug":"world","worldDisplayName":"World",
                   "realmSlug":"main","realmDisplayName":"Realm","visible":true,
                   "publicProduction":true,"stateScope":"SHARED","entryPolicy":"PRESEEDED_ONLY"}}
        """;
    return DraftCommitBinding.create(
        fixture.target(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "genesis:" + receiptId,
        List.of(
            new RevisionPayload("0", UUID.randomUUID(), Owner.GAME_DESIGN_CONTROL_PLANE, payload)),
        List.of(
            new AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                RealmPolicySource.SCOPE,
                fixture.target().canonicalVersionId().toString(),
                RealmPolicySource.SCOPE,
                "effective",
                "0")));
  }

  private static Fixture fixture() {
    String schema = "gd_reviewed_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath())
        .table("flyway_schema_history_game_design_service")
        .load()
        .migrate();
    var write = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    TargetProof target =
        write.execute(
            status -> {
              var game = new Game();
              game.setTenantId("ISOLATED-" + UUID.randomUUID().toString().substring(0, 8));
              game.setName("ISOLATED reviewed source");
              var savedGame = new GameRepository(dsl).save(game);
              var version = new Version();
              version.setTenantId(savedGame.getTenantId());
              version.setVersionNumber(1);
              var saved = new VersionRepository(dsl).save(version);
              return new TargetProof(
                  saved.getCanonicalTenantId(),
                  saved.getCanonicalVersionId(),
                  saved.getId(),
                  saved.getTenantId(),
                  saved.getIdentitySourceGameRowId(),
                  saved.getIdentitySourceGameTenantKey(),
                  saved.getIdentitySourceProvenanceKind());
            });
    return new Fixture(
        dsl,
        write,
        target,
        new GameDesignSourceRepository(dsl),
        new DraftCommitCoordinatorRepository(dsl),
        new GameDesignReviewedBaseRepository(dsl));
  }

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate write,
      TargetProof target,
      GameDesignSourceRepository sources,
      DraftCommitCoordinatorRepository coordinator,
      GameDesignReviewedBaseRepository reviewed) {
    <T> T tx(Supplier<T> work) {
      return write.execute(status -> work.get());
    }

    GameDesignReviewedBaseEvidence resolve(DraftCommitBinding binding) {
      return tx(() -> reviewed.resolve(target, DraftBaseReference.parse(binding.baseCommitId())));
    }
  }
}
