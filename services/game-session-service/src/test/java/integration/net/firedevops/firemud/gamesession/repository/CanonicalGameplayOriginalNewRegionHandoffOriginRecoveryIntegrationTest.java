package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayOriginalNewRegionHandoffOriginRecoveryIntegrationTest
    extends CanonicalGameplayOriginalNewFinalAdmissionOwnerIntegrationTestSupport {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  @Override
  PostgreSQLContainer<?> postgresContainer() {
    return POSTGRES;
  }

  @Override
  GenericContainer<?> redisContainer() {
    return REDIS;
  }

  @Test
  void frozenTransferResumesAfterAdvanceAndInstallFailuresWithTheSameLiveGrant() throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var failFenceReceipt = new java.util.concurrent.atomic.AtomicBoolean(true);
    var failInstallCompletion = new java.util.concurrent.atomic.AtomicBoolean(true);
    var observer =
        new org.jooq.impl.DefaultExecuteListener() {
          @Override
          public void executeStart(org.jooq.ExecuteContext context) {
            String statement = context.sql();
            if (statement == null) return;
            if (statement.contains("UPDATE game_session_region_owner_transfer_item")
                && statement.contains("SET fence_readback=?")
                && failFenceReceipt.compareAndSet(true, false)) {
              throw new IllegalStateException("Controlled failure after successor fence CAS");
            }
            if (statement.contains("UPDATE game_session_region_owner_transfer")
                && statement.contains("SET state='COMPLETED'")
                && failInstallCompletion.compareAndSet(true, false)) {
              throw new IllegalStateException(
                  "Controlled failure before transfer completion commit");
            }
          }
        };
    var observedSql =
        org.jooq.impl.DSL.using(fixture.dsl().configuration().deriveAppending(observer));
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(observedSql, CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            observedSql, CLOCK, repository, capacityOwner(), receiver);
    var identity =
        new net.firedevops.firemud.common.runtime.RuntimeIdentity(
            "game-session-service",
            "final-owner-handoff",
            "localhost",
            CLOCK.instant(),
            "test",
            "test",
            "controlled-test-image");
    CanonicalGameplayRegionIncarnationSource incarnation =
        scope -> {
          assertThat(scope).isEqualTo(original.incarnation.scope());
          return original.incarnation;
        };
    var successor =
        CanonicalGameplayRegionLeaseOwnerFactory.compose(
            fixture.dsl(),
            ownerClient,
            ownerUri,
            catalog,
            incarnation,
            sources.resolver,
            identity,
            java.time.Duration.ofSeconds(30),
            CLOCK);
    admin.pexpire(ascii(original.grant.key()), 0);

    var frozenFailure =
        org.junit.jupiter.api.Assertions.assertThrows(
            CanonicalGameplayRegionLeaseOwner.FrozenTransferUnconfirmed.class,
            () ->
                successor.installForTransition(
                    null, original.transitionId, original.worldRequest, transfer, deadline()));
    assertThat(failFenceReceipt).isFalse();
    var frozen = fixture.dsl().fetchSingle("SELECT * FROM game_session_region_owner_transfer");
    UUID transferId = frozen.get("transfer_id", UUID.class);
    assertThat(frozen.get("state", String.class)).isEqualTo("FROZEN");
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT fence_readback FROM game_session_region_owner_transfer_item WHERE"
                        + " transfer_id=? ORDER BY target_ordinal LIMIT 1",
                    transferId)
                .get(0, byte[].class))
        .isNull();
    String fencedKey = capacityKey(tags.assignmentForTransition(fixture.transitionId()));
    assertThat(admin.hget(ascii(fencedKey), ascii("state")))
        .containsExactly(ascii("FENCED_INDETERMINATE"));
    String frozenSql = sqlImage();
    Map<String, String> frozenPhysical = physicalImage();

    var differentOwner =
        CanonicalGameplayRegionLeaseOwnerFactory.compose(
            fixture.dsl(),
            ownerClient,
            ownerUri,
            catalog,
            incarnation,
            sources.resolver,
            identity,
            java.time.Duration.ofSeconds(30),
            CLOCK);
    assertThatThrownBy(() -> differentOwner.resumeFrozenTransfer(frozenFailure, deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sqlImage()).isEqualTo(frozenSql);
    assertThat(physicalImage()).isEqualTo(frozenPhysical);

    sources.withholdWorldRead =
        CanonicalGameplayOriginalNewFinalAdmissionTestFixture.ControlledRegionSources.WorldRead
            .LIFECYCLE;
    long started = System.nanoTime();
    assertThatThrownBy(() -> successor.resumeFrozenTransfer(frozenFailure, CLOCK.millis() + 1500))
        .isInstanceOf(CanonicalGameplayRegionLeaseOwner.FrozenTransferUnconfirmed.class)
        .rootCause()
        .isInstanceOfSatisfying(
            io.grpc.StatusRuntimeException.class,
            timeout ->
                assertThat(timeout.getStatus().getCode())
                    .isEqualTo(io.grpc.Status.Code.DEADLINE_EXCEEDED));
    assertThat(sources.withheldReadEntered.getCount()).isZero();
    assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
        .isLessThan(3500);
    assertThat(sqlImage()).isEqualTo(frozenSql);
    assertThat(physicalImage()).isEqualTo(frozenPhysical);
    sources.withholdWorldRead = null;

    var installFailure =
        org.junit.jupiter.api.Assertions.assertThrows(
            CanonicalGameplayRegionLeaseOwner.TransferCompletionUnconfirmed.class,
            () -> successor.resumeFrozenTransfer(frozenFailure, deadline()));
    assertThat(failInstallCompletion).isFalse();
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT state,transfer_id FROM game_session_region_owner_transfer"))
        .satisfies(
            row -> {
              assertThat(row.get("state", String.class)).isEqualTo("FROZEN");
              assertThat(row.get("transfer_id", UUID.class)).isEqualTo(transferId);
            });
    long reservedFence =
        fixture
            .dsl()
            .fetchSingle("SELECT successor_owner_fence FROM game_session_region_owner_transfer")
            .get(0, Long.class);
    var recovered = successor.resumeFrozenTransfer(installFailure, deadline());
    assertThat(recovered.status.executorFence()).isEqualTo(reservedFence);
    assertThat(recovered.grant.tokenCorrelation())
        .isEqualTo(recovered.status.leaseTokenCorrelation());
    var completed =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT state,transfer_id,successor_owner_fence FROM"
                    + " game_session_region_owner_transfer");
    assertThat(completed.get("state", String.class)).isEqualTo("COMPLETED");
    assertThat(completed.get("transfer_id", UUID.class)).isEqualTo(transferId);
    assertThat(completed.get("successor_owner_fence", Long.class)).isEqualTo(reservedFence);
    String completedSql = sqlImage();
    admin.pexpire(ascii(recovered.grant.key()), 0);
    assertThatThrownBy(() -> successor.resumeFrozenTransfer(installFailure, deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sqlImage()).isEqualTo(completedSql);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(HandoffAbortPhase.class)
  void sameGrantResumesFrozenAndCompletedHandoffOfDefinitivelyAbortedOriginal(
      HandoffAbortPhase abortPhase) throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    String executionKey = capacityKey(assignment);
    var originalCandidateAndIndexes = physicalImage();
    originalCandidateAndIndexes.remove(executionKey);

    var interruptCapture = new java.util.concurrent.atomic.AtomicBoolean(true);
    var interruptConfirmation = new java.util.concurrent.atomic.AtomicBoolean(true);
    var observer =
        new org.jooq.impl.DefaultExecuteListener() {
          @Override
          public void executeStart(org.jooq.ExecuteContext context) {
            String statement = context.sql();
            if (statement == null) return;
            boolean atCaptureBoundary =
                abortPhase == HandoffAbortPhase.AFTER_CAPTURE
                    ? statement.contains("UPDATE game_session_region_owner_transfer_item")
                        && statement.contains("SET fence_readback=?")
                    : statement.contains("INSERT INTO game_session_region_owner_transfer_item");
            if (atCaptureBoundary && interruptCapture.getAndSet(false)) {
              throw new IllegalStateException("Controlled interruption at original capture");
            }
          }
        };
    var observedSql =
        org.jooq.impl.DSL.using(fixture.dsl().configuration().deriveAppending(observer));
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(observedSql, CLOCK, tags);
    var observationSource =
        new CanonicalGameplayRegionOwnerTransferObservationSource() {
          @Override
          public Context readCurrent(UUID transferId, long targetOrdinal) {
            var current = repository.readCurrent(transferId, targetOrdinal);
            if ("COMPLETED".equals(current.controlState())
                && interruptConfirmation.getAndSet(false)) {
              throw new IllegalStateException("Controlled completed observation failure");
            }
            return current;
          }

          @Override
          public CanonicalGameplayTenantGameplayTagRepository.Assignment readAssignment(
              UUID transitionId, CanonicalGameplayTenantGameplayTagRepository sourceTags) {
            return repository.readAssignment(transitionId, sourceTags);
          }
        };
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(observationSource, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            observedSql, CLOCK, repository, capacityOwner(), receiver);
    var identity =
        new net.firedevops.firemud.common.runtime.RuntimeIdentity(
            "game-session-service",
            "aborted-original-successor",
            "localhost",
            CLOCK.instant(),
            "test",
            "test",
            "controlled-test-image");
    var successor =
        CanonicalGameplayRegionLeaseOwnerFactory.compose(
            fixture.dsl(),
            ownerClient,
            ownerUri,
            catalog,
            scope -> {
              assertThat(scope).isEqualTo(original.incarnation.scope());
              return original.incarnation;
            },
            sources.resolver,
            identity,
            Duration.ofSeconds(30),
            CLOCK);
    byte[] leaseKey = ascii(original.grant.key());
    admin.pexpire(leaseKey, 0);

    var interrupted =
        org.junit.jupiter.api.Assertions.assertThrows(
            CanonicalGameplayRegionLeaseOwner.FrozenTransferUnconfirmed.class,
            () ->
                successor.installForTransition(
                    null, original.transitionId, original.worldRequest, transfer, deadline()));
    assertThat(interruptCapture).isFalse();
    var frozen = fixture.dsl().fetchSingle("SELECT * FROM game_session_region_owner_transfer");
    UUID transferId = frozen.get("transfer_id", UUID.class);
    assertThat(frozen.get("state", String.class)).isEqualTo("FROZEN");
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT count(*) FROM game_session_region_owner_transfer_item WHERE"
                        + " transfer_id=?",
                    transferId)
                .get(0, Long.class))
        .isEqualTo(abortPhase == HandoffAbortPhase.AFTER_CAPTURE ? 1L : 0L);
    byte[] frozenGrant = admin.get(leaseKey);
    assertThat(frozenGrant).isNotEmpty();
    String grantCorrelation =
        CanonicalGameplayIssuerMemberV2Codec.sha256(frozenGrant).substring("sha256:".length());
    assertThat(grantCorrelation).isNotEqualTo(original.grant.tokenCorrelation());

    var abort =
        abortPhase == HandoffAbortPhase.AFTER_INSTALL ? null : fixture.recordOriginalAccountAbort();
    var lostCompletion =
        org.junit.jupiter.api.Assertions.assertThrows(
            CanonicalGameplayRegionLeaseOwner.TransferCompletionUnconfirmed.class,
            () -> successor.resumeFrozenTransfer(interrupted, deadline()));
    assertThat(interruptConfirmation).isFalse();
    var completed = fixture.dsl().fetchSingle("SELECT * FROM game_session_region_owner_transfer");
    assertThat(completed.get("transfer_id", UUID.class)).isEqualTo(transferId);
    assertThat(completed.get("intent_digest", String.class))
        .isEqualTo(frozen.get("intent_digest", String.class));
    assertThat(completed.get("state", String.class)).isEqualTo("COMPLETED");
    assertThat(completed.get("new_lease_correlation", String.class)).isEqualTo(grantCorrelation);
    if (abortPhase == HandoffAbortPhase.AFTER_INSTALL) {
      assertThat(
              fixture
                  .dsl()
                  .fetchSingle(
                      "SELECT status FROM game_session_canonical_binding_transition WHERE"
                          + " transition_id=?",
                      original.transitionId)
                  .get(0, String.class))
          .isEqualTo("PROVISIONAL");
      abort = fixture.recordOriginalAccountAbort();
    }
    assertThat(abort).isNotNull();
    assertThat(abort.isAccountAborted()).isTrue();
    var retained =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT execution_digest,canonical_execution,fence_readback FROM"
                    + " game_session_region_owner_transfer_item WHERE transfer_id=?",
                transferId);
    assertThat(retained.get("execution_digest", String.class))
        .isEqualTo(controlledCapacity.digest());
    assertThat(retained.get("canonical_execution", byte[].class))
        .containsExactly(controlledCapacity.canonicalBytes());
    assertThat(retained.get("fence_readback", byte[].class)).isNotEmpty();

    var recovered = successor.resumeCompletedTransfer(lostCompletion, deadline());
    assertThat(recovered.grant.tokenCorrelation()).isEqualTo(grantCorrelation);
    assertThat(recovered.status.leaseTokenCorrelation()).isEqualTo(grantCorrelation);
    assertThat(recovered.status.executorFence())
        .isEqualTo(completed.get("new_owner_fence", BigInteger.class));
    assertThat(recovered.status.executorFence()).isGreaterThan(original.status.executorFence());
    assertThat(recovered.evidence).isNull();
    assertThat(admin.get(leaseKey)).containsExactly(frozenGrant);
    assertThat(admin.hget(ascii(executionKey), ascii("state")))
        .containsExactly(ascii("FENCED_INDETERMINATE"));
    assertThat(admin.pttl(ascii(executionKey))).isEqualTo(-1L);

    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template()) {
      assertThatThrownBy(
              () ->
                  finalOwner()
                      .finalizeOriginalNewOnce(
                          source,
                          template,
                          accountOpen,
                          issuerOpen,
                          controlledCapacity,
                          deadline()))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThatThrownBy(
            () ->
                capacityOwner()
                    .readExact(controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    var transition =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT status,admission_orphan_cleanup_id,original_new_final_session_sha256 FROM"
                    + " game_session_canonical_binding_transition WHERE transition_id=?",
                original.transitionId);
    assertThat(transition.get("status", String.class)).isEqualTo("ABORTED");
    assertThat(transition.get("admission_orphan_cleanup_id", UUID.class))
        .isEqualTo(abort.orphanCleanupId());
    assertThat(transition.get("original_new_final_session_sha256", String.class)).isNull();
    var execution =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT status,terminal_readback FROM game_session_original_new_capacity_execution"
                    + " WHERE execution_digest=?",
                controlledCapacity.digest());
    assertThat(execution.get("status", String.class)).isEqualTo("AUTHORIZED");
    assertThat(execution.get("terminal_readback", byte[].class)).isNull();
    var candidateAndIndexes = physicalImage();
    candidateAndIndexes.remove(executionKey);
    assertThat(candidateAndIndexes).isEqualTo(originalCandidateAndIndexes);
  }

  private enum HandoffAbortPhase {
    BEFORE_CAPTURE,
    AFTER_CAPTURE,
    AFTER_INSTALL
  }
}
