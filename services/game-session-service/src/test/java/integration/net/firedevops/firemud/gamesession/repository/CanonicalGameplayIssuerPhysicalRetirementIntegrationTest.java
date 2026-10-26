package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.protocol.ProtocolVersion;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalReleaseRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayIssuerBindingLocalRetirementRedisScriptContribution;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual PostgreSQL/Redis proof of bounded original-operation issuer capacity retirement. Account
 * lease and abort inputs, launch target, issuer checkpoint, and incarnation remain controlled; Game
 * Session prepares, projects, releases, retires, and reads back every state.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@Execution(ExecutionMode.SAME_THREAD)
class CanonicalGameplayIssuerPhysicalRetirementIntegrationTest {
  private static final String OWNER_PRINCIPAL = "gamesession_coord_app";
  private static final String ISSUER_KEY_PREFIX = "session:game:index:issuer:";
  private static final BigInteger PARTITION_CAPACITY = BigInteger.valueOf(256);
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  private RedisClient administrator;
  private RedisClient issuerOwner;
  private StatefulRedisConnection<byte[], byte[]> administratorConnection;
  private RedisCommands<byte[], byte[]> administratorCommands;

  @BeforeEach
  void setUp() {
    String password = UUID.randomUUID().toString();
    administrator = createRedisClient(null, null);
    administratorConnection = administrator.connect(ByteArrayCodec.INSTANCE);
    administratorCommands = administratorConnection.sync();
    administratorCommands.aclSetuser(
        OWNER_PRINCIPAL,
        new AclSetuserArgs()
            .reset()
            .on()
            .addPassword(password)
            .keyPattern(ISSUER_KEY_PREFIX + "*")
            .addCommand(CommandType.HELLO)
            .addCommand(CommandType.PING)
            .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
            .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
            .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
            .addCommand(CommandType.EVALSHA)
            .addCommand(CommandType.TYPE)
            .addCommand(CommandType.PTTL)
            .addCommand(CommandType.HGET)
            .addCommand(CommandType.HLEN)
            .addCommand(CommandType.HGETALL)
            .addCommand(CommandType.HSET)
            .addCommand(CommandType.HDEL));
    issuerOwner = createRedisClient(OWNER_PRINCIPAL, password);
  }

  @AfterEach
  void tearDown() {
    if (administratorConnection != null) administratorConnection.close();
    if (issuerOwner != null) issuerOwner.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    if (administrator != null) administrator.shutdown(Duration.ZERO, Duration.ofSeconds(2));
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
      var originalRelease = completeReleaseAfterLostReply(fixture, null);

      String newerSeed = lifetimeSeed(2);
      UUID newerOperationId = fixture.prepareOtherControllerNew(newerSeed);
      var pendingAdd = fixture.prepareOtherAddMutation(newerSeed);
      LuaInvocation staleAddInvocation = addInvocation(fixture, pendingAdd);
      var addReadback = fixture.projectOtherControllerNew(newerSeed);
      assertThat(addReadback.scriptOutcome()).isEqualTo(1L);
      assertThat(fixture.retryOtherControllerNew(newerSeed)).isEqualTo(newerOperationId);
      byte[] afterAdd = physicalFieldSet(partitionKey);
      assertThat(fixture.projectOtherControllerNew(newerSeed).scriptOutcome()).isEqualTo(3L);
      assertThat(physicalFieldSet(partitionKey)).isEqualTo(afterAdd);

      fixture.publishOtherControllerNew(newerSeed);
      assertThat(fixture.recordOtherAccountAbort(newerSeed).isAccountAborted()).isTrue();
      var pendingRelease = fixture.prepareIssuerRelease(newerSeed);
      LuaInvocation staleReleaseInvocation = releaseInvocation(fixture, pendingRelease);
      var releaseReadback = fixture.projectIssuerRelease(pendingRelease);
      var newerRelease = fixture.completeIssuerRelease(pendingRelease, releaseReadback);
      assertThat(newerRelease.status())
          .isEqualTo(CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.RELEASED);
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
      String newerCapacityField =
          CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
              newerRelease.releasedMemberValue().reservation().reservationId());
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

