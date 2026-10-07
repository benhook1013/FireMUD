package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
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

/** Real local source writers and coordinator SQL; publication Account and World remain isolated. */
@Testcontainers(disabledWithoutDocker = true)
class GameDesignSourcePostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void versionInsertSharesGenesisAndPolicyCommandMixedCommitsKeepOneExactOutcome() {
    Fixture fixture = fixture();
    var genesis = fixture.sources().readGenesis(fixture.target()).orElseThrow();
    assertThat(genesis.policy().receiptId()).isEqualTo(genesis.command().receiptId());
    assertThat(genesis.policy().creationTransactionId())
        .isEqualTo(genesis.command().creationTransactionId());
    assertThat(fixture.tx(() -> fixture.sources().enrollFreshDraft(fixture.target())))
        .isEqualTo(genesis);
    assertThat(fixture.coordinator().readVisibilityFence(fixture.target())).isEmpty();

    DraftCommitBinding policy = binding(fixture.target(), null, "0", null, "main", true);
    var policyResult = apply(fixture, policy);
    synchronize(fixture, policy, policyResult);
    var policySnapshot =
        fixture.sources().readSynchronized(fixture.target(), policy.commitId()).orElseThrow();
    assertThat(policySnapshot.policy().policies()).hasSize(1);
    assertThat(policySnapshot.command().definitions()).isEmpty();

    DraftCommitBinding command =
        binding(fixture.target(), policy.commitId(), null, "0", null, false);
    var commandResult = apply(fixture, command);
    synchronize(fixture, command, commandResult);
    var commandSnapshot =
        fixture.sources().readSynchronized(fixture.target(), command.commitId()).orElseThrow();
    assertThat(commandSnapshot.policy().policies())
        .containsExactlyElementsOf(policySnapshot.policy().policies());
    assertThat(commandSnapshot.command().definitions()).hasSize(1);
    assertThat(commandResult.ownerOutcome().appliedEpochs())
        .containsExactly(commandResult.command().orElseThrow().commandAppliedEpoch());

