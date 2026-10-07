package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.config.PostgresProperties;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.PublishAttempt;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.publication.CommandApplication;
import net.firedevops.firemud.gamedesign.publication.CommandSnapshot;
import net.firedevops.firemud.gamedesign.publication.CommandSource;
import net.firedevops.firemud.gamedesign.publication.CommandSourceRepository;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationRepository;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.IsolatedPublicationOperationFixtures;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyGenesis;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySource;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Actual Game Design migrations and owner source writes; external proposal evidence is ISOLATED.
 */
@Testcontainers(disabledWithoutDocker = true)
class CommandSourcePostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String INITIAL_PROPOSAL_BASE = "ISOLATED-explicit-initial-proposal-base";

  @Test
  void freshGenesisMixedUpsertRollbackRetryDisjointInheritanceDeleteAndPendingFreeze()
      throws Exception {
    Fixture fixture = fixture();
    CommandSource.NewDraftGenesisReceipt commandGenesis =
        fixture.commands().readNewDraftGenesis(fixture.target()).orElseThrow();
    RealmPolicyGenesis policyGenesis =
        fixture.policies().readGenesis(fixture.target()).orElseThrow();
    assertThat(commandGenesis.receiptId()).isEqualTo(policyGenesis.receiptId());
    assertThat(commandGenesis.creationTransactionId())
        .isEqualTo(policyGenesis.creationTransactionId());
    assertThat(fixture.coordinator().readVisibilityFence(fixture.target())).isEmpty();
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_draft_commit"))).isZero();
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_command_source_snapshot"))).isZero();

    DraftCommitBinding mixed = mixedUpsertBinding(fixture.target(), INITIAL_PROPOSAL_BASE);
    start(fixture, mixed);
    fixture
        .write()
        .executeWithoutResult(
            status -> {
              fixture.commands().apply(mixed);
              fixture.policies().applyMutation(mixed);
              status.setRollbackOnly();
            });
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_command_source_application")))
        .isZero();
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_command_source_operation")))
        .isZero();
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_realm_policy_application")))
        .isZero();
    assertThat(
            java.util.Objects.requireNonNull(
                    fixture
                        .dsl()
                        .fetchOne(
                            "SELECT source_epoch FROM game_design_command_source_head WHERE canonical_version_id = ?",
                            fixture.target().canonicalVersionId()),
                    "fresh Draft fixture must have its command-source genesis head")
                .get(0, String.class))
        .isEqualTo("0");
    assertThat(
            java.util.Objects.requireNonNull(
                    fixture
                        .dsl()
                        .fetchOne(
                            "SELECT source_epoch FROM game_design_realm_policy_source WHERE canonical_version_id = ?",
                            fixture.target().canonicalVersionId()),
                    "fresh Draft fixture must have its realm-policy genesis source row")
                .get(0, String.class))
        .isEqualTo("0");
    assertThat(fixture.commands().readSnapshot(fixture.target(), mixed.commitId())).isEmpty();
    assertThat(
            fixture
                .coordinator()
                .read(fixture.target(), mixed.requestId())
                .orElseThrow()
                .ownerStates()
                .get(Owner.GAME_DESIGN_CONTROL_PLANE)
                .outcome())
        .isEmpty();

    GameDesignSourceRepository.Application mixedApplication =
        fixture.tx(() -> fixture.sources().apply(mixed));
    CommandApplication commandApplication = mixedApplication.command().orElseThrow();
    assertThat(mixedApplication.ownerOutcome().appliedEpochs()).hasSize(2);
    assertThat(commandApplication.binding().baseCommitId()).isEqualTo(INITIAL_PROPOSAL_BASE);
    assertThat(commandApplication.snapshot().definitions()).hasSize(1);
    assertThat(fixture.commands().readSnapshot(fixture.target(), mixed.commitId())).isEmpty();
    assertThat(fixture.coordinator().readVisibilityFence(fixture.target())).isEmpty();
    assertThat(fixture.tx(() -> fixture.sources().apply(mixed).ownerOutcome()))
        .isEqualTo(mixedApplication.ownerOutcome());
    DraftCommitBinding changed = changedCommandDigest(mixed);
    assertThatThrownBy(() -> fixture.tx(() -> fixture.sources().apply(changed)))
        .hasMessageContaining("COMMAND_SOURCE_COMMIT_BINDING_CONFLICT");
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_command_source_application")))
        .isEqualTo(1);

    fixture.tx(
        () -> {
          fixture
              .coordinator()
              .advanceVisibilityFence(
                  mixed,
                  new DraftCommitCoordinatorRepository.CoordinatorProof(
                      mixed, List.of(mixedApplication.ownerOutcome())));
          fixture.sources().captureSynchronized(mixed);
          fixture.coordinator().releaseApplicationSlot(mixed);
          return null;
        });
    assertThat(
            count(
                fixture.dsl(),
                "SELECT count(*) FROM game_design_draft_commit_owner_result "
                    + "WHERE canonical_version_id = ? AND request_id = ?",
                fixture.target().canonicalVersionId(),
                mixed.requestId()))
        .isEqualTo(1L);
    CommandSnapshot firstSnapshot =
        fixture.commands().readSnapshot(fixture.target(), mixed.commitId()).orElseThrow();
    assertThat(firstSnapshot.binding()).isEqualTo(mixed);
    assertThat(firstSnapshot.definitions()).hasSize(1);
    assertThat(firstSnapshot.definitions().getFirst().sourceCommitId()).isEqualTo(mixed.commitId());
    assertThat(firstSnapshot.definitions().getFirst().sourceRevisionId())
        .isEqualTo(mixed.revisions().getFirst().revisionId());

    DraftCommitBinding disjoint =
        policyBinding(fixture.target(), "1", mixed.commitId().toString(), "side", false);
    start(fixture, disjoint);
    GameDesignSourceRepository.Application disjointApplication =
        fixture.tx(() -> fixture.sources().apply(disjoint));
    assertThat(disjointApplication.command()).isEmpty();
    fixture.tx(
        () -> {
          fixture
              .coordinator()
              .advanceVisibilityFence(
                  disjoint,
                  new DraftCommitCoordinatorRepository.CoordinatorProof(
                      disjoint, List.of(disjointApplication.ownerOutcome())));
          fixture.sources().captureSynchronized(disjoint);
          fixture.coordinator().releaseApplicationSlot(disjoint);
          return null;
        });
    CommandSnapshot inherited =
        fixture.commands().readSnapshot(fixture.target(), disjoint.commitId()).orElseThrow();
    assertThat(inherited.inheritedCommitId()).isEqualTo(mixed.commitId());
    assertThat(inherited.definitions()).containsExactlyElementsOf(firstSnapshot.definitions());
    assertThat(inherited.definitions().getFirst().sourceCommitId()).isEqualTo(mixed.commitId());

    // The reviewed command diff remains based on the last commit that changed commands, not the
    // newer disjoint policy commit; its unchanged command epoch is the freshness boundary.
    DraftCommitBinding delete =
        commandDeleteBinding(fixture.target(), "1", mixed.commitId().toString());
    assertThat(delete.baseCommitId()).isEqualTo(mixed.commitId().toString());
    start(fixture, delete);
    GameDesignSourceRepository.Application deleteApplication =
        fixture.tx(() -> fixture.sources().apply(delete));
    CommandApplication deletion = deleteApplication.command().orElseThrow();
    assertThat(deletion.mutations())
        .singleElement()
        .satisfies(
            mutation -> {
              assertThat(mutation.operation()).isEqualTo(CommandSource.OperationKind.DELETE);
              assertThat(mutation.commandId()).isEqualTo("look");
              assertThat(mutation.revisionId())
                  .isEqualTo(delete.revisions().getFirst().revisionId());
            });
    synchronizeSources(fixture, delete, deleteApplication);
    CommandSnapshot deleted =
        fixture.commands().readSnapshot(fixture.target(), delete.commitId()).orElseThrow();
    assertThat(deleted.definitions()).isEmpty();
    assertThat(
            count(
                fixture.dsl(),
                "SELECT count(*) FROM game_design_command_source_operation "
                    + "WHERE canonical_version_id = ? AND operation_kind = 'DELETE'",
                fixture.target().canonicalVersionId()))
        .isEqualTo(1L);

    GameDesignPublicationOperation operation = retainPendingPublication(fixture, delete);
    GameDesignSourceRepository.Capture capture =
        fixture.tx(() -> fixture.sources().freeze(operation));
    assertThat(fixture.tx(() -> fixture.sources().freeze(operation).command().canonicalBytes()))
        .containsExactly(capture.command().canonicalBytes());
    assertThat(capture.command().snapshot()).isEqualTo(deleted);
    assertThat(operation.account().input().selection().selectedCommit())
        .isEqualTo(deleted.binding());
    assertThat(fixture.sources().readCapture(operation).orElseThrow().command().canonicalBytes())
        .containsExactly(capture.command().canonicalBytes());
    assertThat(
            java.util.Objects.requireNonNull(
                    fixture
                        .dsl()
                        .fetchOne(
                            "SELECT outcome FROM game_design_publication_operation WHERE publish_workflow_id = ?",
                            operation.workflowId()),
                    "reserved publication fixture must have its persisted operation row")
                .get(0, String.class))
        .isEqualTo("PENDING");

    DraftCommitBinding postFreeze =
        commandDeleteBinding(fixture.target(), "2", delete.commitId().toString());
    assertThatThrownBy(() -> start(fixture, postFreeze)).isInstanceOf(RuntimeException.class);
    assertThat(fixture.coordinator().read(fixture.target(), postFreeze.requestId())).isEmpty();
    assertThat(fixture.commands().readSnapshot(fixture.target(), delete.commitId()).orElseThrow())
        .isEqualTo(deleted);
  }

  @Test
  void retainedPreSourceVersionIsNotBackfilledAsAnEmptyCommandSet() {
    Fixture fixture = retainedVersionWithoutBaseline();
    DraftCommitBinding command =
        commandUpsertBinding(fixture.target(), "0", "ISOLATED-retained-proposal-base", "look", "l");
    start(fixture, command);
    assertThatThrownBy(() -> fixture.tx(() -> fixture.sources().apply(command)))
        .hasMessageContaining("GAME_DESIGN_SOURCE_GENESIS_UNAVAILABLE");
    assertThat(fixture.sources().readGenesis(fixture.target())).isEmpty();
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_command_source_application")))
        .isZero();
    assertThat(fixture.coordinator().readVisibilityFence(fixture.target())).isEmpty();
  }

  private static GameDesignPublicationOperation retainPendingPublication(
      Fixture fixture, DraftCommitBinding selected) throws Exception {
    var seed = IsolatedPublicationOperationFixtures.fresh(fixture.target());
    return fixture.tx(
        () -> {
          String publishRequestId = "command-source-freeze-" + UUID.randomUUID();
          var selection =
              new AuthoredDraftPublishSelectionRepository(fixture.dsl(), fixture.coordinator())
                  .reserve(
                      new AuthoredDraftPublishSelection.PublishIntent(
                          fixture.target().canonicalTenantId(),
                          fixture.target().canonicalVersionId(),
                          publishRequestId,
                          "1",
                          "ISOLATED pending operation source-capture proof",
                          selected.requestId(),
                          selected.commitId(),
                          selected.digest()))
                  .selection();
          var operation =
              IsolatedPublicationOperationFixtures.forSelection(
                  AuthoredDraftPublishSelectionBinding.fromStored(
                      selection.canonicalJson(), selection.digest()),
                  seed.world());
          var attempt = new PublishAttempt();
          attempt.setTenantId(fixture.target().gameDesignVersionTenantKey());
          attempt.setPublishWorkflowId(operation.workflowId());
          attempt.setPublishType(PublishType.FULL_VERSION);
          attempt.setVersionId(fixture.target().gameDesignVersionRowId());
          Integer versionNumber =
              java.util.Objects.requireNonNull(
                      fixture
                          .dsl()
                          .fetchOne(
                              "SELECT version_number FROM version WHERE id = ?",
                              fixture.target().gameDesignVersionRowId()),
                      "retained Version fixture must have its persisted version row")
                  .get(0, Integer.class);
          attempt.setVersionNumber(versionNumber);
          attempt.setRequestDigest(selection.digest());
          new PublishAttemptRepository(fixture.dsl()).save(attempt);
          new GameDesignPublicationOperationRepository(fixture.dsl()).reserve(operation);
          return operation;
        });
  }

  private static void synchronizeSources(
      Fixture fixture,
      DraftCommitBinding binding,
      GameDesignSourceRepository.Application application) {
    fixture.tx(
        () -> {
          fixture
              .coordinator()
              .advanceVisibilityFence(
                  binding,
                  new DraftCommitCoordinatorRepository.CoordinatorProof(
                      binding, List.of(application.ownerOutcome())));
          fixture.sources().captureSynchronized(binding);
          fixture.coordinator().releaseApplicationSlot(binding);
          return null;
        });
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

  private static Long count(DSLContext dsl, String sql, Object... bindings) {
    return java.util.Objects.requireNonNull(
            dsl.fetchOne(sql, bindings), "count query must return its aggregate row")
        .get(0, Long.class);
  }

  private static DraftCommitBinding mixedUpsertBinding(TargetProof target, String baseCommitId) {
    String command = commandDefinition("look", "l");
    String policy = policyPayload("main", true);
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        baseCommitId,
        List.of(revision("0", CommandSource.upsertPayload(command)), revision("1", policy)),
        List.of(commandScope(target, "0"), policyScope(target, "0")));
  }

  private static DraftCommitBinding commandUpsertBinding(
      TargetProof target, String epoch, String baseCommitId, String commandId, String alias) {
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        baseCommitId,
        List.of(revision("0", CommandSource.upsertPayload(commandDefinition(commandId, alias)))),
        List.of(commandScope(target, epoch)));
  }

  private static DraftCommitBinding commandDeleteBinding(
      TargetProof target, String epoch, String baseCommitId) {
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        baseCommitId,
        List.of(revision("0", CommandSource.deletePayload("look"))),
        List.of(commandScope(target, epoch)));
  }

  private static DraftCommitBinding policyBinding(
      TargetProof target, String epoch, String baseCommitId, String realm, boolean production) {
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        baseCommitId,
        List.of(revision("0", policyPayload(realm, production))),
        List.of(policyScope(target, epoch)));
  }

  private static DraftCommitBinding changedCommandDigest(DraftCommitBinding binding) {
    var original = binding.revisions().getFirst();
    return DraftCommitBinding.create(
        binding.target(),
        binding.requestId(),
        binding.commitId(),
        binding.baseCommitId(),
        List.of(
            new DraftCommitBinding.RevisionPayload(
                original.revisionOrder(),
                original.revisionId(),
                original.owner(),
                CommandSource.upsertPayload(commandDefinition("look", "changed"))),
            binding.revisions().get(1)),
        binding.affectedUnits());
  }

  private static DraftCommitBinding.RevisionPayload revision(String order, String payload) {
    return new DraftCommitBinding.RevisionPayload(
        order, UUID.randomUUID(), Owner.GAME_DESIGN_CONTROL_PLANE, payload);
  }

  private static DraftCommitBinding.AffectedUnit commandScope(TargetProof target, String epoch) {
    return new DraftCommitBinding.AffectedUnit(
        Owner.GAME_DESIGN_CONTROL_PLANE,
        CommandSource.SCOPE,
        target.canonicalVersionId().toString(),
        CommandSource.SCOPE,
        CommandSource.SCOPE_ID,
        epoch);
  }

  private static DraftCommitBinding.AffectedUnit policyScope(TargetProof target, String epoch) {
    return new DraftCommitBinding.AffectedUnit(
        Owner.GAME_DESIGN_CONTROL_PLANE,
        RealmPolicySource.SCOPE,
        target.canonicalVersionId().toString(),
        RealmPolicySource.SCOPE,
        "effective",
        epoch);
  }

  private static String policyPayload(String realm, boolean production) {
    RealmEntryPolicy policy =
        RealmEntryPolicy.parse(
            "{\"schemaVersion\":1,\"worldSlug\":\"world\",\"worldDisplayName\":\"World\","
                + "\"realmSlug\":\""
                + realm
                + "\",\"realmDisplayName\":\"Realm\",\"visible\":true,"
                + "\"publicProduction\":"
                + production
                + ",\"stateScope\":\"SHARED\","
                + "\"entryPolicy\":\"PRESEEDED_ONLY\"}",
            new ObjectMapper());
    return "{\"revisionKind\":\"REALM_ENTRY_POLICY\",\"logicalRevisionId\":\"authored-"
        + realm
        + "\",\"policy\":"
        + policy.canonicalJson()
        + "}";
  }

  private static String commandDefinition(String id, String alias) {
    return "{\"schemaVersion\":1,\"commandId\":\""
        + id
        + "\",\"semanticOwner\":\"WORLD\","
        + "\"executionDiscipline\":\"DURABLE_GAMEPLAY\",\"stageRequirement\":\"GAMEPLAY\","
        + "\"promptPolicy\":\"NEVER\",\"actionCategory\":\"GAMEPLAY\","
        + "\"historyRecordable\":true,\"aliases\":[\""
        + alias
        + "\"],\"actionTags\":[\"WORLD_BROWSE\"],\"effects\":[],"
        + "\"legacyExtension\":{\"kept\":true}}";
  }

  private static Fixture fixture() {
    String schema = "gd_command_" + UUID.randomUUID().toString().replace("-", "");
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
    var transactions = new DataSourceTransactionManager(dataSource);
    var write = new TransactionTemplate(transactions);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var properties = new PostgresProperties();
    properties.setSchema(schema);
    var policies = new RealmPolicySourceRepository(dsl);
    var commands = new CommandSourceRepository(dsl);
    var coordinator = new DraftCommitCoordinatorRepository(dsl);
    TargetProof target =
        write.execute(
            status -> {
              var game = new Game();
              game.setTenantId("ISOLATED-" + UUID.randomUUID().toString().substring(0, 8));
              game.setName("ISOLATED command source");
              game = new GameRepository(dsl).save(game);
              var version = new Version();
              version.setTenantId(game.getTenantId());
              version.setVersionNumber(1);
              version = new VersionRepository(dsl, properties).save(version);
              return target(version);
            });
    return new Fixture(
        dsl, write, target, policies, commands, coordinator, new GameDesignSourceRepository(dsl));
  }

  private static Fixture retainedVersionWithoutBaseline() {
    String schema = "gd_command_legacy_" + UUID.randomUUID().toString().replace("-", "");
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
        .target("51")
        .load()
        .migrate();
    var transactions = new DataSourceTransactionManager(dataSource);
    var write = new TransactionTemplate(transactions);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    TargetProof target =
        write.execute(
            status -> {
              var game = new Game();
              game.setTenantId("ISOLATED-" + UUID.randomUUID().toString().substring(0, 8));
              game.setName("ISOLATED retained legacy source");
              game = new GameRepository(dsl).save(game);
              var source =
                  java.util.Objects.requireNonNull(
                      dsl.fetchOne(
                          "SELECT canonical_tenant_id, tenant_identity_provenance_kind "
                              + "FROM game WHERE id = ?",
                          game.getId()),
                      "retained Game fixture must have its persisted tenant identity");
              UUID canonicalVersion = UUID.randomUUID();
              var version =
                  java.util.Objects.requireNonNull(
                      dsl.fetchOne(
                          "INSERT INTO version (tenant_id, canonical_version_id, canonical_tenant_id, "
                              + "identity_source_game_row_id, identity_source_game_tenant_key, "
                              + "identity_source_provenance_kind, version_number) "
                              + "VALUES (?, ?, ?, ?, ?, ?, 1) RETURNING id",
                          game.getTenantId(),
                          canonicalVersion,
                          source.get("canonical_tenant_id", UUID.class),
                          game.getId(),
                          game.getTenantId(),
                          source.get("tenant_identity_provenance_kind", String.class)),
                      "retained Version fixture insert must return its persisted row");
              return new TargetProof(
                  source.get("canonical_tenant_id", UUID.class),
                  canonicalVersion,
                  version.get("id", Long.class),
                  game.getTenantId(),
                  game.getId(),
                  game.getTenantId(),
                  source.get("tenant_identity_provenance_kind", String.class));
            });
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
    RealmPolicySourceRepository policies = new RealmPolicySourceRepository(dsl);
    CommandSourceRepository commands = new CommandSourceRepository(dsl);
    DraftCommitCoordinatorRepository coordinator = new DraftCommitCoordinatorRepository(dsl);
    return new Fixture(
        dsl, write, target, policies, commands, coordinator, new GameDesignSourceRepository(dsl));
  }

  private static TargetProof target(Version version) {
    return new TargetProof(
        version.getCanonicalTenantId(),
        version.getCanonicalVersionId(),
        version.getId(),
        version.getTenantId(),
        version.getIdentitySourceGameRowId(),
        version.getIdentitySourceGameTenantKey(),
        version.getIdentitySourceProvenanceKind());
  }

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate write,
      TargetProof target,
      RealmPolicySourceRepository policies,
      CommandSourceRepository commands,
      DraftCommitCoordinatorRepository coordinator,
      GameDesignSourceRepository sources) {
    <T> T tx(Supplier<T> work) {
      return write.execute(status -> work.get());
    }
  }
}
