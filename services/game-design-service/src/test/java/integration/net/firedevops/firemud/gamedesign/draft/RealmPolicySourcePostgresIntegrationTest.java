package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.firedevops.firemud.common.authoring.DraftBaseReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.config.PostgresProperties;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.publication.CommandSource;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyApplication;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyGenesis;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySnapshot;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySource;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySourceRepository;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySourceService;
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
import tools.jackson.databind.ObjectMapper;

/** Real Game Design source writes; remote Account and World authority remain ISOLATED. */
@Testcontainers(disabledWithoutDocker = true)
class RealmPolicySourcePostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void freshGenesisCreatesNoFakeCommitAndFirstActualPolicyCommitReplaysExactly() {
    var fixture = freshFixture();
    var genesis = fixture.sources().readGenesis(fixture.target()).orElseThrow();
    assertThat(genesis.policy().receiptId()).isEqualTo(genesis.command().receiptId());
    assertThat(genesis.policy().creationTransactionId())
        .isEqualTo(genesis.command().creationTransactionId());
    assertThat(fixture.reads().readGenesis(fixture.target())).contains(genesis.policy());
    assertThat(fixture.coordinator().readVisibilityFence(fixture.target())).isEmpty();
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_realm_policy_snapshot"))).isZero();
    var first = policyBinding(fixture, "main", true);
    start(fixture, first);
    var applied = fixture.tx(() -> fixture.sources().apply(first).policy().orElseThrow());
    assertThat(applied.genesis()).isEqualTo(genesis.policy());
    assertThat(applied.inheritedCommitId()).isNull();
    assertThat(applied.snapshot().sourceEpoch()).isEqualTo("1");
    assertThat(
            fixture.tx(
                () -> fixture.sources().apply(first).policy().orElseThrow().canonicalBytes()))
        .containsExactly(applied.canonicalBytes());
    synchronize(fixture, first, applied);
    assertThat(fixture.reads().readSnapshot(fixture.target(), first.commitId()).orElseThrow())
        .isEqualTo(applied.snapshot());
  }

  @Test
  void incompleteDraftPoliciesCanSynchronizeButCannotBecomePublicationCapture() {
    var fixture = freshFixture();
    var first = policyBinding(fixture, "private", false);
    start(fixture, first);
    var applied = fixture.tx(() -> fixture.sources().apply(first).policy().orElseThrow());
    synchronize(fixture, first, applied);
    var operation = publication(fixture);
    assertThatThrownBy(() -> fixture.tx(() -> fixture.policies().freeze(operation)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Exactly one visible");
    assertThat(fixture.reads().readCapture(operation)).isEmpty();
  }

  @Test
  void explicitDeleteMovesRealmAndRetainsDeleteIdentityInTheCompleteStoredBinding() {
    var fixture = policyFixture();
    var side = policyBinding(fixture, "side", false);
    start(fixture, side);
    var sideResult = fixture.tx(() -> fixture.sources().apply(side).policy().orElseThrow());
    synchronize(fixture, side, sideResult);

    UUID deleteRevisionId = UUID.randomUUID();
    var move =
        policyMutationBinding(
            fixture,
            List.of(
                policyDeleteRevision("0", deleteRevisionId, "authored-main", "world", "main"),
                policyUpsertRevision(
                    "1", UUID.randomUUID(), "authored-main", "new-world", "main", true)));
    start(fixture, move);
    var applied = fixture.tx(() -> fixture.sources().apply(move).policy().orElseThrow());
    assertThat(applied.snapshot().sourceEpoch()).isEqualTo("3");
    assertThat(applied.snapshot().policies())
        .extracting(policy -> policy.policy().worldSlug() + "/" + policy.policy().realmSlug())
        .containsExactly("new-world/main", "world/side");
    assertThat(
            fixture.tx(() -> fixture.sources().apply(move).policy().orElseThrow().canonicalBytes()))
        .containsExactly(applied.canonicalBytes());

    var stored =
        java.util.Objects.requireNonNull(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT c.binding_json, a.request_id, a.snapshot_json "
                        + "FROM game_design_draft_commit c "
                        + "JOIN game_design_realm_policy_application a "
                        + "ON a.canonical_tenant_id = c.canonical_tenant_id "
                        + "AND a.canonical_version_id = c.canonical_version_id "
                        + "AND a.request_id = c.request_id AND a.commit_id = c.commit_id "
                        + "WHERE c.canonical_tenant_id = ? AND c.canonical_version_id = ? AND c.commit_id = ?",
                    move.target().canonicalTenantId(),
                    move.target().canonicalVersionId(),
                    move.commitId()),
            "explicit deletion must retain its complete synchronized owner binding");
    assertThat(stored.get("binding_json", String.class)).isEqualTo(move.canonicalJson());
    assertThat(stored.get("request_id", UUID.class)).isEqualTo(move.requestId());
    assertThat(stored.get("snapshot_json", String.class))
        .isEqualTo(applied.snapshot().canonicalJson());

    synchronize(fixture, move, applied);
    var reusedDeleteIdentity =
        policyMutationBinding(
            fixture,
            List.of(
                policyUpsertRevision(
                    "0",
                    deleteRevisionId,
                    "authored-reused-delete",
                    "other-world",
                    "other",
                    false)));
    start(fixture, reusedDeleteIdentity);
    assertThatThrownBy(() -> fixture.tx(() -> fixture.policies().apply(reusedDeleteIdentity)))
        .hasMessageContaining("Authored policy revision identity cannot be reused");
    assertThat(
            java.util.Objects.requireNonNull(
                    fixture
                        .dsl()
                        .fetchOne(
                            "SELECT source_epoch FROM game_design_realm_policy_source "
                                + "WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                            fixture.target().canonicalTenantId(),
                            fixture.target().canonicalVersionId()),
                    "retained policy source head must remain present after rejected reuse")
                .get(0, String.class))
        .isEqualTo("3");
  }

  @Test
  void explicitDeleteRejectsAnAbsentSelectorWithoutAdvancingThePolicySource() {
    var fixture = policyFixture();
    var missingDelete =
        policyMutationBinding(
            fixture,
            List.of(
                policyDeleteRevision(
                    "0", UUID.randomUUID(), "authored-delete-missing", "world", "missing")));
    start(fixture, missingDelete);

    assertThatThrownBy(() -> fixture.tx(() -> fixture.policies().apply(missingDelete)))
        .hasMessageContaining("Cannot delete an absent policy selector");
    assertThat(
            java.util.Objects.requireNonNull(
                    fixture
                        .dsl()
                        .fetchOne("SELECT source_epoch FROM game_design_realm_policy_source"),
                    "retained policy source head must remain present after absent deletion")
                .get(0, String.class))
        .isEqualTo("1");
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_realm_policy_application")))
        .isEqualTo(1);
  }

  @Test
  void retainedVersionUpdateIsNotFreshGenesisAndActualCreationRollbackLeavesNoReceipt() {
    var fixture = retainedFixture();
    migrateLatest(fixture);
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
  void sourceComposesOneOwnerResultAndCommandOnlyFenceInheritsPolicy() {
    var fixture = freshFixture();
    var policy = policyBinding(fixture, "main", true);
    var commandRevision =
        new DraftCommitBinding.RevisionPayload(
            "1",
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            CommandSource.deletePayload("ISOLATED-command"));
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
    var mixedApplication = fixture.tx(() -> fixture.sources().apply(mixed));
    assertThat(mixedApplication.command()).isPresent();
    assertThat(mixedApplication.policy()).isPresent();
    assertThat(mixedApplication.ownerOutcome().resultBytes())
        .contains(mixedApplication.command().orElseThrow().canonicalBytes());
    synchronize(fixture, mixed, mixedApplication);
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
    var commandApplication = fixture.tx(() -> fixture.sources().apply(commandOnly));
    assertThat(commandApplication.command()).isPresent();
    assertThat(commandApplication.policy()).isEmpty();
    synchronize(fixture, commandOnly, commandApplication);
    var inherited =
        fixture
            .sources()
            .readSynchronized(fixture.target(), commandOnly.commitId())
            .orElseThrow()
            .policy();
    assertThat(inherited.sourceEpoch()).isEqualTo(prior.sourceEpoch());
    assertThat(inherited.policies()).containsExactlyElementsOf(prior.policies());
  }

  @Test
  void sourceHeadRejectsUnprovenTransitionsAndDisjointCaptureInheritsExactPredecessor() {
    var fixture = freshFixture();
    var first = policyBinding(fixture, "main", true);
    start(fixture, first);
    var firstApplication = fixture.tx(() -> fixture.sources().apply(first).policy().orElseThrow());
    synchronize(fixture, first, firstApplication);

    var second = policyBinding(fixture, "private", false);
    start(fixture, second);
    var secondApplication =
        fixture.tx(() -> fixture.sources().apply(second).policy().orElseThrow());
    synchronize(fixture, second, secondApplication);
    var current = fixture.reads().readSnapshot(fixture.target(), second.commitId()).orElseThrow();
    assertThat(current.policies()).hasSize(2);

    assertThatThrownBy(
            () ->
                fixture.tx(
                    () ->
                        fixture
                            .dsl()
                            .execute(
                                "UPDATE game_design_realm_policy_source SET source_epoch = '1', visible_commit_id = ? WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                                first.commitId(),
                                fixture.target().canonicalTenantId(),
                                fixture.target().canonicalVersionId())))
        .hasMessageContaining(
            "realm policy source epoch must advance through its exact owner application");
    assertThatThrownBy(
            () ->
                fixture.tx(
                    () ->
                        fixture
                            .dsl()
                            .execute(
                                "UPDATE game_design_realm_policy_source SET source_epoch = '1' WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                                fixture.target().canonicalTenantId(),
                                fixture.target().canonicalVersionId())))
        .hasMessageContaining(
            "realm policy source epoch must advance through its exact owner application");
    assertThatThrownBy(
            () ->
                fixture.tx(
                    () ->
                        fixture
                            .dsl()
                            .execute(
                                "UPDATE game_design_realm_policy_source SET source_epoch = '4' WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                                fixture.target().canonicalTenantId(),
                                fixture.target().canonicalVersionId())))
        .hasMessageContaining(
            "realm policy source epoch must advance through its exact owner application");
    assertThatThrownBy(
            () ->
                fixture.tx(
                    () ->
                        fixture
                            .dsl()
                            .execute(
                                "UPDATE game_design_realm_policy_source SET visible_commit_id = ? WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                                first.commitId(),
                                fixture.target().canonicalTenantId(),
                                fixture.target().canonicalVersionId())))
        .hasMessageContaining(
            "realm policy source pointer must advance to its exact current synchronized snapshot");

    var disjoint = commandOnlyBinding(fixture, second.commitId(), "0");
    start(fixture, disjoint);
    var disjointApplication = fixture.tx(() -> fixture.sources().apply(disjoint));
    synchronize(fixture, disjoint, disjointApplication);
    var inherited =
        fixture
            .sources()
            .readSynchronized(fixture.target(), disjoint.commitId())
            .orElseThrow()
            .policy();
    assertThat(inherited.sourceEpoch()).isEqualTo(current.sourceEpoch());
    assertThat(inherited.policies()).containsExactlyElementsOf(current.policies());
    var snapshotRow =
        fixture
            .dsl()
            .fetchOne(
                "SELECT inherited_commit_id FROM game_design_realm_policy_snapshot WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ?",
                fixture.target().canonicalTenantId(),
                fixture.target().canonicalVersionId(),
                disjoint.commitId());
    assertThat(snapshotRow).isNotNull();
    assertThat(snapshotRow.get("inherited_commit_id", UUID.class)).isEqualTo(second.commitId());
  }

  @Test
  void retainedHistoricalSourceHeadInsertRequiresItsVisibleSnapshotEpoch() {
    var fixture = retainedFixture();
    // Preserve the legacy row set at V50.13, then carry it through the current migrations.
    var snapshot = seedRetainedSnapshot(fixture, "1");
    migrateLatest(fixture);

    assertThatThrownBy(
            () ->
                fixture.tx(
                    () ->
                        fixture
                            .dsl()
                            .execute(
                                "INSERT INTO game_design_realm_policy_source (canonical_tenant_id, canonical_version_id, source_epoch, visible_commit_id) VALUES (?, ?, '2', ?)",
                                fixture.target().canonicalTenantId(),
                                fixture.target().canonicalVersionId(),
                                fixture.baseline().commitId())))
        .hasMessageContaining("realm policy source head must match its visible snapshot epoch");
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_realm_policy_source"))).isZero();
    assertThat(
            fixture
                .reads()
                .readSnapshot(fixture.target(), fixture.baseline().commitId())
                .orElseThrow())
        .isEqualTo(snapshot);

    fixture.tx(
        () -> {
          fixture
              .dsl()
              .execute(
                  "INSERT INTO game_design_realm_policy_source (canonical_tenant_id, canonical_version_id, source_epoch, visible_commit_id) VALUES (?, ?, '1', ?)",
                  fixture.target().canonicalTenantId(),
                  fixture.target().canonicalVersionId(),
                  fixture.baseline().commitId());
          return null;
        });
    var sourceHead =
        java.util.Objects.requireNonNull(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT source_epoch FROM game_design_realm_policy_source WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                    fixture.target().canonicalTenantId(),
                    fixture.target().canonicalVersionId()),
            "valid source-head insert should persist a row");
    assertThat(sourceHead.get("source_epoch", String.class)).isEqualTo("1");
    assertThat(fixture.reads().readSnapshot(fixture.target(), fixture.baseline().commitId()))
        .contains(snapshot);
  }

  @Test
  void visibilityPointerCannotRewindDeleteOrTruncateAndExactFenceRetryRemainsStable() {
    var fixture = freshFixture();
    var first = policyBinding(fixture, "main", true);
    start(fixture, first);
    var firstApplication = fixture.tx(() -> fixture.sources().apply(first).policy().orElseThrow());
    synchronize(fixture, first, firstApplication);

    var second = policyBinding(fixture, "private", false);
    start(fixture, second);
    var secondApplication =
        fixture.tx(() -> fixture.sources().apply(second).policy().orElseThrow());
    synchronize(fixture, second, secondApplication);

    var replayedFence =
        fixture.tx(
            () ->
                fixture
                    .coordinator()
                    .advanceSourceVisibilityFence(
                        second,
                        new DraftCommitCoordinatorRepository.CoordinatorProof(
                            second, List.of(secondApplication.ownerOutcome()))));
    assertThat(replayedFence.commitId()).isEqualTo(second.commitId());
    assertThat(visiblePointerCommit(fixture)).isEqualTo(second.commitId());

    assertThatThrownBy(
            () ->
                fixture.tx(
                    () ->
                        fixture
                            .dsl()
                            .execute(
                                "UPDATE game_design_draft_commit_visibility SET request_id = ?, commit_id = ?, input_digest = ? WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                                first.requestId(),
                                first.commitId(),
                                first.digest(),
                                fixture.target().canonicalTenantId(),
                                fixture.target().canonicalVersionId())))
        .hasMessageContaining("may advance only to its actively applying commit");
    assertThat(visiblePointerCommit(fixture)).isEqualTo(second.commitId());

    assertThatThrownBy(
            () ->
                fixture.tx(
                    () ->
                        fixture
                            .dsl()
                            .execute(
                                "DELETE FROM game_design_draft_commit_visibility WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                                fixture.target().canonicalTenantId(),
                                fixture.target().canonicalVersionId())))
        .hasMessageContaining("Draft visibility pointers cannot be deleted");
    assertThat(visiblePointerCommit(fixture)).isEqualTo(second.commitId());

    assertThatThrownBy(
            () ->
                fixture.tx(
                    () -> fixture.dsl().execute("TRUNCATE game_design_draft_commit_visibility")))
        .hasMessageContaining("Draft visibility pointers cannot be truncated");
    assertThat(visiblePointerCommit(fixture)).isEqualTo(second.commitId());
  }

  @Test
  void absentBaselineDeniesAndApplicationReplayAndMismatchBindActualCoordinatorResult() {
    var fixture = retainedFixture();
    migrateLatest(fixture);
    var binding = historicalPolicyBinding(fixture.target(), "0", "main", true);
    startUnreviewed(fixture, binding);
    assertThatThrownBy(() -> fixture.tx(() -> fixture.policies().apply(binding)))
        .hasMessageContaining("POLICY_SOURCE_BASELINE_UNAVAILABLE");
    var real = policyFixture();
    var mutation = policyBinding(real, "private", false);
    start(real, mutation);
    var result = real.tx(() -> real.sources().apply(mutation).policy().orElseThrow());
    assertThat(
            real.tx(() -> real.sources().apply(mutation).policy().orElseThrow().canonicalBytes()))
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
    var fixture = policyFixture();
    var mutation = policyBinding(fixture, "private", false);
    start(fixture, mutation);
    fixture
        .write()
        .executeWithoutResult(
            status -> {
              fixture.policies().apply(mutation);
              status.setRollbackOnly();
            });
    assertThat(
            fixture
                .dsl()
                .fetchCount(
                    DSL.table("game_design_realm_policy_application"),
                    DSL.field("commit_id").eq(mutation.commitId())))
        .isZero();
    assertThat(fixture.dsl().fetchCount(DSL.table("game_design_realm_policy_application"))).isOne();
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
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("realm policy history is immutable");
  }

  @Test
  void freezeAndSourceWriterUseSameVersionLockAndRollbackDoesNotLeaveCapture() throws Exception {
    var fixture = policyFixture();
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
          var reviewed =
              new GameDesignReviewedBaseRepository(fixture.dsl())
                  .resolve(binding.target(), DraftBaseReference.parse(binding.baseCommitId()));
          fixture.sources().claimReviewed(binding, reviewed);
          fixture.coordinator().claimApplicationSlot(binding);
          fixture.coordinator().markOwnerInProgress(binding, Owner.GAME_DESIGN_CONTROL_PLANE);
          return null;
        });
  }

  private static void startUnreviewed(Fixture fixture, DraftCommitBinding binding) {
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
    synchronize(fixture, binding, result.ownerOutcome());
  }

  private static void synchronize(
      Fixture fixture, DraftCommitBinding binding, GameDesignSourceRepository.Application result) {
    synchronize(fixture, binding, result.ownerOutcome());
  }

  private static void synchronize(
      Fixture fixture,
      DraftCommitBinding binding,
      DraftCommitCoordinatorRepository.OwnerOutcome outcome) {
    fixture.tx(
        () -> {
          fixture
              .coordinator()
              .advanceSourceVisibilityFence(
                  binding,
                  new DraftCommitCoordinatorRepository.CoordinatorProof(binding, List.of(outcome)));
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
      Fixture fixture, String realm, boolean production) {
    TargetProof target = fixture.target();
    SourceHead head = currentSourceHead(fixture);
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
        head.baseReference(),
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
                head.sourceEpoch())));
  }

  private static DraftCommitBinding policyMutationBinding(
      Fixture fixture, List<DraftCommitBinding.RevisionPayload> revisions) {
    TargetProof target = fixture.target();
    SourceHead head = currentSourceHead(fixture);
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        head.baseReference(),
        revisions,
        List.of(
            new DraftCommitBinding.AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                RealmPolicySource.SCOPE,
                target.canonicalVersionId().toString(),
                RealmPolicySource.SCOPE,
                "effective",
                head.sourceEpoch())));
  }

  private static DraftCommitBinding historicalPolicyBinding(
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

  private static SourceHead currentSourceHead(Fixture fixture) {
    var row =
        Objects.requireNonNull(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT source_epoch, visible_commit_id, genesis_receipt_id "
                        + "FROM game_design_realm_policy_source WHERE canonical_tenant_id = ? "
                        + "AND canonical_version_id = ?",
                    fixture.target().canonicalTenantId(),
                    fixture.target().canonicalVersionId()),
            "fresh Draft fixture must retain its actual policy source head");
    UUID visibleCommit = row.get("visible_commit_id", UUID.class);
    UUID genesisReceipt = row.get("genesis_receipt_id", UUID.class);
    UUID sharedGenesisReceipt =
        fixture
            .sources()
            .readGenesis(fixture.target())
            .orElseThrow(
                () -> new IllegalStateException("fresh Draft fixture lacks shared genesis"))
            .policy()
            .receiptId();
    if (visibleCommit == null && !sharedGenesisReceipt.equals(genesisReceipt)) {
      throw new IllegalStateException("empty source head differs from the shared genesis receipt");
    }
    String baseReference =
        visibleCommit == null ? "genesis:" + sharedGenesisReceipt : visibleCommit.toString();
    return new SourceHead(row.get("source_epoch", String.class), baseReference);
  }

  private static DraftCommitBinding.RevisionPayload policyDeleteRevision(
      String order, UUID revisionId, String logicalId, String world, String realm) {
    String payload =
        "{\"revisionKind\":\"REALM_ENTRY_POLICY\",\"logicalRevisionId\":\""
            + logicalId
            + "\",\"operation\":\"DELETE\",\"worldSlug\":\""
            + world
            + "\",\"realmSlug\":\""
            + realm
            + "\"}";
    return new DraftCommitBinding.RevisionPayload(
        order, revisionId, Owner.GAME_DESIGN_CONTROL_PLANE, payload);
  }

  private static DraftCommitBinding.RevisionPayload policyUpsertRevision(
      String order,
      UUID revisionId,
      String logicalId,
      String world,
      String realm,
      boolean production) {
    var policy =
        RealmEntryPolicy.parse(
            "{\"schemaVersion\":1,\"worldSlug\":\""
                + world
                + "\",\"worldDisplayName\":\"World\",\"realmSlug\":\""
                + realm
                + "\",\"realmDisplayName\":\"Realm\",\"visible\":true,\"publicProduction\":"
                + production
                + ",\"stateScope\":\"SHARED\",\"entryPolicy\":\"PRESEEDED_ONLY\"}",
            new ObjectMapper());
    String payload =
        "{\"revisionKind\":\"REALM_ENTRY_POLICY\",\"logicalRevisionId\":\""
            + logicalId
            + "\",\"policy\":"
            + policy.canonicalJson()
            + "}";
    return new DraftCommitBinding.RevisionPayload(
        order, revisionId, Owner.GAME_DESIGN_CONTROL_PLANE, payload);
  }

  private static DraftCommitBinding commandOnlyBinding(
      Fixture fixture, UUID baseCommitId, String expectedCommandEpoch) {
    TargetProof target = fixture.target();
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        baseCommitId.toString(),
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0",
                UUID.randomUUID(),
                Owner.GAME_DESIGN_CONTROL_PLANE,
                "{\"revisionKind\":\"COMMAND_DEFINITION\",\"schemaVersion\":1,"
                    + "\"operation\":\"DELETE\",\"commandId\":\"ISOLATED-command\"}")),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                "COMMAND_DEFINITION_SET",
                target.canonicalVersionId().toString(),
                "COMMAND_DEFINITION_SET",
                "effective",
                expectedCommandEpoch)));
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
        .load()
        .migrate();
    var transactions = new DataSourceTransactionManager(dataSource);
    var write = new TransactionTemplate(transactions);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
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
    var policies = new RealmPolicySourceRepository(dsl);
    var sources = new GameDesignSourceRepository(dsl);
    var coordinator = new DraftCommitCoordinatorRepository(dsl);
    var fixture =
        new Fixture(
            dataSource,
            schema,
            dsl,
            write,
            target,
            null,
            policies,
            sources,
            coordinator,
            new RealmPolicySourceService(policies, transactions));
    var baseline = policyBinding(fixture, "main", true);
    return new Fixture(
        dataSource,
        schema,
        dsl,
        write,
        target,
        baseline,
        policies,
        sources,
        coordinator,
        new RealmPolicySourceService(policies, transactions));
  }

  private static Fixture policyFixture() {
    var fixture = freshFixture();
    var baseline = fixture.baseline();
    start(fixture, baseline);
    var applied = fixture.tx(() -> fixture.sources().apply(baseline).policy().orElseThrow());
    synchronize(fixture, baseline, applied);
    return fixture;
  }

  private static Fixture retainedFixture() {
    String schema = "gd_policy_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    dataSource.setSchema(schema);
    migrate(dataSource, schema, "50.7");
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
    migrate(dataSource, schema, "50.13");
    var target = target(version);
    var baseline = historicalPolicyBinding(target, "0", "main", true);
    var policies = new RealmPolicySourceRepository(dsl);
    var sources = new GameDesignSourceRepository(dsl);
    var coordinator = new DraftCommitCoordinatorRepository(dsl);
    var fixture =
        new Fixture(
            dataSource,
            schema,
            dsl,
            write,
            target,
            baseline,
            policies,
            sources,
            coordinator,
            new RealmPolicySourceService(policies, transactions));
    // Retained fixtures start at V50.13; history tests seed there before migration, while denial
    // tests advance without adding provenance.
    return fixture;
  }

  private static void migrateLatest(Fixture fixture) {
    migrate(fixture.dataSource(), fixture.schema(), "latest");
  }

  private static RealmPolicySnapshot seedRetainedSnapshot(Fixture fixture, String sourceEpoch) {
    // This is only a V50.13 historical row fixture: it creates no reviewed-base evidence and is
    // never used for a current source application.
    return fixture.tx(
        () -> {
          var binding = fixture.baseline();
          fixture.coordinator().claim(binding);
          fixture.coordinator().claimApplicationSlot(binding);
          fixture.coordinator().markOwnerInProgress(binding, Owner.GAME_DESIGN_CONTROL_PLANE);
          var outcome =
              new DraftCommitCoordinatorRepository.OwnerOutcome(
                  Owner.GAME_DESIGN_CONTROL_PLANE,
                  DraftCommitCoordinatorRepository.OwnerStatus.APPLIED,
                  binding.commitId(),
                  binding.digest(),
                  "ISOLATED-initial-owner-baseline",
                  "ISOLATED-authenticated-baseline".getBytes(StandardCharsets.UTF_8),
                  List.of(
                      new DraftCommitCoordinatorRepository.AppliedEpoch(
                          RealmPolicySource.SCOPE,
                          fixture.target().canonicalVersionId().toString(),
                          RealmPolicySource.SCOPE,
                          "effective",
                          "0",
                          BigInteger.ONE.toString())));
          fixture.coordinator().recordOwnerOutcome(binding, outcome);
          fixture
              .coordinator()
              .advanceVisibilityFence(
                  binding,
                  new DraftCommitCoordinatorRepository.CoordinatorProof(binding, List.of(outcome)));
          fixture.coordinator().releaseApplicationSlot(binding);
          var policy = RealmPolicySource.revision(binding, binding.revisions().getFirst());
          var snapshot = new RealmPolicySnapshot(binding, sourceEpoch, List.of(policy));
          fixture
              .dsl()
              .execute(
                  "INSERT INTO game_design_realm_policy_snapshot (canonical_tenant_id, canonical_version_id, commit_id, request_id, snapshot_json) VALUES (?, ?, ?, ?, ?)",
                  fixture.target().canonicalTenantId(),
                  fixture.target().canonicalVersionId(),
                  binding.commitId(),
                  binding.requestId(),
                  snapshot.canonicalJson());
          return snapshot;
        });
  }

  private static UUID visiblePointerCommit(Fixture fixture) {
    var pointer =
        java.util.Objects.requireNonNull(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT commit_id FROM game_design_draft_commit_visibility WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                    fixture.target().canonicalTenantId(),
                    fixture.target().canonicalVersionId()),
            "synchronized Draft commit should retain its visibility pointer");
    return pointer.get("commit_id", UUID.class);
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

  private record SourceHead(String sourceEpoch, String baseReference) {}

  private record Fixture(
      DriverManagerDataSource dataSource,
      String schema,
      DSLContext dsl,
      TransactionTemplate write,
      TargetProof target,
      DraftCommitBinding baseline,
      RealmPolicySourceRepository policies,
      GameDesignSourceRepository sources,
      DraftCommitCoordinatorRepository coordinator,
      RealmPolicySourceService reads) {
    <T> T tx(Supplier<T> work) {
      return write.execute(status -> work.get());
    }
  }
}
