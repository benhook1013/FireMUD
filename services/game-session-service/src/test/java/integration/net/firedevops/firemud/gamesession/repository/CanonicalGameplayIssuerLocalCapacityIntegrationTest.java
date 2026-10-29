package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionAbortSource;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalRedisScriptContribution;
import net.firedevops.firemud.test.TestContainerImages;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual PostgreSQL/Redis proof for local issuer capacity, ordinary ADD, and release boundaries.
 * Account control, abort, lease, and source collaborators remain controlled; Game Session owns
 * durable preparation, physical projection, readback, and release completion.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@Execution(ExecutionMode.SAME_THREAD)
class CanonicalGameplayIssuerLocalCapacityIntegrationTest
    extends CanonicalGameplayIssuerLifecycleProofFixtures {
  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  @Override
  protected PostgreSQLContainer<?> postgres() {
    return POSTGRES;
  }

  @Override
  protected GenericContainer<?> redis() {
    return REDIS;
  }

  @Test
  void unrelatedAccountNewAndOriginalIssuerCleanupSurviveAccountControlAdvance() {
    try (var fixture = newFixture(BigInteger.valueOf(11), BigInteger.ONE, 0)) {
      byte[] partitionKey = fixture.issuerPartitionKey();
      fixture.publishOriginalWithControlledAccountLease();
      var abort = fixture.recordOriginalAccountAbort();
      assertThat(abort.isAccountAborted()).isTrue();

      String staleAccountASeed = "account-a-stale-add-after-control-advance";
      fixture.prepareOtherControllerNew(staleAccountASeed);
      CanonicalGameplayIssuerMemberV2Value originalMember =
          CanonicalGameplayIssuerMemberV2Codec.decode(fixture.originalMemberValueBytes());
      CanonicalGameplayBindingIdentity accountA = originalMember.owner();

      var intent =
          new CanonicalGameplayAccountAdmissionBarrierRepository(fixture.dsl())
              .beginFenceIntent(accountA.accountId(), uuid("account-a-control-advance"));
      assertThat(intent).isNotNull();
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle(
                      "SELECT state FROM game_session_canonical_account_coverage_control"
                          + " WHERE account_id = ?",
                      accountA.accountId())
                  .get(0, String.class))
          .isEqualTo("ACQUISITION_INTENT");

      var inventoryRepository = new CanonicalGameplayBindingInventoryRepository(fixture.dsl());
      String historyBeforeAbortRetry = durableHistoryImage(fixture.dsl(), NIL_UUID);
      String retainedRowsBeforeAbortRetry = fixture.retainedFirstNewAbortRowsImage();
      byte[] fieldsBeforeAbortRetry = physicalFieldSet(partitionKey);
      String controlRowsSql =
          "SELECT jsonb_build_object("
              + "'clock', (SELECT to_jsonb(clock) FROM game_session_canonical_binding_inventory_clock clock WHERE singleton_id = 1),"
              + " 'accountControl', (SELECT to_jsonb(control) FROM game_session_canonical_account_coverage_control control WHERE account_id = ?),"
              + " 'issuerControl', (SELECT to_jsonb(control) FROM game_session_canonical_issuer_layout_bootstrap_control control WHERE issuer_id = ?))::text";
      String controlRowsBeforeAbortRetry =
          fixture
              .dsl()
              .fetchSingle(controlRowsSql, accountA.accountId(), accountA.issuerId())
              .get(0, String.class);
      Runnable assertAbortRetryUnchanged =
          () -> {
            assertThat(durableHistoryImage(fixture.dsl(), NIL_UUID))
                .isEqualTo(historyBeforeAbortRetry);
            assertThat(fixture.retainedFirstNewAbortRowsImage())
                .isEqualTo(retainedRowsBeforeAbortRetry);
            assertThat(physicalFieldSet(partitionKey)).isEqualTo(fieldsBeforeAbortRetry);
            assertThat(
                    fixture
                        .dsl()
                        .fetchSingle(controlRowsSql, accountA.accountId(), accountA.issuerId())
                        .get(0, String.class))
                .isEqualTo(controlRowsBeforeAbortRetry);
          };

      // Exact terminal retries retain their original provenance after the real Account intent.
      var abortRetry =
          inventoryRepository.recordFirstNewAccountAbort(abort, abort.orphanCleanupId());
      assertThat(abortRetry.transition()).isEqualTo(abort.transition());
      assertThat(abortRetry.candidate()).isEqualTo(abort.candidate());
      assertThat(abortRetry.reservation()).isEqualTo(abort.reservation());
      assertThat(abortRetry.originalDecision().leaseEvidenceJson())
          .isEqualTo(abort.originalDecision().leaseEvidenceJson());
      assertThat(abortRetry.orphanCleanupId()).isEqualTo(abort.orphanCleanupId());
      assertAbortRetryUnchanged.run();

      var candidate = abort.candidate();
      var changedCandidate =
          new CanonicalGameplayBindingInventoryEntry(
              candidate.identity(),
              candidate.bindingRef(),
              candidate.bindingGeneration(),
              candidate.accountIndexFence(),
              candidate.issuerReservationId(),
              candidate.transitionId(),
              candidate.lifecycle(),
              candidate.accountIndexState(),
              candidate.issuerIndexState()
                      == CanonicalGameplayBindingInventoryEntry.IndexState.REPAIR_REQUIRED
                  ? CanonicalGameplayBindingInventoryEntry.IndexState.ACKNOWLEDGED
                  : CanonicalGameplayBindingInventoryEntry.IndexState.REPAIR_REQUIRED,
              candidate.inventoryRevision());
      var reservation = abort.reservation();
      var changedReservation =
          new CanonicalIssuerPartitionReservation(
              reservation.reservationId(),
              reservation.owner(),
              reservation.bindingGeneration(),
              reservation.transitionId(),
              reservation.partitionId(),
              reservation.lifecycle(),
              reservation.reservationFence(),
              reservation.issuerCoverageOperationId(),
              reservation.issuerCoverageOperationFence(),
              reservation.coverageFence().add(BigInteger.ONE),
              reservation.inventorySnapshotRevision(),
              reservation.inventoryRevision());
      // Shape-valid caller objects exercise the SQL comparison; no stored evidence is changed.
      for (var changedSource :
          List.of(
              new CanonicalGameplayAdmissionAbortSource(
                  abort.transition(),
                  abort.candidate(),
                  abort.reservation(),
                  abort.originalDecision(),
                  UUID.randomUUID()),
              new CanonicalGameplayAdmissionAbortSource(
                  abort.transition(),
                  changedCandidate,
                  abort.reservation(),
                  abort.originalDecision(),
                  abort.orphanCleanupId()),
              new CanonicalGameplayAdmissionAbortSource(
                  abort.transition(),
                  abort.candidate(),
                  changedReservation,
                  abort.originalDecision(),
                  abort.orphanCleanupId()))) {
        assertThatThrownBy(
                () ->
                    inventoryRepository.recordFirstNewAccountAbort(
                        changedSource, changedSource.orphanCleanupId()))
            .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
            .hasMessageContaining("Retained first-NEW abort source changed during exact retry");
        assertAbortRetryUnchanged.run();
      }
      assertThat(
              inventoryRepository
                  .recordFirstNewAccountAbort(abort, abort.orphanCleanupId())
                  .orphanCleanupId())
          .isEqualTo(abort.orphanCleanupId());
      assertAbortRetryUnchanged.run();

      byte[] beforeStaleAdd = physicalFieldSet(partitionKey);
      long mutationsBeforeStaleAdd = partitionMutationCount(fixture.dsl());
      assertThatThrownBy(() -> fixture.projectOtherControllerNew(staleAccountASeed))
          .isInstanceOf(IllegalStateException.class);
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(beforeStaleAdd);
      assertThat(partitionMutationCount(fixture.dsl())).isEqualTo(mutationsBeforeStaleAdd);

      CanonicalGameplayBindingIdentity accountB =
          otherAccountInPartition(accountA, fixture.originalPartitionId());
      var currentIssuerState =
          new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
              originalMember.sourceCheckpoint(), originalMember.incarnation());
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
          issuerStateSource = () -> currentIssuerState;
      var accountBOperation =
          new CanonicalGameplayIssuerBindingLocalOperationRepository(
                  fixture.dsl(), issuerStateSource)
              .beginOrResumeOrdinaryNew(
                  new CanonicalGameplayIssuerBindingLocalOperation.Request(
                      UUID.randomUUID(), UUID.randomUUID(), accountB, null, null));
      var localProjection =
          new CanonicalGameplayIssuerBindingLocalProjectionRepository(
              fixture.dsl(), issuerStateSource);
      var accountBOwner =
          new CanonicalGameplayIssuerBindingLocalRedisOwner(
              issuerOwner,
              RedisScriptCatalog.fromContributions(
                  List.of(new CanonicalGameplayIssuerBindingLocalRedisScriptContribution())),
              localProjection,
              issuerStateSource);
      var accountBAdd = accountBOwner.projectAndRead(accountBOperation);
      assertThat(accountBAdd.scriptOutcome()).isEqualTo(1L);

      String accountBCapacityField =
          CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
              accountBOperation.reservation().reservationId());
      String accountBBindingField =
          new String(
              accountBOperation.memberValue().owner().bindingRef().bytes(), StandardCharsets.UTF_8);
      var accountBFields = decodeFields(physicalFieldSet(partitionKey));
      assertThat(accountBFields)
          .containsEntry(accountBCapacityField, accountBOperation.exactMemberValueBytes())
          .doesNotContainKey(accountBBindingField);
      assertThat(localOperationCount(fixture.dsl())).isEqualTo(3L);

      var pendingRelease = fixture.prepareIssuerRelease();
      var releaseReadback = fixture.projectIssuerRelease(pendingRelease);
      var release = fixture.completeIssuerRelease(pendingRelease, releaseReadback);
      assertThat(release.status())
          .isEqualTo(CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.RELEASED);
      var releaseRetry = fixture.prepareIssuerRelease();
      assertThat(releaseRetry.operationId()).isEqualTo(release.operationId());
      assertThat(fixture.projectIssuerRelease(releaseRetry).exactPhysicalFieldSetBytes())
          .isEqualTo(release.exactPhysicalReadbackFieldSetBytes());
    }
  }

  @Test
  void addCurrentnessRereadKeepsTheTargetAccountControlFenceWithoutPreparingAnotherMutation() {
    try (var fixture = newFixture(BigInteger.valueOf(12), BigInteger.ONE, 0)) {
      String seed = "add-currentness-account-control-reread";
      fixture.prepareOtherControllerNew(seed);
      var preparedAdd = fixture.prepareOtherAddMutation(seed);
      byte[] partitionKey = fixture.issuerPartitionKey();
      byte[] fieldsBeforeReread = physicalFieldSet(partitionKey);
      long mutationsBeforeReread = partitionMutationCount(fixture.dsl());
      CanonicalGameplayIssuerMemberV2Value originalMember =
          CanonicalGameplayIssuerMemberV2Codec.decode(fixture.originalMemberValueBytes());
      var currentIssuerState =
          new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
              originalMember.sourceCheckpoint(), originalMember.incarnation());
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
          issuerStateSource = () -> currentIssuerState;

      new CanonicalGameplayAccountAdmissionBarrierRepository(fixture.dsl())
          .beginFenceIntent(
              originalMember.owner().accountId(), uuid("add-currentness-account-fence"));

      assertThatThrownBy(
              () ->
                  new CanonicalGameplayIssuerBindingLocalProjectionRepository(
                          fixture.dsl(), issuerStateSource)
                      .requireStillCurrent(preparedAdd))
          .isInstanceOf(IllegalStateException.class);
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(fieldsBeforeReread);
      assertThat(partitionMutationCount(fixture.dsl())).isEqualTo(mutationsBeforeReread);
    }
  }

  @Test
  void refusesRegistrationBeyondTheCurrentLocalEntryBoundWithoutWrites() {
    try (var fixture =
        newFixture(BigInteger.valueOf(9), BigInteger.ONE, BigInteger.valueOf(512), 0)) {
      for (int index = 1; index < PARTITION_CAPACITY.intValueExact(); index++) {
        fixture.prepareOtherControllerNew("local-entry-bound-" + index);
      }

      assertThat(localOperationCount(fixture.dsl())).isEqualTo(256L);
      var currentWork = fixture.readCurrentOriginalPartitionWork();
      assertThat(currentWork.localEntries()).hasSize(256);

      byte[] fieldsBeforeRefusal = physicalFieldSet(fixture.issuerPartitionKey());
      long mutationCountBeforeRefusal = partitionMutationCount(fixture.dsl());
      String sourceImageBeforeRefusal = durableHistoryImage(fixture.dsl(), NIL_UUID);
      assertThatThrownBy(() -> fixture.prepareOtherControllerNew("local-entry-bound-257"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("The local partition operation set has reached the physical owner bound");
      assertThat(durableHistoryImage(fixture.dsl(), NIL_UUID)).isEqualTo(sourceImageBeforeRefusal);
      assertThat(localOperationCount(fixture.dsl())).isEqualTo(256L);
      assertThat(partitionMutationCount(fixture.dsl())).isEqualTo(mutationCountBeforeRefusal);
      assertThat(physicalFieldSet(fixture.issuerPartitionKey())).isEqualTo(fieldsBeforeRefusal);
    }
  }

  @Test
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "VA_PRIMITIVE_ARRAY_PASSED_TO_OBJECT_VARARG",
      justification =
          "RedisCommands<byte[], byte[]> takes binary field keys; byte[][] supplies one field. Native deletion and readback verify the exact semantics.")
  void completedAddRetryReadsReleasedZeroCapacityStateWithoutWritesAndRejectsPhysicalDrift() {
    try (var fixture = newFixture(BigInteger.valueOf(16), BigInteger.ONE, 0)) {
      String capacityAddSql =
          "SELECT to_jsonb(mutation)::text FROM game_session_canonical_issuer_partition_mutation mutation"
              + " WHERE mutation_kind = 'ADD_CAPACITY' AND owner_operation_id = ?";
      String originalCapacityAddReceipt =
          fixture
              .dsl()
              .fetchSingle(capacityAddSql, fixture.originalOperationId())
              .get(0, String.class);
      fixture.publishOriginalWithControlledAccountLease();
      assertThat(fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();
      var pendingRelease = fixture.prepareIssuerRelease();
      var released =
          fixture.completeIssuerRelease(
              pendingRelease, fixture.projectIssuerRelease(pendingRelease));
      assertThat(released.status())
          .isEqualTo(CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.RELEASED);
      var originalMember =
          CanonicalGameplayIssuerMemberV2Codec.decode(fixture.originalMemberValueBytes());
      var current =
          new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
              originalMember.sourceCheckpoint(), originalMember.incarnation());
      var projectionRepository =
          new CanonicalGameplayIssuerBindingLocalProjectionRepository(fixture.dsl(), () -> current);
      var originalOperation = released.originalOperation();
      var work = projectionRepository.readPreparedPartitionWork(originalOperation);
      assertThat(work.baselineProof().physicalCountedCapacityCount()).isZero();
      assertThat(work.targetEntry().countsTowardCapacity()).isFalse();
      assertThat(work.partitionCapacity()).isZero();
      assertThat(work.aggregateCapacity()).isZero();
      var owner =
          new CanonicalGameplayIssuerBindingLocalRedisOwner(
              issuerOwner,
              RedisScriptCatalog.fromContributions(
                  List.of(new CanonicalGameplayIssuerBindingLocalRedisScriptContribution())),
              projectionRepository,
              () -> current);
      byte[] partitionKey = fixture.issuerPartitionKey();
      byte[] releasedFields = physicalFieldSet(partitionKey);
      assertThat(capacityFieldCount(releasedFields)).isEqualTo(1L);
      String releasedCapacityName =
          CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
              released.releasedMemberValue().reservation().reservationId());
      assertThat(decodeFields(releasedFields))
          .containsEntry(releasedCapacityName, released.exactReleasedMemberValueBytes());
      String historyBefore = durableHistoryImage(fixture.dsl(), NIL_UUID);
      String headBefore = partitionHeadImage(fixture.dsl(), fixture.originalPartitionId());
      String anchorBefore = anchorAndPartitionProofImage(fixture.dsl());
      String retainedBefore = fixture.retainedFirstNewAbortRowsImage();
      Runnable assertSqlUnchanged =
          () -> {
            assertThat(
                    fixture
                        .dsl()
                        .fetchSingle(capacityAddSql, fixture.originalOperationId())
                        .get(0, String.class))
                .isEqualTo(originalCapacityAddReceipt);
            assertThat(durableHistoryImage(fixture.dsl(), NIL_UUID)).isEqualTo(historyBefore);
            assertThat(partitionHeadImage(fixture.dsl(), fixture.originalPartitionId()))
                .isEqualTo(headBefore);
            assertThat(anchorAndPartitionProofImage(fixture.dsl())).isEqualTo(anchorBefore);
            assertThat(fixture.retainedFirstNewAbortRowsImage()).isEqualTo(retainedBefore);
          };
      var retry = owner.projectAndRead(originalOperation);
      assertThat(retry.scriptOutcome()).isEqualTo(3L);
      assertThat(retry.exactReadbackBytes()).containsExactly(releasedFields);
      assertThat(physicalFieldSet(partitionKey)).containsExactly(releasedFields);
      assertSqlUnchanged.run();

      byte[] capacityField = releasedCapacityName.getBytes(StandardCharsets.US_ASCII);
      byte[] revisionField =
          CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD.getBytes(
              StandardCharsets.US_ASCII);
      // Administrator-injected absence and conflicting bytes remain non-admitting on read-only
      // retry.
      for (byte[] field : List.of(capacityField, revisionField)) {
        byte[] originalValue =
            java.util.Objects.requireNonNull(
                administratorCommands.hget(partitionKey, field),
                "Expected original released field");
        for (boolean missing : List.of(true, false)) {
          if (missing) administratorCommands.hdel(partitionKey, new byte[][] {field});
          else
            administratorCommands.hset(
                partitionKey,
                field,
                (Arrays.equals(field, revisionField)
                        ? UUID.randomUUID().toString()
                        : "changed-release-field")
                    .getBytes(StandardCharsets.US_ASCII));
          byte[] injectedFields = physicalFieldSet(partitionKey);
          assertThatThrownBy(() -> owner.projectAndRead(originalOperation))
              .isInstanceOf(IllegalStateException.class);
          assertThat(physicalFieldSet(partitionKey)).containsExactly(injectedFields);
          assertSqlUnchanged.run();
          administratorCommands.hset(partitionKey, field, originalValue);
          assertThat(physicalFieldSet(partitionKey)).containsExactly(releasedFields);
        }
      }
      assertThat(owner.projectAndRead(originalOperation).scriptOutcome()).isEqualTo(3L);
      assertThat(physicalFieldSet(partitionKey)).containsExactly(releasedFields);
      assertSqlUnchanged.run();
    }
  }

  @Test
  void ordinaryAddRefusesMissingPartitionRevisionWithoutRedisOrSqlWrites() throws Exception {
    try (var fixture = newFixture(BigInteger.valueOf(4), BigInteger.ONE, 0)) {
      String seed = "missing-revision-add";
      fixture.prepareOtherControllerNew(seed);
      var work = fixture.prepareOtherAddMutation(seed);
      var mutation = work.physicalMutation();
      assertMissingRevisionRefusal(
          fixture,
          fixture.issuerPartitionKey(),
          mutation,
          "game_session_canonical_issuer_binding_local_operation",
          "operation_id",
          fixture.otherOperation(seed).operationId(),
          addInvocation(fixture, work),
          () -> fixture.projectOtherControllerNew(seed));
    }
  }

  @Test
  void ordinaryReleaseRefusesMissingPartitionRevisionWithoutRedisOrSqlWrites() throws Exception {
    try (var fixture = newFixture(BigInteger.valueOf(5), BigInteger.ONE, 0)) {
      fixture.publishOriginalWithControlledAccountLease();
      assertThat(fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();
      var pendingRelease = fixture.prepareIssuerRelease();
      assertMissingRevisionRefusal(
          fixture,
          fixture.issuerPartitionKey(),
          pendingRelease.physicalMutation(),
          "game_session_canonical_issuer_binding_local_release_operation",
          "operation_id",
          pendingRelease.operationId(),
          releaseInvocation(fixture, pendingRelease),
          () -> fixture.projectIssuerRelease(pendingRelease));
    }
  }

  @Test
  void actualReleaseReadbackPersistsAndReloadsWithMoreThan513PhysicalFields() throws Exception {
    try (var fixture = newFixture(BigInteger.valueOf(8), BigInteger.ONE, 250)) {
      assertThat(fixture.originalBaselineReservationCount()).isEqualTo(250);
      assertThat(fixture.originalBaselineCountedCapacity()).isEqualTo(250);
      assertThat(fixture.originalBaselineBindingCount()).isEqualTo(250);
      fixture.publishOriginalWithControlledAccountLease();
      for (int index = 0; index < 4; index++) {
        String seed = "wide-active-" + index;
        fixture.prepareOtherControllerNew(seed);
        fixture.projectOtherControllerNew(seed);
        fixture.publishOtherControllerNew(seed);
      }

      String terminalSeed = "wide-retained-terminal";
      fixture.prepareOtherControllerNew(terminalSeed);
      fixture.projectOtherControllerNew(terminalSeed);
      fixture.publishOtherControllerNew(terminalSeed);
      assertThat(fixture.recordOtherAccountAbort(terminalSeed).isAccountAborted()).isTrue();
      var terminalRelease = fixture.prepareIssuerRelease(terminalSeed);
      var terminalReadback = fixture.projectIssuerRelease(terminalRelease);
      assertThat(fixture.completeIssuerRelease(terminalRelease, terminalReadback).status())
          .isEqualTo(CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.RELEASED);

      String wideReleaseSeed = "wide-release-readback";
      UUID wideOperationId = fixture.prepareOtherControllerNew(wideReleaseSeed);
      var pendingAdd = fixture.prepareOtherAddMutation(wideReleaseSeed);
      assertThat(pendingAdd.aggregateCapacity()).isEqualTo(PARTITION_CAPACITY);
      assertThat(fixture.projectOtherControllerNew(wideReleaseSeed).scriptOutcome()).isEqualTo(1L);
      fixture.publishOtherControllerNew(wideReleaseSeed);
      assertThat(fixture.recordOtherAccountAbort(wideReleaseSeed).isAccountAborted()).isTrue();

      var pendingRelease = fixture.prepareIssuerRelease(wideReleaseSeed);
      var actualReadback = fixture.projectIssuerRelease(pendingRelease);
      assertThat(actualReadback.scriptOutcome()).isEqualTo(1L);
      assertThat(decodeFields(actualReadback.exactPhysicalFieldSetBytes()).size()).isEqualTo(514);
      var completedRelease = fixture.completeIssuerRelease(pendingRelease, actualReadback);
      assertThat(completedRelease.status())
          .isEqualTo(CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.RELEASED);
      assertThat(completedRelease.exactPhysicalReadbackFieldSetBytes())
          .isEqualTo(actualReadback.exactPhysicalFieldSetBytes());

      var reloadedRelease = fixture.prepareIssuerRelease(wideReleaseSeed);
      assertThat(reloadedRelease.operationId()).isEqualTo(pendingRelease.operationId());
      assertThat(reloadedRelease.exactPhysicalReadbackFieldSetBytes())
          .isEqualTo(actualReadback.exactPhysicalFieldSetBytes());
      var completedRetry = fixture.projectIssuerRelease(reloadedRelease);
      assertThat(completedRetry.scriptOutcome()).isEqualTo(2L);
      assertThat(completedRetry.exactPhysicalFieldSetBytes())
          .isEqualTo(actualReadback.exactPhysicalFieldSetBytes());
      assertThat(physicalFieldSet(fixture.issuerPartitionKey()))
          .isEqualTo(actualReadback.exactPhysicalFieldSetBytes());
      assertThat(localOperationCount(fixture.dsl())).isEqualTo(7L);
      assertThat(releaseCount(fixture.dsl(), "RELEASED")).isEqualTo(2L);
      assertThat(wideOperationId).isEqualTo(pendingRelease.originalOperationId());
    }
  }
}
