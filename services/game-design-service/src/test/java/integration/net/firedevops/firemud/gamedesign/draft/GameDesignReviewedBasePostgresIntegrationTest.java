package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.common.authoring.DraftBaseReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.config.PostgresProperties;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.publication.CommandSource;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyGenesis;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySource;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
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

/**
 * Genuine local owner persistence; Account/current permission and upstream authority are isolated.
 */
@Testcontainers(disabledWithoutDocker = true)
class GameDesignReviewedBasePostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void newDraftSharedGenesisClaimsAndReadsExactImmutableBinding() {
    Fixture f = fixture();
    var evidence = f.genesis();
    var binding = policy(f.target(), evidence.reference().canonicalValue(), "0", "first");
    assertThat(f.tx(() -> f.sources().claimReviewed(binding, evidence)).binding())
        .isEqualTo(binding);
    var retained = f.reviewed().read(binding).orElseThrow();
    assertThat(retained.canonicalBytes()).containsExactly(evidence.canonicalBytes());
    assertThat(retained.digest()).isEqualTo(evidence.digest());
    assertThat(f.tx(() -> f.sources().claimReviewed(binding, evidence)).binding())
        .isEqualTo(binding);
    assertThat(f.dsl().fetchCount(DSL.table("game_design_reviewed_draft_base"))).isEqualTo(1);
    assertThat(f.coordinator().readVisibilityFence(f.target())).isEmpty();
  }

  @Test
  void wrongDraftWrongReceiptAndUnknownRetainedBaseDenyBeforeClaim() {
    Fixture f = fixture();
    var evidence = f.genesis();
    TargetProof other =
        f.tx(
            () -> {
              var version = new Version();
              version.setTenantId(f.target().gameDesignVersionTenantKey());
              version.setVersionNumber(2);
              var properties = new PostgresProperties();
              properties.setSchema(
                  f.dsl()
                      .fetchSingle("SELECT current_schema() AS schema_name")
                      .get("schema_name", String.class));
              version = new VersionRepository(f.dsl(), properties).save(version);
              return new TargetProof(
                  version.getCanonicalTenantId(),
                  version.getCanonicalVersionId(),
                  version.getId(),
                  version.getTenantId(),
                  version.getIdentitySourceGameRowId(),
                  version.getIdentitySourceGameTenantKey(),
                  version.getIdentitySourceProvenanceKind());
            });
    var wrongDraft = policy(other, evidence.reference().canonicalValue(), "0", "wrong");
    assertThatThrownBy(() -> f.tx(() -> f.sources().claimReviewed(wrongDraft, evidence)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> f.tx(() -> f.reviewed().resolve(other, evidence.reference())))
        .isInstanceOf(IllegalArgumentException.class);
    var wrongReceipt = new DraftBaseReference(DraftBaseReference.Kind.GENESIS, UUID.randomUUID());
    assertThatThrownBy(() -> f.tx(() -> f.reviewed().resolve(f.target(), wrongReceipt)))
        .isInstanceOf(IllegalArgumentException.class);
    var unknown =
        new DraftBaseReference(DraftBaseReference.Kind.AUTHORED_COMMIT, UUID.randomUUID());
    assertThatThrownBy(() -> f.tx(() -> f.reviewed().resolve(f.target(), unknown)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(f.dsl().fetchCount(DSL.table("game_design_draft_commit"))).isZero();
  }

  @Test
  void changedCreationWitnessAndDigestCannotReplaceReviewedEvidence() {
    Fixture f = fixture();
    var actual = f.sources().readGenesis(f.target()).orElseThrow();
    String changedWitness =
        new java.math.BigInteger(actual.policy().creationTransactionId())
            .add(java.math.BigInteger.ONE)
            .toString();
    var fabricated =
        GameDesignReviewedBaseEvidence.genesis(
            new GameDesignSourceRepository.Genesis(
                new RealmPolicyGenesis(f.target(), actual.policy().receiptId(), changedWitness),
                new CommandSource.NewDraftGenesisReceipt(
                    f.target(), actual.policy().receiptId(), changedWitness)));
    var binding = policy(f.target(), fabricated.reference().canonicalValue(), "0", "first");
    assertThatThrownBy(() -> f.tx(() -> f.sources().claimReviewed(binding, fabricated)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("OWNER_EVIDENCE_CONFLICT");
    assertThat(f.coordinator().read(f.target(), binding.requestId())).isEmpty();
    assertThat(f.dsl().fetchCount(DSL.table("game_design_realm_policy_application"))).isZero();
    assertThat(f.dsl().fetchCount(DSL.table("game_design_command_source_application"))).isZero();
    var actualEvidence = f.genesis();
    f.tx(() -> f.sources().claimReviewed(binding, actualEvidence));
    assertThatThrownBy(
            () ->
                f.tx(
                    () ->
                        f.dsl()
                            .execute(
                                "UPDATE game_design_reviewed_draft_base SET evidence_digest = ? WHERE request_id = ?",
                                "sha256:" + "0".repeat(64),
                                binding.requestId())))
        .hasMessageContaining("immutable");
    assertThatThrownBy(() -> f.tx(() -> f.sources().claimReviewed(binding, fabricated)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("STORED_EVIDENCE_CONFLICT");
    assertThat(f.reviewed().read(binding).orElseThrow().canonicalBytes())
        .containsExactly(actualEvidence.canonicalBytes());
  }

  @Test
  void retainedAuthoredBaseSurvivesNewerDisjointCommandAdvanceAndExactRetry() {
    Fixture f = fixture();
    var genesis = f.genesis();
    var first = policy(f.target(), genesis.reference().canonicalValue(), "0", "first");
    f.tx(() -> f.sources().claimReviewed(first, genesis));
    synchronize(f, first);
    var reviewed =
        f.tx(
            () ->
                f.reviewed()
                    .resolve(f.target(), DraftBaseReference.parse(first.commitId().toString())));
    var command = command(f.target(), first.commitId().toString());
    f.tx(() -> f.sources().claimReviewed(command, reviewed));
    synchronize(f, command);
    var proposal = policy(f.target(), first.commitId().toString(), "1", "second");
    f.tx(() -> f.sources().claimReviewed(proposal, reviewed));
    assertThat(f.coordinator().readVisibilityFence(f.target()).orElseThrow().commitId())
        .isEqualTo(command.commitId());
    assertThat(f.reviewed().read(proposal).orElseThrow().canonicalBytes())
        .containsExactly(reviewed.canonicalBytes());
    assertThat(f.tx(() -> f.sources().claimReviewed(proposal, reviewed)).binding())
        .isEqualTo(proposal);
    assertThat(f.tx(() -> f.reviewed().resolve(f.target(), reviewed.reference())))
        .isEqualTo(reviewed);
    synchronize(f, proposal);
    assertThat(
            f.sources()
                .readSynchronized(f.target(), proposal.commitId())
                .orElseThrow()
                .command()
                .definitions())
        .hasSize(1);
    assertThat(f.tx(() -> f.sources().apply(first)).ownerOutcome())
        .isEqualTo(
            f.coordinator()
                .read(f.target(), first.requestId())
                .orElseThrow()
                .ownerStates()
                .get(Owner.GAME_DESIGN_CONTROL_PLANE)
                .outcome()
                .orElseThrow());
  }

  @Test
  void reviewedGenesisRemainsOriginalProvenanceAfterDisjointSourceAdvance() {
    Fixture f = fixture();
    var reviewedGenesis = f.genesis();
    var command = command(f.target(), reviewedGenesis.reference().canonicalValue());
    f.tx(() -> f.sources().claimReviewed(command, reviewedGenesis));
    synchronize(f, command);
    var policy = policy(f.target(), reviewedGenesis.reference().canonicalValue(), "0", "first");
    f.tx(() -> f.sources().claimReviewed(policy, reviewedGenesis));
    assertThat(f.reviewed().read(policy).orElseThrow().canonicalBytes())
        .containsExactly(reviewedGenesis.canonicalBytes());
    assertThat(f.tx(() -> f.sources().claimReviewed(command, reviewedGenesis)).binding())
        .isEqualTo(command);
    synchronize(f, policy);
    assertThat(f.tx(() -> f.sources().apply(command)).command().orElseThrow().binding())
        .isEqualTo(command);
  }

  @Test
  void unreviewedClaimCannotWriteOrCaptureCanonicalSources() {
    Fixture f = fixture();
    var binding = policy(f.target(), f.genesis().reference().canonicalValue(), "0", "first");
    f.tx(
        () -> {
          f.coordinator().claim(binding);
          f.coordinator().claimApplicationSlot(binding);
          f.coordinator().markOwnerInProgress(binding, Owner.GAME_DESIGN_CONTROL_PLANE);
          return null;
        });
    assertThatThrownBy(() -> f.tx(() -> f.sources().apply(binding)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("REVIEWED_BASE_UNAVAILABLE");
    assertThatThrownBy(() -> f.tx(() -> f.sources().captureSynchronized(binding)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("REVIEWED_BASE_UNAVAILABLE");
    assertThat(f.dsl().fetchCount(DSL.table("game_design_realm_policy_application"))).isZero();
    assertThat(f.dsl().fetchCount(DSL.table("game_design_command_source_application"))).isZero();
    assertThat(f.dsl().fetchCount(DSL.table("game_design_realm_policy_snapshot"))).isZero();
    assertThat(f.dsl().fetchCount(DSL.table("game_design_command_source_snapshot"))).isZero();
    assertThat(f.coordinator().readVisibilityFence(f.target())).isEmpty();
  }

  @Test
  void unreviewedPublicationSelectionCannotFreezeOrConsumeCanonicalSources() {
    Fixture f = fixture();
    var operation =
        f.tx(
            () -> {
              try {
                return IsolatedPublicationOwnerSetup.retain(f.dsl(), f.target(), 1);
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            });
    assertThatThrownBy(() -> f.tx(() -> f.sources().freeze(operation)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("REVIEWED_BASE_UNAVAILABLE");
    assertThatThrownBy(() -> f.sources().readCapture(operation))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("REVIEWED_BASE_UNAVAILABLE");
    assertThat(f.dsl().fetchCount(DSL.table("game_design_command_source_capture"))).isZero();
    assertThat(f.dsl().fetchCount(DSL.table("game_design_realm_policy_capture"))).isZero();
  }

  @Test
  void storageRejectsLateReviewedDecorationAfterDispatchSlotClaim() {
    Fixture f = fixture();
    var evidence = f.genesis();
    var binding = policy(f.target(), evidence.reference().canonicalValue(), "0", "first");
    f.tx(
        () -> {
          f.coordinator().claim(binding);
          f.coordinator().claimApplicationSlot(binding);
          return null;
        });
    assertThatThrownBy(
            () ->
                f.tx(
                    () ->
                        f.dsl()
                            .execute(
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
                                evidence.digest())))
        .hasMessageContaining("exact new full Draft binding");
    assertThat(f.reviewed().read(binding)).isEmpty();
    assertThat(f.dsl().fetchCount(DSL.table("game_design_realm_policy_application"))).isZero();
  }

  @Test
  void rollbackRemovesClaimAndEvidenceTogetherAndOpaqueHistoryCannotBeBackfilled() {
    Fixture f = fixture();
    var evidence = f.genesis();
    var binding = policy(f.target(), evidence.reference().canonicalValue(), "0", "first");
    f.write()
        .executeWithoutResult(
            status -> {
              f.sources().claimReviewed(binding, evidence);
              status.setRollbackOnly();
            });
    assertThat(f.reviewed().read(binding)).isEmpty();
    assertThat(f.coordinator().read(f.target(), binding.requestId())).isEmpty();
    f.tx(() -> f.coordinator().claim(binding));
    assertThatThrownBy(() -> f.tx(() -> f.sources().claimReviewed(binding, evidence)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LACKS_EVIDENCE");
    assertThat(f.reviewed().read(binding)).isEmpty();
  }

  private static void synchronize(Fixture f, DraftCommitBinding binding) {
    f.tx(
        () -> {
          f.coordinator().claimApplicationSlot(binding);
          f.coordinator().markOwnerInProgress(binding, Owner.GAME_DESIGN_CONTROL_PLANE);
          var actual = f.sources().apply(binding);
          f.coordinator()
              .advanceSourceVisibilityFence(
                  binding,
                  new DraftCommitCoordinatorRepository.CoordinatorProof(
                      binding, List.of(actual.ownerOutcome())));
          f.sources().captureSynchronized(binding);
          f.coordinator().releaseApplicationSlot(binding);
          return null;
        });
  }

  private static DraftCommitBinding policy(
      TargetProof target, String base, String epoch, String realm) {
    String payload =
        "{\"revisionKind\":\"REALM_ENTRY_POLICY\",\"logicalRevisionId\":\"authored-"
            + realm
            + "\",\"policy\":{\"schemaVersion\":1,\"worldSlug\":\"world\",\"worldDisplayName\":\"World\","
            + "\"realmSlug\":\""
            + realm
            + "\",\"realmDisplayName\":\"Realm\",\"visible\":true,"
            + "\"publicProduction\":false,\"stateScope\":\"SHARED\",\"entryPolicy\":\"PRESEEDED_ONLY\"}}";
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        base,
        List.of(
            new RevisionPayload("0", UUID.randomUUID(), Owner.GAME_DESIGN_CONTROL_PLANE, payload)),
        List.of(
            new AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                RealmPolicySource.SCOPE,
                target.canonicalVersionId().toString(),
                RealmPolicySource.SCOPE,
                "effective",
                epoch)));
  }

  private static DraftCommitBinding command(TargetProof target, String base) {
    String definition =
        "{\"schemaVersion\":1,\"commandId\":\"look\",\"semanticOwner\":\"WORLD\","
            + "\"executionDiscipline\":\"DURABLE_GAMEPLAY\",\"stageRequirement\":\"GAMEPLAY\","
            + "\"promptPolicy\":\"NEVER\",\"actionCategory\":\"GAMEPLAY\",\"historyRecordable\":true,"
            + "\"aliases\":[\"see\"],\"actionTags\":[\"WORLD_BROWSE\"],\"effects\":[]}";
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        base,
        List.of(
            new RevisionPayload(
                "0",
                UUID.randomUUID(),
                Owner.GAME_DESIGN_CONTROL_PLANE,
                CommandSource.upsertPayload(definition))),
        List.of(
            new AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                CommandSource.SCOPE,
                target.canonicalVersionId().toString(),
                CommandSource.SCOPE,
                CommandSource.SCOPE_ID,
                "0")));
  }

  private static Fixture fixture() {
    String schema = "gd_reviewed_" + UUID.randomUUID().toString().replace("-", "");
    var ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    ds.setSchema(schema);
    Flyway.configure()
        .dataSource(ds)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .table("flyway_schema_history_game_design_service")
        .target("55")
        .load()
        .migrate();
    var write = new TransactionTemplate(new DataSourceTransactionManager(ds));
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(ds), SQLDialect.POSTGRES);
    var properties = new PostgresProperties();
    properties.setSchema(schema);
    TargetProof target =
        write.execute(
            status -> {
              var game = new Game();
              game.setTenantId("ISOLATED-" + UUID.randomUUID().toString().substring(0, 8));
              game.setName("ISOLATED reviewed source");
              game = new GameRepository(dsl).save(game);
              var version = new Version();
              version.setTenantId(game.getTenantId());
              version.setVersionNumber(1);
              version = new VersionRepository(dsl, properties).save(version);
              return new TargetProof(
                  version.getCanonicalTenantId(),
                  version.getCanonicalVersionId(),
                  version.getId(),
                  version.getTenantId(),
                  version.getIdentitySourceGameRowId(),
                  version.getIdentitySourceGameTenantKey(),
                  version.getIdentitySourceProvenanceKind());
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

    GameDesignReviewedBaseEvidence genesis() {
      var actual = sources.readGenesis(target).orElseThrow();
      return tx(
          () ->
              reviewed.resolve(
                  target, DraftBaseReference.parse("genesis:" + actual.policy().receiptId())));
    }
  }
}
