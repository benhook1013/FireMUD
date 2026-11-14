package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayOriginalNewRegionHandoffIntegrationTest
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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "false,false",
    "false,true",
    "true,false",
    "true,true"
  })
  void exactAbortedOriginalCompletesSameTransferBeforeAndAfterCapture(
      boolean abortAfterCapture, boolean originalReservationExpired) throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    Clock transferClock =
        originalReservationExpired ? Clock.offset(CLOCK, Duration.ofHours(1)) : CLOCK;
    if (originalReservationExpired)
      assertThat(controlledCapacity.reservation().expiresAt()).isBefore(transferClock.instant());
    var repository =
        new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), transferClock, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture(transferClock)
            .receiver(repository, tags);
    var capacity =
        new CanonicalGameplayOriginalNewCapacityExecutionOwner(
            fixture.dsl(), ownerClient, catalog, transferClock, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), transferClock, repository, capacity, receiver);
    UUID transferId = null;
    if (abortAfterCapture) {
      transferId =
          repository.within(
              transferClock.millis() + 5000L,
              ignored -> repository.begin(original.source, original.status));
      UUID capturedId = transferId;
      var captured =
          repository.within(
              transferClock.millis() + 5000L, ignored -> repository.capture(capturedId));
      assertThat(captured).hasSize(1);
    }
    assertThat(fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();
    byte[] candidate = admin.get(ascii(installation.sessionKey()));
    UUID resumed =
        transfer.advance(original.source, original.status, transferClock.millis() + 5000L);
    if (transferId != null) assertThat(resumed).isEqualTo(transferId);
    String intent =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT intent_digest FROM game_session_region_owner_transfer WHERE transfer_id=?",
                resumed)
            .get(0, String.class);
    // Real expiry of the incumbent Redis lease, followed by actual grant acquisition and protected
    // installation.
    admin.pexpire(ascii(original.grant.key()), 0);
    var installed =
        abortAfterCapture && !originalReservationExpired
            ? resumeAbortedOriginalWithLeaseOwner(transfer, original)
            : installAbortedTransferSuccessor(transfer, original, resumed, transferClock);
    assertThat(installed.ownerInstanceId())
        .isEqualTo(
            abortAfterCapture && !originalReservationExpired
                ? "final-owner-handoff"
                : "aborted-original-successor");
    assertThat(installed.executorFence()).isGreaterThan(original.status.executorFence());
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl()).find(original.source))
        .contains(installed);
    var completed =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT transfer_id,intent_digest,state FROM game_session_region_owner_transfer");
    assertThat(completed.get("transfer_id", UUID.class)).isEqualTo(resumed);
    assertThat(completed.get("intent_digest", String.class)).isEqualTo(intent);
    assertThat(completed.get("state", String.class)).isEqualTo("COMPLETED");
    assertThat(
            receiver
                .observeForRegionOwnerTransfer(resumed, 0, transferClock.millis() + 5000L)
                .regionOwnerTransferContext()
                .controlState())
        .isEqualTo("COMPLETED");
    assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(candidate);
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_canonical_binding_transition WHERE"
                        + " transition_id=?",
                    fixture.transitionId())
                .get(0, String.class))
        .isEqualTo("ABORTED");
    assertThatThrownBy(() -> tags.assignmentForTransition(fixture.transitionId()))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    assertThatThrownBy(
            () ->
                capacity.readExact(controlledCapacity.request(), controlledCapacity.reservation()))
        .isInstanceOf(IllegalStateException.class);
    String terminalRows = sqlImage();
    assertThatThrownBy(
            () ->
                transfer.advance(original.source, original.status, transferClock.millis() + 5000L))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sqlImage()).isEqualTo(terminalRows);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "binding", "ttl"})
  void abortedTransferCannotInstallWithMissingOrChangedRetainedFence(String fault)
      throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    assertThat(fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
    UUID id = transfer.advance(original.source, original.status, deadline());
    byte[] key = ascii(capacityKey(assignment));
    if (fault.equals("missing")) admin.del(java.util.List.of(key).toArray(byte[][]::new));
    else if (fault.equals("binding")) admin.hset(key, ascii("binding"), ascii("changed"));
    else admin.pexpire(key, 60_000L);
    byte[] altered =
        CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(admin, key);
    String retained = sqlImage();
    admin.pexpire(ascii(original.grant.key()), 0);
    assertThatThrownBy(() -> installAbortedTransferSuccessor(transfer, original, id, CLOCK))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl()).find(original.source))
        .contains(original.status);
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT state FROM game_session_region_owner_transfer")
                .get(0, String.class))
        .isEqualTo("FROZEN");
    assertThat(sqlImage()).isEqualTo(retained);
    assertThat(CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(admin, key))
        .containsExactly(altered);
  }

  @Test
  void authorizedUnknownHandoffKeepsOriginalCandidateAndRefusesCompletedMutation()
      throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    byte[] candidate = admin.get(ascii(installation.sessionKey()));
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
    assertThatThrownBy(() -> replaceRegionOwner("unproved-successor"))
        .isInstanceOf(IllegalStateException.class);
    admin.pexpire(ascii(original.grant.key()), 0);
    var successor =
        CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
            fixture.dsl(),
            ownerClient,
            ownerUri,
            catalog,
            original,
            sources.resolver,
            transfer,
            deadline());
    UUID transferId =
        fixture
            .dsl()
            .fetchSingle("SELECT transfer_id FROM game_session_region_owner_transfer")
            .get(0, UUID.class);
    var completed = receiver.observeForRegionOwnerTransfer(transferId, 0, deadline());
    assertThat(completed.regionOwnerTransferContext().controlState()).isEqualTo("COMPLETED");
    assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(candidate);
    assertThatThrownBy(
            () -> repository.within(deadline(), ignored -> repository.capture(transferId)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(successor.status.ownerInstanceId()).isEqualTo("final-owner-handoff");
  }

  @Test
  void completedTransferResumesOnlyWithTheSameLivePrivateGrant() throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var failAfterCommit = new java.util.concurrent.atomic.AtomicBoolean(true);
    var observationSource =
        new CanonicalGameplayRegionOwnerTransferObservationSource() {
          @Override
          public Context readCurrent(UUID transferId, long targetOrdinal) {
            var context = repository.readCurrent(transferId, targetOrdinal);
            if ("COMPLETED".equals(context.controlState()) && failAfterCommit.getAndSet(false)) {
              throw new IllegalStateException("Controlled completed observation failure");
            }
            return context;
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
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
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
    var failure =
        org.junit.jupiter.api.Assertions.assertThrows(
            CanonicalGameplayRegionLeaseOwner.TransferCompletionUnconfirmed.class,
            () ->
                successor.installForTransition(
                    null, original.transitionId, original.worldRequest, transfer, deadline()));
    assertThat(failAfterCommit).isFalse();
    var durable =
        new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl())
            .find(original.source)
            .orElseThrow();
    assertThat(durable.executorFence()).isGreaterThan(original.status.executorFence());
    var completed =
        fixture
            .dsl()
            .fetchSingle("SELECT state, transfer_id FROM game_session_region_owner_transfer");
    assertThat(completed.get("state", String.class)).isEqualTo("COMPLETED");
    assertThatThrownBy(
            () ->
                successor.installForTransition(
                    null, original.transitionId, original.worldRequest, transfer, deadline()))
        .isInstanceOf(IllegalStateException.class);
    String beforeWorldTimeout = sqlImage();
    Map<String, String> beforeWorldTimeoutRedis = physicalImage();
    sources.withholdWorldRead =
        CanonicalGameplayOriginalNewFinalAdmissionTestFixture.ControlledRegionSources.WorldRead
            .PLACEMENT;
    long started = System.nanoTime();
    assertThatThrownBy(() -> successor.resumeCompletedTransfer(failure, CLOCK.millis() + 1500))
        .isInstanceOfSatisfying(
            io.grpc.StatusRuntimeException.class,
            timeout ->
                assertThat(timeout.getStatus().getCode())
                    .isEqualTo(io.grpc.Status.Code.DEADLINE_EXCEEDED));
    assertThat(sources.withheldReadEntered.getCount()).isZero();
    assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
        .isLessThan(3500);
    assertThat(sqlImage()).isEqualTo(beforeWorldTimeout);
    assertThat(physicalImage()).isEqualTo(beforeWorldTimeoutRedis);
    sources.withholdWorldRead = null;
    var recovered = successor.resumeCompletedTransfer(failure, deadline());
    assertThat(recovered.status).isEqualTo(durable);
    assertThat(recovered.grant.tokenCorrelation()).isEqualTo(durable.leaseTokenCorrelation());
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT state, transfer_id FROM game_session_region_owner_transfer"))
        .isEqualTo(completed);
    var completedSql = sqlImage();
    assertThat(successor.resumeCompletedTransfer(failure, deadline()).status).isEqualTo(durable);
    assertThat(sqlImage()).isEqualTo(completedSql);
    var restarted =
        CanonicalGameplayRegionLeaseOwnerFactory.compose(
            fixture.dsl(),
            ownerClient,
            ownerUri,
            catalog,
            incarnation,
            sources.resolver,
            new net.firedevops.firemud.common.runtime.RuntimeIdentity(
                "game-session-service",
                identity.serviceInstanceId(),
                "localhost",
                CLOCK.instant(),
                "test",
                "test",
                "controlled-test-image"),
            java.time.Duration.ofSeconds(30),
            CLOCK);
    assertThatThrownBy(() -> restarted.resumeCompletedTransfer(failure, deadline()))
        .isInstanceOf(IllegalStateException.class);
    byte[] leaseKey = ascii(recovered.grant.key());
    assertThatThrownBy(
            () ->
                restarted.installForTransition(
                    null, original.transitionId, original.worldRequest, transfer, deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sqlImage()).isEqualTo(completedSql);
    admin.pexpire(leaseKey, 0);
    assertThatThrownBy(() -> successor.resumeCompletedTransfer(failure, deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl()).find(original.source))
        .contains(durable);

    assertThat(
            admin.set(
                leaseKey,
                ascii(UUID.randomUUID().toString()),
                io.lettuce.core.SetArgs.Builder.nx().px(30_000)))
        .isEqualTo("OK");
    assertThatThrownBy(
            () ->
                restarted.installForTransition(
                    null, original.transitionId, original.worldRequest, transfer, deadline()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sqlImage()).isEqualTo(completedSql);

    admin.pexpire(leaseKey, 0);
    var fresh =
        restarted.installForTransition(
            null, original.transitionId, original.worldRequest, transfer, deadline());
    assertThat(fresh.status.ownerInstanceId()).isEqualTo(durable.ownerInstanceId());
    assertThat(fresh.status.executorFence()).isGreaterThan(durable.executorFence());
    assertThat(fresh.grant.tokenCorrelation()).isNotEqualTo(recovered.grant.tokenCorrelation());
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT state, transfer_id FROM game_session_region_owner_transfer WHERE"
                        + " transfer_id = ?",
                    completed.get("transfer_id", UUID.class)))
        .isEqualTo(completed);
    assertThat(
            fixture.dsl().fetchCount(org.jooq.impl.DSL.table("game_session_region_owner_transfer")))
        .isEqualTo(2);
  }

  @Test
  void completedTransferRecoversExactAbortRetainedBeforeIndependentVerification() throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var pinnedEvidence = inventory.readProvisionalCasEvidence(original.transitionId);
    var pinnedCandidate =
        inventory.readRegionTransferOriginalCandidate(original.transitionId, pinnedEvidence);
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var accountAbortedAfterInstall = new java.util.concurrent.atomic.AtomicBoolean();
    var observationSource =
        new CanonicalGameplayRegionOwnerTransferObservationSource() {
          @Override
          public Context readCurrent(UUID transferId, long targetOrdinal) {
            var context = repository.readCurrent(transferId, targetOrdinal);
            if ("COMPLETED".equals(context.controlState())
                && accountAbortedAfterInstall.compareAndSet(false, true)) {
              assertThat(fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();
            }
            return context;
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
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
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
    var interrupted =
        org.junit.jupiter.api.Assertions.assertThrows(
            CanonicalGameplayRegionLeaseOwner.TransferCompletionUnconfirmed.class,
            () ->
                successor.installForTransition(
                    null, original.transitionId, original.worldRequest, transfer, deadline()));

    assertThat(accountAbortedAfterInstall).isTrue();
    var durable =
        new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl())
            .find(original.source)
            .orElseThrow();
    assertThat(durable.executorFence()).isGreaterThan(original.status.executorFence());
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT state FROM game_session_region_owner_transfer")
                .get(0, String.class))
        .isEqualTo("COMPLETED");
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_canonical_binding_transition WHERE"
                        + " transition_id=?",
                    original.transitionId)
                .get(0, String.class))
        .isEqualTo("ABORTED");

    var recovered = successor.resumeCompletedTransfer(interrupted, deadline());
    assertThat(recovered.status).isEqualTo(durable);
    assertThat(recovered.grant.tokenCorrelation()).isEqualTo(durable.leaseTokenCorrelation());
    assertThat(inventory.readRegionTransferTerminalSource(original.transitionId, pinnedCandidate))
        .hasValueSatisfying(
            terminal -> {
              assertThat(terminal)
                  .isInstanceOf(
                      CanonicalGameplayBindingInventoryRepository.AbortedRegionTransferSource
                          .class);
              assertThat(terminal.candidate()).isEqualTo(pinnedCandidate);
            });
    assertThatThrownBy(() -> inventory.readProvisionalCasEvidence(original.transitionId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a retryable provisional operation");
    assertThatThrownBy(() -> successor.revalidateBeforeBridge(recovered))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void committedOriginalStartsHandoffWithoutAnExistingTransfer() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var original = regionBinding.installation();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var readback =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      inventory.commitOriginalNewFinalAdmission(readback);
    }
    var retained = inventory.readRegionTransferOriginalSource(fixture.transitionId(), source);
    var terminal =
        capacityOwner().readExact(controlledCapacity.request(), controlledCapacity.reservation());
    assertThat(terminal.disposition()).isEqualTo("COMMITTED");
    assertThat(
            fixture.dsl().fetchCount(org.jooq.impl.DSL.table("game_session_region_owner_transfer")))
        .isZero();
    byte[] session = admin.get(ascii(installation.sessionKey()));

    admin.pexpire(ascii(original.grant.key()), 0);
    var successor = actualTransferHandoff();

    assertThat(successor.evidence).isNull();
    assertThat(successor.status.executorFence()).isGreaterThan(original.status.executorFence());
    assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(session);
    var current = inventory.readRegionTransferOriginalSource(fixture.transitionId(), retained);
    assertThat(current.finalReceipt()).isEqualTo(retained.finalReceipt());
    assertThat(
            capacityOwner()
                .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                .canonicalReadback())
        .containsExactly(terminal.canonicalReadback());
  }

  @Test
  void committedOriginalCanStartTopologyHandoffUnderRetainedAccountFenceIntent() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var original = regionBinding.installation();
    var accountOpen = controlledAccountOpen();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var readback =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source, template, accountOpen, issuerOpen(), controlledCapacity, deadline())) {
      inventory.commitOriginalNewFinalAdmission(readback);
    }

    var retained = inventory.readRegionTransferOriginalSource(fixture.transitionId(), source);
    assertThat(retained.transitionStatus())
        .isEqualTo(CanonicalGameplayBindingTransitionSnapshot.Status.COMMITTED);
    var terminal =
        capacityOwner().readExact(controlledCapacity.request(), controlledCapacity.reservation());
    byte[] session = admin.get(ascii(installation.sessionKey()));
    byte[] accountBarrier =
        CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(
            admin, ascii(accountOpen.key()));
    String originalRepair =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT to_jsonb(repair)::text FROM"
                    + " game_session_canonical_account_member_repair_operation repair WHERE"
                    + " transition_id=?",
                fixture.transitionId())
            .get(0, String.class);

    var fenceIntent =
        new CanonicalGameplayAccountAdmissionBarrierRepository(fixture.dsl())
            .beginFenceIntent(source.candidate().bindingIdentity().accountId(), UUID.randomUUID());
    assertThat(fenceIntent.state().name()).isEqualTo("ACQUISITION_INTENT");
    assertThat(fenceIntent.cohortRows())
        .filteredOn(
            row ->
                row.kind() == CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.TRANSITION
                    && row.sourceKey().equals(fixture.transitionId().toString()))
        .hasSize(1);
    String fencedControl =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT to_jsonb(control)::text FROM"
                    + " game_session_canonical_account_coverage_control control WHERE account_id=?",
                source.candidate().bindingIdentity().accountId())
            .get(0, String.class);

    assertThatThrownBy(() -> inventory.readOriginalNewFinalAdmissionSource(decision))
        .isExactlyInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    var underFence = inventory.readRegionTransferOriginalSource(fixture.transitionId(), source);
    assertThat(underFence.finalReceipt()).isEqualTo(retained.finalReceipt());

    admin.pexpire(ascii(original.grant.key()), 0);
    var successor = actualTransferHandoff();

    assertThat(successor.evidence).isNull();
    assertThat(successor.status.executorFence()).isGreaterThan(original.status.executorFence());
    assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(session);
    assertThat(
            CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(
                admin, ascii(accountOpen.key())))
        .containsExactly(accountBarrier);
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT to_jsonb(control)::text FROM"
                        + " game_session_canonical_account_coverage_control control WHERE"
                        + " account_id=?",
                    source.candidate().bindingIdentity().accountId())
                .get(0, String.class))
        .isEqualTo(fencedControl);
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT to_jsonb(repair)::text FROM"
                        + " game_session_canonical_account_member_repair_operation repair WHERE"
                        + " transition_id=?",
                    fixture.transitionId())
                .get(0, String.class))
        .isEqualTo(originalRepair);
    assertThat(
            inventory
                .readRegionTransferOriginalSource(fixture.transitionId(), underFence)
                .finalReceipt())
        .isEqualTo(retained.finalReceipt());
    assertThat(
            capacityOwner()
                .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                .canonicalReadback())
        .containsExactly(terminal.canonicalReadback());
  }

  @Test
  void frozenTransferResumesExactTerminalOriginalThatCommittedBeforeCapture() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var original = regionBinding.installation();
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
    UUID transferId;
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var readback =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      transferId =
          repository.within(
              deadline(), ignored -> repository.begin(original.source, original.status));
      assertThat(transferId).isNotNull();
      inventory.commitOriginalNewFinalAdmission(readback);
    }
    var captured = repository.within(deadline(), ignored -> repository.capture(transferId));
    assertThat(captured).isEmpty();
    var retained = inventory.readRegionTransferOriginalSource(fixture.transitionId(), source);
    byte[] committed = admin.get(ascii(installation.sessionKey()));
    assertThat(retained.finalReceipt()).isPresent();
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl()).find(original.source))
        .contains(original.status);
    admin.pexpire(ascii(original.grant.key()), 0);
    var successor =
        CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
            fixture.dsl(),
            ownerClient,
            ownerUri,
            catalog,
            original,
            sources.resolver,
            transfer,
            deadline());
    assertThat(successor.evidence).isNull();
    assertThat(successor.status.executorFence()).isGreaterThan(original.status.executorFence());
    var completed = fixture.dsl().fetchSingle("SELECT * FROM game_session_region_owner_transfer");
    assertThat(completed.get("transfer_id", UUID.class)).isEqualTo(transferId);
    assertThat(completed.get("state", String.class)).isEqualTo("COMPLETED");
    assertThat(
            fixture
                .dsl()
                .fetchCount(org.jooq.impl.DSL.table("game_session_region_owner_transfer_item")))
        .isZero();
    assertThat(
            inventory
                .readRegionTransferOriginalSource(fixture.transitionId(), retained)
                .finalReceipt())
        .isEqualTo(retained.finalReceipt());
    assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(committed);
  }

  @Test
  void secondFrozenHandoffResumesOlderOriginalAbortedBeforeCapture() throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var originalCandidate = inventory.readOriginalNewFinalAdmissionSource(decision).candidate();
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);

    UUID firstTransferId = transfer.advance(original.source, original.status, deadline());
    assertThat(firstTransferId).isNotNull();
    admin.pexpire(ascii(original.grant.key()), 0);
    var firstSuccessor =
        CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
            fixture.dsl(),
            ownerClient,
            ownerUri,
            catalog,
            original,
            sources.resolver,
            transfer,
            deadline());
    assertThat(firstSuccessor.status.executorFence())
        .isGreaterThan(original.status.executorFence());
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT state FROM game_session_region_owner_transfer WHERE transfer_id=?",
                    firstTransferId)
                .get(0, String.class))
        .isEqualTo("COMPLETED");

    UUID secondTransferId =
        repository.within(
            deadline(), ignored -> repository.begin(firstSuccessor.source, firstSuccessor.status));
    // Leave the committed second freeze without capture, as after an interrupted process.
    assertThat(secondTransferId).isNotNull().isNotEqualTo(firstTransferId);
    var secondFrozen =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT old_owner_id,old_owner_fence,state FROM game_session_region_owner_transfer"
                    + " WHERE transfer_id=?",
                secondTransferId);
    assertThat(secondFrozen.get("old_owner_id", String.class))
        .isEqualTo(firstSuccessor.status.ownerInstanceId())
        .isNotEqualTo(controlledCapacity.selectedOwnerId());
    assertThat(secondFrozen.get("old_owner_fence", Long.class))
        .isGreaterThan(controlledCapacity.selectedOwnerFence());
    assertThat(secondFrozen.get("state", String.class)).isEqualTo("FROZEN");
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT count(*) FROM game_session_region_owner_transfer_item WHERE"
                        + " transfer_id=?",
                    secondTransferId)
                .get(0, Long.class))
        .isZero();
    assertThat(fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();

    var frozenOriginal =
        repository.within(deadline(), ignored -> repository.frozenOriginal(fixture.transitionId()));
    assertThat(frozenOriginal).isNotNull();
    assertThat(frozenOriginal.transferId()).isEqualTo(secondTransferId);
    assertThat(frozenOriginal.executionDigest()).isEqualTo(controlledCapacity.digest());
    assertThat(frozenOriginal.candidate().canonicalBytes())
        .containsExactly(originalCandidate.canonicalBytes());

    byte[] candidateBytes = admin.get(ascii(installation.sessionKey()));
    var thirdIdentity =
        new net.firedevops.firemud.common.runtime.RuntimeIdentity(
            "game-session-service",
            "third-owner-handoff",
            "localhost",
            CLOCK.instant(),
            "test",
            "test",
            "controlled-test-image");
    CanonicalGameplayRegionIncarnationSource thirdIncarnation =
        scope -> {
          assertThat(scope).isEqualTo(firstSuccessor.incarnation.scope());
          return firstSuccessor.incarnation;
        };
    var thirdOwner =
        CanonicalGameplayRegionLeaseOwnerFactory.compose(
            fixture.dsl(),
            ownerClient,
            ownerUri,
            catalog,
            thirdIncarnation,
            sources.resolver,
            thirdIdentity,
            Duration.ofSeconds(30),
            CLOCK);
    admin.pexpire(ascii(firstSuccessor.grant.key()), 0);
    var secondSuccessor =
        thirdOwner.installForTransition(
            null, firstSuccessor.transitionId, firstSuccessor.worldRequest, transfer, deadline());
    assertThat(secondSuccessor.status.ownerInstanceId()).isEqualTo("third-owner-handoff");
    assertThat(secondSuccessor.status.executorFence())
        .isGreaterThan(firstSuccessor.status.executorFence());
    assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(candidateBytes);
    var retainedExecution =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT selected_owner_id,selected_owner_fence,status FROM"
                    + " game_session_original_new_capacity_execution WHERE execution_digest=?",
                controlledCapacity.digest());
    assertThat(retainedExecution.get("selected_owner_id", String.class))
        .isEqualTo(controlledCapacity.selectedOwnerId());
    assertThat(retainedExecution.get("selected_owner_fence", Long.class))
        .isEqualTo(controlledCapacity.selectedOwnerFence());
    assertThat(retainedExecution.get("status", String.class)).isEqualTo("AUTHORIZED");
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT old_owner_id,old_owner_fence,new_owner_id,new_owner_fence,state FROM"
                        + " game_session_region_owner_transfer WHERE transfer_id=?",
                    secondTransferId)
                .get("state", String.class))
        .isEqualTo("COMPLETED");
    var completedSecondTransfer =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT old_owner_id,old_owner_fence,new_owner_id,new_owner_fence FROM"
                    + " game_session_region_owner_transfer WHERE transfer_id=?",
                secondTransferId);
    assertThat(completedSecondTransfer.get("old_owner_id", String.class))
        .isEqualTo(firstSuccessor.status.ownerInstanceId());
    assertThat(completedSecondTransfer.get("new_owner_id", String.class))
        .isEqualTo("third-owner-handoff");
    assertThat(completedSecondTransfer.get("new_owner_fence", Long.class))
        .isGreaterThan(completedSecondTransfer.get("old_owner_fence", Long.class));
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT status FROM game_session_canonical_binding_transition WHERE"
                        + " transition_id=?",
                    fixture.transitionId())
                .get(0, String.class))
        .isEqualTo("ABORTED");
  }

  @Test
  void frozenTransferResumesSameCommittedOriginalAfterFenceRetentionFailure() throws Exception {
    repairAccountMember();
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    byte[] committed;
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var ignored =
            finalOwner()
                .finalizeOriginalNewOnce(
                    source,
                    template,
                    controlledAccountOpen(),
                    issuerOpen(),
                    controlledCapacity,
                    deadline())) {
      committed = admin.get(ascii(installation.sessionKey()));
    }
    var original = regionBinding.installation();
    var failRetention = new java.util.concurrent.atomic.AtomicBoolean(true);
    var failed = new java.util.concurrent.atomic.AtomicBoolean();
    var observer =
        new org.jooq.impl.DefaultExecuteListener() {
          @Override
          public void executeStart(org.jooq.ExecuteContext context) {
            String query = context.sql();
            if (query == null || !query.contains(" SET fence_readback=")) return;
            if (!failRetention.get()) return;
            assertThat(
                    fixture
                        .dsl()
                        .fetchSingle(
                            "SELECT status FROM game_session_original_new_capacity_execution WHERE"
                                + " execution_digest=?",
                            controlledCapacity.digest())
                        .get(0, String.class))
                .isEqualTo("COMMITTED");
            assertThat(
                    fixture
                        .dsl()
                        .fetchSingle(
                            "SELECT original_new_final_session_sha256 FROM"
                                + " game_session_canonical_binding_transition WHERE"
                                + " transition_id=?",
                            fixture.transitionId())
                        .get(0, String.class))
                .isNotBlank();
            failed.set(true);
            throw new IllegalStateException("Controlled failure after positive SQL retention");
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
            Duration.ofSeconds(30),
            CLOCK);
    admin.pexpire(ascii(original.grant.key()), 0);
    var frozenFailure =
        org.junit.jupiter.api.Assertions.assertThrows(
            CanonicalGameplayRegionLeaseOwner.FrozenTransferUnconfirmed.class,
            () ->
                successor.installForTransition(
                    null, original.transitionId, original.worldRequest, transfer, deadline()));
    assertThat(failed).isTrue();
    var frozen = fixture.dsl().fetchSingle("SELECT * FROM game_session_region_owner_transfer");
    UUID transferId = frozen.get("transfer_id", UUID.class);
    String intent = frozen.get("intent_digest", String.class);
    assertThat(frozen.get("state", String.class)).isEqualTo("FROZEN");
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl()).find(original.source))
        .contains(original.status);
    var retained = inventory.readRegionTransferOriginalSource(fixture.transitionId(), source);
    assertThat(retained.transitionStatus())
        .isEqualTo(CanonicalGameplayBindingTransitionSnapshot.Status.COMMITTED);
    assertThat(retained.finalReceipt()).isPresent();
    String executionImage =
        fixture
            .dsl()
            .fetchSingle(
                "SELECT to_jsonb(e)::text FROM game_session_original_new_capacity_execution e WHERE"
                    + " execution_digest=?",
                controlledCapacity.digest())
            .get(0, String.class);
    long sequence =
        fixture
            .dsl()
            .fetchSingle("SELECT last_value FROM game_session_region_executor_fence_seq")
            .get(0, Long.class);
    failRetention.set(false);
    var recovered = successor.resumeFrozenTransfer(frozenFailure, deadline());
    assertThat(recovered.status.ownerInstanceId()).isEqualTo("final-owner-handoff");
    var completed = fixture.dsl().fetchSingle("SELECT * FROM game_session_region_owner_transfer");
    assertThat(completed.get("transfer_id", UUID.class)).isEqualTo(transferId);
    assertThat(completed.get("intent_digest", String.class)).isEqualTo(intent);
    assertThat(completed.get("state", String.class)).isEqualTo("COMPLETED");
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT last_value FROM game_session_region_executor_fence_seq")
                .get(0, Long.class))
        .isEqualTo(sequence + 1);
    assertThat(
            inventory
                .readRegionTransferOriginalSource(fixture.transitionId(), retained)
                .finalReceipt())
        .isEqualTo(retained.finalReceipt());
    assertThat(
            fixture
                .dsl()
                .fetchSingle(
                    "SELECT to_jsonb(e)::text FROM game_session_original_new_capacity_execution e"
                        + " WHERE execution_digest=?",
                    controlledCapacity.digest())
                .get(0, String.class))
        .isEqualTo(executionImage);
    assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(committed);
    assertThat(recovered.evidence)
        .as("committed continuation grants no provisional bridge authority")
        .isNull();
    // The old epoch cannot reacquire the lease after the successor advances the timeline.
    assertThatThrownBy(
            () ->
                CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
                    fixture.dsl(),
                    ownerClient,
                    ownerUri,
                    catalog,
                    original,
                    sources.resolver,
                    transfer,
                    deadline()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Canonical Game Session region lease is unavailable");
    assertThat(
            new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl()).find(original.source))
        .contains(recovered.status);
    assertThat(
            fixture
                .dsl()
                .fetchSingle("SELECT last_value FROM game_session_region_executor_fence_seq")
                .get(0, Long.class))
        .isEqualTo(sequence + 1);
  }

  private net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus
      resumeAbortedOriginalWithLeaseOwner(
          CanonicalGameplayRegionOwnerTransferCoordinator transfer,
          CanonicalGameplayRegionLeaseOwner.Installation original) {
    var resumed =
        CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
            fixture.dsl(),
            ownerClient,
            ownerUri,
            catalog,
            original,
            sources.resolver,
            transfer,
            deadline());
    assertThat(resumed.evidence).isNull();
    return resumed.status;
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void abortedCapturedOriginalRetainsPhysicalClosureBeforeSuccessorInstallation(
      boolean closeBeforeAbort) throws Exception {
    repairAccountMember();
    bridge();
    var original = regionBinding.installation();
    var repository = new CanonicalGameplayRegionOwnerTransferRepository(fixture.dsl(), CLOCK, tags);
    var receiver =
        new CanonicalGameplayControlledRegionTransferObservationFixture()
            .receiver(repository, tags);
    var transfer =
        new CanonicalGameplayRegionOwnerTransferCoordinator(
            fixture.dsl(), CLOCK, repository, capacityOwner(), receiver);
    UUID id =
        repository.within(
            deadline(), ignored -> repository.begin(original.source, original.status));
    var captured = repository.within(deadline(), ignored -> repository.capture(id));
    assertThat(captured).hasSize(1);
    if (closeBeforeAbort)
      capacityOwner()
          .closeOriginalAttempt(controlledCapacity.request(), controlledCapacity.reservation());
    assertThat(fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();
    if (!closeBeforeAbort)
      capacityOwner()
          .closeOriginalAttempt(controlledCapacity.request(), controlledCapacity.reservation());
    var proof =
        capacityOwner().readExact(controlledCapacity.request(), controlledCapacity.reservation());
    assertThat(proof.disposition()).isEqualTo("NOT_EXECUTED_AFTER_AUTHORIZATION");
    byte[] tombstone =
        CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(
            admin,
            ascii(
                capacityKey(tags.assignmentForRetainedCapacityExecution(fixture.transitionId()))));
    assertThat(transfer.advance(original.source, original.status, deadline())).isEqualTo(id);
    admin.pexpire(ascii(original.grant.key()), 0);
    var installed = installAbortedTransferSuccessor(transfer, original, id, CLOCK);
    assertThat(installed.executorFence()).isGreaterThan(original.status.executorFence());
    assertThat(
            receiver
                .observeForRegionOwnerTransfer(id, 0, deadline())
                .regionOwnerTransferContext()
                .controlState())
        .isEqualTo("COMPLETED");
    assertThat(
            capacityOwner()
                .readExact(controlledCapacity.request(), controlledCapacity.reservation())
                .canonicalReadback())
        .containsExactly(proof.canonicalReadback());
    assertThat(
            CanonicalGameplayOriginalNewFinalAdmissionPhysicalImageFixture.image(
                admin,
                ascii(
                    capacityKey(
                        tags.assignmentForRetainedCapacityExecution(fixture.transitionId())))))
        .containsExactly(tombstone);
  }

  private net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus
      installAbortedTransferSuccessor(
          CanonicalGameplayRegionOwnerTransferCoordinator transfer,
          CanonicalGameplayRegionLeaseOwner.Installation original,
          UUID transferId,
          Clock transferClock) {
    var identity =
        new net.firedevops.firemud.common.runtime.RuntimeIdentity(
            "game-session-service",
            "aborted-original-successor",
            "localhost",
            transferClock.instant(),
            "test",
            "test",
            "controlled-test-image");
    var leases = new CanonicalGameplayRegionLeaseRedisOwner(ownerClient, ownerUri, catalog);
    var grant =
        leases.acquire(
            original.source, original.status.regionEpoch(), Duration.ofSeconds(30), identity);
    leases.requireGrantScope(grant, original.source, original.status.regionEpoch(), identity);
    var preparation =
        new CanonicalGameplayRegionRuntimeStatusRepository(fixture.dsl())
            .findTimelinePreparation(original.source)
            .orElseThrow();
    leases.verifyInitializedTimeline(grant, preparation);
    leases.requireCurrent(grant);
    var installed =
        transfer.install(
            transferId,
            original.source,
            original.status,
            grant.tokenCorrelation(),
            identity.service(),
            identity.serviceInstanceId(),
            transferClock.millis() + 5000L);
    leases.requireCurrent(grant);
    return installed;
  }
}