      assertThat(localOperationCount(fixture.dsl())).isEqualTo(256L);
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

      assertThat(localOperationCount(fixture.dsl())).isEqualTo(257L);
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
  void refusesProjectionBeyondTheCurrentLocalEntryBoundWithoutWrites() {
    try (var fixture =
        newFixture(BigInteger.valueOf(9), BigInteger.ONE, BigInteger.valueOf(512), 0)) {
      for (int index = 1; index < PARTITION_CAPACITY.intValueExact(); index++) {
        fixture.prepareOtherControllerNew("local-entry-bound-" + index);
      }

      assertThat(localOperationCount(fixture.dsl())).isEqualTo(256L);
      var currentWork = fixture.readCurrentOriginalPartitionWork();
      assertThat(currentWork.localEntries()).hasSize(256);

      byte[] fieldsBefore257th = physicalFieldSet(fixture.issuerPartitionKey());
      long mutationCountBefore257th = partitionMutationCount(fixture.dsl());
      String seed257 = "local-entry-bound-257";
      fixture.prepareOtherControllerNew(seed257);
      assertThat(localOperationCount(fixture.dsl())).isEqualTo(257L);
      assertThat(partitionMutationCount(fixture.dsl())).isEqualTo(mutationCountBefore257th);
      assertThat(physicalFieldSet(fixture.issuerPartitionKey())).isEqualTo(fieldsBefore257th);

      byte[] fieldsBeforeRefusal = physicalFieldSet(fixture.issuerPartitionKey());
      long mutationCountBeforeRefusal = partitionMutationCount(fixture.dsl());
      String sourceImageBeforeRefusal = durableHistoryImage(fixture.dsl(), NIL_UUID);
      assertThatThrownBy(() -> fixture.projectOtherControllerNew(seed257))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "Issuer binding-local projection is unavailable: The local partition operation set exceeds the physical owner bound");
      assertThat(durableHistoryImage(fixture.dsl(), NIL_UUID)).isEqualTo(sourceImageBeforeRefusal);
      assertThat(localOperationCount(fixture.dsl())).isEqualTo(257L);
      assertThat(partitionMutationCount(fixture.dsl())).isEqualTo(mutationCountBeforeRefusal);
      assertThat(physicalFieldSet(fixture.issuerPartitionKey())).isEqualTo(fieldsBeforeRefusal);
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

  private RedisClient createRedisClient(String username, String password) {
    RedisURI.Builder builder = RedisURI.Builder.redis(REDIS.getHost(), REDIS.getMappedPort(6379));
    if (username != null) builder.withAuthentication(username, password);
    RedisURI uri = builder.build();
    uri.setTimeout(Duration.ofSeconds(5));
    RedisClient client = RedisClient.create(uri);
    client.setOptions(
        ClientOptions.builder()
            .autoReconnect(false)
            .protocolVersion(ProtocolVersion.RESP2)
            .build());
    return client;
  }

  private CanonicalGameplayAdmissionAbortCompositionFixture newFixture() {
    return newFixture(controlledCandidate(), 0);
  }

  private CanonicalGameplayAdmissionAbortCompositionFixture newFixture(
      BigInteger layoutVersion, BigInteger partitionCount, int seedBootstrapMemberCount) {
    return newFixture(controlledCandidate(layoutVersion, partitionCount), seedBootstrapMemberCount);
  }

  private CanonicalGameplayAdmissionAbortCompositionFixture newFixture(
      BigInteger layoutVersion,
      BigInteger partitionCount,
      BigInteger partitionCapacity,
      int seedBootstrapMemberCount) {
    return newFixture(
        controlledCandidate(layoutVersion, partitionCount, partitionCapacity),
        seedBootstrapMemberCount);
  }

  private CanonicalGameplayAdmissionAbortCompositionFixture newFixture(
      Candidate candidate, int seedBootstrapMemberCount) {
    DriverManagerDataSource accountDataSource = new DriverManagerDataSource();
    accountDataSource.setDriverClassName("org.postgresql.Driver");
    String postgresJdbcUrl = POSTGRES.getJdbcUrl();
    accountDataSource.setUrl(
        postgresJdbcUrl + (postgresJdbcUrl.contains("?") ? "&" : "?") + "currentSchema=public");
    accountDataSource.setUsername(POSTGRES.getUsername());
    accountDataSource.setPassword(POSTGRES.getPassword());
    return new CanonicalGameplayAdmissionAbortCompositionFixture(
        accountDataSource, candidate, issuerOwner, seedBootstrapMemberCount);
  }

  private static Candidate controlledCandidate() {
    return controlledCandidate(BigInteger.valueOf(3), BigInteger.ONE);
  }

  private static Candidate controlledCandidate(
      BigInteger layoutVersion, BigInteger partitionCount) {
    return controlledCandidate(layoutVersion, partitionCount, PARTITION_CAPACITY);
  }

  private static Candidate controlledCandidate(
      BigInteger layoutVersion, BigInteger partitionCount, BigInteger partitionCapacity) {
    return new Candidate(
        uuid("retirement-account"),
        uuid("retirement-tenant"),
        uuid("retirement-playable-state"),
        uuid("retirement-character"),
        "SHARED",
        uuid("retirement-game"),
        1L,
        uuid("retirement-session"),
        uuid("retirement-region"),
        BigInteger.ONE,
        GameSessionAccountDelegationProfile.ISSUER,
        BigInteger.ONE,
        layoutVersion,
        partitionCount,
        partitionCapacity);
  }

  private void assertMissingRevisionRefusal(
      CanonicalGameplayAdmissionAbortCompositionFixture fixture,
      byte[] partitionKey,
      CanonicalGameplayIssuerPartitionMutation mutation,
      String ownerTable,
      String ownerIdColumn,
      UUID ownerOperationId,
      LuaInvocation exactInvocation,
      Runnable ordinaryOwnerCall) {
    byte[] beforeFault = physicalFieldSet(partitionKey);
    assertThat(decodeFields(beforeFault))
        .containsKey(CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD);
    String pendingCustodyBefore =
        pendingMutationCustodyImage(
            fixture.dsl(), mutation, ownerTable, ownerIdColumn, ownerOperationId);

    byte[] revisionField =
        CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD.getBytes(
            StandardCharsets.US_ASCII);
    byte[][] revisionFields = {revisionField};
    assertThat(administratorCommands.hdel(partitionKey, revisionFields)).isEqualTo(1L);
    byte[] afterInjectedAbsence = physicalFieldSet(partitionKey);
    assertThat(decodeFields(afterInjectedAbsence))
        .doesNotContainKey(CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD);
    assertThat(
            pendingMutationCustodyImage(
                fixture.dsl(), mutation, ownerTable, ownerIdColumn, ownerOperationId))
        .isEqualTo(pendingCustodyBefore);

    assertThat(evaluate(exactInvocation)).isNegative();
    assertThat(physicalFieldSet(partitionKey)).isEqualTo(afterInjectedAbsence);
    assertThatThrownBy(ordinaryOwnerCall::run).isInstanceOf(IllegalStateException.class);
    assertThat(physicalFieldSet(partitionKey)).isEqualTo(afterInjectedAbsence);
    assertThat(administratorCommands.hget(partitionKey, revisionField)).isNull();
    assertThat(
            pendingMutationCustodyImage(
                fixture.dsl(), mutation, ownerTable, ownerIdColumn, ownerOperationId))
        .isEqualTo(pendingCustodyBefore);
  }

  private static String pendingMutationCustodyImage(
      DSLContext dsl,
      CanonicalGameplayIssuerPartitionMutation mutation,
      String ownerTable,
      String ownerIdColumn,
      UUID ownerOperationId) {
    String sql =
        "SELECT jsonb_build_object("
            + "'head', (SELECT to_jsonb(head) FROM "
            + CanonicalGameplayIssuerPartitionMutationRepository.HEAD_TABLE
            + " head WHERE head.issuer_id = ? AND head.partition_id = ?),"
            + "'mutation', (SELECT to_jsonb(mutation) FROM "
            + "game_session_canonical_issuer_partition_mutation"
            + " mutation WHERE mutation.mutation_id = ?),"
            + "'owner', (SELECT to_jsonb(owner_row) FROM "
            + ownerTable
            + " owner_row WHERE owner_row."
            + ownerIdColumn
            + " = ?))::text";
    return dsl.fetchSingle(
            sql,
            GameSessionAccountDelegationProfile.ISSUER,
            mutation.partitionId(),
            mutation.mutationId(),
            ownerOperationId)
        .get(0, String.class);
  }

  private static String localOperationImage(DSLContext dsl, UUID operationId) {
    return dsl.fetchSingle(
            "SELECT to_jsonb(operation)::text FROM"
                + " game_session_canonical_issuer_binding_local_operation operation"
                + " WHERE operation.operation_id = ?",
            operationId)
        .get(0, String.class);
  }

  private static String partitionHeadImage(DSLContext dsl, BigInteger partitionId) {
    return dsl.fetchSingle(
            "SELECT to_jsonb(head)::text FROM "
                + CanonicalGameplayIssuerPartitionMutationRepository.HEAD_TABLE
                + " head WHERE head.issuer_id = ? AND head.partition_id = ?",
            GameSessionAccountDelegationProfile.ISSUER,
            partitionId)
        .get(0, String.class);
  }

  private static UUID insertUnaccountedCurrentReservation(
      CanonicalGameplayAdmissionAbortCompositionFixture fixture,
      String sourceSeed,
      BigInteger partitionId) {
    // This row is deliberate run-owned SQL drift: it has no transition or local owner operation.
    // The ordinary projection must detect that missing accounting and make no Redis change.
    var source = fixture.otherOperation(sourceSeed).memberValue();
    var owner = source.owner();
    var reservation = source.reservation();
    String sessionId;
    CanonicalGameplayBindingRef bindingRef;
    do {
      sessionId = UUID.randomUUID().toString();
      bindingRef =
          CanonicalGameplayBindingRef.of(owner.tenantId(), owner.gameInstanceId(), sessionId);
    } while (!bindingRef.partitionId(owner.issuerIndexPartitionCount()).equals(partitionId));
    UUID reservationId = UUID.randomUUID();
    fixture
        .dsl()
        .execute(
            "INSERT INTO game_session_canonical_issuer_partition_reservation"
                + " (reservation_id, binding_ref, account_id, tenant_id, game_instance_id,"
                + " runtime_game_instance_id, session_id, binding_generation, transition_id, issuer_id,"
                + " issuer_auth_generation, playable_state_namespace_id, character_id,"
                + " playable_state_scope, region_id, region_epoch, partition_id,"
                + " issuer_index_layout_version, issuer_index_partition_count,"
                + " issuer_index_partition_capacity, lifecycle, reservation_fence,"
                + " issuer_coverage_operation_id, issuer_coverage_operation_fence, coverage_fence,"
                + " inventory_snapshot_revision, inventory_revision)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?, ?, ?, ?, ?, ?)",
            reservationId,
            bindingRef.bytes(),
            owner.accountId(),
            owner.tenantId(),
            owner.gameInstanceId(),
            owner.runtimeGameInstanceId(),
            sessionId,
            source.bindingGeneration(),
            UUID.randomUUID(),
            owner.issuerId(),
            owner.issuerAuthGeneration(),
            owner.playableStateNamespaceId(),
            UUID.randomUUID(),
            owner.playableStateScope(),
            owner.regionId(),
            owner.regionEpoch(),
            partitionId,
            owner.issuerIndexLayoutVersion(),
            owner.issuerIndexPartitionCount(),
            owner.issuerIndexPartitionCapacity(),
            "RESERVED",
            UUID.randomUUID(),
            UUID.randomUUID(),
            reservation.issuerCoverageOperationFence(),
            reservation.coverageFence(),
            reservation.inventorySnapshotRevision(),
            reservation.inventoryRevision());
    return reservationId;
  }

  private static String reservationImage(DSLContext dsl, UUID reservationId) {
    return dsl.fetchSingle(
            "SELECT to_jsonb(reservation)::text FROM"
                + " game_session_canonical_issuer_partition_reservation reservation"
                + " WHERE reservation.reservation_id = ?",
            reservationId)
        .get(0, String.class);
  }

  private static String retirementImage(DSLContext dsl, UUID transitionId) {
    return dsl.fetchSingle(
            "SELECT to_jsonb(retirement)::text FROM"
                + " game_session_canonical_issuer_binding_local_retirement retirement"
                + " WHERE retirement.transition_id = ?",
            transitionId)
        .get(0, String.class);
  }

  private static CanonicalGameplayIssuerBindingLocalReleaseOperation completeReleaseAfterLostReply(
      CanonicalGameplayAdmissionAbortCompositionFixture fixture, String seed) {
    var pending =
        seed == null ? fixture.prepareIssuerRelease() : fixture.prepareIssuerRelease(seed);
    assertThatThrownBy(() -> fixture.projectIssuerReleaseAndLoseReply(pending))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("controlled lost issuer release reply");
    var resumed =
        seed == null ? fixture.prepareIssuerRelease() : fixture.prepareIssuerRelease(seed);
    var recoveredReadback = fixture.projectIssuerRelease(resumed);
    assertThat(recoveredReadback.scriptOutcome()).isEqualTo(2L);
    return fixture.completeIssuerRelease(resumed, recoveredReadback);
  }

  private static CanonicalGameplayIssuerBindingLocalReleaseOperation completeRelease(
      CanonicalGameplayAdmissionAbortCompositionFixture fixture, String seed) {
    var pending = fixture.prepareIssuerRelease(seed);
    var readback = fixture.projectIssuerRelease(pending);
    return fixture.completeIssuerRelease(pending, readback);
  }

  private static String lifetimeSeed(int index) {
    return "lifetime-" + index;
  }

  private static LuaInvocation addInvocation(
      CanonicalGameplayAdmissionAbortCompositionFixture fixture,
      CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork work)
      throws IOException, ReflectiveOperationException {
    return new LuaInvocation(
        readScript(CanonicalGameplayIssuerBindingLocalRedisScriptContribution.RESOURCE_PATH),
        fixture.issuerPartitionKey(),
        scriptArguments(
            CanonicalGameplayIssuerBindingLocalRedisOwner.class,
            new Class<?>[] {
              CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork.class
            },
            work));
  }

  private static LuaInvocation releaseInvocation(
      CanonicalGameplayAdmissionAbortCompositionFixture fixture,
      CanonicalGameplayIssuerBindingLocalReleaseOperation operation)
      throws IOException, ReflectiveOperationException {
    var work = fixture.readPreparedPartitionWork(operation.originalOperation());
    return new LuaInvocation(
        readScript(CanonicalGameplayIssuerBindingLocalReleaseRedisScriptContribution.RESOURCE_PATH),
        fixture.issuerPartitionKey(),
        scriptArguments(
            CanonicalGameplayIssuerBindingLocalReleaseRedisOwner.class,
            new Class<?>[] {
              CanonicalGameplayIssuerBindingLocalProjectionRepository.PreparedPartitionWork.class,
              CanonicalGameplayIssuerBindingLocalReleaseOperation.class
            },
            work,
            operation));
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

  private static byte[][] scriptArguments(
      Class<?> owner, Class<?>[] parameterTypes, Object... arguments)
      throws ReflectiveOperationException {
    Method method = owner.getDeclaredMethod("scriptArguments", parameterTypes);
    if (!method.trySetAccessible()) {
      throw new IllegalStateException("Test could not capture the exact owner Lua arguments");
    }
    try {
      return (byte[][]) method.invoke(null, arguments);
    } catch (InvocationTargetException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof RuntimeException runtime) throw runtime;
      throw failure;
    }
  }

  private static byte[] readScript(String resourcePath) throws IOException {
    try (InputStream input =
        CanonicalGameplayIssuerPhysicalRetirementIntegrationTest.class
            .getClassLoader()
            .getResourceAsStream(resourcePath)) {
      if (input == null) throw new IOException("Missing Redis Lua resource: " + resourcePath);
      byte[] bytes = input.readAllBytes();
      if (bytes.length == 0) throw new IOException("Empty Redis Lua resource: " + resourcePath);
      return bytes;
    }
  }

  private Long evaluate(LuaInvocation invocation) {
    return administratorCommands.eval(
        invocation.script(),
        ScriptOutputType.INTEGER,
        new byte[][] {invocation.key()},
        invocation.arguments());
  }

  private void assertStaleLuaRejectedWithoutWrites(
      LuaInvocation staleInvocation, byte[] partitionKey) {
    byte[] before = physicalFieldSet(partitionKey);
    Long outcome = evaluate(staleInvocation);
    assertThat(outcome).isNegative();
    assertThat(physicalFieldSet(partitionKey)).isEqualTo(before);
  }

  private byte[] physicalFieldSet(byte[] key) {
    Map<byte[], byte[]> raw = administratorCommands.hgetall(key);
    Map<String, byte[]> fields = new HashMap<>();
    raw.forEach(
        (field, value) -> fields.put(new String(field, StandardCharsets.UTF_8), value.clone()));
    return CanonicalGameplayIssuerBootstrapCoverageRepository.encodeFieldSet(fields);
  }

  private static Map<String, byte[]> decodeFields(byte[] fieldSet) {
    return CanonicalGameplayIssuerBootstrapCoverageRepository.decodeFieldSet(fieldSet);
  }

  private static long capacityFieldCount(byte[] fieldSet) {
    return decodeFields(fieldSet).keySet().stream()
        .filter(
            field ->
                field.startsWith(
                    CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec
                        .RESERVATION_FIELD_PREFIX))
        .count();
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

  private static long localOperationCount(DSLContext dsl) {
    return dsl.fetchSingle(
            "SELECT count(*) FROM game_session_canonical_issuer_binding_local_operation")
        .get(0, Long.class);
  }

  private static long partitionMutationCount(DSLContext dsl) {
    return dsl.fetchSingle("SELECT count(*) FROM game_session_canonical_issuer_partition_mutation")
        .get(0, Long.class);
  }

  private static long releaseCount(DSLContext dsl, String status) {
    return dsl.fetchSingle(
            "SELECT count(*) FROM game_session_canonical_issuer_binding_local_release_operation"
                + " WHERE status = ?",
            status)
        .get(0, Long.class);
  }

  private static long releaseProofCount(DSLContext dsl) {
    return dsl.fetchSingle(
            "SELECT count(*) FROM game_session_canonical_issuer_binding_local_release_operation"
                + " WHERE status = 'RELEASED' AND physical_readback_field_set_bytes IS NOT NULL"
                + " AND completion_inventory_revision > source_inventory_revision")
        .get(0, Long.class);
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

  private static String durableHistoryImage(DSLContext dsl, UUID excludedTransitionId) {
    String sql =
        "SELECT jsonb_build_object("
            + "'transitions', COALESCE((SELECT jsonb_agg(to_jsonb(entry) ORDER BY entry.transition_id)"
            + " FROM game_session_canonical_binding_transition entry WHERE entry.transition_id <> ?), '[]'::jsonb),"
            + "'inventory', COALESCE((SELECT jsonb_agg(to_jsonb(entry) ORDER BY entry.binding_ref)"
            + " FROM game_session_canonical_gameplay_binding_inventory entry WHERE entry.transition_id <> ?), '[]'::jsonb),"
            + "'reservations', COALESCE((SELECT jsonb_agg(to_jsonb(entry) ORDER BY entry.reservation_id)"
            + " FROM game_session_canonical_issuer_partition_reservation entry WHERE entry.transition_id <> ?), '[]'::jsonb),"
            + "'accountObligations', COALESCE((SELECT jsonb_agg(to_jsonb(entry) ORDER BY entry.transition_id, entry.obligation_ordinal)"
            + " FROM game_session_canonical_binding_account_index_obligation entry WHERE entry.transition_id <> ?), '[]'::jsonb),"
            + "'issuerObligations', COALESCE((SELECT jsonb_agg(to_jsonb(entry) ORDER BY entry.transition_id, entry.binding_ref)"
            + " FROM game_session_canonical_binding_issuer_index_obligation entry WHERE entry.transition_id <> ?), '[]'::jsonb),"
            + "'regionObligations', COALESCE((SELECT jsonb_agg(to_jsonb(entry) ORDER BY entry.transition_id)"
            + " FROM game_session_canonical_binding_region_bridge_obligation entry WHERE entry.transition_id <> ?), '[]'::jsonb),"
            + "'localOperations', COALESCE((SELECT jsonb_agg(to_jsonb(entry) ORDER BY entry.operation_id)"
            + " FROM game_session_canonical_issuer_binding_local_operation entry WHERE entry.transition_id <> ?), '[]'::jsonb),"
            + "'releases', COALESCE((SELECT jsonb_agg(to_jsonb(entry) ORDER BY entry.operation_id)"
            + " FROM game_session_canonical_issuer_binding_local_release_operation entry WHERE entry.transition_id <> ?), '[]'::jsonb),"
            + "'mutations', COALESCE((SELECT jsonb_agg(to_jsonb(mutation) ORDER BY mutation.mutation_id)"
            + " FROM game_session_canonical_issuer_partition_mutation mutation WHERE mutation.owner_operation_id IN ("
            + " SELECT local_operation.operation_id FROM game_session_canonical_issuer_binding_local_operation local_operation WHERE local_operation.transition_id <> ?"
            + " UNION SELECT release_entry.operation_id FROM game_session_canonical_issuer_binding_local_release_operation release_entry WHERE release_entry.transition_id <> ?)), '[]'::jsonb)"
            + ")::text";
    return dsl.fetchSingle(
            sql,
            excludedTransitionId,
            excludedTransitionId,
            excludedTransitionId,
            excludedTransitionId,
            excludedTransitionId,
            excludedTransitionId,
            excludedTransitionId,
            excludedTransitionId,
            excludedTransitionId,
            excludedTransitionId)
        .get(0, String.class);
  }

  private static UUID uuid(String seed) {
    return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
  }

  private record LuaInvocation(byte[] script, byte[] key, byte[][] arguments) {
    private LuaInvocation {
      script = script.clone();
      key = key.clone();
      arguments = Arrays.stream(arguments).map(byte[]::clone).toArray(byte[][]::new);
    }

    @Override
    public byte[] script() {
      return script.clone();
    }

    @Override
    public byte[] key() {
      return key.clone();
    }

    @Override
    public byte[][] arguments() {
      return Arrays.stream(arguments).map(byte[]::clone).toArray(byte[][]::new);
    }
  }
}
