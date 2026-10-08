package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.config.PostgresProperties;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyApplication;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyGenesis;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySnapshot;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySource;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySourceRepository;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySourceService;
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
import tools.jackson.databind.ObjectMapper;

/** Real GD SQL writes. Initial typed owner baseline, Account currentness and World are ISOLATED. */
@Testcontainers(disabledWithoutDocker = true)
class RealmPolicySourcePostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void freshGenesisCreatesNoFakeCommitAndFirstActualPolicyCommitReplaysExactly() {
    var fixture = freshFixture();
    var genesis = fixture.reads().readGenesis(fixture.target()).orElseThrow();
    assertThat(fixture.tx(() -> fixture.policies().recordFreshGenesis(fixture.target())))
        .isEqualTo(genesis);
    assertThat(fixture.coordinator().readVisibilityFence(fixture.target())).isEmpty();
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_realm_policy_snapshot"))).isZero();
    var first = policyBinding(fixture.target(), "0", "main", true);
    start(fixture, first);
    var applied = fixture.tx(() -> fixture.policies().apply(first));
    assertThat(applied.genesis()).isEqualTo(genesis);
    assertThat(applied.inheritedCommitId()).isNull();
    assertThat(applied.snapshot().sourceEpoch()).isEqualTo("1");
    assertThat(fixture.tx(() -> fixture.policies().apply(first)).canonicalBytes())
        .containsExactly(applied.canonicalBytes());
    synchronize(fixture, first, applied);
    assertThat(fixture.reads().readSnapshot(fixture.target(), first.commitId()).orElseThrow())
        .isEqualTo(applied.snapshot());
  }

  @Test
  void incompleteDraftPoliciesCanSynchronizeButCannotBecomePublicationCapture() {
    var fixture = freshFixture();
    var first = policyBinding(fixture.target(), "0", "private", false);
    start(fixture, first);
    var applied = fixture.tx(() -> fixture.policies().apply(first));
    synchronize(fixture, first, applied);
    var operation = publication(fixture);
    assertThatThrownBy(() -> fixture.tx(() -> fixture.policies().freeze(operation)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Exactly one visible");
    assertThat(fixture.reads().readCapture(operation)).isEmpty();
  }

  @Test
  void retainedVersionUpdateIsNotFreshGenesisAndActualCreationRollbackLeavesNoReceipt() {
    var fixture = retainedFixture(false);
    assertThatThrownBy(
            () ->
                fixture.tx(
                    () -> {
                      fixture
                          .dsl()
                          .execute(
                              "UPDATE version SET notes = 'ISOLATED retained update' WHERE id = ?",
                              fixture.target().gameDesignVersionRowId());
                      return fixture.policies().recordFreshGenesis(fixture.target());
                    }))
        .hasMessageContaining("POLICY_FRESH_VERSION_INSERT_PROOF_UNAVAILABLE");
    var rolledBack = new java.util.concurrent.atomic.AtomicReference<TargetProof>();
    fixture
        .write()
        .executeWithoutResult(
            status -> {
              var properties = new PostgresProperties();
              var version = new Version();
              version.setTenantId(fixture.target().gameDesignVersionTenantKey());
              version.setVersionNumber(2);
              version = new VersionRepository(fixture.dsl()).save(version);
              var target =
                  new TargetProof(
                      version.getCanonicalTenantId(),
                      version.getCanonicalVersionId(),
                      version.getId(),
                      version.getTenantId(),
                      version.getIdentitySourceGameRowId(),
                      version.getIdentitySourceGameTenantKey(),
                      version.getIdentitySourceProvenanceKind());
              rolledBack.set(target);
              RealmPolicyGenesis receipt = fixture.policies().recordFreshGenesis(target);
              assertThat(fixture.policies().recordFreshGenesis(target)).isEqualTo(receipt);
              status.setRollbackOnly();
            });
    assertThat(fixture.reads().readGenesis(rolledBack.get())).isEmpty();
    assertThat(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT id FROM version WHERE id = ?",
                    rolledBack.get().gameDesignVersionRowId()))
        .isNull();
  }

  @Test
  void isolatedSiblingSourceComposesOneOwnerResultAndCommandOnlyFenceInheritsPolicy() {
    var fixture = freshFixture();
    var policy = policyBinding(fixture.target(), "0", "main", true);
    var commandRevision =
        new DraftCommitBinding.RevisionPayload(
            "1",
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            "{\"revisionKind\":\"COMMAND_DEFINITION\",\"schemaVersion\":1,\"operation\":\"DELETE\",\"commandId\":\"ISOLATED-command\"}");
    var commandScope =
        new DraftCommitBinding.AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            "COMMAND_DEFINITION_SET",
            fixture.target().canonicalVersionId().toString(),
            "COMMAND_DEFINITION_SET",
            "effective",
            "0");
    var mixed =
        DraftCommitBinding.create(
            policy.target(),
            policy.requestId(),
            policy.commitId(),
            policy.baseCommitId(),
            List.of(policy.revisions().getFirst(), commandRevision),
            List.of(commandScope, policy.affectedUnits().getFirst()));
    start(fixture, mixed);
    assertThatThrownBy(() -> fixture.tx(() -> fixture.policies().apply(mixed)))
        .hasMessageContaining("Mixed Game Design commit");
    fixture.tx(
        () -> {
          var mutation = fixture.policies().applyMutation(mixed);
          // ISOLATED assumption: the sibling command owner store has applied/read back these bytes.
          // This fixture proves only policy's one-result composition, not the actual command
          // writer.
          byte[] sibling =
              "ISOLATED authenticated command application".getBytes(StandardCharsets.UTF_8);
          var epochs =
              List.of(
                  new DraftCommitCoordinatorRepository.AppliedEpoch(
                      "COMMAND_DEFINITION_SET",
                      fixture.target().canonicalVersionId().toString(),
                      "COMMAND_DEFINITION_SET",
                      "effective",
                      "0",
                      "1"),
                  mutation.ownerOutcome().appliedEpochs().getFirst());
          fixture.policies().recordCombinedOwnerOutcome(mixed, sibling, epochs);
          var outcome =
              fixture
                  .coordinator()
                  .read(fixture.target(), mixed.requestId())
                  .orElseThrow()
                  .ownerStates()
                  .get(Owner.GAME_DESIGN_CONTROL_PLANE)
                  .outcome()
                  .orElseThrow();
          fixture
              .coordinator()
              .advanceVisibilityFence(
                  mixed,
                  new DraftCommitCoordinatorRepository.CoordinatorProof(mixed, List.of(outcome)));
          fixture.policies().captureSynchronized(mixed);
          fixture.coordinator().releaseApplicationSlot(mixed);
          return null;
        });
    var prior = fixture.reads().readSnapshot(fixture.target(), mixed.commitId()).orElseThrow();
    var commandOnly =
        DraftCommitBinding.create(
            fixture.target(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            mixed.commitId().toString(),
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    commandRevision.payload())),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    "COMMAND_DEFINITION_SET",
                    fixture.target().canonicalVersionId().toString(),
                    "COMMAND_DEFINITION_SET",
                    "effective",
                    "1")));
    start(fixture, commandOnly);
    fixture.tx(
        () -> {
          var outcome =
              new DraftCommitCoordinatorRepository.OwnerOutcome(
                  Owner.GAME_DESIGN_CONTROL_PLANE,
                  DraftCommitCoordinatorRepository.OwnerStatus.APPLIED,
                  commandOnly.commitId(),
                  commandOnly.digest(),
                  "ISOLATED-command-only",
                  "ISOLATED command owner readback".getBytes(StandardCharsets.UTF_8),
                  List.of(
                      new DraftCommitCoordinatorRepository.AppliedEpoch(
                          "COMMAND_DEFINITION_SET",
                          fixture.target().canonicalVersionId().toString(),
                          "COMMAND_DEFINITION_SET",
                          "effective",
                          "1",
                          "2")));
          fixture.coordinator().recordOwnerOutcome(commandOnly, outcome);
          fixture
              .coordinator()
              .advanceVisibilityFence(
                  commandOnly,
                  new DraftCommitCoordinatorRepository.CoordinatorProof(
                      commandOnly, List.of(outcome)));
          var inherited = fixture.policies().captureSynchronized(commandOnly);
          assertThat(inherited.sourceEpoch()).isEqualTo(prior.sourceEpoch());
          assertThat(inherited.policies()).containsExactlyElementsOf(prior.policies());
          fixture.coordinator().releaseApplicationSlot(commandOnly);
          return null;
        });
  }

  @Test
  void absentBaselineDeniesAndApplicationReplayAndMismatchBindActualCoordinatorResult() {
    var fixture = retainedFixture(false);
    var binding = policyBinding(fixture.target(), "0", "main", true);
    start(fixture, binding);
    assertThatThrownBy(() -> fixture.tx(() -> fixture.policies().apply(binding)))
        .hasMessageContaining("POLICY_SOURCE_BASELINE_UNAVAILABLE");
    var real = retainedFixture(true);
    var mutation = policyBinding(real.target(), "1", "private", false);
    start(real, mutation);
    var result = real.tx(() -> real.policies().apply(mutation));
    assertThat(real.tx(() -> real.policies().apply(mutation)).canonicalBytes())
        .containsExactly(result.canonicalBytes());
    var changed =
        DraftCommitBinding.create(
            mutation.target(),
            mutation.requestId(),
            mutation.commitId(),
            mutation.baseCommitId(),
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    mutation.revisions().getFirst().revisionId(),
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    mutation.revisions().getFirst().payload().replace("private", "changed"))),
            mutation.affectedUnits());
    assertThatThrownBy(() -> real.tx(() -> real.policies().apply(changed)))
        .hasMessageContaining("POLICY_COMMIT_BINDING_CONFLICT");
    assertThat(
            real.coordinator()
                .read(real.target(), mutation.requestId())
                .orElseThrow()
                .ownerStates()
                .get(Owner.GAME_DESIGN_CONTROL_PLANE)
                .outcome())
        .contains(result.ownerOutcome());
  }

  @Test
  void rollbackAndPartialWritesPreserveLastVisibleSourceThenDisjointCommitInheritsExactProvenance()
      throws Exception {
    var fixture = retainedFixture(true);
    var mutation = policyBinding(fixture.target(), "1", "private", false);
    start(fixture, mutation);
    fixture
        .write()
        .executeWithoutResult(
            status -> {
              fixture.policies().apply(mutation);
              status.setRollbackOnly();
            });
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_realm_policy_application")))
        .isZero();
    var result = fixture.tx(() -> fixture.policies().apply(mutation));
    assertThat(
            fixture
                .reads()
                .readSnapshot(fixture.target(), fixture.baseline().commitId())
                .orElseThrow()
                .policies())
        .hasSize(1);
    assertThat(fixture.reads().readSnapshot(fixture.target(), mutation.commitId())).isEmpty();
    synchronize(fixture, mutation, result);
    var operation = publication(fixture);
    var inherited =
        fixture
            .reads()
            .readSnapshot(
                fixture.target(),
                operation.account().input().selection().selectedCommit().commitId())
            .orElseThrow();
    assertThat(inherited.policies()).containsExactlyElementsOf(result.snapshot().policies());
    var capture = fixture.tx(() -> fixture.policies().freeze(operation));
    assertThat(fixture.tx(() -> fixture.policies().freeze(operation)).canonicalBytes())
        .containsExactly(capture.canonicalBytes());
    assertThat(fixture.reads().readCapture(operation).orElseThrow().canonicalBytes())
        .containsExactly(capture.canonicalBytes());
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
    assertThatThrownBy(
            () ->
                fixture.tx(
                    () ->
                        fixture
                            .dsl()
                            .execute(
                                "UPDATE game_design_realm_policy_snapshot SET snapshot_json = snapshot_json")))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void freezeAndSourceWriterUseSameVersionLockAndRollbackDoesNotLeaveCapture() throws Exception {
    var fixture = retainedFixture(true);
    var operation = publication(fixture);
    fixture
        .write()
        .executeWithoutResult(
            status -> {
              fixture.policies().freeze(operation);
              status.setRollbackOnly();
            });
    assertThat(fixture.reads().readCapture(operation)).isEmpty();
    var frozen = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var attempting = new CountDownLatch(1);
    var freezerPid = new java.util.concurrent.atomic.AtomicInteger();
    var writerPid = new java.util.concurrent.atomic.AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var freezer =
          executor.submit(
              () ->
                  fixture.tx(
                      () -> {
                        var result = fixture.policies().freeze(operation);
                        freezerPid.set(
                            java.util.Objects.requireNonNull(
                                    fixture.dsl().fetchOne("SELECT pg_backend_pid()"),
                                    "freezer transaction must expose its PostgreSQL backend")
                                .get(0, Integer.class));
                        frozen.countDown();
                        await(release);
                        return result;
                      }));
      assertThat(frozen.await(10, TimeUnit.SECONDS)).isTrue();
      var writer =
          executor.submit(
              () ->
                  fixture.tx(
                      () -> {
                        writerPid.set(
                            java.util.Objects.requireNonNull(
                                    fixture.dsl().fetchOne("SELECT pg_backend_pid()"),
                                    "writer transaction must expose its PostgreSQL backend")
                                .get(0, Integer.class));
                        attempting.countDown();
                        // Acquire the exact Version lock used by the repository's source writer
                        // before exercising the storage guard, independently of creator preflight.
                        fixture
                            .dsl()
                            .fetchOne(
                                "SELECT id FROM version WHERE canonical_tenant_id = ? AND canonical_version_id = ? FOR UPDATE",
                                fixture.target().canonicalTenantId(),
                                fixture.target().canonicalVersionId());
                        return fixture
                            .dsl()
                            .execute(
                                "UPDATE game_design_realm_policy_source SET source_epoch = '2' WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                                fixture.target().canonicalTenantId(),
                                fixture.target().canonicalVersionId());
                      }));
      assertThat(attempting.await(10, TimeUnit.SECONDS)).isTrue();
      boolean observedVersionBlock = false;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < deadline) {
        var activity =
            fixture
                .dsl()
                .fetchOne(
                    "SELECT ? = ANY(pg_blocking_pids(pid)) AND wait_event_type = 'Lock' "
                        + "AND query LIKE 'SELECT id FROM version%FOR UPDATE%' AS blocked "
                        + "FROM pg_stat_activity WHERE pid = ?",
                    freezerPid.get(), writerPid.get());
        if (activity != null && Boolean.TRUE.equals(activity.get("blocked", Boolean.class))) {
          observedVersionBlock = true;
          break;
        }
        Thread.sleep(25);
      }
      assertThat(observedVersionBlock)
          .as("writer backend blocked by freezer on the exact Version SELECT FOR UPDATE")
          .isTrue();
      release.countDown();
      assertThat(freezer.get(10, TimeUnit.SECONDS).snapshot().policies()).hasSize(1);
      assertThatThrownBy(() -> writer.get(10, TimeUnit.SECONDS))
          .isInstanceOf(java.util.concurrent.ExecutionException.class)
          .rootCause()
          .hasMessageContaining("realm policy source is frozen");
    } finally {
      release.countDown();
    }
    assertThat(
            java.util.Objects.requireNonNull(
                    fixture
                        .dsl()
                        .fetchOne("SELECT source_epoch FROM game_design_realm_policy_source"),
                    "retained Version fixture must have its realm-policy source row")
                .get(0, String.class))
        .isEqualTo("1");
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS))
        throw new IllegalStateException("ISOLATED fixture latch timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
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
      Fixture fixture, DraftCommitBinding binding, RealmPolicyApplication result) {
    fixture.tx(
        () -> {
          fixture
              .coordinator()
              .advanceVisibilityFence(
                  binding,
                  new DraftCommitCoordinatorRepository.CoordinatorProof(
                      binding, List.of(result.ownerOutcome())));
          fixture.policies().captureSynchronized(binding);
          fixture.coordinator().releaseApplicationSlot(binding);
          return null;
        });
  }

  private static GameDesignPublicationOperation publication(Fixture fixture) {
    return fixture.tx(
        () -> {
          try {
            // Existing fixture stipulates actual World and Account producer bytes; it runs GD's
            // real
            // disjoint World commit, selection, attempt and operation repositories. Capture
            // integration
            // here is isolated in the same transaction, not a claim of normal producer composition.
            var operation =
                IsolatedPublicationOwnerSetup.retain(fixture.dsl(), fixture.target(), 1);
            fixture
                .policies()
                .captureSynchronized(operation.account().input().selection().selectedCommit());
            return operation;
          } catch (Exception failure) {
            throw new IllegalStateException(failure);
          }
        });
  }

  private static DraftCommitBinding policyBinding(
      TargetProof target, String epoch, String realm, boolean production) {
    var policy =
        RealmEntryPolicy.parse(
            "{\"schemaVersion\":1,\"worldSlug\":\"world\",\"worldDisplayName\":\"World\","
                + "\"realmSlug\":\""
                + realm
                + "\",\"realmDisplayName\":\"Realm\",\"visible\":true,\"publicProduction\":"
                + production
                + ",\"stateScope\":\"SHARED\",\"entryPolicy\":\"PRESEEDED_ONLY\"}",
            new ObjectMapper());
    String payload =
        "{\"revisionKind\":\"REALM_ENTRY_POLICY\",\"logicalRevisionId\":\"authored-"
            + realm
            + "\",\"policy\":"
            + policy.canonicalJson()
            + "}";
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "ISOLATED-explicit-base",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0", UUID.randomUUID(), Owner.GAME_DESIGN_CONTROL_PLANE, payload)),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                RealmPolicySource.SCOPE,
                target.canonicalVersionId().toString(),
                RealmPolicySource.SCOPE,
                "effective",
                epoch)));
  }

  private static Fixture freshFixture() {
    String schema = "gd_policy_" + UUID.randomUUID().toString().replace("-", "");
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
    var target =
        write.execute(
            status -> {
              var game = new Game();
              game.setTenantId("ISOLATED-" + UUID.randomUUID().toString().substring(0, 8));
              game.setName("ISOLATED policy source");
              game = new GameRepository(dsl).save(game);
              var version = new Version();
              version.setTenantId(game.getTenantId());
              version.setVersionNumber(1);
              version = new VersionRepository(dsl).save(version);
              return target(version);
            });
    var baseline = policyBinding(target, "0", "main", true);
    var policies = new RealmPolicySourceRepository(dsl);
    var coordinator = new DraftCommitCoordinatorRepository(dsl);
    return new Fixture(
        dsl,
        write,
        target,
        baseline,
        policies,
        coordinator,
        new RealmPolicySourceService(policies, transactions));
  }

  private static Fixture retainedFixture(boolean isolatedBaseline) {
    String schema = "gd_policy_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    dataSource.setSchema(schema);
    migrate(dataSource, schema, "51");
    var transactions = new DataSourceTransactionManager(dataSource);
    var write = new TransactionTemplate(transactions);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var properties = new PostgresProperties();
    properties.setSchema(schema);
    var game =
        write.execute(
            status -> {
              var created = new Game();
              created.setTenantId("ISOLATED-" + UUID.randomUUID().toString().substring(0, 8));
              created.setName("ISOLATED retained policy source");
              return new GameRepository(dsl).save(created);
            });
    var version = write.execute(status -> insertRetainedVersion(dsl, properties, game, 1));
    migrate(dataSource, schema, "54");
    var target = target(version);
    var baseline = policyBinding(target, "0", "main", true);
    var policies = new RealmPolicySourceRepository(dsl);
    var coordinator = new DraftCommitCoordinatorRepository(dsl);
    var fixture =
        new Fixture(
            dsl,
            write,
            target,
            baseline,
            policies,
            coordinator,
            new RealmPolicySourceService(policies, transactions));
    if (isolatedBaseline) {
      fixture.tx(
          () -> {
            // Explicit ISOLATED assumption: prior authenticated owner creation supplied this actual
            // complete policy source/epoch. There is intentionally no production initializer API.
            coordinator.claim(baseline);
            coordinator.claimApplicationSlot(baseline);
            coordinator.markOwnerInProgress(baseline, Owner.GAME_DESIGN_CONTROL_PLANE);
            var outcome =
                new DraftCommitCoordinatorRepository.OwnerOutcome(
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    DraftCommitCoordinatorRepository.OwnerStatus.APPLIED,
                    baseline.commitId(),
                    baseline.digest(),
                    "ISOLATED-initial-owner-baseline",
                    "ISOLATED-authenticated-baseline".getBytes(StandardCharsets.UTF_8),
                    List.of(
                        new DraftCommitCoordinatorRepository.AppliedEpoch(
                            RealmPolicySource.SCOPE,
                            target.canonicalVersionId().toString(),
                            RealmPolicySource.SCOPE,
                            "effective",
                            "0",
                            BigInteger.ONE.toString())));
            coordinator.recordOwnerOutcome(baseline, outcome);
            coordinator.advanceVisibilityFence(
                baseline,
                new DraftCommitCoordinatorRepository.CoordinatorProof(baseline, List.of(outcome)));
            coordinator.releaseApplicationSlot(baseline);
            var policy = RealmPolicySource.revision(baseline, baseline.revisions().getFirst());
            var snapshot = new RealmPolicySnapshot(baseline, "1", List.of(policy));
            dsl.execute(
                "INSERT INTO game_design_realm_policy_snapshot (canonical_tenant_id, canonical_version_id, commit_id, request_id, snapshot_json) VALUES (?, ?, ?, ?, ?)",
                target.canonicalTenantId(),
                target.canonicalVersionId(),
                baseline.commitId(),
                baseline.requestId(),
                snapshot.canonicalJson());
            dsl.execute(
                "INSERT INTO game_design_realm_policy_source (canonical_tenant_id, canonical_version_id, source_epoch, visible_commit_id) VALUES (?, ?, '1', ?)",
                target.canonicalTenantId(),
                target.canonicalVersionId(),
                baseline.commitId());
            return null;
          });
    }
    return fixture;
  }

  private static void migrate(DriverManagerDataSource dataSource, String schema, String target) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .table("flyway_schema_history_game_design_service")
        .target(target)
        .load()
        .migrate();
  }

  private static Version insertRetainedVersion(
      DSLContext dsl, PostgresProperties properties, Game game, int versionNumber) {
    var inserted =
        dsl.fetchOne(
            "INSERT INTO version (tenant_id, canonical_version_id, canonical_tenant_id, "
                + "identity_source_game_row_id, identity_source_game_tenant_key, "
                + "identity_source_provenance_kind, version_number, version_state, version_state_epoch, "
                + "script_patch_version, base_version_id, is_script_only, notes, created_at, updated_at) "
                + "SELECT g.tenant_id, ?, g.canonical_tenant_id, g.id, g.tenant_id, "
                + "g.tenant_identity_provenance_kind, ?, 'DRAFT', 1, NULL, NULL, FALSE, "
                + "'ISOLATED retained source fixture', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
                + "FROM game g WHERE g.id = ? RETURNING id",
            UUID.randomUUID(),
            versionNumber,
            game.getId());
    if (inserted == null) {
      throw new IllegalStateException("ISOLATED retained Game Design source row is absent");
    }
    return new VersionRepository(dsl).findById(inserted.get("id", Long.class)).orElseThrow();
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
      DraftCommitBinding baseline,
      RealmPolicySourceRepository policies,
      DraftCommitCoordinatorRepository coordinator,
      RealmPolicySourceService reads) {
    <T> T tx(Supplier<T> work) {
      return write.execute(status -> work.get());
    }
  }
}