    DraftCommitBinding mixed =
        binding(fixture.target(), command.commitId(), "1", "1", "main", true);
    var mixedResult = apply(fixture, mixed);
    assertThat(mixedResult.ownerOutcome().appliedEpochs()).hasSize(2);
    assertThat(mixedResult.ownerOutcome().resultBytes())
        .contains(mixedResult.command().orElseThrow().canonicalBytes());
    assertThat(fixture.tx(() -> fixture.sources().apply(policy)).ownerOutcome())
        .isEqualTo(policyResult.ownerOutcome());
    assertThat(fixture.tx(() -> fixture.sources().apply(command)).ownerOutcome())
        .isEqualTo(commandResult.ownerOutcome());
    assertThat(fixture.tx(() -> fixture.sources().apply(mixed)).ownerOutcome())
        .isEqualTo(mixedResult.ownerOutcome());
    synchronize(fixture, mixed, mixedResult);
    var mixedSnapshot =
        fixture.sources().readSynchronized(fixture.target(), mixed.commitId()).orElseThrow();
    assertThat(mixedSnapshot.policy().sourceEpoch()).isEqualTo("2");
    assertThat(mixedSnapshot.command().sourceEpoch()).isEqualTo("2");
    assertThat(mixedSnapshot.command().definitions().getFirst().sourceCommitId())
        .isEqualTo(mixed.commitId());
    assertThat(fixture.tx(() -> fixture.sources().captureSynchronized(mixed)))
        .isEqualTo(mixedSnapshot);
    assertThat(fixture.tx(() -> fixture.sources().apply(policy)).ownerOutcome())
        .isEqualTo(policyResult.ownerOutcome());
    assertThat(fixture.tx(() -> fixture.sources().apply(command)).ownerOutcome())
        .isEqualTo(commandResult.ownerOutcome());
  }

  @Test
  void sourceRollbackIncompleteScopesAndPublicationCaptureFailClosed() {
    Fixture fixture = fixture();
    DraftCommitBinding incomplete = binding(fixture.target(), null, "0", null, "private", false);
    start(fixture, incomplete);
    fixture
        .write()
        .executeWithoutResult(
            status -> {
              fixture.sources().apply(incomplete);
              status.setRollbackOnly();
            });
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_realm_policy_application")))
        .isZero();
    assertThat(
            fixture
                .coordinator()
                .read(fixture.target(), incomplete.requestId())
                .orElseThrow()
                .ownerStates()
                .get(Owner.GAME_DESIGN_CONTROL_PLANE)
                .outcome())
        .isEmpty();
    var result = fixture.tx(() -> fixture.sources().apply(incomplete));
    synchronize(fixture, incomplete, result);

    var unsupported =
        DraftCommitBinding.create(
            fixture.target(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            incomplete.commitId().toString(),
            List.of(
                new RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    CommandSource.deletePayload("unused"))),
            List.of(
                new AffectedUnit(
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    RealmPolicySource.SCOPE,
                    fixture.target().canonicalVersionId().toString(),
                    RealmPolicySource.SCOPE,
                    "effective",
                    "1")));
    assertThatThrownBy(() -> fixture.tx(() -> fixture.sources().apply(unsupported)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Complete supported Game Design source scopes");

    var operation =
        fixture.tx(
            () -> {
              try {
                var retained =
                    IsolatedPublicationOwnerSetup.retainSourceBacked(
                        fixture.dsl(), fixture.target(), 1);
                fixture
                    .sources()
                    .captureSynchronized(retained.account().input().selection().selectedCommit());
                return retained;
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            });
    assertThatThrownBy(() -> fixture.tx(() -> fixture.sources().freeze(operation)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Exactly one visible public-production realm");
    assertThat(fixture.sources().readCapture(operation)).isEmpty();
  }

  @Test
  void completeSelectedSourcesFreezeTogetherAndExactRetryReadsStoredBytes() {
    Fixture fixture = fixture();
    var authored = binding(fixture.target(), null, "0", "0", "main", true);
    synchronize(fixture, authored, apply(fixture, authored));
    var operation =
        fixture.tx(
            () -> {
              try {
                var retained =
                    IsolatedPublicationOwnerSetup.retainSourceBacked(
                        fixture.dsl(), fixture.target(), 1);
                fixture
                    .sources()
                    .captureSynchronized(retained.account().input().selection().selectedCommit());
                return retained;
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            });
    assertThat(fixture.sources().readCapture(operation)).isEmpty();
    fixture
        .write()
        .executeWithoutResult(
            status -> {
              fixture.sources().freeze(operation);
              status.setRollbackOnly();
            });
    assertThat(fixture.sources().readCapture(operation)).isEmpty();
    var first = fixture.tx(() -> fixture.sources().freeze(operation));
    var retry = fixture.tx(() -> fixture.sources().freeze(operation));
    var read = fixture.sources().readCapture(operation).orElseThrow();
    assertThat(retry.command().canonicalBytes()).containsExactly(first.command().canonicalBytes());
    assertThat(retry.policy().canonicalBytes()).containsExactly(first.policy().canonicalBytes());
    assertThat(read.command().canonicalBytes()).containsExactly(first.command().canonicalBytes());
    assertThat(read.policy().canonicalBytes()).containsExactly(first.policy().canonicalBytes());
    assertThat(read.command().snapshot().binding())
        .isEqualTo(operation.account().input().selection().selectedCommit());
    assertThat(read.policy().snapshot().binding())
        .isEqualTo(operation.account().input().selection().selectedCommit());
  }

  private static GameDesignSourceRepository.Application apply(
      Fixture fixture, DraftCommitBinding binding) {
    start(fixture, binding);
    return fixture.tx(() -> fixture.sources().apply(binding));
  }

  private static void start(Fixture fixture, DraftCommitBinding binding) {
    fixture.tx(
        () -> {
          fixture.coordinator().claim(binding);
          fixture.coordinator().claimApplicationSlot(binding);
          fixture.coordinator().markOwnerInProgress(binding, Owner.GAME_DESIGN_CONTROL_PLANE);
          return null;
        });
  }

  private static void synchronize(
      Fixture fixture, DraftCommitBinding binding, GameDesignSourceRepository.Application result) {
    fixture.tx(
        () -> {
          fixture
              .coordinator()
              .advanceSourceVisibilityFence(
                  binding,
                  new DraftCommitCoordinatorRepository.CoordinatorProof(
                      binding, List.of(result.ownerOutcome())));
          fixture.sources().captureSynchronized(binding);
          fixture.coordinator().releaseApplicationSlot(binding);
          return null;
        });
  }

  private static DraftCommitBinding binding(
      TargetProof target,
      UUID base,
      String policyEpoch,
      String commandEpoch,
      String realm,
      boolean production) {
    var revisions = new java.util.ArrayList<RevisionPayload>();
    var scopes = new java.util.ArrayList<AffectedUnit>();
    if (commandEpoch != null) {
      revisions.add(
          new RevisionPayload(
              Integer.toString(revisions.size()),
              UUID.randomUUID(),
              Owner.GAME_DESIGN_CONTROL_PLANE,
              CommandSource.upsertPayload(command("look", realm == null ? "see" : realm))));
      scopes.add(
          new AffectedUnit(
              Owner.GAME_DESIGN_CONTROL_PLANE,
              CommandSource.SCOPE,
              target.canonicalVersionId().toString(),
              CommandSource.SCOPE,
              CommandSource.SCOPE_ID,
              commandEpoch));
    }
    if (policyEpoch != null) {
      String payload =
          "{\"revisionKind\":\"REALM_ENTRY_POLICY\",\"logicalRevisionId\":\"authored-"
              + realm
              + "\",\"policy\":{\"schemaVersion\":1,\"worldSlug\":\"world\","
              + "\"worldDisplayName\":\"World\",\"realmSlug\":\""
              + realm
              + "\","
              + "\"realmDisplayName\":\"Realm\",\"visible\":true,\"publicProduction\":"
              + production
              + ",\"stateScope\":\"SHARED\",\"entryPolicy\":\"PRESEEDED_ONLY\"}}";
      revisions.add(
          new RevisionPayload(
              Integer.toString(revisions.size()),
              UUID.randomUUID(),
              Owner.GAME_DESIGN_CONTROL_PLANE,
              payload));
      scopes.add(
          new AffectedUnit(
              Owner.GAME_DESIGN_CONTROL_PLANE,
              RealmPolicySource.SCOPE,
              target.canonicalVersionId().toString(),
              RealmPolicySource.SCOPE,
              "effective",
              policyEpoch));
    }
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        base == null ? "ISOLATED-explicit-base" : base.toString(),
        revisions,
        scopes);
  }

  private static String command(String id, String alias) {
    return "{\"schemaVersion\":1,\"commandId\":\""
        + id
        + "\",\"semanticOwner\":\"WORLD\","
        + "\"executionDiscipline\":\"DURABLE_GAMEPLAY\",\"stageRequirement\":\"GAMEPLAY\","
        + "\"promptPolicy\":\"NEVER\",\"actionCategory\":\"GAMEPLAY\",\"historyRecordable\":true,"
        + "\"aliases\":[\""
        + alias
        + "\"],\"actionTags\":[\"WORLD_BROWSE\"],\"effects\":[],"
        + "\"legacyExtension\":{\"kept\":true}}";
  }

  private static Fixture fixture() {
    String schema = "gd_combined_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .table("flyway_schema_history_game_design_service")
        .target("54")
        .load()
        .migrate();
    var write = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var properties = new PostgresProperties();
    properties.setSchema(schema);
    TargetProof target =
        write.execute(
            status -> {
              var game = new Game();
              game.setTenantId("ISOLATED-" + UUID.randomUUID().toString().substring(0, 8));
              game.setName("ISOLATED composed source");
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
        new DraftCommitCoordinatorRepository(dsl));
  }

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate write,
      TargetProof target,
      GameDesignSourceRepository sources,
      DraftCommitCoordinatorRepository coordinator) {
    <T> T tx(Supplier<T> work) {
      return write.execute(status -> work.get());
    }
  }
}
