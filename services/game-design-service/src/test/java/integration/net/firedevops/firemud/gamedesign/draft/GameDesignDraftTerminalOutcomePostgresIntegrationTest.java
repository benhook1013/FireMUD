package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
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
 * Persistence proof only. Account source authentication and remote owner producer authenticity are
 * stipulated by fixture inputs here; this test wires neither a protected Account producer nor a
 * permission verifier and does not establish genuine authorization.
 */
@Testcontainers(disabledWithoutDocker = true)
class GameDesignDraftTerminalOutcomePostgresIntegrationTest {
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static Fixture fixture;

  @BeforeAll
  static void setUpSchema() {
    fixture = createFixture(null);
  }

  @Test
  void exactAccountClaimAndTerminalReadbackRetainOriginalBytes() {
    TargetProof target = fixture.newTarget();
    DraftCommitBinding binding = binding(target);
    DraftAuthorizationFenceBinding account = accountBinding(binding, "stipulated-source-a");
    GameDesignDraftTerminalOperation operation =
        new GameDesignDraftTerminalOperation(account, binding);

    fixture.inTransaction(() -> fixture.coordinator().claim(binding, account));
    fixture.inTransaction(() -> fixture.coordinator().claim(binding, account));

    var retainedOperation =
        new GameDesignDraftTerminalOutcomeRepository(fixture.dsl())
            .readOperation(account.operationId())
            .orElseThrow();
    assertThat(retainedOperation.accountBindingBytes()).containsExactly(account.canonicalBytes());
    assertThat(retainedOperation.accountBindingDigest())
        .isEqualTo(operation.accountBindingDigest());
    assertThat(
            new GameDesignDraftTerminalOutcomeRepository(fixture.dsl())
                .readAccountBound(account.canonicalBytes()))
        .isEmpty();

    DraftAuthorizationFenceBinding changed =
        accountBinding(
            binding,
            account.operationId(),
            account.fenceId(),
            account.actorAccountId(),
            "stipulated-source-b");
    assertThatThrownBy(
            () -> fixture.inTransaction(() -> fixture.coordinator().claim(binding, changed)))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException.class);
    assertThat(retainedOperation.accountBindingBytes()).containsExactly(account.canonicalBytes());
  }

  @Test
  void committedOutcomeIsAtomicWithSynchronizedFenceAndCompleteOwnerVector() {
    TargetProof target = fixture.newTarget();
    DraftCommitBinding binding = binding(target);
    DraftAuthorizationFenceBinding account = accountBinding(binding, "stipulated-source-commit");
    List<DraftCommitCoordinatorRepository.OwnerOutcome> outcomes =
        beginAndApplyOwners(binding, account, null);

    fixture.inTransaction(
        () -> {
          fixture
              .coordinator()
              .advanceVisibilityFence(
                  binding,
                  new DraftCommitCoordinatorRepository.CoordinatorProof(binding, outcomes));
          return null;
        });

    GameDesignDraftTerminalOutcome readback =
        new GameDesignDraftTerminalOutcomeRepository(fixture.dsl())
            .readAccountBound(account.canonicalBytes())
            .orElseThrow();
    assertThat(readback.result()).isEqualTo(GameDesignDraftTerminalOutcome.Result.COMMITTED);
    assertThat(readback.operation().accountBindingBytes())
        .containsExactly(account.canonicalBytes());
    assertThat(readback.ownerResultVectorJson())
        .isEqualTo(
            fixture.coordinator().readVisibilityFence(target).orElseThrow().resultVectorJson());
    assertThat(readback.finalEvidenceBytes())
        .containsExactly(readback.ownerResultVectorJson().getBytes(StandardCharsets.UTF_8));
    OwnerReadback ownerReadback = readback.toOwnerReadback();
    assertThat(ownerReadback.owner()).isEqualTo(DraftAuthorizationFenceBinding.Owner.GAME_DESIGN);
    assertThat(ownerReadback.outcome()).isEqualTo(DraftAuthorizationFenceBinding.Outcome.COMMITTED);
    assertThat(ownerReadback.operationId()).isEqualTo(account.operationId());
    assertThat(ownerReadback.commitId()).isEqualTo(account.commitId());
    assertThat(ownerReadback.fenceId()).isEqualTo(account.fenceId());
    assertThat(ownerReadback.inputDigest()).isEqualTo(account.inputDigest());
    assertThat(ownerReadback.fullBinding()).containsExactly(account.canonicalBytes());
    assertThat(ownerReadback.result()).containsExactly(readback.finalEvidenceBytes());
    OwnerReadback storedOwnerReadback = OwnerReadback.fromStored(ownerReadback.canonicalBytes());
    assertThat(storedOwnerReadback.canonicalBytes())
        .containsExactly(ownerReadback.canonicalBytes());
    storedOwnerReadback.requireBinding(account);
    DraftAuthorizationFenceBinding changedAccount =
        accountBinding(
            binding,
            account.operationId(),
            account.fenceId(),
            account.actorAccountId(),
            "changed-original-account-bytes");
    assertThatThrownBy(() -> storedOwnerReadback.requireBinding(changedAccount))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new GameDesignDraftTerminalOutcomeRepository(fixture.dsl())
                    .readAccountBound(changedAccount.canonicalBytes()))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException.class);
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () -> {
                      fixture
                          .dsl()
                          .execute(
                              "UPDATE game_design_draft_terminal_outcome "
                                  + "SET terminal_evidence_digest = ? WHERE operation_id = ?",
                              "sha256:" + "0".repeat(64),
                              account.operationId());
                      return null;
                    }))
        .isInstanceOf(RuntimeException.class);
    fixture.inTransaction(
        () -> {
          fixture
              .coordinator()
              .advanceVisibilityFence(
                  binding,
                  new DraftCommitCoordinatorRepository.CoordinatorProof(binding, outcomes));
          return null;
        });
    assertThat(
            new GameDesignDraftTerminalOutcomeRepository(fixture.dsl())
                .readAccountBound(account.canonicalBytes())
                .orElseThrow()
                .createdAt())
        .isEqualTo(readback.createdAt());
    assertThat(
            fixture.coordinator().read(target, binding.requestId()).orElseThrow().workflowState())
        .isEqualTo(DraftCommitCoordinatorRepository.WorkflowState.SYNCHRONIZED);
  }

  @Test
  void abortRetainsMixedAppliedAndRejectedParticipantsAndDeniesLateWriters() {
    TargetProof target = fixture.newTarget();
    DraftCommitBinding binding = binding(target);
    DraftAuthorizationFenceBinding account = accountBinding(binding, "stipulated-source-abort");
    beginAndApplyOwners(binding, account, Owner.ENTITY_MANAGEMENT);
    byte[] abortEvidence =
        "stipulated exact final-abort producer evidence".getBytes(StandardCharsets.UTF_8);

    fixture.inTransaction(
        () -> {
          fixture.coordinator().recordDefinitiveFinalAbort(binding, abortEvidence);
          return null;
        });

    GameDesignDraftTerminalOutcome readback =
        new GameDesignDraftTerminalOutcomeRepository(fixture.dsl())
            .readAccountBound(account.canonicalBytes())
            .orElseThrow();
    assertThat(readback.result())
        .isEqualTo(GameDesignDraftTerminalOutcome.Result.DEFINITIVELY_ABORTED);
    assertThat(readback.finalEvidenceBytes()).containsExactly(abortEvidence);
    OwnerReadback ownerReadback =
        OwnerReadback.fromStored(readback.toOwnerReadback().canonicalBytes());
    assertThat(ownerReadback.owner()).isEqualTo(DraftAuthorizationFenceBinding.Owner.GAME_DESIGN);
    assertThat(ownerReadback.outcome())
        .isEqualTo(DraftAuthorizationFenceBinding.Outcome.DEFINITIVELY_ABORTED);
    assertThat(ownerReadback.fullBinding()).containsExactly(account.canonicalBytes());
    assertThat(ownerReadback.result()).containsExactly(abortEvidence);
    ownerReadback.requireBinding(account);
    assertThat(readback.ownerResultVectorJson()).contains("\"owner\":\"WORLD_MANAGEMENT\"");
    assertThat(readback.ownerResultVectorJson()).contains("\"status\":\"APPLIED\"");
    assertThat(readback.ownerResultVectorJson()).contains("\"owner\":\"ENTITY_MANAGEMENT\"");
    assertThat(readback.ownerResultVectorJson()).contains("\"status\":\"REJECTED\"");
    assertThat(fixture.coordinator().readVisibilityFence(target)).isEmpty();
    var firstAbortReplay =
        fixture.inTransaction(
            () -> fixture.coordinator().recordDefinitiveFinalAbort(binding, abortEvidence));
    var secondAbortReplay =
        fixture.inTransaction(
            () -> fixture.coordinator().recordDefinitiveFinalAbort(binding, abortEvidence));
    assertThat(secondAbortReplay.createdAt()).isEqualTo(firstAbortReplay.createdAt());

    assertThatThrownBy(
            () -> fixture.inTransaction(() -> fixture.coordinator().claim(binding, account)))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitStateConflictException.class);
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.coordinator().markOwnerInProgress(binding, Owner.WORLD_MANAGEMENT)))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitStateConflictException.class);
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture
                            .coordinator()
                            .recordOwnerOutcome(
                                binding, appliedOutcome(binding, Owner.WORLD_MANAGEMENT))))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitStateConflictException.class);
    assertThatThrownBy(
            () -> fixture.inTransaction(() -> fixture.coordinator().claimApplicationSlot(binding)))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitStateConflictException.class);
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture
                            .coordinator()
                            .advanceVisibilityFence(
                                binding,
                                new DraftCommitCoordinatorRepository.CoordinatorProof(
                                    binding, allAppliedOutcomes(binding)))))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitStateConflictException.class);
  }

  @Test
  void terminalCommitAndAbortRaceHasOneSameVersionWinner() throws Exception {
    TargetProof target = fixture.newTarget();
    DraftCommitBinding binding = binding(target);
    DraftAuthorizationFenceBinding account = accountBinding(binding, "stipulated-source-race");
    GameDesignDraftTerminalOperation operation =
        new GameDesignDraftTerminalOperation(account, binding);
    List<DraftCommitCoordinatorRepository.OwnerOutcome> outcomes =
        beginAndApplyOwners(binding, account, null);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<FinalizeAttempt> commit =
          executor.submit(
              () -> {
                awaitStart(ready, start);
                return attempt(
                    () ->
                        fixture
                            .coordinator()
                            .advanceVisibilityFence(
                                binding,
                                new DraftCommitCoordinatorRepository.CoordinatorProof(
                                    binding, outcomes)));
              });
      Future<FinalizeAttempt> abort =
          executor.submit(
              () -> {
                awaitStart(ready, start);
                return attempt(
                    () ->
                        fixture
                            .coordinator()
                            .recordDefinitiveFinalAbort(
                                binding,
                                "stipulated race-abort evidence".getBytes(StandardCharsets.UTF_8)));
              });
      await(ready);
      start.countDown();
      FinalizeAttempt commitResult = commit.get(20, TimeUnit.SECONDS);
      FinalizeAttempt abortResult = abort.get(20, TimeUnit.SECONDS);

      assertThat(commitResult.succeeded()).isNotEqualTo(abortResult.succeeded());
      GameDesignDraftTerminalOutcome result =
          new GameDesignDraftTerminalOutcomeRepository(fixture.dsl()).read(operation).orElseThrow();
      if (commitResult.succeeded()) {
        assertThat(result.result()).isEqualTo(GameDesignDraftTerminalOutcome.Result.COMMITTED);
      } else {
        assertThat(result.result())
            .isEqualTo(GameDesignDraftTerminalOutcome.Result.DEFINITIVELY_ABORTED);
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void rollbackCannotLeaveAVisibilityFenceWithoutItsTerminalOutcome() {
    TargetProof target = fixture.newTarget();
    DraftCommitBinding binding = binding(target);
    DraftAuthorizationFenceBinding account = accountBinding(binding, "stipulated-source-rollback");
    GameDesignDraftTerminalOperation operation =
        new GameDesignDraftTerminalOperation(account, binding);
    List<DraftCommitCoordinatorRepository.OwnerOutcome> outcomes =
        beginAndApplyOwners(binding, account, null);

    fixture
        .transaction()
        .executeWithoutResult(
            status -> {
              fixture
                  .coordinator()
                  .advanceVisibilityFence(
                      binding,
                      new DraftCommitCoordinatorRepository.CoordinatorProof(binding, outcomes));
              status.setRollbackOnly();
            });

    assertThat(new GameDesignDraftTerminalOutcomeRepository(fixture.dsl()).read(operation))
        .isEmpty();
    assertThat(fixture.coordinator().readVisibilityFence(target)).isEmpty();
    assertThat(
            fixture.coordinator().read(target, binding.requestId()).orElseThrow().workflowState())
        .isEqualTo(DraftCommitCoordinatorRepository.WorkflowState.APPLYING);
  }

  @Test
  void rollbackCannotLeaveFinalAbortTombstoneWithoutItsTerminalOutcome() {
    TargetProof target = fixture.newTarget();
    DraftCommitBinding binding = binding(target);
    DraftAuthorizationFenceBinding account =
        accountBinding(binding, "stipulated-source-abort-rollback");
    GameDesignDraftTerminalOperation operation =
        new GameDesignDraftTerminalOperation(account, binding);
    beginAndApplyOwners(binding, account, Owner.ENTITY_MANAGEMENT);
    byte[] abortEvidence = "stipulated rollback abort evidence".getBytes(StandardCharsets.UTF_8);

    fixture
        .transaction()
        .executeWithoutResult(
            status -> {
              fixture.coordinator().recordDefinitiveFinalAbort(binding, abortEvidence);
              status.setRollbackOnly();
            });

    assertThat(new GameDesignDraftTerminalOutcomeRepository(fixture.dsl()).read(operation))
        .isEmpty();
    assertThat(
            fixture
                .dsl()
                .fetchCount(
                    DSL.table(DSL.name("game_design_draft_commit_final_abort")),
                    DSL.field(DSL.name("request_id"), UUID.class).eq(binding.requestId())))
        .isZero();
    assertThat(
            fixture.coordinator().read(target, binding.requestId()).orElseThrow().workflowState())
        .isEqualTo(DraftCommitCoordinatorRepository.WorkflowState.RECONCILIATION_REQUIRED);
  }

  @Test
  void migrationDoesNotInventAccountOutcomesForRetainedStatusOnlyRows() {
    Fixture legacy = createFixture(MigrationVersion.fromVersion("47"));
    TargetProof target = legacy.newTarget();
    DraftCommitBinding binding = binding(target);
    List<DraftCommitCoordinatorRepository.OwnerOutcome> outcomes = allAppliedOutcomes(binding);

    legacy.inTransaction(() -> legacy.coordinator().claim(binding));
    legacy
        .transaction()
        .executeWithoutResult(
            status -> {
              legacy
                  .dsl()
                  .execute(
                      "UPDATE game_design_draft_commit SET workflow_state = 'APPLYING' "
                          + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND request_id = ?",
                      target.canonicalTenantId(),
                      target.canonicalVersionId(),
                      binding.requestId());
              for (DraftCommitCoordinatorRepository.OwnerOutcome outcome : outcomes) {
                legacy
                    .dsl()
                    .execute(
                        "UPDATE game_design_draft_commit_owner_result SET status = 'IN_PROGRESS', "
                            + "updated_at = CURRENT_TIMESTAMP WHERE canonical_tenant_id = ? "
                            + "AND canonical_version_id = ? AND request_id = ? AND owner = ?",
                        target.canonicalTenantId(),
                        target.canonicalVersionId(),
                        binding.requestId(),
                        outcome.owner().name());
                legacy
                    .dsl()
                    .execute(
                        "UPDATE game_design_draft_commit_owner_result SET status = 'APPLIED', "
                            + "result_commit_id = ?, result_binding_digest = ?, result_identity = ?, "
                            + "result_bytes = ?, applied_units_json = ?, updated_at = CURRENT_TIMESTAMP "
                            + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                            + "AND request_id = ? AND owner = ?",
                        outcome.commitId(),
                        outcome.bindingDigest(),
                        outcome.resultIdentity(),
                        outcome.resultBytes(),
                        appliedEpochsJson(outcome.appliedEpochs()),
                        target.canonicalTenantId(),
                        target.canonicalVersionId(),
                        binding.requestId(),
                        outcome.owner().name());
              }
              String vector = ownerVectorJson(binding, outcomes);
              legacy
                  .dsl()
                  .execute(
                      "INSERT INTO game_design_draft_commit_visibility_fence "
                          + "(canonical_tenant_id, canonical_version_id, request_id, commit_id, "
                          + "input_digest, result_vector_json) VALUES (?, ?, ?, ?, ?, ?)",
                      target.canonicalTenantId(),
                      target.canonicalVersionId(),
                      binding.requestId(),
                      binding.commitId(),
                      binding.digest(),
                      vector);
              legacy
                  .dsl()
                  .execute(
                      "INSERT INTO game_design_draft_commit_visibility "
                          + "(canonical_tenant_id, canonical_version_id, request_id, commit_id, input_digest) "
                          + "VALUES (?, ?, ?, ?, ?)",
                      target.canonicalTenantId(),
                      target.canonicalVersionId(),
                      binding.requestId(),
                      binding.commitId(),
                      binding.digest());
              legacy
                  .dsl()
                  .execute(
                      "UPDATE game_design_draft_commit SET workflow_state = 'SYNCHRONIZED', "
                          + "updated_at = CURRENT_TIMESTAMP WHERE canonical_tenant_id = ? "
                          + "AND canonical_version_id = ? AND request_id = ?",
                      target.canonicalTenantId(),
                      target.canonicalVersionId(),
                      binding.requestId());
            });
    legacy.migrateToLatest();

    assertThat(
            new GameDesignDraftTerminalOutcomeRepository(legacy.dsl())
                .readOperation(UUID.randomUUID()))
        .isEmpty();
    assertThat(legacy.dsl().fetchCount(DSL.table(DSL.name("game_design_draft_terminal_operation"))))
        .isZero();
    assertThat(legacy.dsl().fetchCount(DSL.table(DSL.name("game_design_draft_terminal_outcome"))))
        .isZero();
  }

  private static List<DraftCommitCoordinatorRepository.OwnerOutcome> beginAndApplyOwners(
      DraftCommitBinding binding, DraftAuthorizationFenceBinding account, Owner rejectedOwner) {
    fixture.inTransaction(() -> fixture.coordinator().claim(binding, account));
    List<DraftCommitCoordinatorRepository.OwnerOutcome> outcomes =
        binding.requiredOwners().stream()
            .map(
                owner ->
                    owner == rejectedOwner
                        ? rejectedOutcome(binding, owner)
                        : appliedOutcome(binding, owner))
            .toList();
    fixture.inTransaction(
        () -> {
          fixture.coordinator().claimApplicationSlot(binding);
          for (DraftCommitCoordinatorRepository.OwnerOutcome outcome : outcomes) {
            fixture.coordinator().markOwnerInProgress(binding, outcome.owner());
            fixture.coordinator().recordOwnerOutcome(binding, outcome);
          }
          return null;
        });
    return outcomes;
  }

  private static List<DraftCommitCoordinatorRepository.OwnerOutcome> allAppliedOutcomes(
      DraftCommitBinding binding) {
    return binding.requiredOwners().stream().map(owner -> appliedOutcome(binding, owner)).toList();
  }

  private static DraftCommitCoordinatorRepository.OwnerOutcome appliedOutcome(
      DraftCommitBinding binding, Owner owner) {
    List<DraftCommitCoordinatorRepository.AppliedEpoch> epochs =
        binding.affectedUnits(owner).stream()
            .map(
                unit ->
                    new DraftCommitCoordinatorRepository.AppliedEpoch(
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch(),
                        new BigInteger(unit.expectedEpoch()).add(BigInteger.ONE).toString()))
            .toList();
    return new DraftCommitCoordinatorRepository.OwnerOutcome(
        owner,
        DraftCommitCoordinatorRepository.OwnerStatus.APPLIED,
        binding.commitId(),
        binding.digest(),
        owner.name().toLowerCase(java.util.Locale.ROOT) + "-stipulated-result",
        (owner.name() + " stipulated full producer result bytes").getBytes(StandardCharsets.UTF_8),
        epochs);
  }

  private static DraftCommitCoordinatorRepository.OwnerOutcome rejectedOutcome(
      DraftCommitBinding binding, Owner owner) {
    return new DraftCommitCoordinatorRepository.OwnerOutcome(
        owner,
        DraftCommitCoordinatorRepository.OwnerStatus.REJECTED,
        binding.commitId(),
        binding.digest(),
        owner.name().toLowerCase(java.util.Locale.ROOT) + "-stipulated-rejection",
        (owner.name() + " stipulated full rejection result bytes").getBytes(StandardCharsets.UTF_8),
        List.of());
  }

  private static DraftCommitBinding binding(TargetProof target) {
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "base-commit-7",
        List.of(
            new RevisionPayload("0", UUID.randomUUID(), Owner.WORLD_MANAGEMENT, "world change"),
            new RevisionPayload("1", UUID.randomUUID(), Owner.ENTITY_MANAGEMENT, "entity change")),
        List.of(
            new AffectedUnit(Owner.WORLD_MANAGEMENT, "WORLD", "world-1", "ROOM", "room-1", "0"),
            new AffectedUnit(
                Owner.ENTITY_MANAGEMENT, "ENTITY", "entity-1", "ACTOR", "actor-1", "0")));
  }

  private static DraftAuthorizationFenceBinding accountBinding(
      DraftCommitBinding binding, String evidence) {
    return accountBinding(
        binding, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), evidence);
  }

  private static DraftAuthorizationFenceBinding accountBinding(
      DraftCommitBinding binding, UUID operationId, UUID fenceId, UUID actorId, String evidence) {
    return new DraftAuthorizationFenceBinding(
        operationId,
        binding.requestId(),
        binding.commitId(),
        fenceId,
        actorId,
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding.baseCommitId(),
        "7",
        binding.canonicalBytes(),
        binding.canonicalBytes(),
        binding.digest(),
        List.of(
            new SourceEvidence(
                SourceKind.TENANT,
                binding.target().canonicalTenantId().toString(),
                null,
                "1",
                null,
                null,
                evidence.getBytes(StandardCharsets.UTF_8))));
  }

  private static String ownerVectorJson(
      DraftCommitBinding binding, List<DraftCommitCoordinatorRepository.OwnerOutcome> outcomes) {
    Map<Owner, DraftCommitCoordinatorRepository.OwnerOutcome> byOwner =
        new java.util.EnumMap<>(Owner.class);
    outcomes.forEach(outcome -> byOwner.put(outcome.owner(), outcome));
    List<Map<String, Object>> vector = new ArrayList<>();
    for (Owner owner : binding.requiredOwners()) {
      DraftCommitCoordinatorRepository.OwnerOutcome outcome = byOwner.get(owner);
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("owner", outcome.owner().name());
      item.put("status", outcome.status().name());
      item.put("commitId", outcome.commitId().toString());
      item.put("bindingDigest", outcome.bindingDigest());
      item.put("resultIdentity", outcome.resultIdentity());
      item.put("resultBytesBase64", Base64.getEncoder().encodeToString(outcome.resultBytes()));
      item.put(
          "appliedEpochs",
          outcome.appliedEpochs().stream()
              .map(GameDesignDraftTerminalOutcomePostgresIntegrationTest::epochMap)
              .toList());
      vector.add(item);
    }
    try {
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(vector)),
          StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Fixture owner result vector cannot be canonicalized", exception);
    }
  }

  private static String appliedEpochsJson(
      List<DraftCommitCoordinatorRepository.AppliedEpoch> epochs) {
    try {
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(
              JSON.writeValueAsString(
                  epochs.stream()
                      .map(GameDesignDraftTerminalOutcomePostgresIntegrationTest::epochMap)
                      .toList())),
          StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Fixture applied epoch vector cannot be canonicalized", exception);
    }
  }

  private static Map<String, Object> epochMap(DraftCommitCoordinatorRepository.AppliedEpoch epoch) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("aggregateType", epoch.aggregateType());
    value.put("aggregateId", epoch.aggregateId());
    value.put("scopeType", epoch.scopeType());
    value.put("scopeId", epoch.scopeId());
    value.put("expectedEpoch", epoch.expectedEpoch());
    value.put("resultingEpoch", epoch.resultingEpoch());
    return value;
  }

  private static FinalizeAttempt attempt(Supplier<?> finalize) {
    try {
      fixture.inTransaction(finalize);
      return new FinalizeAttempt(true, null);
    } catch (RuntimeException exception) {
      return new FinalizeAttempt(false, exception);
    }
  }

  private static void awaitStart(CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out coordinating concurrent terminal finalization");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while coordinating terminal finalization", exception);
    }
  }

  private static Fixture createFixture(MigrationVersion targetVersion) {
    String schema = "gd_terminal_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    dataSource.setSchema(schema);
    var flywayConfiguration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table(FLYWAY_TABLE);
    if (targetVersion != null) {
      flywayConfiguration.target(targetVersion);
    }
    flywayConfiguration.locations("classpath:db/migration").load().migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return new Fixture(
        schema,
        dataSource,
        dsl,
        transaction,
        new GameRepository(dsl),
        new VersionRepository(dsl, null),
        new DraftCommitCoordinatorRepository(dsl));
  }

  private record FinalizeAttempt(boolean succeeded, RuntimeException failure) {}

  private record Fixture(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      TransactionTemplate transaction,
      GameRepository games,
      VersionRepository versions,
      DraftCommitCoordinatorRepository coordinator) {
    TargetProof newTarget() {
      String tenantKey = "d-" + UUID.randomUUID().toString().replace("-", "");
      Game game = new Game();
      game.setTenantId(tenantKey);
      game.setName("Game Design terminal evidence source");
      game.setDescription("Canonical Version fixture");
      Game savedGame = Objects.requireNonNull(transaction.execute(status -> games.save(game)));

      Version version = new Version();
      version.setTenantId(savedGame.getTenantId());
      version.setVersionNumber(1);
      version.setVersionState(net.firedevops.firemud.gamedesign.model.VersionLifecycleState.DRAFT);
      version.setVersionStateEpoch(1L);
      Version savedVersion =
          Objects.requireNonNull(transaction.execute(status -> versions.save(version)));
      return new TargetProof(
          savedVersion.getCanonicalTenantId(),
          savedVersion.getCanonicalVersionId(),
          savedVersion.getId(),
          savedVersion.getTenantId(),
          savedVersion.getIdentitySourceGameRowId(),
          savedVersion.getIdentitySourceGameTenantKey(),
          savedVersion.getIdentitySourceProvenanceKind());
    }

    <T> T inTransaction(Supplier<T> action) {
      return transaction.execute(status -> action.get());
    }

    void migrateToLatest() {
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .table(FLYWAY_TABLE)
          .locations("classpath:db/migration")
          .load()
          .migrate();
    }
  }
}
