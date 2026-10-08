package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
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
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.ApplicationSlot;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.AppliedEpoch;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.CommitSnapshot;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.FinalAbortReceipt;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerOutcome;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerState;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.OwnerStatus;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.WorkflowState;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import org.flywaydb.core.Flyway;
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

@Testcontainers(disabledWithoutDocker = true)
class DraftCommitCoordinatorRepositoryIntegrationTest {
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";
  private static final String LARGE_EPOCH = "900719925474099312345678901234567890";

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static Fixture fixture;

  @BeforeAll
  static void setUpSchema() {
    fixture = createFixture();
  }

  @Test
  void exactClaimReplayRetainsFullBindingAndChangedRetryConflictsWithoutRewrite() {
    TargetProof target = fixture.newTarget();
    var binding = binding(target, UUID.randomUUID(), UUID.randomUUID());

    CommitSnapshot first = inTransaction(() -> fixture.coordinator().claim(binding));
    OffsetDateTimePair firstTimes =
        new OffsetDateTimePair(
            first.createdAt(), first.ownerStates().get(Owner.WORLD_MANAGEMENT).updatedAt());
    CommitSnapshot exactReplay = inTransaction(() -> fixture.coordinator().claim(binding));

    assertThat(exactReplay.binding().canonicalBytes()).containsExactly(binding.canonicalBytes());
    assertThat(exactReplay.binding().digest()).isEqualTo(binding.digest());
    assertThat(exactReplay.createdAt()).isEqualTo(firstTimes.commitCreatedAt());
    assertThat(exactReplay.ownerStates().get(Owner.WORLD_MANAGEMENT).updatedAt())
        .isEqualTo(firstTimes.ownerUpdatedAt());
    assertThat(exactReplay.ownerStates().values())
        .allMatch(state -> state.status() == OwnerStatus.NOT_ATTEMPTED);

    var changed =
        DraftCommitBinding.create(
            binding.target(),
            binding.requestId(),
            binding.commitId(),
            binding.baseCommitId(),
            List.of(
                new RevisionPayload(
                    "0",
                    binding.revisions().getFirst().revisionId(),
                    Owner.WORLD_MANAGEMENT,
                    "{\"room\":\"changed but still typed input\"}"),
                binding.revisions().get(1)),
            binding.affectedUnits());
    assertThatThrownBy(() -> inTransaction(() -> fixture.coordinator().claim(changed)))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException.class);
  }

  @Test
  void pendingBindingAndOwnerRowsRollBackTogether() {
    TargetProof target = fixture.newTarget();
    var binding = binding(target, UUID.randomUUID(), UUID.randomUUID());

    fixture
        .ownerTransaction()
        .executeWithoutResult(
            status -> {
              fixture.coordinator().claim(binding);
              status.setRollbackOnly();
            });

    assertThat(fixture.coordinator().read(target, binding.requestId())).isEmpty();
    assertThat(fixture.coordinator().readApplicationSlot(target)).isEmpty();
  }

  @Test
  void concurrentExactClaimsConvergeOnOneImmutableBinding() throws Exception {
    TargetProof target = fixture.newTarget();
    var binding = binding(target, UUID.randomUUID(), UUID.randomUUID());
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<CommitSnapshot> left =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return inTransaction(() -> fixture.coordinator().claim(binding));
              });
      Future<CommitSnapshot> right =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return inTransaction(() -> fixture.coordinator().claim(binding));
              });
      await(ready);
      start.countDown();
      CommitSnapshot first = left.get(20, TimeUnit.SECONDS);
      CommitSnapshot second = right.get(20, TimeUnit.SECONDS);

      assertThat(first.binding().canonicalBytes())
          .containsExactly(second.binding().canonicalBytes());
      assertThat(fixture.coordinator().read(target, binding.requestId())).isPresent();
      assertThat(
              fixture.coordinator().read(target, binding.requestId()).orElseThrow().ownerStates())
          .containsOnlyKeys(Owner.WORLD_MANAGEMENT, Owner.ENTITY_MANAGEMENT);
      assertThat(
              fixture
                  .dsl()
                  .fetchCount(
                      DSL.table(DSL.name("game_design_draft_commit")),
                      DSL.field(DSL.name("request_id"), UUID.class).eq(binding.requestId())))
          .isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void concurrentDifferentRequestsCannotClaimTheSameCommitIdentity() throws Exception {
    TargetProof target = fixture.newTarget();
    UUID commitId = UUID.randomUUID();
    var firstBinding = binding(target, UUID.randomUUID(), commitId);
    var secondBinding = binding(target, UUID.randomUUID(), commitId);

    ConcurrentClaims claims = concurrentlyClaim(firstBinding, secondBinding);
    assertExactlyOneClaimSucceeded(claims);
    DraftCommitBinding winningBinding =
        claims.left().snapshot() == null ? secondBinding : firstBinding;
    DraftCommitBinding rejectedBinding =
        claims.left().snapshot() == null ? firstBinding : secondBinding;

    CommitSnapshot persisted =
        fixture.coordinator().read(target, winningBinding.requestId()).orElseThrow();
    assertThat(persisted.binding().canonicalBytes())
        .containsExactly(winningBinding.canonicalBytes());
    assertThat(persisted.ownerStates())
        .containsOnlyKeys(Owner.WORLD_MANAGEMENT, Owner.ENTITY_MANAGEMENT);
    assertThat(fixture.coordinator().read(target, rejectedBinding.requestId())).isEmpty();
    assertThat(
            fixture
                .dsl()
                .fetchCount(
                    DSL.table(DSL.name("game_design_draft_commit")),
                    DSL.field(DSL.name("commit_id"), UUID.class).eq(commitId)))
        .isEqualTo(1);
    assertThat(
            fixture
                .dsl()
                .fetchCount(
                    DSL.table(DSL.name("game_design_draft_commit_owner_result")),
                    DSL.field(DSL.name("request_id"), UUID.class).eq(winningBinding.requestId())))
        .isEqualTo(winningBinding.requiredOwners().size());
    assertThat(
            fixture
                .dsl()
                .fetchCount(
                    DSL.table(DSL.name("game_design_draft_commit_owner_result")),
                    DSL.field(DSL.name("request_id"), UUID.class).eq(rejectedBinding.requestId())))
        .isZero();
  }

  @Test
  void concurrentChangedInputForOneRequestIsRejectedWithoutExtraOwnerRows() throws Exception {
    TargetProof target = fixture.newTarget();
    UUID requestId = UUID.randomUUID();
    UUID commitId = UUID.randomUUID();
    var firstBinding = binding(target, requestId, commitId);
    var changedBinding =
        DraftCommitBinding.create(
            firstBinding.target(),
            firstBinding.requestId(),
            firstBinding.commitId(),
            firstBinding.baseCommitId(),
            List.of(
                new RevisionPayload(
                    "0",
                    firstBinding.revisions().getFirst().revisionId(),
                    Owner.WORLD_MANAGEMENT,
                    "{\"room\":\"changed concurrent input\"}"),
                firstBinding.revisions().get(1)),
            firstBinding.affectedUnits());

    ConcurrentClaims claims = concurrentlyClaim(firstBinding, changedBinding);
    assertExactlyOneClaimSucceeded(claims);
    DraftCommitBinding winningBinding =
        claims.left().snapshot() == null ? changedBinding : firstBinding;

    CommitSnapshot persisted = fixture.coordinator().read(target, requestId).orElseThrow();
    assertThat(persisted.binding().canonicalBytes())
        .containsExactly(winningBinding.canonicalBytes());
    assertThat(persisted.ownerStates())
        .containsOnlyKeys(Owner.WORLD_MANAGEMENT, Owner.ENTITY_MANAGEMENT);
    assertThat(
            fixture
                .dsl()
                .fetchCount(
                    DSL.table(DSL.name("game_design_draft_commit")),
                    DSL.field(DSL.name("request_id"), UUID.class).eq(requestId)))
        .isEqualTo(1);
    assertThat(
            fixture
                .dsl()
                .fetchCount(
                    DSL.table(DSL.name("game_design_draft_commit_owner_result")),
                    DSL.field(DSL.name("request_id"), UUID.class).eq(requestId)))
        .isEqualTo(winningBinding.requiredOwners().size());
  }

  @Test
  void partialUnknownOwnerResultKeepsApplicationSlotAndCannotCreateVisibilityFence() {
    TargetProof target = fixture.newTarget();
    var binding = binding(target, UUID.randomUUID(), UUID.randomUUID());
    inTransaction(() -> fixture.coordinator().claim(binding));
    ApplicationSlot slot = inTransaction(() -> fixture.coordinator().claimApplicationSlot(binding));
    assertThat(slot.commitId()).isEqualTo(binding.commitId());

    inTransaction(
        () -> {
          fixture.coordinator().markOwnerInProgress(binding, Owner.WORLD_MANAGEMENT);
          fixture.coordinator().recordOwnerOutcome(binding, appliedWorldOutcome(binding));
          fixture.coordinator().markOwnerInProgress(binding, Owner.ENTITY_MANAGEMENT);
          fixture
              .coordinator()
              .recordOwnerOutcome(
                  binding,
                  new OwnerOutcome(
                      Owner.ENTITY_MANAGEMENT,
                      OwnerStatus.UNKNOWN,
                      binding.commitId(),
                      binding.digest(),
                      null,
                      null,
                      List.of()));
          return null;
        });

    CommitSnapshot partial = fixture.coordinator().read(target, binding.requestId()).orElseThrow();
    assertThat(partial.workflowState()).isEqualTo(WorkflowState.RECONCILIATION_REQUIRED);
    assertThat(partial.ownerStates().get(Owner.WORLD_MANAGEMENT).status())
        .isEqualTo(OwnerStatus.APPLIED);
    assertThat(partial.ownerStates().get(Owner.ENTITY_MANAGEMENT).status())
        .isEqualTo(OwnerStatus.UNKNOWN);
    assertThat(fixture.coordinator().readApplicationSlot(target)).isPresent();
    assertThat(fixture.coordinator().readVisibilityFence(target)).isEmpty();
    assertThatThrownBy(
            () ->
                inTransaction(
                    () -> {
                      fixture.coordinator().releaseApplicationSlot(binding);
                      return null;
                    }))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitStateConflictException.class)
        .hasMessageContaining("cannot release");

    OwnerState world = partial.ownerStates().get(Owner.WORLD_MANAGEMENT);
    assertThatThrownBy(
            () ->
                inTransaction(
                    () ->
                        fixture
                            .coordinator()
                            .recordOwnerOutcome(
                                binding,
                                new OwnerOutcome(
                                    Owner.WORLD_MANAGEMENT,
                                    OwnerStatus.APPLIED,
                                    binding.commitId(),
                                    binding.digest(),
                                    "world-result-exact-1",
                                    "different bytes".getBytes(StandardCharsets.UTF_8),
                                    appliedWorldOutcome(binding).appliedEpochs()))))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitStateConflictException.class);
    assertThat(
            fixture
                .coordinator()
                .read(target, binding.requestId())
                .orElseThrow()
                .ownerStates()
                .get(Owner.WORLD_MANAGEMENT)
                .updatedAt())
        .isEqualTo(world.updatedAt());
  }

  @Test
  void exactAppliedResultReplayIsImmutableBeforeAndAfterSlotRelease() {
    TargetProof target = fixture.newTarget();
    var binding = worldOnlyBinding(target, UUID.randomUUID(), UUID.randomUUID());
    inTransaction(() -> fixture.coordinator().claim(binding));
    inTransaction(() -> fixture.coordinator().claimApplicationSlot(binding));

    OwnerOutcome original = appliedWorldOutcome(binding);
    OwnerState first =
        inTransaction(
            () -> {
              fixture.coordinator().markOwnerInProgress(binding, Owner.WORLD_MANAGEMENT);
              return fixture.coordinator().recordOwnerOutcome(binding, original);
            });
    OwnerOutcome exactRetry = appliedWorldOutcome(binding);
    OwnerState beforeRelease =
        inTransaction(() -> fixture.coordinator().recordOwnerOutcome(binding, exactRetry));
    assertThat(beforeRelease.updatedAt()).isEqualTo(first.updatedAt());
    assertThat(beforeRelease.outcome()).contains(original);
    assertThat(beforeRelease.outcome().orElseThrow().resultBytes())
        .containsExactly(original.resultBytes());

    OwnerOutcome changed =
        new OwnerOutcome(
            Owner.WORLD_MANAGEMENT,
            OwnerStatus.APPLIED,
            binding.commitId(),
            binding.digest(),
            original.resultIdentity(),
            "changed owner evidence".getBytes(StandardCharsets.UTF_8),
            original.appliedEpochs());
    assertThatThrownBy(
            () -> inTransaction(() -> fixture.coordinator().recordOwnerOutcome(binding, changed)))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitStateConflictException.class);

    inTransaction(
        () -> {
          fixture
              .coordinator()
              .advanceVisibilityFence(
                  binding,
                  new DraftCommitCoordinatorRepository.CoordinatorProof(
                      binding, List.of(original)));
          fixture.coordinator().releaseApplicationSlot(binding);
          return null;
        });
    assertThat(fixture.coordinator().readApplicationSlot(target)).isEmpty();

    OwnerState afterRelease =
        inTransaction(() -> fixture.coordinator().recordOwnerOutcome(binding, exactRetry));
    assertThat(afterRelease.updatedAt()).isEqualTo(first.updatedAt());
    assertThat(afterRelease.outcome()).contains(original);
    assertThat(afterRelease.outcome().orElseThrow().resultBytes())
        .containsExactly(original.resultBytes());
  }

  @Test
  void mixedFinalAbortPreservesPriorFenceAndOwnerEvidenceAndFencesDelayedAttempts() {
    TargetProof target = fixture.newTarget();
    var priorBinding = worldOnlyBinding(target, UUID.randomUUID(), UUID.randomUUID());
    OwnerOutcome priorWorld = appliedWorldOutcome(priorBinding);
    inTransaction(
        () -> {
          fixture.coordinator().claim(priorBinding);
          fixture.coordinator().claimApplicationSlot(priorBinding);
          fixture.coordinator().markOwnerInProgress(priorBinding, Owner.WORLD_MANAGEMENT);
          fixture.coordinator().recordOwnerOutcome(priorBinding, priorWorld);
          fixture
              .coordinator()
              .advanceVisibilityFence(
                  priorBinding,
                  new DraftCommitCoordinatorRepository.CoordinatorProof(
                      priorBinding, List.of(priorWorld)));
          fixture.coordinator().releaseApplicationSlot(priorBinding);
          return null;
        });
    var priorFence = fixture.coordinator().readVisibilityFence(target).orElseThrow();

    var failedBinding = binding(target, UUID.randomUUID(), UUID.randomUUID());
    OwnerOutcome appliedWorld = appliedWorldOutcome(failedBinding);
    OwnerOutcome rejectedEntity =
        new OwnerOutcome(
            Owner.ENTITY_MANAGEMENT,
            OwnerStatus.REJECTED,
            failedBinding.commitId(),
            failedBinding.digest(),
            "entity-rejected-final-1",
            "durable exact rejection".getBytes(StandardCharsets.UTF_8),
            List.of());
    byte[] abortBytes = "trusted producer final-abort receipt".getBytes(StandardCharsets.UTF_8);
    inTransaction(
        () -> {
          fixture.coordinator().claim(failedBinding);
          fixture.coordinator().claimApplicationSlot(failedBinding);
          fixture.coordinator().markOwnerInProgress(failedBinding, Owner.WORLD_MANAGEMENT);
          fixture.coordinator().recordOwnerOutcome(failedBinding, appliedWorld);
          fixture.coordinator().markOwnerInProgress(failedBinding, Owner.ENTITY_MANAGEMENT);
          fixture.coordinator().recordOwnerOutcome(failedBinding, rejectedEntity);
          return null;
        });

    FinalAbortReceipt first =
        inTransaction(
            () -> fixture.coordinator().recordDefinitiveFinalAbort(failedBinding, abortBytes));
    CommitSnapshot failed =
        fixture.coordinator().read(target, failedBinding.requestId()).orElseThrow();
    assertThat(failed.workflowState()).isEqualTo(WorkflowState.FAILED_NONPUBLICATION);
    assertThat(failed.ownerStates().get(Owner.WORLD_MANAGEMENT).outcome()).contains(appliedWorld);
    assertThat(failed.ownerStates().get(Owner.ENTITY_MANAGEMENT).outcome())
        .contains(rejectedEntity);
    assertThat(fixture.coordinator().readVisibilityFence(target)).contains(priorFence);

    FinalAbortReceipt exactReplay =
        inTransaction(
            () -> fixture.coordinator().recordDefinitiveFinalAbort(failedBinding, abortBytes));
    assertThat(exactReplay.binding()).isEqualTo(first.binding());
    assertThat(exactReplay.abortBytes()).containsExactly(first.abortBytes());
    assertThat(exactReplay.createdAt()).isEqualTo(first.createdAt());
    assertThatThrownBy(
            () ->
                inTransaction(
                    () ->
                        fixture
                            .coordinator()
                            .recordDefinitiveFinalAbort(
                                failedBinding,
                                "changed final-abort bytes".getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException.class);

    assertThat(
            inTransaction(
                    () -> fixture.coordinator().recordOwnerOutcome(failedBinding, appliedWorld))
                .outcome())
        .contains(appliedWorld);
    OwnerOutcome changedWorld =
        new OwnerOutcome(
            Owner.WORLD_MANAGEMENT,
            OwnerStatus.APPLIED,
            failedBinding.commitId(),
            failedBinding.digest(),
            appliedWorld.resultIdentity(),
            "changed World result".getBytes(StandardCharsets.UTF_8),
            appliedWorld.appliedEpochs());
    assertThatThrownBy(
            () ->
                inTransaction(
                    () -> fixture.coordinator().recordOwnerOutcome(failedBinding, changedWorld)))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitStateConflictException.class);

    assertThatThrownBy(
            () -> inTransaction(() -> fixture.coordinator().claimApplicationSlot(failedBinding)))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitStateConflictException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    () ->
                        fixture
                            .coordinator()
                            .markOwnerInProgress(failedBinding, Owner.WORLD_MANAGEMENT)))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitStateConflictException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    () ->
                        fixture
                            .dsl()
                            .execute(
                                "UPDATE game_design_draft_commit_owner_result "
                                    + "SET status = 'IN_PROGRESS', updated_at = CURRENT_TIMESTAMP "
                                    + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                                    + "AND request_id = ? AND owner = 'WORLD_MANAGEMENT'",
                                target.canonicalTenantId(),
                                target.canonicalVersionId(),
                                failedBinding.requestId())))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    () ->
                        fixture
                            .dsl()
                            .execute(
                                "INSERT INTO game_design_draft_commit_visibility_fence "
                                    + "(canonical_tenant_id, canonical_version_id, request_id, "
                                    + "commit_id, input_digest, result_vector_json) "
                                    + "VALUES (?, ?, ?, ?, ?, '[]')",
                                target.canonicalTenantId(),
                                target.canonicalVersionId(),
                                failedBinding.requestId(),
                                failedBinding.commitId(),
                                failedBinding.digest())))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    () ->
                        fixture
                            .dsl()
                            .execute(
                                "INSERT INTO game_design_draft_commit_application_slot "
                                    + "(canonical_tenant_id, canonical_version_id, request_id, commit_id) "
                                    + "VALUES (?, ?, ?, ?)",
                                target.canonicalTenantId(),
                                target.canonicalVersionId(),
                                failedBinding.requestId(),
                                failedBinding.commitId())))
        .isInstanceOf(RuntimeException.class);

    inTransaction(
        () -> {
          fixture.coordinator().releaseApplicationSlot(failedBinding);
          return null;
        });
    assertThat(fixture.coordinator().readApplicationSlot(target)).isEmpty();
    assertThat(fixture.coordinator().readVisibilityFence(target)).contains(priorFence);

    var laterBinding = worldOnlyBinding(target, UUID.randomUUID(), UUID.randomUUID());
    inTransaction(
        () -> {
          fixture.coordinator().claim(laterBinding);
          fixture.coordinator().claimApplicationSlot(laterBinding);
          return null;
        });
    FinalAbortReceipt replayAfterReclaim =
        inTransaction(
            () -> fixture.coordinator().recordDefinitiveFinalAbort(failedBinding, abortBytes));
    assertThat(replayAfterReclaim.createdAt()).isEqualTo(first.createdAt());
    inTransaction(
        () -> {
          fixture.coordinator().releaseApplicationSlot(failedBinding);
          return null;
        });
    assertThat(fixture.coordinator().readApplicationSlot(target).orElseThrow().requestId())
        .isEqualTo(laterBinding.requestId());
    assertThat(fixture.coordinator().readVisibilityFence(target)).contains(priorFence);
  }

  @Test
  void unknownOwnerAndMissingRowsCannotProduceFinalAbort() {
    TargetProof target = fixture.newTarget();
    var binding = binding(target, UUID.randomUUID(), UUID.randomUUID());
    OwnerOutcome world = appliedWorldOutcome(binding);
    inTransaction(
        () -> {
          fixture.coordinator().claim(binding);
          fixture.coordinator().claimApplicationSlot(binding);
          fixture.coordinator().markOwnerInProgress(binding, Owner.WORLD_MANAGEMENT);
          fixture.coordinator().recordOwnerOutcome(binding, world);
          fixture.coordinator().markOwnerInProgress(binding, Owner.ENTITY_MANAGEMENT);
          fixture
              .coordinator()
              .recordOwnerOutcome(
                  binding,
                  new OwnerOutcome(
                      Owner.ENTITY_MANAGEMENT,
                      OwnerStatus.UNKNOWN,
                      binding.commitId(),
                      binding.digest(),
                      null,
                      null,
                      List.of()));
          return null;
        });

    byte[] abortBytes = "not enough proof".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () ->
                inTransaction(
                    () -> fixture.coordinator().recordDefinitiveFinalAbort(binding, abortBytes)))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitStateConflictException.class);
    assertThat(
            fixture.coordinator().read(target, binding.requestId()).orElseThrow().workflowState())
        .isEqualTo(WorkflowState.RECONCILIATION_REQUIRED);
    assertThat(fixture.coordinator().readApplicationSlot(target)).isPresent();

    assertThatThrownBy(
            () ->
                inTransaction(
                    () -> {
                      fixture
                          .dsl()
                          .execute(
                              "DELETE FROM game_design_draft_commit_owner_result "
                                  + "WHERE canonical_tenant_id = ? AND canonical_version_id = ? "
                                  + "AND request_id = ? AND owner = 'ENTITY_MANAGEMENT'",
                              target.canonicalTenantId(),
                              target.canonicalVersionId(),
                              binding.requestId());
                      return null;
                    }))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    () ->
                        fixture
                            .dsl()
                            .execute(
                                "INSERT INTO game_design_draft_commit_final_abort "
                                    + "(canonical_tenant_id, canonical_version_id, request_id, "
                                    + "commit_id, input_digest, binding_json, abort_bytes) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                                target.canonicalTenantId(),
                                target.canonicalVersionId(),
                                binding.requestId(),
                                binding.commitId(),
                                binding.digest(),
                                binding.canonicalJson(),
                                abortBytes)))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void allAppliedOwnersIncludingGameDesignCanBeFinalAbortedWithoutVisibility() {
    TargetProof target = fixture.newTarget();
    var binding = allOwnerBinding(target, UUID.randomUUID(), UUID.randomUUID());
    inTransaction(
        () -> {
          fixture.coordinator().claim(binding);
          fixture.coordinator().claimApplicationSlot(binding);
          for (Owner owner : binding.requiredOwners()) {
            OwnerOutcome outcome = appliedOutcome(binding, owner);
            fixture.coordinator().markOwnerInProgress(binding, owner);
            fixture.coordinator().recordOwnerOutcome(binding, outcome);
          }
          return null;
        });

    FinalAbortReceipt receipt =
        inTransaction(
            () ->
                fixture
                    .coordinator()
                    .recordDefinitiveFinalAbort(
                        binding,
                        "Game Design final no-commit proof".getBytes(StandardCharsets.UTF_8)));
    CommitSnapshot snapshot = fixture.coordinator().read(target, binding.requestId()).orElseThrow();
    assertThat(snapshot.workflowState()).isEqualTo(WorkflowState.FAILED_NONPUBLICATION);
    assertThat(snapshot.ownerStates()).containsKeys(Owner.GAME_DESIGN_CONTROL_PLANE);
    assertThat(snapshot.ownerStates().values())
        .allMatch(state -> state.status() == OwnerStatus.APPLIED);
    assertThat(receipt.binding()).isEqualTo(binding);
    assertThat(fixture.coordinator().readVisibilityFence(target)).isEmpty();
    List<OwnerOutcome> allApplied =
        binding.requiredOwners().stream().map(owner -> appliedOutcome(binding, owner)).toList();
    assertThatThrownBy(
            () ->
                inTransaction(
                    () ->
                        fixture
                            .coordinator()
                            .advanceVisibilityFence(
                                binding,
                                new DraftCommitCoordinatorRepository.CoordinatorProof(
                                    binding, allApplied))))
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitStateConflictException.class);
    inTransaction(
        () -> {
          fixture.coordinator().releaseApplicationSlot(binding);
          return null;
        });
    assertThat(fixture.coordinator().readApplicationSlot(target)).isEmpty();
  }

  @Test
  void directSqlFinalAbortRejectsMissingRequiredOwnerRows() {
    TargetProof target = fixture.newTarget();
    var binding = binding(target, UUID.randomUUID(), UUID.randomUUID());
    byte[] abortBytes = "missing owner is not no-commit proof".getBytes(StandardCharsets.UTF_8);
    inTransaction(
        () -> {
          fixture
              .dsl()
              .execute(
                  "INSERT INTO game_design_draft_commit "
                      + "(canonical_tenant_id, canonical_version_id, "
                      + "game_design_version_row_id, game_design_version_tenant_key, "
                      + "source_game_row_id, source_game_tenant_key, source_provenance_kind, "
                      + "request_id, commit_id, base_commit_id, input_digest, binding_json, "
                      + "workflow_state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'APPLYING')",
                  target.canonicalTenantId(),
                  target.canonicalVersionId(),
                  target.gameDesignVersionRowId(),
                  target.gameDesignVersionTenantKey(),
                  target.sourceGameRowId(),
                  target.sourceGameTenantKey(),
                  target.sourceProvenanceKind(),
                  binding.requestId(),
                  binding.commitId(),
                  binding.baseCommitId(),
                  binding.digest(),
                  binding.canonicalJson());
          fixture
              .dsl()
              .execute(
                  "INSERT INTO game_design_draft_commit_application_slot "
                      + "(canonical_tenant_id, canonical_version_id, request_id, commit_id) "
                      + "VALUES (?, ?, ?, ?)",
                  target.canonicalTenantId(),
                  target.canonicalVersionId(),
                  binding.requestId(),
                  binding.commitId());
          return null;
        });
    assertThat(
            fixture
                .dsl()
                .fetchCount(
                    DSL.table(DSL.name("game_design_draft_commit")),
                    DSL.field(DSL.name("request_id"), UUID.class).eq(binding.requestId())))
        .isEqualTo(1);
    assertThat(fixture.coordinator().readApplicationSlot(target).orElseThrow().requestId())
        .isEqualTo(binding.requestId());

    assertThatThrownBy(
            () ->
                inTransaction(
                    () ->
                        fixture
                            .dsl()
                            .execute(
                                "INSERT INTO game_design_draft_commit_final_abort "
                                    + "(canonical_tenant_id, canonical_version_id, request_id, "
                                    + "commit_id, input_digest, binding_json, abort_bytes) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                                target.canonicalTenantId(),
                                target.canonicalVersionId(),
                                binding.requestId(),
                                binding.commitId(),
                                binding.digest(),
                                binding.canonicalJson(),
                                abortBytes)))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining(
            "Final-abort evidence requires every exact owner result to be terminal");
  }

  private static Fixture createFixture() {
    String schema = "gd_draft_coord_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table(FLYWAY_TABLE)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    GameRepository gameRepository = new GameRepository(dsl);
    VersionRepository versionRepository = new VersionRepository(dsl);
    DraftCommitCoordinatorRepository coordinator = new DraftCommitCoordinatorRepository(dsl);
    return new Fixture(dsl, transactionTemplate, gameRepository, versionRepository, coordinator);
  }

  private static DraftCommitBinding binding(TargetProof target, UUID requestId, UUID commitId) {
    return DraftCommitBinding.create(
        target,
        requestId,
        commitId,
        "base-commit-source-7",
        List.of(
            new RevisionPayload(
                "0",
                UUID.randomUUID(),
                Owner.WORLD_MANAGEMENT,
                "{\"completeWorldMutation\":{\"opaque\":\"preserved\"}}"),
            new RevisionPayload(
                "1",
                UUID.randomUUID(),
                Owner.ENTITY_MANAGEMENT,
                "{\"completeEntityMutation\":{\"opaque\":\"preserved\"}}")),
        List.of(
            new AffectedUnit(
                Owner.WORLD_MANAGEMENT,
                "WORLD_TEMPLATE",
                "world-1",
                "ROOM_SCOPE",
                "room-scope-1",
                LARGE_EPOCH),
            new AffectedUnit(
                Owner.ENTITY_MANAGEMENT,
                "ENTITY_TEMPLATE",
                "entity-1",
                "ACTOR_SCOPE",
                "actor-scope-1",
                "0")));
  }

  private static DraftCommitBinding worldOnlyBinding(
      TargetProof target, UUID requestId, UUID commitId) {
    return DraftCommitBinding.create(
        target,
        requestId,
        commitId,
        "base-commit-source-7",
        List.of(
            new RevisionPayload(
                "0",
                UUID.randomUUID(),
                Owner.WORLD_MANAGEMENT,
                "{\"completeWorldMutation\":{\"opaque\":\"preserved\"}}")),
        List.of(
            new AffectedUnit(
                Owner.WORLD_MANAGEMENT,
                "WORLD_TEMPLATE",
                "world-1",
                "ROOM_SCOPE",
                "room-scope-1",
                LARGE_EPOCH)));
  }

  private static DraftCommitBinding allOwnerBinding(
      TargetProof target, UUID requestId, UUID commitId) {
    List<RevisionPayload> revisions = new java.util.ArrayList<>();
    List<AffectedUnit> affected = new java.util.ArrayList<>();
    int index = 0;
    for (Owner owner : Owner.values()) {
      revisions.add(
          new RevisionPayload(
              Integer.toString(index),
              UUID.randomUUID(),
              owner,
              "{\"ownerMutation\":\"" + owner.name() + "\"}"));
      affected.add(
          new AffectedUnit(
              owner,
              owner.name() + "_AGGREGATE",
              "aggregate-" + owner.name().toLowerCase(java.util.Locale.ROOT),
              owner.name() + "_SCOPE",
              "scope-" + owner.name().toLowerCase(java.util.Locale.ROOT),
              "0"));
      index++;
    }
    return DraftCommitBinding.create(
        target, requestId, commitId, "base-commit-source-7", revisions, affected);
  }

  private static OwnerOutcome appliedOutcome(DraftCommitBinding binding, Owner owner) {
    AffectedUnit expected = binding.affectedUnits(owner).getFirst();
    String resultingEpoch =
        new java.math.BigInteger(expected.expectedEpoch()).add(java.math.BigInteger.ONE).toString();
    return new OwnerOutcome(
        owner,
        OwnerStatus.APPLIED,
        binding.commitId(),
        binding.digest(),
        owner.name().toLowerCase(java.util.Locale.ROOT) + "-result-exact-1",
        ("full durable " + owner.name() + " response").getBytes(StandardCharsets.UTF_8),
        List.of(
            new AppliedEpoch(
                expected.aggregateType(),
                expected.aggregateId(),
                expected.scopeType(),
                expected.scopeId(),
                expected.expectedEpoch(),
                resultingEpoch)));
  }

  private static OwnerOutcome appliedWorldOutcome(DraftCommitBinding binding) {
    AffectedUnit expected = binding.affectedUnits(Owner.WORLD_MANAGEMENT).getFirst();
    String resultingEpoch =
        new java.math.BigInteger(expected.expectedEpoch()).add(java.math.BigInteger.ONE).toString();
    return new OwnerOutcome(
        Owner.WORLD_MANAGEMENT,
        OwnerStatus.APPLIED,
        binding.commitId(),
        binding.digest(),
        "world-result-exact-1",
        "full durable owner response".getBytes(StandardCharsets.UTF_8),
        List.of(
            new AppliedEpoch(
                expected.aggregateType(),
                expected.aggregateId(),
                expected.scopeType(),
                expected.scopeId(),
                expected.expectedEpoch(),
                resultingEpoch)));
  }

  private static <T> T inTransaction(Supplier<T> work) {
    return fixture.ownerTransaction().execute(status -> work.get());
  }

  private static ConcurrentClaims concurrentlyClaim(
      DraftCommitBinding leftBinding, DraftCommitBinding rightBinding) throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<ClaimAttempt> left =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return claimAttempt(leftBinding);
              });
      Future<ClaimAttempt> right =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return claimAttempt(rightBinding);
              });
      await(ready);
      start.countDown();
      return new ConcurrentClaims(left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }
  }

  private static ClaimAttempt claimAttempt(DraftCommitBinding binding) {
    try {
      return new ClaimAttempt(inTransaction(() -> fixture.coordinator().claim(binding)), null);
    } catch (RuntimeException exception) {
      return new ClaimAttempt(null, exception);
    }
  }

  private static void assertExactlyOneClaimSucceeded(ConcurrentClaims claims) {
    assertThat(claims.left().snapshot() == null).isNotEqualTo(claims.right().snapshot() == null);
    ClaimAttempt rejected = claims.left().snapshot() == null ? claims.left() : claims.right();
    ClaimAttempt succeeded = claims.left().snapshot() == null ? claims.right() : claims.left();
    assertThat(rejected.failure())
        .isInstanceOf(DraftCommitCoordinatorRepository.DraftCommitIdentityConflictException.class);
    assertThat(succeeded.failure()).isNull();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for concurrent Draft commit claim");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while coordinating concurrent Draft claims", exception);
    }
  }

  private record ClaimAttempt(CommitSnapshot snapshot, RuntimeException failure) {}

  private record ConcurrentClaims(ClaimAttempt left, ClaimAttempt right) {}

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate ownerTransaction,
      GameRepository games,
      VersionRepository versions,
      DraftCommitCoordinatorRepository coordinator) {
    TargetProof newTarget() {
      String tenantKey = "d-" + UUID.randomUUID().toString().replace("-", "");
      Game game = new Game();
      game.setTenantId(tenantKey);
      game.setName("Draft coordinator integration source");
      game.setDescription("Canonical Version source fixture");
      Game savedGame = Objects.requireNonNull(ownerTransaction.execute(status -> games.save(game)));

      Version version = new Version();
      version.setTenantId(savedGame.getTenantId());
      version.setVersionNumber(1);
      version.setVersionState(net.firedevops.firemud.gamedesign.model.VersionLifecycleState.DRAFT);
      version.setVersionStateEpoch(1L);
      Version savedVersion =
          Objects.requireNonNull(ownerTransaction.execute(status -> versions.save(version)));
      return new TargetProof(
          savedVersion.getCanonicalTenantId(),
          savedVersion.getCanonicalVersionId(),
          savedVersion.getId(),
          savedVersion.getTenantId(),
          savedVersion.getIdentitySourceGameRowId(),
          savedVersion.getIdentitySourceGameTenantKey(),
          savedVersion.getIdentitySourceProvenanceKind());
    }
  }

  private record OffsetDateTimePair(
      java.time.OffsetDateTime commitCreatedAt, java.time.OffsetDateTime ownerUpdatedAt) {}
}
