package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalRetirementRedisScriptContribution;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual PostgreSQL/Redis proof of original-operation physical retirement and bounded capacity
 * turnover. Account lease, abort, source, launch, and incarnation inputs remain controlled
 * collaborators.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@Execution(ExecutionMode.SAME_THREAD)
class CanonicalGameplayIssuerPhysicalRetirementIntegrationTest
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
  void retiresOriginalReleasesAndContinuesPastThePhysicalHistoryBound() throws Exception {
    try (var fixture = newFixture()) {
      assertThat(currentOperationIds(fixture.dsl())).containsExactly(fixture.originalOperationId());
      byte[] partitionKey = fixture.issuerPartitionKey();
      byte[] initialFields = physicalFieldSet(partitionKey);
      int baselineFieldCount =
          fixture.originalBaselineReservationCount() + fixture.originalBaselineBindingCount();
      assertThat(decodeFields(initialFields).size()).isEqualTo(baselineFieldCount + 3);

      fixture.publishOriginalWithControlledAccountLease();
      var originalAbort = fixture.recordOriginalAccountAbort();
      assertThat(originalAbort.isAccountAborted()).isTrue();

      byte[] beforePrematureRetirement = physicalFieldSet(partitionKey);
      String historyBeforePrematureRetirement = durableHistoryImage(fixture.dsl(), NIL_UUID);
      String headBeforePrematureRetirement =
          partitionHeadImage(fixture.dsl(), fixture.originalPartitionId());
      String inventoryClockBeforePrematureRetirement =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT to_jsonb(clock)::text FROM"
                      + " game_session_canonical_binding_inventory_clock clock"
                      + " WHERE singleton_id = 1")
              .get(0, String.class);
      long releasesBeforePrematureRetirement =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT count(*) FROM"
                      + " game_session_canonical_issuer_binding_local_release_operation")
              .get(0, Long.class);
      long mutationsBeforePrematureRetirement = partitionMutationCount(fixture.dsl());
      assertThatThrownBy(() -> fixture.prepareIssuerRetirement(fixture.transitionId()))
          .isInstanceOf(IllegalStateException.class);
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(beforePrematureRetirement);
      assertThat(durableHistoryImage(fixture.dsl(), NIL_UUID))
          .isEqualTo(historyBeforePrematureRetirement);
      assertThat(partitionHeadImage(fixture.dsl(), fixture.originalPartitionId()))
          .isEqualTo(headBeforePrematureRetirement);
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle(
                      "SELECT to_jsonb(clock)::text FROM"
                          + " game_session_canonical_binding_inventory_clock clock"
                          + " WHERE singleton_id = 1")
                  .get(0, String.class))
          .isEqualTo(inventoryClockBeforePrematureRetirement);
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) FROM"
                          + " game_session_canonical_issuer_binding_local_release_operation")
                  .get(0, Long.class))
          .isEqualTo(releasesBeforePrematureRetirement);
      assertThat(partitionMutationCount(fixture.dsl()))
          .isEqualTo(mutationsBeforePrematureRetirement);
      assertThat(retirementCount(fixture.dsl())).isZero();

      var originalRelease = completeReleaseAfterLostReply(fixture, null);
      assertThat(currentOperationIds(fixture.dsl())).containsExactly(fixture.originalOperationId());

      String newerSeed = lifetimeSeed(2);
      UUID newerOperationId = fixture.prepareOtherControllerNew(newerSeed);
      String neverProjectedSeed = "same-partition-never-projected";
      UUID neverProjectedOperationId = fixture.prepareOtherControllerNew(neverProjectedSeed);
      var neverProjectedOperation = fixture.otherOperation(neverProjectedSeed);
      var newerOperation = fixture.otherOperation(newerSeed);
      assertThat(currentOperationIds(fixture.dsl()))
          .containsExactlyInAnyOrder(
              fixture.originalOperationId(), newerOperationId, neverProjectedOperationId);
      assertThat(neverProjectedOperationId).isEqualTo(neverProjectedOperation.operationId());
      assertThat(neverProjectedOperation.reservation().partitionId())
          .isEqualTo(newerOperation.reservation().partitionId());
      String newerCapacityField =
          CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
              newerOperation.reservation().reservationId());
      String neverProjectedCapacityField =
          CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
              neverProjectedOperation.reservation().reservationId());
      assertThat(decodeFields(physicalFieldSet(partitionKey)))
          .doesNotContainKey(newerCapacityField)
          .doesNotContainKey(neverProjectedCapacityField);
      var pendingAdd = fixture.prepareOtherAddMutation(newerSeed);
      byte[] currentRevisionFieldValue =
          pendingAdd
              .physicalMutation()
              .expectedRevision()
              .toString()
              .getBytes(StandardCharsets.US_ASCII);
      Map<String, byte[]> staleReadbackFields =
          new HashMap<>(decodeFields(physicalFieldSet(partitionKey)));
      assertThat(
              staleReadbackFields.get(
                  CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD))
          .containsExactly(currentRevisionFieldValue);
      byte[] staleReadback =
          CanonicalGameplayIssuerBootstrapCoverageRepository.encodeFieldSet(staleReadbackFields);
      String mutationCustodyBeforeStaleReadback =
          pendingMutationCustodyImage(
              fixture.dsl(),
              pendingAdd.physicalMutation(),
              "game_session_canonical_issuer_binding_local_operation",
              "operation_id",
              pendingAdd.physicalMutation().ownerOperationId());
      byte[] partitionBeforeStaleReadback = physicalFieldSet(partitionKey);
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .transactionResult(
                          configuration ->
                              CanonicalGameplayIssuerPartitionMutationRepository
                                  .completeInTransaction(
                                      org.jooq.impl.DSL.using(configuration),
                                      pendingAdd.physicalMutation(),
                                      staleReadback)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("exact current physical mutation revision");
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(partitionBeforeStaleReadback);
      assertThat(
              pendingMutationCustodyImage(
                  fixture.dsl(),
                  pendingAdd.physicalMutation(),
                  "game_session_canonical_issuer_binding_local_operation",
                  "operation_id",
                  pendingAdd.physicalMutation().ownerOperationId()))
          .isEqualTo(mutationCustodyBeforeStaleReadback);

      LuaInvocation staleAddInvocation = addInvocation(fixture, pendingAdd);
      var addReadback = fixture.projectOtherControllerNew(newerSeed);
      assertThat(addReadback.scriptOutcome()).isEqualTo(1L);
      var addFields = decodeFields(addReadback.exactReadbackBytes());
      assertThat(addFields)
          .containsEntry(newerCapacityField, newerOperation.exactMemberValueBytes());
      assertThat(addFields)
          .doesNotContainKey(
              new String(
                  newerOperation.memberValue().owner().bindingRef().bytes(),
                  StandardCharsets.UTF_8));
      assertThat(addFields).doesNotContainKey(neverProjectedCapacityField);
      assertThat(addFields)
          .doesNotContainKey(
              new String(
                  neverProjectedOperation.memberValue().owner().bindingRef().bytes(),
                  StandardCharsets.UTF_8));
      assertThat(fixture.retryOtherControllerNew(newerSeed)).isEqualTo(newerOperationId);
      byte[] afterAdd = physicalFieldSet(partitionKey);
      assertThat(fixture.projectOtherControllerNew(newerSeed).scriptOutcome()).isEqualTo(3L);
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(afterAdd);
      assertOuterBootstrapRetryIsReadOnly(fixture, partitionKey);

      fixture.publishOtherControllerNew(newerSeed);
      assertThat(fixture.recordOtherAccountAbort(newerSeed).isAccountAborted()).isTrue();
      var pendingRelease = fixture.prepareIssuerRelease(newerSeed);
      LuaInvocation staleReleaseInvocation = releaseInvocation(fixture, pendingRelease);
      var releaseReadback = fixture.projectIssuerRelease(pendingRelease);
      var newerRelease = fixture.completeIssuerRelease(pendingRelease, releaseReadback);
      assertThat(newerRelease.status())
          .isEqualTo(CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.RELEASED);
      assertOuterBootstrapRetryIsReadOnly(fixture, partitionKey);
      assertThat(decodeFields(physicalFieldSet(partitionKey)))
          .doesNotContainKey(neverProjectedCapacityField);
      byte[] beforeReleaseRetry = physicalFieldSet(partitionKey);
      var completedReleaseRetry = fixture.prepareIssuerRelease(newerSeed);
      var releaseRetryReadback = fixture.projectIssuerRelease(completedReleaseRetry);
      assertThat(releaseRetryReadback.scriptOutcome()).isEqualTo(2L);
      assertThat(releaseRetryReadback.exactPhysicalFieldSetBytes())
          .isEqualTo(newerRelease.exactPhysicalReadbackFieldSetBytes());
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(beforeReleaseRetry);

      assertThat(releaseCount(fixture.dsl(), "RELEASED")).isEqualTo(2L);
      assertThat(retirementCount(fixture.dsl())).isZero();
      byte[] beforeOldestRetirement = physicalFieldSet(partitionKey);
      assertThat(capacityFieldCount(beforeOldestRetirement)).isEqualTo(2L);
      assertThat(decodeFields(beforeOldestRetirement).size()).isEqualTo(baselineFieldCount + 4);
      String historyBeforeOriginalRetirement = durableHistoryImage(fixture.dsl(), NIL_UUID);

      var pendingOldestRetirement = fixture.prepareIssuerRetirement(fixture.transitionId());
      assertThat(pendingOldestRetirement.release().operationId())
          .isEqualTo(originalRelease.operationId());
      assertThat(pendingOldestRetirement.status())
          .isEqualTo(
              CanonicalGameplayIssuerBindingLocalRetirementOperation.Status.PENDING_PHYSICAL_PROOF);
      LuaInvocation oldestRetirementInvocation =
          retirementInvocation(fixture, pendingOldestRetirement);

      List<UUID> membershipBeforeRetirement = currentOperationIds(fixture.dsl());
      assertThat(membershipBeforeRetirement)
          .containsExactlyInAnyOrder(
              fixture.originalOperationId(), newerOperationId, neverProjectedOperationId);
      long operationCountBeforeBlockedAdmission = localOperationCount(fixture.dsl());
      assertThatThrownBy(() -> fixture.prepareOtherControllerNew("pending-retirement-blocked"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("same-partition physical mutation is unresolved");
      assertThat(localOperationCount(fixture.dsl()))
          .isEqualTo(operationCountBeforeBlockedAdmission);
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(beforeOldestRetirement);

      fixture.moveIssuerSourceCheckpoint();
      assertThatThrownBy(() -> fixture.retireIssuerCapacity(pendingOldestRetirement))
          .isInstanceOf(IllegalStateException.class);
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(beforeOldestRetirement);
      fixture.restoreIssuerState();

      byte[] foreignField = "!issuer-test-foreign/v1".getBytes(StandardCharsets.US_ASCII);
      byte[] foreignValue = "controlled-foreign-field".getBytes(StandardCharsets.US_ASCII);
      byte[][] foreignFields = {foreignField};
      administratorCommands.hset(partitionKey, foreignField, foreignValue);
      byte[] fieldsWithForeign = physicalFieldSet(partitionKey);
      assertThatThrownBy(() -> fixture.retireIssuerCapacity(pendingOldestRetirement))
          .isInstanceOf(IllegalStateException.class);
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(fieldsWithForeign);
      administratorCommands.hdel(partitionKey, foreignFields);
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(beforeOldestRetirement);

      long lostRetirementReply = evaluate(oldestRetirementInvocation);
      assertThat(lostRetirementReply).isEqualTo(1L);
      byte[] afterUncommittedRetirement = physicalFieldSet(partitionKey);
      assertRetirementFieldDelta(
          beforeOldestRetirement, afterUncommittedRetirement, pendingOldestRetirement);
      assertThat(retirementStatus(fixture.dsl(), pendingOldestRetirement.operationId()))
          .isEqualTo("PENDING_PHYSICAL_PROOF");
      assertThat(currentOperationIds(fixture.dsl())).isEqualTo(membershipBeforeRetirement);

      // Exercise the actual SQL completion and automatic membership removal, then abort the
      // transaction. Physical absence alone must not make the original cease to be current.
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .transaction(
                          configuration -> {
                            DSLContext transaction = DSL.using(configuration);
                            CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(
                                transaction);
                            CanonicalGameplayIssuerPartitionMutationRepository
                                .completeInTransaction(
                                    transaction,
                                    pendingOldestRetirement.physicalMutation(),
                                    afterUncommittedRetirement);
                            assertThat(
                                    transaction.execute(
                                        "UPDATE game_session_canonical_issuer_binding_local_retirement"
                                            + " SET status = 'RETIRED',"
                                            + " exact_absence_readback_field_set_bytes = ?,"
                                            + " exact_absence_readback_field_set_digest = ?,"
                                            + " completed_at = CURRENT_TIMESTAMP"
                                            + " WHERE retirement_operation_id = ?",
                                        afterUncommittedRetirement,
                                        CanonicalGameplayIssuerMemberV2Codec.sha256(
                                            afterUncommittedRetirement),
                                        pendingOldestRetirement.operationId()))
                                .isOne();
                            assertThat(currentOperationIds(transaction))
                                .containsExactlyInAnyOrder(
                                    newerOperationId, neverProjectedOperationId);
                            throw new IllegalStateException(
                                "controlled retirement completion rollback");
                          }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("controlled retirement completion rollback");
      assertThat(currentOperationIds(fixture.dsl())).isEqualTo(membershipBeforeRetirement);
      assertThat(retirementStatus(fixture.dsl(), pendingOldestRetirement.operationId()))
          .isEqualTo("PENDING_PHYSICAL_PROOF");
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(afterUncommittedRetirement);

      var resumedOldestRetirement = fixture.prepareIssuerRetirement(fixture.transitionId());
      assertThat(resumedOldestRetirement.operationId())
          .isEqualTo(pendingOldestRetirement.operationId());
      assertThat(resumedOldestRetirement.physicalMutation().expectedRevision())
          .isEqualTo(pendingOldestRetirement.physicalMutation().expectedRevision());
      assertThat(resumedOldestRetirement.physicalMutation().successorRevision())
          .isEqualTo(pendingOldestRetirement.physicalMutation().successorRevision());
      var recoveredOldestProof = fixture.retireIssuerCapacity(resumedOldestRetirement);
      assertThat(currentOperationIds(fixture.dsl()))
          .containsExactlyInAnyOrder(newerOperationId, neverProjectedOperationId);
      assertThat(recoveredOldestProof.scriptOutcome()).isEqualTo(2L);
      assertThat(recoveredOldestProof.exactAbsenceReadbackFieldSetBytes())
          .isEqualTo(afterUncommittedRetirement);
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(afterUncommittedRetirement);
      assertThat(durableHistoryImage(fixture.dsl(), NIL_UUID))
          .isEqualTo(historyBeforeOriginalRetirement);
      assertThat(capacityFieldCount(afterUncommittedRetirement)).isEqualTo(1L);
      assertThat(decodeFields(afterUncommittedRetirement))
          .containsEntry(newerCapacityField, newerRelease.exactReleasedMemberValueBytes());

      byte[] beforeCompletedRetirementRetry = physicalFieldSet(partitionKey);
      var completedOldestRetirement = fixture.prepareIssuerRetirement(fixture.transitionId());
      assertThat(completedOldestRetirement.status())
          .isEqualTo(CanonicalGameplayIssuerBindingLocalRetirementOperation.Status.RETIRED);
      var completedOldestRetryProof = fixture.retireIssuerCapacity(completedOldestRetirement);
      assertThat(currentOperationIds(fixture.dsl()))
          .containsExactlyInAnyOrder(newerOperationId, neverProjectedOperationId);
      assertThat(completedOldestRetryProof.scriptOutcome()).isEqualTo(2L);
      assertThat(completedOldestRetryProof.exactAbsenceReadbackFieldSetBytes())
          .isEqualTo(beforeCompletedRetirementRetry);
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(beforeCompletedRetirementRetry);
      assertOuterBootstrapRetryIsReadOnly(fixture, partitionKey);

      assertStaleLuaRejectedWithoutWrites(staleAddInvocation, partitionKey);
      assertStaleLuaRejectedWithoutWrites(staleReleaseInvocation, partitionKey);

      UUID newerTransitionId = fixture.otherOperation(newerSeed).request().transitionId();
      var pendingNewerRetirement = fixture.prepareIssuerRetirement(newerTransitionId);
      byte[] beforeNewerRetirement = physicalFieldSet(partitionKey);
      var completedNewerRetirement = fixture.retireIssuerCapacity(pendingNewerRetirement);
      byte[] afterNewerRetirement = physicalFieldSet(partitionKey);
      assertThat(completedNewerRetirement.scriptOutcome()).isEqualTo(1L);
      assertThat(completedNewerRetirement.exactAbsenceReadbackFieldSetBytes())
          .isEqualTo(afterNewerRetirement);
      assertThat(decodeFields(afterNewerRetirement)).doesNotContainKey(neverProjectedCapacityField);
      assertRetirementFieldDelta(
          beforeNewerRetirement, afterNewerRetirement, pendingNewerRetirement);
      byte[] beforeNewerRetirementRetry = physicalFieldSet(partitionKey);
      var completedNewerRetirementRetry = fixture.prepareIssuerRetirement(newerTransitionId);
      assertThat(completedNewerRetirementRetry.status())
          .isEqualTo(CanonicalGameplayIssuerBindingLocalRetirementOperation.Status.RETIRED);
      var newerRetirementRetryProof = fixture.retireIssuerCapacity(completedNewerRetirementRetry);
      assertThat(newerRetirementRetryProof.scriptOutcome()).isEqualTo(2L);
      assertThat(newerRetirementRetryProof.exactAbsenceReadbackFieldSetBytes())
          .isEqualTo(beforeNewerRetirementRetry);
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(beforeNewerRetirementRetry);

      assertThat(currentOperationIds(fixture.dsl())).containsExactly(neverProjectedOperationId);
      for (int index = 3; index <= 256; index++) {
        String seed = lifetimeSeed(index);
        UUID cycleOperationId = fixture.prepareOtherControllerNew(seed);
        assertThat(currentOperationIds(fixture.dsl()))
            .containsExactlyInAnyOrder(neverProjectedOperationId, cycleOperationId);
        assertThat(fixture.projectOtherControllerNew(seed).scriptOutcome()).isEqualTo(1L);
        fixture.publishOtherControllerNew(seed);
        assertThat(fixture.recordOtherAccountAbort(seed).isAccountAborted()).isTrue();

        var pendingCycleRelease = fixture.prepareIssuerRelease(seed);
        var cycleReleaseReadback = fixture.projectIssuerRelease(pendingCycleRelease);
        var completedCycleRelease =
            fixture.completeIssuerRelease(pendingCycleRelease, cycleReleaseReadback);
        assertThat(completedCycleRelease.status())
            .isEqualTo(CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.RELEASED);

        UUID transitionId = fixture.otherOperation(seed).request().transitionId();
        var pendingCycleRetirement = fixture.prepareIssuerRetirement(transitionId);
        byte[] beforeCycleRetirement = physicalFieldSet(partitionKey);
        var cycleRetirement = fixture.retireIssuerCapacity(pendingCycleRetirement);
        assertThat(currentOperationIds(fixture.dsl())).containsExactly(neverProjectedOperationId);
        byte[] afterCycleRetirement = physicalFieldSet(partitionKey);
        assertThat(cycleRetirement.scriptOutcome()).isEqualTo(1L);
        assertThat(cycleRetirement.exactAbsenceReadbackFieldSetBytes())
            .isEqualTo(afterCycleRetirement);
        assertRetirementFieldDelta(
            beforeCycleRetirement, afterCycleRetirement, pendingCycleRetirement);
        assertThat(capacityFieldCount(afterCycleRetirement)).isZero();
        assertThat(decodeFields(afterCycleRetirement).size()).isEqualTo(baselineFieldCount + 2);
      }

      assertThat(currentOperationIds(fixture.dsl())).containsExactly(neverProjectedOperationId);
      assertThat(localOperationCount(fixture.dsl())).isEqualTo(257L);
      assertThat(releaseCount(fixture.dsl(), "RELEASED")).isEqualTo(256L);
      assertThat(releaseProofCount(fixture.dsl())).isEqualTo(256L);
      assertThat(retirementCount(fixture.dsl())).isEqualTo(256L);
      assertThat(retirementCountByStatus(fixture.dsl(), "RETIRED")).isEqualTo(256L);
      String historyBefore257thRelease = durableHistoryImage(fixture.dsl(), NIL_UUID);

      String newSeed = lifetimeSeed(257);
      UUID newOperationId = fixture.prepareOtherControllerNew(newSeed);
      assertThat(currentOperationIds(fixture.dsl()))
          .containsExactlyInAnyOrder(neverProjectedOperationId, newOperationId);
      var finalPendingAdd = fixture.prepareOtherAddMutation(newSeed);
      LuaInvocation staleFinalAddInvocation = addInvocation(fixture, finalPendingAdd);
      var finalAddReadback = fixture.projectOtherControllerNew(newSeed);
      assertThat(finalAddReadback.scriptOutcome()).isEqualTo(1L);
      assertThat(fixture.retryOtherControllerNew(newSeed)).isEqualTo(newOperationId);
      byte[] afterFinalAdd = physicalFieldSet(partitionKey);
      assertThat(fixture.projectOtherControllerNew(newSeed).scriptOutcome()).isEqualTo(3L);
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(afterFinalAdd);

      fixture.publishOtherControllerNew(newSeed);
      assertThat(fixture.recordOtherAccountAbort(newSeed).isAccountAborted()).isTrue();
      var finalPendingRelease = fixture.prepareIssuerRelease(newSeed);
      LuaInvocation staleFinalReleaseInvocation = releaseInvocation(fixture, finalPendingRelease);
      var finalReleaseReadback = fixture.projectIssuerRelease(finalPendingRelease);
      var finalRelease = fixture.completeIssuerRelease(finalPendingRelease, finalReleaseReadback);
      assertThat(finalRelease.status())
          .isEqualTo(CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.RELEASED);

      byte[] beforeFinalReleaseRetry = physicalFieldSet(partitionKey);
      var completedFinalReleaseRetry = fixture.prepareIssuerRelease(newSeed);
      var finalReleaseRetryReadback = fixture.projectIssuerRelease(completedFinalReleaseRetry);
      assertThat(finalReleaseRetryReadback.scriptOutcome()).isEqualTo(2L);
      assertThat(finalReleaseRetryReadback.exactPhysicalFieldSetBytes())
          .isEqualTo(finalRelease.exactPhysicalReadbackFieldSetBytes());
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(beforeFinalReleaseRetry);

      UUID finalTransitionId = fixture.otherOperation(newSeed).request().transitionId();
      var pendingFinalRetirement = fixture.prepareIssuerRetirement(finalTransitionId);
      byte[] beforeFinalRetirement = physicalFieldSet(partitionKey);
      var completedFinalRetirement = fixture.retireIssuerCapacity(pendingFinalRetirement);
      byte[] afterFinalRetirement = physicalFieldSet(partitionKey);
      assertThat(completedFinalRetirement.scriptOutcome()).isEqualTo(1L);
      assertThat(completedFinalRetirement.exactAbsenceReadbackFieldSetBytes())
          .isEqualTo(afterFinalRetirement);
      assertRetirementFieldDelta(
          beforeFinalRetirement, afterFinalRetirement, pendingFinalRetirement);

      assertStaleLuaRejectedWithoutWrites(staleFinalAddInvocation, partitionKey);
      assertStaleLuaRejectedWithoutWrites(staleFinalReleaseInvocation, partitionKey);
      byte[] beforeFinalRetirementRetry = physicalFieldSet(partitionKey);
      var completedFinalRetirementRetry = fixture.prepareIssuerRetirement(finalTransitionId);
      assertThat(completedFinalRetirementRetry.status())
          .isEqualTo(CanonicalGameplayIssuerBindingLocalRetirementOperation.Status.RETIRED);
      var finalRetirementRetryProof = fixture.retireIssuerCapacity(completedFinalRetirementRetry);
      assertThat(finalRetirementRetryProof.scriptOutcome()).isEqualTo(2L);
      assertThat(finalRetirementRetryProof.exactAbsenceReadbackFieldSetBytes())
          .isEqualTo(beforeFinalRetirementRetry);
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(beforeFinalRetirementRetry);

      assertThat(currentOperationIds(fixture.dsl())).containsExactly(neverProjectedOperationId);
      assertThat(localOperationCount(fixture.dsl())).isEqualTo(258L);
      assertThat(releaseCount(fixture.dsl(), "RELEASED")).isEqualTo(257L);
      assertThat(releaseProofCount(fixture.dsl())).isEqualTo(257L);
      assertThat(retirementCount(fixture.dsl())).isEqualTo(257L);
      assertThat(retirementCountByStatus(fixture.dsl(), "RETIRED")).isEqualTo(257L);
      assertThat(capacityFieldCount(afterFinalRetirement)).isZero();
      assertThat(decodeFields(afterFinalRetirement).size()).isEqualTo(baselineFieldCount + 2);
      assertThat(durableHistoryImage(fixture.dsl(), finalTransitionId))
          .isEqualTo(historyBefore257thRelease);
    }
  }

  @Test
  void accountControlAdvancePreservesUnrelatedCapacityDuringOriginalRetirement() {
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

      new CanonicalGameplayAccountAdmissionBarrierRepository(fixture.dsl())
          .beginFenceIntent(accountA.accountId(), uuid("account-a-control-advance"));

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

      var pendingRelease = fixture.prepareIssuerRelease();
      var releaseReadback = fixture.projectIssuerRelease(pendingRelease);
      var release = fixture.completeIssuerRelease(pendingRelease, releaseReadback);
      assertThat(release.status())
          .isEqualTo(CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.RELEASED);
      var releaseRetry = fixture.prepareIssuerRelease();
      assertThat(releaseRetry.operationId()).isEqualTo(release.operationId());
      assertThat(fixture.projectIssuerRelease(releaseRetry).exactPhysicalFieldSetBytes())
          .isEqualTo(release.exactPhysicalReadbackFieldSetBytes());

      var pendingRetirement = fixture.prepareIssuerRetirement(fixture.transitionId());
      byte[] releasedCapacityField =
          CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
                  release.releasedMemberValue().reservation().reservationId())
              .getBytes(StandardCharsets.US_ASCII);
      byte[] beforeConflictingCapacity = physicalFieldSet(partitionKey);
      administratorCommands.hset(
          partitionKey, releasedCapacityField, accountBOperation.exactMemberValueBytes());
      byte[] changedCapacity = physicalFieldSet(partitionKey);
      assertThatThrownBy(() -> fixture.retireIssuerCapacity(pendingRetirement))
          .isInstanceOf(IllegalStateException.class);
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(changedCapacity);
      administratorCommands.hset(
          partitionKey, releasedCapacityField, release.exactReleasedMemberValueBytes());
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(beforeConflictingCapacity);

      var retired = fixture.retireIssuerCapacity(pendingRetirement);
      assertThat(retired.scriptOutcome()).isEqualTo(1L);
      var afterRetirement = decodeFields(physicalFieldSet(partitionKey));
      assertThat(afterRetirement)
          .containsEntry(accountBCapacityField, accountBOperation.exactMemberValueBytes())
          .doesNotContainKey(accountBBindingField)
          .doesNotContainKey(new String(releasedCapacityField, StandardCharsets.US_ASCII));
      assertThat(capacityFieldCount(physicalFieldSet(partitionKey))).isEqualTo(1L);
      assertThat(localOperationCount(fixture.dsl())).isEqualTo(3L);

      var retirementRetry = fixture.prepareIssuerRetirement(fixture.transitionId());
      assertThat(retirementRetry.status())
          .isEqualTo(CanonicalGameplayIssuerBindingLocalRetirementOperation.Status.RETIRED);
      byte[] beforeRetirementRetry = physicalFieldSet(partitionKey);
      assertThat(fixture.retireIssuerCapacity(retirementRetry).scriptOutcome()).isEqualTo(2L);
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(beforeRetirementRetry);

      String historyBeforeCompletedRead = durableHistoryImage(fixture.dsl(), NIL_UUID);
      String headBeforeCompletedRead =
          partitionHeadImage(fixture.dsl(), fixture.originalPartitionId());
      fixture.moveIssuerSourceCheckpoint();
      fixture.moveIssuerIncarnation();
      var historicalRetry = fixture.prepareIssuerRetirement(fixture.transitionId());
      assertThat(historicalRetry.operationId()).isEqualTo(retirementRetry.operationId());
      assertThat(historicalRetry.exactAbsenceReadbackFieldSetBytes())
          .isEqualTo(retirementRetry.exactAbsenceReadbackFieldSetBytes());

      var unavailableCurrentIssuerRepository =
          new CanonicalGameplayIssuerBindingLocalRetirementOperationRepository(
              fixture.dsl(),
              () -> {
                throw new AssertionError(
                    "Completed history must not read current issuer authority");
              });
      var unavailableAuthorityRetry =
          unavailableCurrentIssuerRepository.prepareOrResume(fixture.transitionId());
      assertThat(unavailableAuthorityRetry.status())
          .isEqualTo(CanonicalGameplayIssuerBindingLocalRetirementOperation.Status.RETIRED);
      assertThat(unavailableAuthorityRetry.operationId()).isEqualTo(retirementRetry.operationId());
      assertThat(unavailableAuthorityRetry.exactAbsenceReadbackFieldSetBytes())
          .isEqualTo(retirementRetry.exactAbsenceReadbackFieldSetBytes());
      assertThatThrownBy(() -> fixture.prepareIssuerRetirement(UUID.randomUUID()))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class)
          .hasMessage("First-NEW admission abort source does not exist");
      assertThat(durableHistoryImage(fixture.dsl(), NIL_UUID))
          .isEqualTo(historyBeforeCompletedRead);
      assertThat(partitionHeadImage(fixture.dsl(), fixture.originalPartitionId()))
          .isEqualTo(headBeforeCompletedRead);
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(beforeRetirementRetry);
    }
  }

  @Test
  void retirementRequiresAndPreservesExactSeededBaselineSnapshot() throws Exception {
    // Sibling cases retain Redis hashes, so use a distinct layout for the seeded baseline.
    try (var fixture = newFixture(BigInteger.valueOf(13), BigInteger.ONE, 1)) {
      assertThat(fixture.originalBaselineReservationCount()).isOne();
      assertThat(fixture.originalBaselineBindingCount()).isOne();
      fixture.publishOriginalWithControlledAccountLease();
      assertThat(fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();
      var pendingRelease = fixture.prepareIssuerRelease();
      var releaseReadback = fixture.projectIssuerRelease(pendingRelease);
      var release = fixture.completeIssuerRelease(pendingRelease, releaseReadback);
      assertThat(release.status())
          .isEqualTo(CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.RELEASED);

      var pendingRetirement = fixture.prepareIssuerRetirement(fixture.transitionId());
      var baseline =
          fixture.readPreparedPartitionWork(release.originalOperation()).baselineFields();
      var baselineCapacity =
          baseline.entrySet().stream()
              .filter(
                  entry ->
                      entry
                          .getKey()
                          .startsWith(
                              CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec
                                  .RESERVATION_FIELD_PREFIX))
              .findFirst()
              .orElseThrow();
      byte[] baselineCapacityField = baselineCapacity.getKey().getBytes(StandardCharsets.US_ASCII);
      byte[] partitionKey = fixture.issuerPartitionKey();
      byte[] beforeRetirement = physicalFieldSet(partitionKey);
      String historyBefore = durableHistoryImage(fixture.dsl(), NIL_UUID);
      String headBefore = partitionHeadImage(fixture.dsl(), fixture.originalPartitionId());
      String retirementBefore = retirementImage(fixture.dsl(), fixture.transitionId());
      List<UUID> membershipBefore = currentOperationIds(fixture.dsl());
      LuaInvocation invocation = retirementInvocation(fixture, pendingRetirement);

      byte[][] baselineCapacityFields = {baselineCapacityField};
      administratorCommands.hdel(partitionKey, baselineCapacityFields);
      byte[] missingBaseline = physicalFieldSet(partitionKey);
      assertThat(evaluate(invocation)).isEqualTo(-1L);
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(missingBaseline);

      administratorCommands.hset(
          partitionKey, baselineCapacityField, release.exactReleasedMemberValueBytes());
      byte[] mismatchedBaseline = physicalFieldSet(partitionKey);
      assertThat(evaluate(invocation)).isEqualTo(-1L);
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(mismatchedBaseline);
      assertThat(durableHistoryImage(fixture.dsl(), NIL_UUID)).isEqualTo(historyBefore);
      assertThat(partitionHeadImage(fixture.dsl(), fixture.originalPartitionId()))
          .isEqualTo(headBefore);
      assertThat(retirementImage(fixture.dsl(), fixture.transitionId()))
          .isEqualTo(retirementBefore);
      assertThat(currentOperationIds(fixture.dsl())).isEqualTo(membershipBefore);

      administratorCommands.hset(partitionKey, baselineCapacityField, baselineCapacity.getValue());
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(beforeRetirement);
      assertThat(fixture.retireIssuerCapacity(pendingRetirement).scriptOutcome()).isEqualTo(1L);
      byte[] afterRetirement = physicalFieldSet(partitionKey);
      assertRetirementFieldDelta(beforeRetirement, afterRetirement, pendingRetirement);
      var retiredFields = decodeFields(afterRetirement);
      baseline.forEach((field, value) -> assertThat(retiredFields.get(field)).isEqualTo(value));
    }
  }

  @Test
  void retirementRefusesMissingPartitionRevisionWithoutRedisOrSqlWrites() throws Exception {
    try (var fixture = newFixture(BigInteger.valueOf(6), BigInteger.ONE, 0)) {
      fixture.publishOriginalWithControlledAccountLease();
      assertThat(fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();
      var pendingRelease = fixture.prepareIssuerRelease();
      var releaseReadback = fixture.projectIssuerRelease(pendingRelease);
      assertThat(fixture.completeIssuerRelease(pendingRelease, releaseReadback).status())
          .isEqualTo(CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.RELEASED);

      var pendingRetirement = fixture.prepareIssuerRetirement(fixture.transitionId());
      assertMissingRevisionRefusal(
          fixture,
          fixture.issuerPartitionKey(),
          pendingRetirement.physicalMutation(),
          "game_session_canonical_issuer_binding_local_retirement",
          "retirement_operation_id",
          pendingRetirement.operationId(),
          retirementInvocation(fixture, pendingRetirement),
          () -> fixture.retireIssuerCapacity(pendingRetirement));
    }
  }

  @Test
  void postRetirementSqlRejectsUnaccountedCurrentReservationInAnotherPartition() throws Exception {
    try (var fixture = newFixture(BigInteger.valueOf(7), BigInteger.TWO, 0)) {
      fixture.publishOriginalWithControlledAccountLease();
      assertThat(fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();
      var release = fixture.prepareIssuerRelease();
      var releaseReadback = fixture.projectIssuerRelease(release);
      assertThat(fixture.completeIssuerRelease(release, releaseReadback).status())
          .isEqualTo(CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.RELEASED);
      var pendingRetirement = fixture.prepareIssuerRetirement(fixture.transitionId());
      assertThat(fixture.retireIssuerCapacity(pendingRetirement).scriptOutcome()).isEqualTo(1L);

      BigInteger retiredPartition = fixture.originalPartitionId();
      BigInteger otherPartition =
          retiredPartition.equals(BigInteger.ZERO) ? BigInteger.ONE : BigInteger.ZERO;
      String otherPartitionSeed = "cross-partition-current";
      UUID otherOperationId =
          fixture.prepareOtherControllerNewInPartition(otherPartitionSeed, otherPartition);
      assertThat(fixture.projectOtherControllerNew(otherPartitionSeed).scriptOutcome())
          .isEqualTo(1L);
      String pendingTargetSeed = "cross-partition-drift-target";
      UUID pendingTargetOperationId = fixture.prepareOtherControllerNew(pendingTargetSeed);
      byte[] retiredPartitionBefore = physicalFieldSet(fixture.issuerPartitionKey());
      byte[] otherPartitionBefore =
          physicalFieldSet(fixture.issuerPartitionKey(otherPartitionSeed));
      String historyBefore = durableHistoryImage(fixture.dsl(), NIL_UUID);
      List<UUID> membershipBefore = currentOperationIds(fixture.dsl());
      String retiredAuditBefore = retirementImage(fixture.dsl(), fixture.transitionId());
      long reservationsBefore = reservationCount(fixture.dsl());

      assertThatThrownBy(
              () ->
                  insertUnaccountedCurrentReservation(fixture, otherPartitionSeed, otherPartition))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining(
              "Installed issuer reservation requires exact bootstrap or current local-operation membership");

      assertThat(reservationCount(fixture.dsl())).isEqualTo(reservationsBefore);
      assertThat(currentOperationIds(fixture.dsl())).isEqualTo(membershipBefore);
      assertThat(durableHistoryImage(fixture.dsl(), NIL_UUID)).isEqualTo(historyBefore);
      assertThat(retirementImage(fixture.dsl(), fixture.transitionId()))
          .isEqualTo(retiredAuditBefore);
      assertThat(physicalFieldSet(fixture.issuerPartitionKey())).isEqualTo(retiredPartitionBefore);
      assertThat(physicalFieldSet(fixture.issuerPartitionKey(otherPartitionSeed)))
          .isEqualTo(otherPartitionBefore);
      assertThat(currentOperationIds(fixture.dsl()))
          .containsExactlyInAnyOrder(otherOperationId, pendingTargetOperationId);
      // A refused orphan transaction cannot poison the next legitimate same-anchor operation.
      assertThat(fixture.projectOtherControllerNew(pendingTargetSeed).scriptOutcome())
          .isEqualTo(1L);
    }
  }

  @Test
  void installedBootstrapCleanupUpgradePreservesPopulatedBootstrapAndOrdinarySources() {
    try (var fixture =
        newFixture(
            controlledCandidate(BigInteger.valueOf(37), BigInteger.ONE),
            1,
            MigrationVersion.fromVersion("1050.3"),
            this::upgradeInstalledBootstrapCleanupSources)) {
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) FROM game_session_canonical_issuer_binding_local_operation")
                  .get(0, Long.class))
          .isEqualTo(2L);
    }
  }

  private void upgradeInstalledBootstrapCleanupSources(
      org.springframework.jdbc.datasource.DriverManagerDataSource dataSource,
      CanonicalGameplayIssuerLayoutBootstrapIntent intent) {
    DSLContext parent = DSL.using(dataSource, org.jooq.SQLDialect.POSTGRES);
    var original =
        CanonicalGameplayIssuerMemberV2Codec.decode(
            Objects.requireNonNull(
                parent
                    .fetchSingle(
                        "SELECT member_value_bytes FROM game_session_canonical_issuer_bootstrap_member_operation WHERE bootstrap_operation_id = ?",
                        intent.operationId())
                    .get(0, byte[].class)));
    var state =
        new CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState(
            intent.sourceCheckpoint(), original.incarnation());
    var anchor =
        new CanonicalGameplayIssuerBootstrapCoverageRepository(parent)
            .readInstalledAnchor(intent.operationId(), intent.requestId(), state)
            .orElseThrow();
    new CanonicalGameplayIssuerAdmissionControlRepository(parent)
        .installInitialOpen(anchor, () -> state);
    var owner = original.owner();
    var candidate =
        new CanonicalGameplayBindingIdentity(
            owner.accountId(),
            owner.tenantId(),
            owner.playableStateNamespaceId(),
            UUID.randomUUID(),
            owner.playableStateScope(),
            owner.gameInstanceId(),
            owner.runtimeGameInstanceId(),
            UUID.randomUUID().toString(),
            owner.regionId(),
            owner.regionEpoch(),
            owner.issuerId(),
            owner.issuerAuthGeneration(),
            owner.issuerIndexLayoutVersion(),
            owner.issuerIndexPartitionCount(),
            owner.issuerIndexPartitionCapacity());
    new CanonicalGameplayIssuerBindingLocalOperationRepository(parent, () -> state)
        .beginOrResumeOrdinaryNew(
            new CanonicalGameplayIssuerBindingLocalOperation.Request(
                UUID.randomUUID(), UUID.randomUUID(), candidate, null, null));
    var tables =
        List.of(
            "game_session_canonical_issuer_layout_bootstrap_intent",
            "game_session_canonical_issuer_partition_build_cohort_row",
            "game_session_canonical_issuer_bootstrap_member_operation",
            "game_session_canonical_issuer_installed_anchor",
            "game_session_canonical_issuer_bootstrap_partition_proof",
            "game_session_canonical_issuer_binding_local_operation",
            "game_session_canonical_issuer_current_local_operation");
    Map<String, String> before = new HashMap<>();
    for (String table : tables)
      before.put(
          table,
          parent
              .fetch(
                  "SELECT to_jsonb(retained)::text FROM "
                      + table
                      + " retained ORDER BY to_jsonb(retained)::text")
              .formatJSON());
    var history =
        parent
            .fetch(
                "SELECT to_jsonb(retained)::text FROM flyway_schema_history retained ORDER BY installed_rank")
            .getValues(0, String.class);
    assertThat(
            CanonicalGameplayAdmissionAbortCompositionFixture
                .upgradeWithDefaultGameSessionMigrations(
                    MigrationVersion.fromVersion("1050.4"), dataSource))
        .isOne();
    for (String table : tables)
      assertThat(
              parent
                  .fetch(
                      "SELECT to_jsonb(retained)::text FROM "
                          + table
                          + " retained ORDER BY to_jsonb(retained)::text")
                  .formatJSON())
          .isEqualTo(before.get(table));
    assertThat(
            parent
                .fetch(
                    "SELECT to_jsonb(retained)::text FROM flyway_schema_history retained ORDER BY installed_rank")
                .getValues(0, String.class))
        .startsWith(history.toArray(String[]::new));
    assertThat(
            parent
                .fetchSingle(
                    "SELECT count(*) FROM pg_constraint WHERE connamespace = current_schema()::regnamespace AND conname IN ('chk_gs_issuer_release_original_xor', 'chk_gs_issuer_retirement_original_xor') AND convalidated")
                .get(0, Long.class))
        .isEqualTo(2L);
  }

  @Test
  void bootstrapMembershipPreservesFrozenOwnershipAcrossAnUnrelatedAdditiveColumn() {
    try (var fixture =
        newFixture(
            controlledCandidate(BigInteger.valueOf(19), BigInteger.ONE),
            1,
            MigrationVersion.fromVersion("1040.5"),
            this::upgradeRetainedBootstrapAcrossAdditiveColumn)) {
      assertThat(fixture.originalBaselineReservationCount()).isOne();
      assertThat(currentOperationIds(fixture.dsl())).containsExactly(fixture.originalOperationId());
    }
  }

  private void upgradeRetainedBootstrapAcrossAdditiveColumn(
      org.springframework.jdbc.datasource.DriverManagerDataSource dataSource,
      CanonicalGameplayIssuerLayoutBootstrapIntent intent) {
    DSLContext parent = DSL.using(dataSource, org.jooq.SQLDialect.POSTGRES);
    byte[] partitionKey =
        CanonicalGameplayIssuerRedisKeys.forPartition(intent.layoutDescriptor(), BigInteger.ZERO)
            .getBytes(StandardCharsets.US_ASCII);
    String baselineSourceSql =
        " FROM game_session_canonical_issuer_installed_anchor anchor"
            + " JOIN game_session_canonical_issuer_partition_build_cohort_row cohort"
            + " ON cohort.build_operation_id = anchor.build_operation_id"
            + " AND cohort.source_operation_id = anchor.bootstrap_operation_id"
            + " AND cohort.issuer_id = anchor.issuer_id"
            + " AND cohort.source_kind = 'RESERVATION'";
    UUID baselineReservationId =
        parent.fetchSingle("SELECT cohort.source_key::uuid" + baselineSourceSql).get(0, UUID.class);
    String retainedOwnerSql =
        "SELECT jsonb_build_object('cohort', to_jsonb(cohort), 'anchor', to_jsonb(anchor))::text"
            + baselineSourceSql;
    String retainedOwnerBefore = parent.fetchSingle(retainedOwnerSql).get(0, String.class);
    String reservationBefore = reservationImage(parent, baselineReservationId);
    byte[] partitionBefore = physicalFieldSet(partitionKey);
    List<UUID> membershipBefore = currentOperationIds(parent);
    assertThat(membershipBefore).isEmpty();
    String durableHistoryBefore = durableHistoryImage(parent, NIL_UUID);
    List<String> migrationHistoryBefore =
        parent
            .fetch(
                "SELECT to_jsonb(retained_row)::text FROM flyway_schema_history retained_row ORDER BY installed_rank")
            .getValues(0, String.class);

    assertThat(
            CanonicalGameplayAdmissionAbortCompositionFixture
                .upgradeWithDefaultGameSessionMigrations(
                    MigrationVersion.fromVersion("1040.6"), dataSource))
        .isOne();
    List<String> migrationHistoryAfter =
        parent
            .fetch(
                "SELECT to_jsonb(retained_row)::text FROM flyway_schema_history retained_row ORDER BY installed_rank")
            .getValues(0, String.class);
    assertThat(migrationHistoryAfter).hasSize(migrationHistoryBefore.size() + 1);
    assertThat(migrationHistoryAfter.subList(0, migrationHistoryBefore.size()))
        .containsExactlyElementsOf(migrationHistoryBefore);
    assertThat(durableHistoryImage(parent, NIL_UUID)).isEqualTo(durableHistoryBefore);
    assertThat(parent.fetchSingle(retainedOwnerSql).get(0, String.class))
        .isEqualTo(retainedOwnerBefore);
    assertThat(reservationImage(parent, baselineReservationId)).isEqualTo(reservationBefore);
    assertThat(currentOperationIds(parent)).isEqualTo(membershipBefore);
    assertThat(physicalFieldSet(partitionKey)).isEqualTo(partitionBefore);

    parent.execute(
        "ALTER TABLE game_session_canonical_issuer_partition_reservation"
            + " ADD COLUMN non_authority_annotation text");
    // The captured immutable JSON has no new key; the current row has an explicit SQL NULL.
    assertThat(
            parent
                .fetchSingle(
                    "SELECT jsonb_exists(cohort.source_row_json::jsonb, 'non_authority_annotation')"
                        + baselineSourceSql)
                .get(0, Boolean.class))
        .isFalse();
    assertThat(
            parent
                .fetchSingle(
                    "SELECT non_authority_annotation FROM"
                        + " game_session_canonical_issuer_partition_reservation"
                        + " WHERE reservation_id = ?",
                    baselineReservationId)
                .get(0, String.class))
        .isNull();
    parent.fetch(
        "SELECT gs_require_issuer_reservation_current_membership(?)", baselineReservationId);

    assertThatThrownBy(
            () ->
                parent.transaction(
                    configuration -> {
                      DSLContext transaction = DSL.using(configuration);
                      assertThat(
                              transaction.execute(
                                  "UPDATE game_session_canonical_issuer_partition_reservation"
                                      + " SET coverage_fence = coverage_fence + 1"
                                      + " WHERE reservation_id = ?",
                                  baselineReservationId))
                          .isOne();
                      transaction.fetch(
                          "SELECT gs_require_issuer_reservation_current_membership(?)",
                          baselineReservationId);
                    }))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .rootCause()
        .isInstanceOf(org.postgresql.util.PSQLException.class)
        .hasMessageContaining(
            "Installed issuer reservation requires exact bootstrap or current local-operation membership");
    parent.fetch(
        "SELECT gs_require_issuer_reservation_current_membership(?)", baselineReservationId);
    assertThat(
            parent
                .fetchSingle(
                    "SELECT (to_jsonb(reservation) - 'non_authority_annotation')::text FROM"
                        + " game_session_canonical_issuer_partition_reservation reservation"
                        + " WHERE reservation_id = ?",
                    baselineReservationId)
                .get(0, String.class))
        .isEqualTo(reservationBefore);
    assertThat(parent.fetchSingle(retainedOwnerSql).get(0, String.class))
        .isEqualTo(retainedOwnerBefore);
    assertThat(currentOperationIds(parent)).isEqualTo(membershipBefore);
    assertThat(physicalFieldSet(partitionKey)).isEqualTo(partitionBefore);
  }

  @Test
  void bootstrapMembershipPreservesNativeAttributesAcrossSessionRenderingChanges() {
    try (var fixture =
        newFixture(
            controlledCandidate(BigInteger.valueOf(23), BigInteger.ONE),
            1,
            MigrationVersion.fromVersion("1040.4"),
            this::upgradeRetainedBootstrapWithChangedSession)) {
      assertThat(fixture.originalBaselineReservationCount()).isOne();
      String baselineSourceSql =
          " FROM game_session_canonical_issuer_installed_anchor anchor"
              + " JOIN game_session_canonical_issuer_partition_build_cohort_row cohort"
              + " ON cohort.build_operation_id = anchor.build_operation_id"
              + " AND cohort.source_operation_id = anchor.bootstrap_operation_id"
              + " AND cohort.issuer_id = anchor.issuer_id"
              + " AND cohort.source_kind = 'RESERVATION'";
      UUID baselineReservationId =
          fixture
              .dsl()
              .fetchSingle("SELECT cohort.source_key::uuid" + baselineSourceSql)
              .get(0, UUID.class);
      String retainedOwnerSql =
          "SELECT jsonb_build_object('cohort', to_jsonb(cohort), 'anchor', to_jsonb(anchor))::text"
              + baselineSourceSql;
      String retainedOwnerBefore = fixture.dsl().fetchSingle(retainedOwnerSql).get(0, String.class);
      String reservationBefore = reservationImage(fixture.dsl(), baselineReservationId);
      String historyBefore = durableHistoryImage(fixture.dsl(), NIL_UUID);
      List<String> migrationHistoryAfter = fixture.migrationHistoryImage();
      List<UUID> membershipBefore = currentOperationIds(fixture.dsl());
      byte[] partitionBefore = physicalFieldSet(fixture.issuerPartitionKey());

      Map<String, String> timestampRenderings = new HashMap<>();
      Map<String, String> binaryRenderings = new HashMap<>();
      for (String timeZone : List.of("UTC", "Pacific/Auckland", "America/New_York")) {
        for (String byteaOutput : List.of("hex", "escape")) {
          fixture
              .dsl()
              .transaction(
                  configuration -> {
                    DSLContext transaction = DSL.using(configuration);
                    // LOCAL settings and the guard use this transaction's same owned connection.
                    transaction.execute("SET LOCAL TimeZone = '" + timeZone + "'");
                    transaction.execute("SET LOCAL bytea_output = '" + byteaOutput + "'");
                    var rendered =
                        transaction.fetchSingle(
                            "SELECT to_jsonb(created_at)::text, to_jsonb(binding_ref)::text FROM"
                                + " game_session_canonical_issuer_partition_reservation"
                                + " WHERE reservation_id = ?",
                            baselineReservationId);
                    timestampRenderings.put(timeZone, rendered.get(0, String.class));
                    binaryRenderings.put(byteaOutput, rendered.get(1, String.class));
                    transaction.fetch(
                        "SELECT gs_require_issuer_reservation_current_membership(?)",
                        baselineReservationId);
                  });
        }
      }
      assertThat(timestampRenderings.values()).doesNotHaveDuplicates();
      assertThat(binaryRenderings.values()).doesNotHaveDuplicates();
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .transaction(
                          configuration -> {
                            DSLContext transaction = DSL.using(configuration);
                            transaction.execute("SET LOCAL TimeZone = 'Pacific/Auckland'");
                            transaction.execute("SET LOCAL bytea_output = 'escape'");
                            assertThat(
                                    transaction.execute(
                                        "UPDATE game_session_canonical_issuer_partition_reservation"
                                            + " SET coverage_fence = coverage_fence + 1"
                                            + " WHERE reservation_id = ?",
                                        baselineReservationId))
                                .isOne();
                            transaction.fetch(
                                "SELECT gs_require_issuer_reservation_current_membership(?)",
                                baselineReservationId);
                          }))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .rootCause()
          .isInstanceOf(org.postgresql.util.PSQLException.class)
          .hasMessageContaining(
              "Installed issuer reservation requires exact bootstrap or current local-operation membership");
      assertThat(fixture.migrationHistoryImage()).containsExactlyElementsOf(migrationHistoryAfter);
      assertThat(durableHistoryImage(fixture.dsl(), NIL_UUID)).isEqualTo(historyBefore);
      assertThat(fixture.dsl().fetchSingle(retainedOwnerSql).get(0, String.class))
          .isEqualTo(retainedOwnerBefore);
      assertThat(reservationImage(fixture.dsl(), baselineReservationId))
          .isEqualTo(reservationBefore);
      assertThat(currentOperationIds(fixture.dsl())).isEqualTo(membershipBefore);
      assertThat(physicalFieldSet(fixture.issuerPartitionKey())).isEqualTo(partitionBefore);
      String immutableReservationSql =
          "SELECT (to_jsonb(reservation) - 'lifecycle' - 'inventory_revision')::text FROM"
              + " game_session_canonical_issuer_partition_reservation reservation"
              + " WHERE reservation_id = ?";
      String immutableReservationBefore =
          fixture
              .dsl()
              .fetchSingle(immutableReservationSql, baselineReservationId)
              .get(0, String.class);
      var aborted =
          fixture.recordBootstrapAccountAbort(baselineReservationId, "Pacific/Auckland", "escape");
      assertThat(aborted.isAccountAborted()).isTrue();
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle(
                      "SELECT lifecycle FROM game_session_canonical_issuer_partition_reservation WHERE reservation_id = ?",
                      baselineReservationId)
                  .get(0, String.class))
          .isEqualTo("RELEASE_PENDING");
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle(immutableReservationSql, baselineReservationId)
                  .get(0, String.class))
          .isEqualTo(immutableReservationBefore);
      assertThat(fixture.dsl().fetchSingle(retainedOwnerSql).get(0, String.class))
          .isEqualTo(retainedOwnerBefore);
      String afterAbort = reservationImage(fixture.dsl(), baselineReservationId);
      String afterAbortHistory = durableHistoryImage(fixture.dsl(), NIL_UUID);
      assertThat(
              fixture
                  .recordBootstrapAccountAbort(baselineReservationId, "America/New_York", "hex")
                  .orphanCleanupId())
          .isEqualTo(aborted.orphanCleanupId());
      assertThat(reservationImage(fixture.dsl(), baselineReservationId)).isEqualTo(afterAbort);
      assertThat(durableHistoryImage(fixture.dsl(), NIL_UUID)).isEqualTo(afterAbortHistory);
      assertThat(physicalFieldSet(fixture.issuerPartitionKey())).isEqualTo(partitionBefore);
    }
  }

  private void upgradeRetainedBootstrapWithChangedSession(
      org.springframework.jdbc.datasource.DriverManagerDataSource dataSource,
      CanonicalGameplayIssuerLayoutBootstrapIntent intent) {
    DSLContext parent = DSL.using(dataSource, org.jooq.SQLDialect.POSTGRES);
    assertThat(
            parent
                .fetchSingle(
                    "SELECT to_regclass('game_session_canonical_issuer_current_local_operation')::text")
                .get(0, String.class))
        .isNull();
    String history = durableHistoryImage(parent, NIL_UUID);
    String anchor = anchorAndPartitionProofImage(parent);
    List<String> cohort =
        parent
            .fetch(
                "SELECT xmin::text || ':' || to_jsonb(retained_row)::text FROM"
                    + " game_session_canonical_issuer_partition_build_cohort_row retained_row ORDER BY source_row_ordinal")
            .getValues(0, String.class);
    List<String> before =
        parent
            .fetch(
                "SELECT to_jsonb(retained_row)::text FROM flyway_schema_history retained_row ORDER BY installed_rank")
            .getValues(0, String.class);
    byte[] partitionKey =
        CanonicalGameplayIssuerRedisKeys.forPartition(intent.layoutDescriptor(), BigInteger.ZERO)
            .getBytes(StandardCharsets.US_ASCII);
    byte[] physical = physicalFieldSet(partitionKey);
    var changed =
        CanonicalGameplayIssuerTestFixtures.withSessionRendering(
            dataSource, "America/New_York", "escape");
    var settings =
        DSL.using(changed, org.jooq.SQLDialect.POSTGRES)
            .fetchSingle("SELECT current_setting('TimeZone'), current_setting('bytea_output')");
    assertThat(settings.get(0, String.class)).isEqualTo("America/New_York");
    assertThat(settings.get(1, String.class)).isEqualTo("escape");
    assertThat(
            CanonicalGameplayAdmissionAbortCompositionFixture
                .upgradeWithDefaultGameSessionMigrations(
                    MigrationVersion.fromVersion("1040.6"), changed))
        .isEqualTo(2);
    List<String> after =
        parent
            .fetch(
                "SELECT to_jsonb(retained_row)::text FROM flyway_schema_history retained_row ORDER BY installed_rank")
            .getValues(0, String.class);
    assertThat(after).hasSize(before.size() + 2);
    assertThat(after.subList(0, before.size())).containsExactlyElementsOf(before);
    assertThat(durableHistoryImage(parent, NIL_UUID)).isEqualTo(history);
    assertThat(anchorAndPartitionProofImage(parent)).isEqualTo(anchor);
    assertThat(
            parent
                .fetch(
                    "SELECT xmin::text || ':' || to_jsonb(retained_row)::text FROM"
                        + " game_session_canonical_issuer_partition_build_cohort_row retained_row ORDER BY source_row_ordinal")
                .getValues(0, String.class))
        .containsExactlyElementsOf(cohort);
    assertThat(physicalFieldSet(partitionKey)).isEqualTo(physical);
  }

  @Test
  void currentMembershipRejectsPrematureDeletionMutationTruncateAndDeferredSourceMismatch() {
    // A seeded baseline needs its own physical layout; sibling cases retain their issuer hashes.
    try (var fixture = newFixture(BigInteger.valueOf(17), BigInteger.ONE, 1)) {
      UUID originalId = fixture.originalOperationId();
      List<UUID> membershipBefore = currentOperationIds(fixture.dsl());
      String originalBefore = localOperationImage(fixture.dsl(), originalId);
      String historyBefore = durableHistoryImage(fixture.dsl(), NIL_UUID);
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "DELETE FROM game_session_canonical_issuer_current_local_operation WHERE operation_id = ?",
                          originalId))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("exact committed original retirement");
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "UPDATE game_session_canonical_issuer_current_local_operation"
                              + " SET partition_id = partition_id WHERE operation_id = ?",
                          originalId))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("cannot be updated or truncated");
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute("TRUNCATE game_session_canonical_issuer_current_local_operation"))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("cannot be updated or truncated");
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .execute(
                          "INSERT INTO game_session_canonical_issuer_current_local_operation"
                              + " (operation_id, issuer_id, anchor_bootstrap_operation_id, partition_id)"
                              + " SELECT operation_id, issuer_id, anchor_bootstrap_operation_id, partition_id + 1"
                              + " FROM game_session_canonical_issuer_binding_local_operation WHERE operation_id = ?",
                          originalId))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("exact unretired immutable source");
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .transaction(
                          configuration -> {
                            DSLContext transaction = DSL.using(configuration);
                            // The write itself succeeds; the reverse guard checks the committed
                            // postimage.
                            assertThat(
                                    transaction.execute(
                                        "UPDATE game_session_canonical_issuer_partition_reservation reservation"
                                            + " SET coverage_fence = reservation.coverage_fence + 1"
                                            + " FROM game_session_canonical_issuer_binding_local_operation original"
                                            + " WHERE original.operation_id = ?"
                                            + " AND reservation.reservation_id = original.reservation_id",
                                        originalId))
                                .isOne();
                          }))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .rootCause()
          .isInstanceOf(org.postgresql.util.PSQLException.class)
          .hasMessageContaining("exact installed local-operation source");
      assertThatThrownBy(
              () ->
                  fixture
                      .dsl()
                      .transaction(
                          configuration -> {
                            DSLContext transaction = DSL.using(configuration);
                            // A seeded bootstrap reservation is also bound to its complete frozen
                            // owner
                            // and coverage tuple, rather than being admitted by reservation
                            // identity alone.
                            assertThat(
                                    transaction.execute(
                                        "UPDATE game_session_canonical_issuer_partition_reservation reservation"
                                            + " SET coverage_fence = reservation.coverage_fence + 1"
                                            + " FROM game_session_canonical_issuer_installed_anchor anchor"
                                            + " JOIN game_session_canonical_issuer_partition_build_cohort_row cohort"
                                            + " ON cohort.build_operation_id = anchor.build_operation_id"
                                            + " AND cohort.source_operation_id = anchor.bootstrap_operation_id"
                                            + " AND cohort.issuer_id = anchor.issuer_id"
                                            + " AND cohort.source_kind = 'RESERVATION'"
                                            + " WHERE reservation.reservation_id::text = cohort.source_key"))
                                .isOne();
                          }))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .rootCause()
          .isInstanceOf(org.postgresql.util.PSQLException.class)
          .hasMessageContaining(
              "Installed issuer reservation requires exact bootstrap or current local-operation membership");
      assertThat(currentOperationIds(fixture.dsl())).isEqualTo(membershipBefore);
      assertThat(localOperationImage(fixture.dsl(), originalId)).isEqualTo(originalBefore);
      assertThat(durableHistoryImage(fixture.dsl(), NIL_UUID)).isEqualTo(historyBefore);
    }
  }

  private static List<UUID> currentOperationIds(DSLContext dsl) {
    // Execute the positive current partition lookup used by the projection, rather than deriving
    // currentness by subtracting retained RETIRED history in the test.
    return dsl.fetch(
            "SELECT current.operation_id FROM game_session_canonical_issuer_current_local_operation current"
                + " JOIN game_session_canonical_issuer_installed_anchor anchor"
                + " ON anchor.issuer_id = current.issuer_id"
                + " AND anchor.bootstrap_operation_id = current.anchor_bootstrap_operation_id"
                + " JOIN game_session_canonical_issuer_partition_mutation_head head"
                + " ON head.issuer_id = current.issuer_id"
                + " AND head.bootstrap_operation_id = current.anchor_bootstrap_operation_id"
                + " AND head.partition_id = current.partition_id"
                + " WHERE anchor.issuer_id = 'firemud-account-service'"
                + " ORDER BY current.operation_id")
        .getValues("operation_id", UUID.class);
  }

  private static long reservationCount(DSLContext dsl) {
    return dsl.fetchSingle(
            "SELECT count(*) FROM game_session_canonical_issuer_partition_reservation")
        .get(0, Long.class);
  }

  private static String retirementImage(DSLContext dsl, UUID transitionId) {
    return dsl.fetchSingle(
            "SELECT to_jsonb(retirement)::text FROM"
                + " game_session_canonical_issuer_binding_local_retirement retirement"
                + " WHERE retirement.transition_id = ?",
            transitionId)
        .get(0, String.class);
  }

  private static LuaInvocation retirementInvocation(
      CanonicalGameplayAdmissionAbortCompositionFixture fixture,
      CanonicalGameplayIssuerBindingLocalRetirementOperation operation)
      throws IOException, ReflectiveOperationException {
    var work = fixture.readPreparedPartitionWork(operation.release().originalOperation());
    return new LuaInvocation(
        readScript(
            CanonicalGameplayIssuerBindingLocalRetirementRedisScriptContribution.RESOURCE_PATH),
        fixture.issuerPartitionKey(),
        scriptArguments(
            CanonicalGameplayIssuerBindingLocalRetirementRedisOwner.class,
            new Class<?>[] {
              CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork.class,
              CanonicalGameplayIssuerBindingLocalRetirementOperation.class
            },
            work,
            operation));
  }

  private static void assertRetirementFieldDelta(
      byte[] before,
      byte[] after,
      CanonicalGameplayIssuerBindingLocalRetirementOperation operation) {
    Map<String, byte[]> expected = decodeFields(before);
    String capacityField =
        CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
            operation.release().releasedMemberValue().reservation().reservationId());
    assertThat(expected.get(capacityField))
        .isEqualTo(operation.release().exactReleasedMemberValueBytes());
    expected.remove(capacityField);
    expected.put(
        CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD,
        operation
            .physicalMutation()
            .successorRevision()
            .toString()
            .getBytes(StandardCharsets.US_ASCII));
    assertThat(after)
        .isEqualTo(CanonicalGameplayIssuerBootstrapCoverageRepository.encodeFieldSet(expected));
  }

  private static long retirementCount(DSLContext dsl) {
    return dsl.fetchSingle(
            "SELECT count(*) FROM game_session_canonical_issuer_binding_local_retirement")
        .get(0, Long.class);
  }

  private static long retirementCountByStatus(DSLContext dsl, String status) {
    return dsl.fetchSingle(
            "SELECT count(*) FROM game_session_canonical_issuer_binding_local_retirement WHERE status = ?",
            status)
        .get(0, Long.class);
  }

  private static String retirementStatus(DSLContext dsl, UUID retirementOperationId) {
    return dsl.fetchSingle(
            "SELECT status FROM game_session_canonical_issuer_binding_local_retirement"
                + " WHERE retirement_operation_id = ?",
            retirementOperationId)
        .get(0, String.class);
  }
}
