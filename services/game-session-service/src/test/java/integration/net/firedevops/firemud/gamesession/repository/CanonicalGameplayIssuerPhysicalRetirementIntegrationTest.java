package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalRetirementRedisScriptContribution;
import org.jooq.DSLContext;
import net.firedevops.firemud.test.TestContainerImages;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
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

      String newerSeed = lifetimeSeed(2);
      UUID newerOperationId = fixture.prepareOtherControllerNew(newerSeed);
      String neverProjectedSeed = "same-partition-never-projected";
      UUID neverProjectedOperationId = fixture.prepareOtherControllerNew(neverProjectedSeed);
      var neverProjectedOperation = fixture.otherOperation(neverProjectedSeed);
      var newerOperation = fixture.otherOperation(newerSeed);
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

      var resumedOldestRetirement = fixture.prepareIssuerRetirement(fixture.transitionId());
      assertThat(resumedOldestRetirement.operationId())
          .isEqualTo(pendingOldestRetirement.operationId());
      assertThat(resumedOldestRetirement.physicalMutation().expectedRevision())
          .isEqualTo(pendingOldestRetirement.physicalMutation().expectedRevision());
      assertThat(resumedOldestRetirement.physicalMutation().successorRevision())
          .isEqualTo(pendingOldestRetirement.physicalMutation().successorRevision());
      var recoveredOldestProof = fixture.retireIssuerCapacity(resumedOldestRetirement);
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

      for (int index = 3; index <= 256; index++) {
        String seed = lifetimeSeed(index);
        fixture.prepareOtherControllerNew(seed);
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
        byte[] afterCycleRetirement = physicalFieldSet(partitionKey);
        assertThat(cycleRetirement.scriptOutcome()).isEqualTo(1L);
        assertThat(cycleRetirement.exactAbsenceReadbackFieldSetBytes())
            .isEqualTo(afterCycleRetirement);
        assertRetirementFieldDelta(
            beforeCycleRetirement, afterCycleRetirement, pendingCycleRetirement);
        assertThat(capacityFieldCount(afterCycleRetirement)).isZero();
        assertThat(decodeFields(afterCycleRetirement).size()).isEqualTo(baselineFieldCount + 2);
      }

      assertThat(localOperationCount(fixture.dsl())).isEqualTo(257L);
      assertThat(releaseCount(fixture.dsl(), "RELEASED")).isEqualTo(256L);
      assertThat(releaseProofCount(fixture.dsl())).isEqualTo(256L);
      assertThat(retirementCount(fixture.dsl())).isEqualTo(256L);
      assertThat(retirementCountByStatus(fixture.dsl(), "RETIRED")).isEqualTo(256L);
      String historyBefore257thRelease = durableHistoryImage(fixture.dsl(), NIL_UUID);

      String newSeed = lifetimeSeed(257);
      UUID newOperationId = fixture.prepareOtherControllerNew(newSeed);
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
  void postRetirementProjectionRejectsUnaccountedCurrentReservationInAnotherPartition()
      throws Exception {
    try (var fixture = newFixture(BigInteger.valueOf(7), BigInteger.TWO, 0)) {
      fixture.publishOriginalWithControlledAccountLease();
      assertThat(fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();
      var release = fixture.prepareIssuerRelease();
      var releaseReadback = fixture.projectIssuerRelease(release);
      assertThat(fixture.completeIssuerRelease(release, releaseReadback).status())
          .isEqualTo(CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.RELEASED);
      var pendingRetirement = fixture.prepareIssuerRetirement(fixture.transitionId());
      var retired = fixture.retireIssuerCapacity(pendingRetirement);
      assertThat(retired.scriptOutcome()).isEqualTo(1L);

      BigInteger retiredPartition = fixture.originalPartitionId();
      BigInteger otherPartition =
          retiredPartition.equals(BigInteger.ZERO) ? BigInteger.ONE : BigInteger.ZERO;
      String otherPartitionSeed = "cross-partition-current";
      fixture.prepareOtherControllerNewInPartition(otherPartitionSeed, otherPartition);
      assertThat(fixture.projectOtherControllerNew(otherPartitionSeed).scriptOutcome())
          .isEqualTo(1L);

      String pendingTargetSeed = "cross-partition-drift-target";
      UUID pendingTargetOperationId = fixture.prepareOtherControllerNew(pendingTargetSeed);
      var pendingTarget = fixture.otherOperation(pendingTargetSeed);
      byte[] retiredPartitionKey = fixture.issuerPartitionKey();
      byte[] otherPartitionKey = fixture.issuerPartitionKey(otherPartitionSeed);
      byte[] retiredPartitionBefore = physicalFieldSet(retiredPartitionKey);
      byte[] otherPartitionBefore = physicalFieldSet(otherPartitionKey);
      String pendingTargetBefore = localOperationImage(fixture.dsl(), pendingTargetOperationId);
      String otherPartitionHeadBefore = partitionHeadImage(fixture.dsl(), otherPartition);
      UUID unaccountedReservationId =
          insertUnaccountedCurrentReservation(fixture, otherPartitionSeed, otherPartition);
      String unaccountedReservationBefore =
          reservationImage(fixture.dsl(), unaccountedReservationId);
      String retiredAuditBefore = retirementImage(fixture.dsl(), fixture.transitionId());

      assertThatThrownBy(() -> fixture.projectOtherControllerNew(pendingTargetSeed))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Durable issuer reservations differ");

      assertThat(physicalFieldSet(retiredPartitionKey)).isEqualTo(retiredPartitionBefore);
      assertThat(physicalFieldSet(otherPartitionKey)).isEqualTo(otherPartitionBefore);
      assertThat(localOperationImage(fixture.dsl(), pendingTargetOperationId))
          .isEqualTo(pendingTargetBefore);
      assertThat(partitionHeadImage(fixture.dsl(), otherPartition))
          .isEqualTo(otherPartitionHeadBefore);
      assertThat(reservationImage(fixture.dsl(), unaccountedReservationId))
          .isEqualTo(unaccountedReservationBefore);
      assertThat(retirementImage(fixture.dsl(), fixture.transitionId()))
          .isEqualTo(retiredAuditBefore);
      assertThat(retirementStatus(fixture.dsl(), pendingRetirement.operationId()))
          .isEqualTo("RETIRED");
      assertThat(retirementCount(fixture.dsl())).isEqualTo(1L);
      assertThat(releaseProofCount(fixture.dsl())).isEqualTo(1L);
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle(
                      "SELECT count(*) FROM game_session_canonical_issuer_partition_mutation"
                          + " WHERE owner_operation_id = ? AND mutation_kind = 'ADD_CAPACITY'",
                      pendingTargetOperationId)
                  .get(0, Long.class))
          .isZero();
      assertThat(pendingTarget.reservation().partitionId()).isEqualTo(retiredPartition);
    }
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
