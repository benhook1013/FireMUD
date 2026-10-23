package net.firedevops.firemud.entitymanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.account.RuntimeAccountIdentityEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRetirementEvidence;
import net.firedevops.firemud.entitymanagement.repository.CanonicalGameplayRosterRetentionRepository.ReferenceKind;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterActor;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterExecutionContext;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterReadRequest;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRetentionBinding;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRetentionPolicy;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSnapshot;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSnapshotReference;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidence;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorCorePayload;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryRequest;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Controlled SQL owner-family proof only. Fixture owner evidence is synthetic; these tests do not
 * authenticate upstream release/retirement authority or prove eligible disposal.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayRosterRetentionPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private String schema;
  private DSLContext dsl;
  private TransactionTemplate transaction;
  private CanonicalGameplayRosterRetentionRepository repository;
  private CanonicalGameplayRosterRetentionBinding binding;

  @BeforeEach
  void upgradeRetainedV1011ActorReceiptAndSealedSnapshot() {
    schema = "roster_retention_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, "1011");
    dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    repository = new CanonicalGameplayRosterRetentionRepository(dsl);
    binding =
        new CanonicalGameplayRosterRetentionBinding(
            UUID.randomUUID(),
            new CanonicalGameplayRosterSnapshotReference(UUID.randomUUID(), "a".repeat(64)),
            target());
    transaction.executeWithoutResult(ignored -> seedAssignedActorAndSnapshot());
    List<String> retained = retainedRows();
    migrate(dataSource, "1014");
    assertThat(retainedRows()).containsExactlyElementsOf(retained);
  }

  @AfterEach
  void dropOwnedFixture() {
    if (schema != null) {
      DSL.using(dataSource(null), SQLDialect.POSTGRES)
          .execute("DROP SCHEMA \"" + schema.replaceAll("[^a-zA-Z0-9_]", "") + "\" CASCADE");
    }
  }

  @Test
  void immutableAcquireReleaseAndHoldOutcomesNeverGrantTerminality() {
    UUID acquireOperation = UUID.randomUUID();
    UUID reference = UUID.randomUUID();
    UUID hold = UUID.randomUUID();
    UUID releaseOperation = UUID.randomUUID();
    transaction.executeWithoutResult(
        ignored -> {
          repository.acquire(
              binding, acquireOperation, reference, ReferenceKind.ORIGINAL_SELECTION);
          repository.acquire(
              binding, acquireOperation, reference, ReferenceKind.ORIGINAL_SELECTION);
          repository.acquire(binding, UUID.randomUUID(), hold, ReferenceKind.HOLD);
          assertThat(repository.protection(binding).liveReferences()).isEqualTo(1);
          assertThat(repository.protection(binding).holds()).isEqualTo(1);
          repository.release(binding, releaseOperation, reference);
          repository.release(binding, releaseOperation, reference);
          assertThat(repository.protection(binding).liveReferences()).isZero();
          assertThat(repository.protection(binding).holds()).isEqualTo(1);
          assertThat(repository.protection(binding).disposalEligible()).isFalse();
          repository.release(binding, UUID.randomUUID(), hold);
          var zeroReferences = repository.protection(binding);
          assertThat(zeroReferences.liveReferences()).isZero();
          assertThat(zeroReferences.holds()).isZero();
          assertThat(zeroReferences.terminality())
              .isEqualTo("AUTHENTICATED_SUPERSESSION_UNAVAILABLE");
          assertThat(zeroReferences.disposalEligible()).isFalse();
        });
    List<String> retained = retainedRows();
    for (String table :
        List.of(
            "entity_canonical_roster_retention_lifecycle",
            "entity_canonical_roster_retention_references",
            "entity_canonical_roster_retention_releases",
            "entity_canonical_gameplay_roster_snapshots",
            "entity_canonical_gameplay_roster_snapshot_actors")) {
      assertThatThrownBy(() -> dsl.execute("DELETE FROM " + table))
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(() -> dsl.execute("TRUNCATE " + table + " CASCADE"))
          .isInstanceOf(DataAccessException.class);
    }
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE entity_canonical_roster_retention_lifecycle "
                        + "SET lifecycle_state = 'RETIRED'"))
        .isInstanceOf(DataAccessException.class);
    assertThat(retainedRows()).containsExactlyElementsOf(retained);
  }

  @Test
  void staleTargetFenceUnknownReferenceAndIdentityReuseFailClosed() {
    UUID acquireOperation = UUID.randomUUID();
    UUID reference = UUID.randomUUID();
    transaction.executeWithoutResult(
        ignored ->
            repository.acquire(binding, acquireOperation, reference, ReferenceKind.RECOVERY));
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.acquire(
                            binding, UUID.randomUUID(), reference, ReferenceKind.RECOVERY)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_REFERENCE_IDEMPOTENCY_CONFLICT");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.release(binding, UUID.randomUUID(), UUID.randomUUID())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_REFERENCE_UNAVAILABLE");
    var old = binding.target();
    var stale =
        new CanonicalGameplayRosterTarget(
            old.tenantUuid(),
            old.realmUuid(),
            old.worldSlug(),
            old.realmSlug(),
            old.gameInstanceUuid(),
            old.catalogRevision(),
            old.pointerVersion() + 1,
            old.activeWorldEpoch(),
            old.canonicalVersionUuid(),
            old.publishedPolicyDigest(),
            old.publishedReleaseBundleRef(),
            old.admissionPointerSnapshotDigest(),
            old.publishedOwnerProofDigest(),
            old.playableStateNamespaceId(),
            old.playableStateScope(),
            old.entryPolicy());
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.protection(
                            new CanonicalGameplayRosterRetentionBinding(
                                binding.canonicalAccountUuid(), binding.snapshot(), stale))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_RETENTION_TARGET_MISMATCH");
    assertThatThrownBy(() -> repository.protection(binding))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_RETENTION_OWNER_TRANSACTION_REQUIRED");
  }

  @Test
  void rollbackPreservesActorReceiptSnapshotAndDoesNotReleaseAReference() {
    UUID reference = UUID.randomUUID();
    transaction.executeWithoutResult(
        ignored ->
            repository.acquire(
                binding, UUID.randomUUID(), reference, ReferenceKind.ORIGINAL_SELECTION));
    List<String> retained = retainedRows();
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> {
                      repository.release(binding, UUID.randomUUID(), reference);
                      throw new IllegalStateException("controlled rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    transaction.executeWithoutResult(
        ignored -> assertThat(repository.protection(binding).liveReferences()).isEqualTo(1));
    assertThat(retainedRows()).containsExactlyElementsOf(retained);
    assertThat(
            dsl.fetchSingle("SELECT count(*) FROM entity_canonical_roster_retention_releases")
                .get(0, Long.class))
        .isZero();
  }

  @Test
  void concurrentAcquisitionRemainsAtomicWhileDirectDisposalIsRefused() throws Exception {
    CountDownLatch acquired = new CountDownLatch(1);
    CountDownLatch inspected = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var acquire =
          executor.submit(
              () ->
                  transaction.executeWithoutResult(
                      ignored -> {
                        repository.acquire(
                            binding, UUID.randomUUID(), UUID.randomUUID(), ReferenceKind.RECOVERY);
                        acquired.countDown();
                        await(inspected);
                      }));
      var dispose =
          executor.submit(
              () -> {
                await(acquired);
                try {
                  assertThatThrownBy(
                          () ->
                              transaction.executeWithoutResult(
                                  ignored -> {
                                    dsl.execute("SET LOCAL lock_timeout = '500ms'");
                                    dsl.execute(
                                        "DELETE FROM entity_canonical_gameplay_roster_snapshots "
                                            + "WHERE snapshot_uuid = ?",
                                        binding.snapshot().snapshotUuid());
                                  }))
                      .isInstanceOf(DataAccessException.class);
                } finally {
                  inspected.countDown();
                }
              });
      acquire.get(15, TimeUnit.SECONDS);
      dispose.get(15, TimeUnit.SECONDS);
    }
    transaction.executeWithoutResult(
        ignored -> {
          assertThat(repository.protection(binding).liveReferences()).isEqualTo(1);
          assertThat(repository.protection(binding).disposalEligible()).isFalse();
        });
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "DELETE FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ?",
                    binding.snapshot().snapshotUuid()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void closedNonceRejectsDelayedBindingAndExactRecoveryReplayIsImmutable() {
    var local = localRequest();
    var recovery = recovery(local);
    var owner = recoveryOwner(recovery);
    UUID nonce = transaction.execute(ignored -> repository.reserveAdmission(local));
    var response =
        transaction.execute(
            ignored -> repository.closeAdmission(recovery, owner, policy(Duration.ofHours(1))));
    assertThat(response.getAdmissionUuid()).isEqualTo(nonce.toString());
    assertThat(response.getCanonicalRosterReceipt()).isEmpty();
    assertThat(
            transaction
                .<net.firedevops.firemud.entitymanagement.v1
                        .CanonicalGameplayRosterRecoveryResponse>
                    execute(
                        ignored ->
                            repository.closeAdmission(
                                recovery, owner, policy(Duration.ofHours(2)))))
        .isEqualTo(response);
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.requireAdmission(local, nonce)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.bindAdmission(
                            local.requestUuid(), nonce, binding.snapshot().snapshotUuid())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    assertThatThrownBy(() -> transaction.execute(ignored -> repository.reserveAdmission(local)))
        .isInstanceOf(IllegalStateException.class);
    var conflict = recovery.toBuilder().setRecoveryUuid(UUID.randomUUID().toString()).build();
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.closeAdmission(
                            conflict, recoveryOwner(conflict), policy(Duration.ofHours(1)))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_RECOVERY_CONFLICT");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE entity_canonical_roster_admissions SET admission_state = 'OPEN', closed_at = NULL, minimum_retention_millis = NULL"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE entity_canonical_roster_admissions SET recovery_response = ?",
                    new byte[] {1}))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> dsl.execute("DELETE FROM entity_canonical_roster_admissions"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void closedUnboundAdmissionRequiresExactPositiveOwnerAcknowledgementAndLocalHorizon() {
    var local = localRequest();
    var recovery = recovery(local);
    var owner = recoveryOwner(recovery);
    transaction.execute(
        ignored -> repository.closeAdmission(recovery, owner, policy(Duration.ofMillis(1))));
    var candidate =
        transaction.execute(ignored -> repository.closedAdmissionCandidates(null, 1)).getFirst();
    // Let the physical database's clock pass the deliberately tiny controlled fixture horizon.
    dsl.fetch("SELECT pg_sleep(0.02)");
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.purgeClosedAdmission(candidate, owner)))
        .isFalse();
    assertThatThrownBy(() -> dsl.execute("DELETE FROM entity_canonical_roster_admissions"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.purgeClosedAdmission(
                            candidate,
                            owner.toBuilder()
                                .setRecoveryUuid(UUID.randomUUID().toString())
                                .setTerminalOutcome("DENIED")
                                .build())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        repository.purgeClosedAdmission(
                            candidate,
                            owner.toBuilder()
                                .setOwnerFence(2)
                                .setTerminalOutcome("DENIED")
                                .build())))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            transaction.<Boolean>execute(
                ignored ->
                    repository.purgeClosedAdmission(
                        candidate, owner.toBuilder().setTerminalOutcome("DENIED").build())))
        .isTrue();
    assertThat(
            transaction
                .<List<CanonicalGameplayRosterRetentionRepository.ClosedAdmissionCandidate>>execute(
                    ignored -> repository.closedAdmissionCandidates(null, 1)))
        .isEmpty();
    assertThatThrownBy(() -> transaction.execute(ignored -> repository.reserveAdmission(local)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.requireAdmission(local, candidate.admissionUuid())))
        .isInstanceOf(IllegalStateException.class);
    assertThat(retainedRows()).hasSize(4);

    var heldLocal = localRequest();
    var heldRecovery = recovery(heldLocal);
    var heldOwner = recoveryOwner(heldRecovery);
    transaction.execute(
        ignored -> repository.closeAdmission(heldRecovery, heldOwner, policy(Duration.ofHours(1))));
    var held =
        transaction.execute(ignored -> repository.closedAdmissionCandidates(null, 1)).getFirst();
    assertThat(
            transaction.<Boolean>execute(
                ignored ->
                    repository.purgeClosedAdmission(
                        held, heldOwner.toBuilder().setTerminalOutcome("DENIED").build())))
        .isFalse();
  }

  @Test
  void qualifiedDisposalExtendsClosedAdmissionRetentionWhenRetirementHorizonIncreases() {
    var local = localRequest();
    var recovery = recovery(local);
    var owner = recoveryOwner(recovery);
    UUID nonce = transaction.execute(ignored -> repository.reserveAdmission(local));
    var actor =
        dsl.fetchSingle(
                "SELECT character_uuid FROM entity_canonical_gameplay_roster_snapshot_actors WHERE snapshot_uuid = ?",
                binding.snapshot().snapshotUuid())
            .get("character_uuid", UUID.class);
    var snapshot =
        new CanonicalGameplayRosterSnapshot(
            binding.canonicalAccountUuid(),
            binding.snapshot().snapshotUuid(),
            binding.snapshot().snapshotDigest(),
            binding.target(),
            List.of(new CanonicalGameplayRosterActor(actor, "Retained Actor")));
    transaction.executeWithoutResult(
        ignored -> {
          repository.requireAdmission(local, nonce);
          repository.bindOriginal(owner, snapshot);
          repository.bindAdmission(local.requestUuid(), nonce, snapshot.snapshotUuid());
        });
    var closed =
        transaction.execute(
            ignored -> repository.closeAdmission(recovery, owner, policy(Duration.ofMillis(1))));
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE entity_canonical_roster_admissions SET minimum_retention_millis = 2 WHERE operation_uuid = ?",
                    local.requestUuid()))
        .isInstanceOf(DataAccessException.class);
    var proof =
        CanonicalGameplayRosterReferenceEvidence.sealRetirement(
            owner.toBuilder()
                .setCanonicalRosterReceipt(snapshot.toWire().toByteString())
                .setState(
                    net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState
                        .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RETIRED)
                .setTerminalOutcome("DENIED")
                .setRetirementUuid(UUID.randomUUID().toString())
                .setSupersedingPointerDigest("f".repeat(64))
                .setSupersedingPointerVersion(binding.target().pointerVersion() + 1)
                .setSupersedingCatalogRevision(binding.target().catalogRevision())
                .setSupersedingWorldEpoch(binding.target().activeWorldEpoch())
                .build());
    var protectedCandidate =
        transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    assertThat(
            transaction.<Boolean>execute(
                ignored ->
                    repository.observeRetirement(
                        protectedCandidate, proof, policy(Duration.ofMillis(20)))))
        .isTrue();
    dsl.fetch("SELECT pg_sleep(0.03)");
    var retiredCandidate =
        transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    assertThat(transaction.<Boolean>execute(ignored -> repository.dispose(retiredCandidate)))
        .isTrue();
    var disposed =
        dsl.fetchSingle(
            "SELECT * FROM entity_canonical_roster_admissions WHERE operation_uuid = ?",
            local.requestUuid());
    assertThat(disposed.get("admission_state", String.class)).isEqualTo("DISPOSED");
    assertThat(disposed.get("minimum_retention_millis", Long.class)).isEqualTo(20L);
    assertThat(disposed.get("recovery_uuid", UUID.class).toString())
        .isEqualTo(recovery.getRecoveryUuid());
    assertThat(disposed.get("recovery_request", byte[].class)).isEqualTo(recovery.toByteArray());
    assertThat(disposed.get("recovery_response", byte[].class)).isEqualTo(closed.toByteArray());
    assertThat(disposed.get("recovery_owner_request", byte[].class))
        .isEqualTo(owner.getRequest().toByteArray());
    assertThat(disposed.get("recovery_owner_fence", Long.class)).isEqualTo(owner.getOwnerFence());
    assertThat(disposed.get("recovery_target_fence_uuid", UUID.class).toString())
        .isEqualTo(owner.getTargetFenceUuid());
    assertThat(disposed.get("admission_uuid", UUID.class)).isEqualTo(nonce);
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_gameplay_roster_snapshots"))).isZero();
  }

  @Test
  void observedRetirementCommitsStableActualNoncesAndHeldSnapshotsCannotRemintTransfers() {
    var first = localRequest();
    var second = localRequest();
    var firstOwner = recoveryOwner(recovery(first));
    var secondOwner =
        CanonicalGameplayRosterReferenceEvidence.response(
            recovery(second).getOriginalSelection(),
            firstOwner.getOwnerFence(),
            UUID.fromString(firstOwner.getTargetFenceUuid()),
            null);
    var snapshot = retainedSnapshot();
    UUID firstNonce = bindSnapshot(first, firstOwner, snapshot);
    UUID secondNonce = bindSnapshot(second, secondOwner, snapshot);
    UUID hold = UUID.randomUUID();
    transaction.executeWithoutResult(
        ignored -> repository.acquire(binding, UUID.randomUUID(), hold, ReferenceKind.HOLD));
    var candidate = transaction.execute(ignored -> repository.candidates(null, 2)).getFirst();
    var proof =
        retirement(
            candidate.ownerRequest().equals(firstOwner.getRequest()) ? firstOwner : secondOwner,
            snapshot);
    transaction.executeWithoutResult(
        ignored ->
            assertThat(repository.observeRetirement(candidate, proof, policy(Duration.ofMillis(1))))
                .isTrue());

    // Reconstruct the repository after the commit: no handler-local identity is needed to retry.
    repository = new CanonicalGameplayRosterRetentionRepository(dsl);
    var transfers = transaction.execute(ignored -> repository.retirementTransfers(null, 2));
    assertThat(transfers).hasSize(2);
    assertThat(transfers)
        .allSatisfy(
            transfer -> {
              assertThat(transfer.state()).isEqualTo("PENDING_ACK");
              var request = transfer.acknowledgementRequest();
              assertThat(request.getObservedRetirement()).isEqualTo(proof);
              assertThat(request.getSnapshotUuid()).isEqualTo(snapshot.snapshotUuid().toString());
              assertThat(request.getSnapshotDigest()).isEqualTo(snapshot.snapshotDigest());
              UUID expectedNonce =
                  request
                          .getOriginalSelection()
                          .getOperationUuid()
                          .equals(first.requestUuid().toString())
                      ? firstNonce
                      : secondNonce;
              assertThat(request.getAdmissionUuid()).isEqualTo(expectedNonce.toString());
              assertThatThrownBy(
                      () ->
                          dsl.execute(
                              "DELETE FROM entity_canonical_roster_retirement_transfers WHERE observation_uuid = ?",
                              transfer.observationUuid()))
                  .isInstanceOf(DataAccessException.class);
              var acknowledgement =
                  CanonicalGameplayRosterRetirementEvidence.response(request, UUID.randomUUID(), 1);
              transaction.executeWithoutResult(
                  ignored -> repository.recordRetirementAcknowledgement(transfer, acknowledgement));
            });
    for (var acknowledged :
        transaction.execute(ignored -> repository.retirementTransfers(null, 2))) {
      var confirmation =
          CanonicalGameplayRosterRetirementEvidence.confirmation(
              acknowledged.acknowledgementRequest(), acknowledged.acknowledgement());
      transaction.executeWithoutResult(
          ignored ->
              repository.recordRetirementConfirmation(
                  acknowledged,
                  CanonicalGameplayRosterRetirementEvidence.confirmationResponse(confirmation)));
    }
    dsl.fetch("SELECT pg_sleep(0.02)");
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeConfirmedRetirementTransfers(2)))
        .isZero();
    var retired = transaction.execute(ignored -> repository.candidates(null, 2)).getFirst();
    assertThat(transaction.<Boolean>execute(ignored -> repository.dispose(retired))).isFalse();
    transaction.executeWithoutResult(
        ignored ->
            assertThat(repository.observeRetirement(retired, proof, policy(Duration.ofHours(1))))
                .isTrue());
    assertThat(
            dsl.fetch("SELECT observation_uuid FROM entity_canonical_roster_retirement_transfers")
                .getValues(0, UUID.class))
        .containsExactlyInAnyOrderElementsOf(
            transfers.stream().map(t -> t.observationUuid()).toList());
    assertThatThrownBy(() -> transaction.execute(ignored -> repository.reserveAdmission(first)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.requireAdmission(second, secondNonce)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE entity_canonical_roster_admissions SET snapshot_uuid = snapshot_uuid WHERE operation_uuid = ?",
                    first.requestUuid()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void
      pendingAndActualAcknowledgedTransfersSurvivePayloadDisposalUntilConfirmationAndEffectiveHorizon() {
    var local = localRequest();
    var recovery = recovery(local);
    var owner = recoveryOwner(recovery);
    var snapshot = retainedSnapshot();
    UUID nonce = bindSnapshot(local, owner, snapshot);
    transaction.executeWithoutResult(
        ignored -> repository.closeAdmission(recovery, owner, policy(Duration.ofMillis(1))));
    var proof = retirement(owner, snapshot);
    var candidate = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    transaction.executeWithoutResult(
        ignored -> repository.observeRetirement(candidate, proof, policy(Duration.ofMillis(1))));
    var committed =
        transaction.execute(ignored -> repository.retirementTransfers(null, 1)).getFirst();
    byte[] exactRequest = committed.acknowledgementRequest().toByteArray();
    dsl.fetch("SELECT pg_sleep(0.02)");
    var retired = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    assertThat(transaction.<Boolean>execute(ignored -> repository.dispose(retired))).isTrue();
    dsl.fetch("SELECT pg_sleep(0.02)");
    transaction.executeWithoutResult(ignored -> repository.purgeDisposedTombstones(1));
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_original_selections"))).isZero();
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_admissions"))).isZero();
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_gameplay_roster_snapshots"))).isZero();
    repository = new CanonicalGameplayRosterRetentionRepository(dsl);
    var retried =
        transaction.execute(ignored -> repository.retirementTransfers(null, 1)).getFirst();
    assertThat(retried).isEqualTo(committed);
    assertThat(retried.acknowledgementRequest().toByteArray()).isEqualTo(exactRequest);
    assertThat(retried.acknowledgementRequest().getAdmissionUuid()).isEqualTo(nonce.toString());
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeConfirmedRetirementTransfers(1)))
        .isZero();
    assertThatThrownBy(
            () -> dsl.execute("DELETE FROM entity_canonical_roster_retirement_transfers"))
        .isInstanceOf(DataAccessException.class);

    var acknowledgement =
        CanonicalGameplayRosterRetirementEvidence.response(
            retried.acknowledgementRequest(), UUID.randomUUID(), 2000);
    transaction.executeWithoutResult(
        ignored ->
            assertThat(repository.recordRetirementAcknowledgement(retried, acknowledgement))
                .isTrue());
    // A lost ACK reply reuses the exact persisted request and response; a changed ACK conflicts.
    transaction.executeWithoutResult(
        ignored ->
            assertThat(repository.recordRetirementAcknowledgement(retried, acknowledgement))
                .isFalse());
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.recordRetirementAcknowledgement(
                            retried,
                            CanonicalGameplayRosterRetirementEvidence.response(
                                retried.acknowledgementRequest(), UUID.randomUUID(), 2000))))
        .isInstanceOf(IllegalStateException.class);
    repository = new CanonicalGameplayRosterRetentionRepository(dsl);
    var acknowledged =
        transaction.execute(ignored -> repository.retirementTransfers(null, 1)).getFirst();
    assertThat(acknowledged.state()).isEqualTo("ACKNOWLEDGED");
    assertThat(acknowledged.acknowledgement()).isEqualTo(acknowledgement);
    assertThatThrownBy(
            () -> dsl.execute("DELETE FROM entity_canonical_roster_retirement_transfers"))
        .isInstanceOf(DataAccessException.class);
    var confirmation =
        CanonicalGameplayRosterRetirementEvidence.confirmation(
            acknowledged.acknowledgementRequest(), acknowledgement);
    var confirmed = CanonicalGameplayRosterRetirementEvidence.confirmationResponse(confirmation);
    transaction.executeWithoutResult(
        ignored ->
            assertThat(repository.recordRetirementConfirmation(acknowledged, confirmed)).isTrue());
    transaction.executeWithoutResult(
        ignored ->
            assertThat(repository.recordRetirementConfirmation(acknowledged, confirmed)).isFalse());
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeConfirmedRetirementTransfers(1)))
        .isZero();
    assertThatThrownBy(
            () -> dsl.execute("DELETE FROM entity_canonical_roster_retirement_transfers"))
        .isInstanceOf(DataAccessException.class);
    dsl.fetch("SELECT pg_sleep(2.1)");
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeConfirmedRetirementTransfers(1)))
        .isEqualTo(1);
  }

  @Test
  void deniedUnboundNonceLeavesExactWaitingIntentUntilIndependentPositiveRetirement() {
    var local = localRequest();
    var recovery = recovery(local);
    var owner = recoveryOwner(recovery);
    var closed =
        transaction.execute(
            ignored -> repository.closeAdmission(recovery, owner, policy(Duration.ofMillis(1))));
    var closure =
        transaction.execute(ignored -> repository.closedAdmissionCandidates(null, 1)).getFirst();
    dsl.fetch("SELECT pg_sleep(0.02)");
    var denied = owner.toBuilder().setTerminalOutcome("DENIED").build();
    assertThat(
            transaction.<Boolean>execute(
                ignored -> repository.purgeClosedAdmission(closure, denied)))
        .isTrue();
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_admissions"))).isZero();
    var row = dsl.fetchSingle("SELECT * FROM entity_canonical_roster_retirement_transfers");
    assertThat(row.get("admission_uuid", UUID.class).toString())
        .isEqualTo(closed.getAdmissionUuid());
    assertThat(row.get("closed_admission_receipt", byte[].class)).isEqualTo(closed.toByteArray());
    repository = new CanonicalGameplayRosterRetentionRepository(dsl);
    var waiting =
        transaction.execute(ignored -> repository.retirementTransfers(null, 1)).getFirst();
    assertThat(waiting.state()).isEqualTo("WAITING_RETIREMENT");
    assertThat(waiting.acknowledgementRequest()).isNull();
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> repository.observeWaitingRetirement(waiting, denied)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> transaction.execute(ignored -> repository.reserveAdmission(local)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> dsl.execute("DELETE FROM entity_canonical_roster_retirement_transfers"))
        .isInstanceOf(DataAccessException.class);
    var retired = retirement(owner, null);
    transaction.executeWithoutResult(
        ignored -> assertThat(repository.observeWaitingRetirement(waiting, retired)).isTrue());
    transaction.executeWithoutResult(
        ignored -> assertThat(repository.observeWaitingRetirement(waiting, retired)).isFalse());
    var pending =
        transaction.execute(ignored -> repository.retirementTransfers(null, 1)).getFirst();
    assertThat(pending.observationUuid()).isEqualTo(waiting.observationUuid());
    assertThat(pending.acknowledgementRequest().getOriginalSelection())
        .isEqualTo(recovery.getOriginalSelection());
    assertThat(pending.acknowledgementRequest().getAdmissionUuid())
        .isEqualTo(closed.getAdmissionUuid());
    assertThat(pending.acknowledgementRequest().getSnapshotUuid()).isEmpty();
    assertThat(pending.acknowledgementRequest().getObservedRetirement()).isEqualTo(retired);
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_retirement_transfers")))
        .isEqualTo(1);
  }

  @Test
  void committedObservationSerializesTheDelayedNonceAndDeniesCaptureAfterItsLockReleases()
      throws Exception {
    var local = localRequest();
    var owner = recoveryOwner(recovery(local));
    var snapshot = retainedSnapshot();
    UUID nonce = bindSnapshot(local, owner, snapshot);
    var proof = retirement(owner, snapshot);
    var candidate = transaction.execute(ignored -> repository.candidates(null, 1)).getFirst();
    var observationPid = new java.util.concurrent.atomic.AtomicInteger();
    var delayedPid = new java.util.concurrent.atomic.AtomicInteger();
    CountDownLatch observed = new CountDownLatch(1);
    CountDownLatch delayedStarted = new CountDownLatch(1);
    CountDownLatch allowCommit = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var observation =
          executor.submit(
              () ->
                  transaction.executeWithoutResult(
                      ignored -> {
                        observationPid.set(
                            dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
                        assertThat(
                                repository.observeRetirement(
                                    candidate, proof, policy(Duration.ofHours(1))))
                            .isTrue();
                        observed.countDown();
                        await(allowCommit);
                      }));
      await(observed);
      var delayed =
          executor.submit(
              () ->
                  assertThatThrownBy(
                          () ->
                              transaction.executeWithoutResult(
                                  ignored -> {
                                    delayedPid.set(
                                        dsl.fetchSingle("SELECT pg_backend_pid()")
                                            .get(0, Integer.class));
                                    delayedStarted.countDown();
                                    repository.requireAdmission(local, nonce);
                                    repository.bindOriginal(owner, snapshot);
                                    repository.bindAdmission(
                                        local.requestUuid(), nonce, snapshot.snapshotUuid());
                                  }))
                      .isInstanceOf(IllegalStateException.class)
                      .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED"));
      await(delayedStarted);
      try {
        awaitBackendBlockedBy(delayedPid.get(), observationPid.get());
      } finally {
        allowCommit.countDown();
      }
      observation.get(15, TimeUnit.SECONDS);
      delayed.get(15, TimeUnit.SECONDS);
    } finally {
      allowCommit.countDown();
    }
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_original_selections")))
        .isEqualTo(1);
    var transfer =
        transaction.execute(ignored -> repository.retirementTransfers(null, 1)).getFirst();
    assertThat(transfer.acknowledgementRequest().getAdmissionUuid()).isEqualTo(nonce.toString());
  }

  private void awaitBackendBlockedBy(int waiter, int holder) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    boolean blocked = false;
    while (System.nanoTime() < deadline) {
      blocked =
          Boolean.TRUE.equals(
              dsl.fetchSingle("SELECT ? = ANY(pg_blocking_pids(?))", holder, waiter)
                  .get(0, Boolean.class));
      if (blocked) break;
      TimeUnit.MILLISECONDS.sleep(10);
    }
    assertThat(blocked)
        .as("Delayed admission waits on the exact retirement observation transaction")
        .isTrue();
  }

  @Test
  void transferGuardUsesDatabaseClockInsteadOfCallerBackdatedLifecycleTimes() {
    var local = localRequest();
    var recovery = recovery(local);
    var owner = recoveryOwner(recovery);
    transaction.executeWithoutResult(
        ignored -> repository.closeAdmission(recovery, owner, policy(Duration.ofMillis(1))));
    UUID observation = UUID.randomUUID();
    dsl.execute(
        "INSERT INTO entity_canonical_roster_retirement_transfers "
            + "(observation_uuid, operation_uuid, original_request, admission_uuid, closed_admission_receipt, owner_fence, target_fence_uuid, minimum_retention_millis, transfer_state, recorded_at) "
            + "SELECT ?, operation_uuid, recovery_owner_request, admission_uuid, recovery_response, recovery_owner_fence, recovery_target_fence_uuid, minimum_retention_millis, 'WAITING_RETIREMENT', clock_timestamp() - interval '1 day' "
            + "FROM entity_canonical_roster_admissions WHERE operation_uuid = ?",
        observation,
        local.requestUuid());
    assertThat(
            dsl.fetchSingle(
                    "SELECT recorded_at > clock_timestamp() - interval '1 minute' FROM entity_canonical_roster_retirement_transfers")
                .get(0, Boolean.class))
        .isTrue();
    var waiting =
        transaction.execute(ignored -> repository.retirementTransfers(null, 1)).getFirst();
    transaction.executeWithoutResult(
        ignored -> repository.observeWaitingRetirement(waiting, retirement(owner, null)));
    var pending =
        transaction.execute(ignored -> repository.retirementTransfers(null, 1)).getFirst();
    var acknowledgement =
        CanonicalGameplayRosterRetirementEvidence.response(
            pending.acknowledgementRequest(), UUID.randomUUID(), 2000);
    dsl.execute(
        "UPDATE entity_canonical_roster_retirement_transfers SET transfer_state = 'ACKNOWLEDGED', acknowledgement_response = ?, effective_minimum_retention_millis = ?, acknowledged_at = clock_timestamp() - interval '1 day' WHERE observation_uuid = ?",
        acknowledgement.toByteArray(),
        acknowledgement.getMinimumRetentionMillis(),
        observation);
    assertThat(
            dsl.fetchSingle(
                    "SELECT acknowledged_at > clock_timestamp() - interval '1 minute' FROM entity_canonical_roster_retirement_transfers")
                .get(0, Boolean.class))
        .isTrue();
    var confirmation =
        CanonicalGameplayRosterRetirementEvidence.confirmation(
            pending.acknowledgementRequest(), acknowledgement);
    dsl.execute(
        "UPDATE entity_canonical_roster_retirement_transfers SET transfer_state = 'CONFIRMED', confirmation_response = ?, confirmed_at = clock_timestamp() - interval '1 day' WHERE observation_uuid = ?",
        CanonicalGameplayRosterRetirementEvidence.confirmationResponse(confirmation).toByteArray(),
        observation);
    assertThat(
            dsl.fetchSingle(
                    "SELECT confirmed_at > clock_timestamp() - interval '1 minute' FROM entity_canonical_roster_retirement_transfers")
                .get(0, Boolean.class))
        .isTrue();
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeConfirmedRetirementTransfers(1)))
        .isZero();
    assertThatThrownBy(
            () -> dsl.execute("DELETE FROM entity_canonical_roster_retirement_transfers"))
        .isInstanceOf(DataAccessException.class);
  }

  private CanonicalGameplayRosterSnapshot retainedSnapshot() {
    UUID actor =
        dsl.fetchSingle(
                "SELECT character_uuid FROM entity_canonical_gameplay_roster_snapshot_actors WHERE snapshot_uuid = ?",
                binding.snapshot().snapshotUuid())
            .get("character_uuid", UUID.class);
    return new CanonicalGameplayRosterSnapshot(
        binding.canonicalAccountUuid(),
        binding.snapshot().snapshotUuid(),
        binding.snapshot().snapshotDigest(),
        binding.target(),
        List.of(new CanonicalGameplayRosterActor(actor, "Retained Actor")));
  }

  private UUID bindSnapshot(
      CanonicalGameplayRosterReadRequest local,
      GetCanonicalGameplayRosterReferenceResponse owner,
      CanonicalGameplayRosterSnapshot snapshot) {
    return transaction.execute(
        ignored -> {
          UUID nonce = repository.reserveAdmission(local);
          repository.requireAdmission(local, nonce);
          repository.bindOriginal(owner, snapshot);
          repository.bindAdmission(local.requestUuid(), nonce, snapshot.snapshotUuid());
          return nonce;
        });
  }

  private GetCanonicalGameplayRosterReferenceResponse retirement(
      GetCanonicalGameplayRosterReferenceResponse owner, CanonicalGameplayRosterSnapshot snapshot) {
    var builder =
        owner.toBuilder()
            .setState(
                net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState
                    .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RETIRED)
            .setTerminalOutcome("DENIED")
            .setRetirementUuid(UUID.randomUUID().toString())
            .setSupersedingPointerDigest("f".repeat(64))
            .setSupersedingPointerVersion(binding.target().pointerVersion() + 1)
            .setSupersedingCatalogRevision(binding.target().catalogRevision())
            .setSupersedingWorldEpoch(binding.target().activeWorldEpoch());
    if (snapshot != null) builder.setCanonicalRosterReceipt(snapshot.toWire().toByteString());
    return CanonicalGameplayRosterReferenceEvidence.sealRetirement(builder.build());
  }

  @Test
  void orphanCleanupRevokesOnlyExpiredUnboundOpenNonces() {
    var expired = localRequest();
    var fresh = localRequest();
    var bound = localRequest();
    UUID expiredNonce = insertAgedAdmission(expired, Duration.ofHours(2));
    UUID boundNonce = insertAgedAdmission(bound, Duration.ofHours(2));
    UUID freshNonce = transaction.execute(ignored -> repository.reserveAdmission(fresh));
    transaction.executeWithoutResult(
        ignored ->
            repository.bindAdmission(
                bound.requestUuid(), boundNonce, binding.snapshot().snapshotUuid()));
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE entity_canonical_roster_admissions SET admission_state = 'EXPIRED', minimum_retention_millis = 3600000 WHERE operation_uuid = ?",
                    fresh.requestUuid()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE entity_canonical_roster_admissions SET admission_state = 'EXPIRED', minimum_retention_millis = 3600000 WHERE operation_uuid = ?",
                    bound.requestUuid()))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            transaction.<Integer>execute(
                ignored -> repository.purgeOrphanOpenAdmissions(policy(Duration.ofHours(1)), 1)))
        .isEqualTo(1);
    transaction.executeWithoutResult(
        ignored -> {
          repository.requireAdmission(fresh, freshNonce);
          repository.requireAdmission(bound, boundNonce);
        });
    UUID newNonce = transaction.execute(ignored -> repository.reserveAdmission(expired));
    assertThat(newNonce).isNotEqualTo(expiredNonce);
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        repository.bindAdmission(
                            expired.requestUuid(),
                            expiredNonce,
                            binding.snapshot().snapshotUuid())))
        .isInstanceOf(IllegalStateException.class);
    assertThat(retainedRows()).hasSize(4);
  }

  @Test
  void closeBeforeBindSerializesAndDeniesTheDelayedNonce() throws Exception {
    raceClosureAndBind(true);
  }

  @Test
  void bindBeforeCloseSerializesAndReturnsTheExactOriginalReceipt() throws Exception {
    raceClosureAndBind(false);
  }

  @Test
  void orphanCleanupBeforeBindRevokesTheDelayedNonce() throws Exception {
    raceOrphanCleanupAndBind(true);
  }

  @Test
  void bindingBeforeOrphanCleanupPreservesTheBoundNonce() throws Exception {
    raceOrphanCleanupAndBind(false);
  }

  private void raceOrphanCleanupAndBind(boolean cleanupFirst) throws Exception {
    var local = localRequest();
    UUID nonce = insertAgedAdmission(local, Duration.ofHours(2));
    CountDownLatch firstLocked = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    CountDownLatch allowCommit = new CountDownLatch(1);
    Runnable bind =
        () ->
            transaction.executeWithoutResult(
                ignored -> {
                  repository.requireAdmission(local, nonce);
                  repository.bindAdmission(
                      local.requestUuid(), nonce, binding.snapshot().snapshotUuid());
                  if (!cleanupFirst) {
                    firstLocked.countDown();
                    await(allowCommit);
                  }
                });
    Runnable cleanup =
        () ->
            transaction.executeWithoutResult(
                ignored -> {
                  assertThat(repository.purgeOrphanOpenAdmissions(policy(Duration.ofHours(1)), 1))
                      .isEqualTo(cleanupFirst ? 1 : 0);
                  if (cleanupFirst) {
                    firstLocked.countDown();
                    await(allowCommit);
                  }
                });
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(cleanupFirst ? cleanup : bind);
      await(firstLocked);
      var second =
          executor.submit(
              () -> {
                secondStarted.countDown();
                if (cleanupFirst) {
                  assertThatThrownBy(bind::run)
                      .isInstanceOf(IllegalStateException.class)
                      .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
                } else {
                  cleanup.run();
                }
              });
      await(secondStarted);
      allowCommit.countDown();
      first.get(15, TimeUnit.SECONDS);
      second.get(15, TimeUnit.SECONDS);
    } finally {
      allowCommit.countDown();
    }
    if (cleanupFirst) {
      UUID freshNonce = transaction.execute(ignored -> repository.reserveAdmission(local));
      assertThat(freshNonce).isNotEqualTo(nonce);
    } else {
      transaction.executeWithoutResult(ignored -> repository.requireAdmission(local, nonce));
    }
    assertThat(retainedRows()).hasSize(4);
  }

  private void raceClosureAndBind(boolean closeFirst) throws Exception {
    var local = localRequest();
    var recovery = recovery(local);
    var owner = recoveryOwner(recovery);
    UUID nonce = transaction.execute(ignored -> repository.reserveAdmission(local));
    var actor =
        dsl.fetchSingle(
                "SELECT character_uuid FROM entity_canonical_gameplay_roster_snapshot_actors WHERE snapshot_uuid = ?",
                binding.snapshot().snapshotUuid())
            .get("character_uuid", UUID.class);
    var snapshot =
        new CanonicalGameplayRosterSnapshot(
            binding.canonicalAccountUuid(),
            binding.snapshot().snapshotUuid(),
            binding.snapshot().snapshotDigest(),
            binding.target(),
            List.of(new CanonicalGameplayRosterActor(actor, "Retained Actor")));
    var expected = snapshot.toWire();
    var response =
        new java.util.concurrent.atomic.AtomicReference<
            net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryResponse>();
    CountDownLatch firstLocked = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    CountDownLatch allowCommit = new CountDownLatch(1);
    Runnable bind =
        () ->
            transaction.executeWithoutResult(
                ignored -> {
                  repository.requireAdmission(local, nonce);
                  repository.bindOriginal(owner, snapshot);
                  repository.bindAdmission(local.requestUuid(), nonce, snapshot.snapshotUuid());
                  if (!closeFirst) {
                    firstLocked.countDown();
                    await(allowCommit);
                  }
                });
    Runnable close =
        () ->
            transaction.executeWithoutResult(
                ignored -> {
                  response.set(
                      repository.closeAdmission(recovery, owner, policy(Duration.ofHours(1))));
                  if (closeFirst) {
                    firstLocked.countDown();
                    await(allowCommit);
                  }
                });
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(closeFirst ? close : bind);
      await(firstLocked);
      var second =
          executor.submit(
              () -> {
                secondStarted.countDown();
                if (closeFirst) {
                  assertThatThrownBy(bind::run)
                      .isInstanceOf(IllegalStateException.class)
                      .hasMessage("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
                } else {
                  close.run();
                }
              });
      await(secondStarted);
      allowCommit.countDown();
      first.get(15, TimeUnit.SECONDS);
      second.get(15, TimeUnit.SECONDS);
    } finally {
      allowCommit.countDown();
    }
    assertThat(response.get().getAdmissionUuid()).isEqualTo(nonce.toString());
    if (closeFirst) {
      assertThat(response.get().getCanonicalRosterReceipt()).isEmpty();
      assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_original_selections"))).isZero();
    } else {
      assertThat(response.get().getCanonicalRosterReceipt()).isEqualTo(expected.toByteString());
      transaction.executeWithoutResult(
          ignored -> {
            assertThat(repository.originalReceipt(owner)).contains(expected);
            assertThat(repository.protection(binding).liveReferences()).isEqualTo(1);
            assertThat(repository.protection(binding).disposalEligible()).isFalse();
          });
      assertThat(
              transaction
                  .<List<CanonicalGameplayRosterRetentionRepository.ClosedAdmissionCandidate>>
                      execute(ignored -> repository.closedAdmissionCandidates(null, 2)))
          .isEmpty();
    }
    assertThat(retainedRows()).hasSize(4);
  }

  private UUID insertAgedAdmission(CanonicalGameplayRosterReadRequest request, Duration age) {
    UUID nonce = UUID.randomUUID();
    byte[] bindingBytes =
        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRequest.newBuilder()
            .setRequestUuid(request.requestUuid().toString())
            .setCanonicalAccountUuid(request.canonicalAccountUuid().toString())
            .setExpectedTarget(request.expectedTarget().toWire())
            .setPlayerExecutionContext(
                net.firedevops.firemud.shared.v1.PlayerExecutionContext.newBuilder()
                    .setSessionId(request.playerExecutionContext().sessionUuid().toString()))
            .build()
            .toByteArray();
    dsl.execute(
        "INSERT INTO entity_canonical_roster_admissions (operation_uuid, admission_uuid, request_binding, recorded_at) VALUES (?, ?, ?, clock_timestamp() - ? * interval '1 millisecond')",
        request.requestUuid(),
        nonce,
        bindingBytes,
        age.toMillis());
    return nonce;
  }

  private CanonicalGameplayRosterReadRequest localRequest() {
    var target = binding.target();
    UUID operation = UUID.randomUUID();
    return new CanonicalGameplayRosterReadRequest(
        operation,
        binding.canonicalAccountUuid(),
        target,
        new CanonicalGameplayRosterExecutionContext(
            binding.canonicalAccountUuid(),
            target.tenantUuid(),
            target.playableStateNamespaceId(),
            target.gameInstanceUuid(),
            null,
            UUID.randomUUID(),
            target.realmUuid(),
            operation,
            target.playableStateScope()));
  }

  private static CanonicalGameplayRosterRecoveryRequest recovery(
      CanonicalGameplayRosterReadRequest local) {
    return CanonicalGameplayRosterRecoveryRequest.newBuilder()
        .setSchemaVersion(1)
        .setRecoveryUuid(UUID.randomUUID().toString())
        .setOriginalSelection(
            CanonicalGameplayRosterReferenceEvidence.request(
                local.requestUuid(),
                local.canonicalAccountUuid(),
                local.playerExecutionContext().sessionUuid(),
                "gameplay",
                local.expectedTarget().toWire()))
        .build();
  }

  private static GetCanonicalGameplayRosterReferenceResponse recoveryOwner(
      CanonicalGameplayRosterRecoveryRequest request) {
    return CanonicalGameplayRosterReferenceEvidence.response(
            request.getOriginalSelection(), 1, UUID.randomUUID(), null)
        .toBuilder()
        .setRecoveryUuid(request.getRecoveryUuid())
        .build();
  }

  private static CanonicalGameplayRosterRetentionPolicy policy(Duration duration) {
    return new CanonicalGameplayRosterRetentionPolicy(
        duration, duration, duration, Duration.ofMinutes(1), 2);
  }

  private void seedAssignedActorAndSnapshot() {
    var target = binding.target();
    UUID assignment = UUID.randomUUID();
    var request =
        new PreseededActorAssignmentRequest(
            assignment,
            binding.canonicalAccountUuid(),
            new PreseededActorCorePayload(
                PreseededActorCorePayload.ActorKind.PLAYER, "Retained Actor"),
            new PreseededActorAssignmentExpectedTarget(
                target.tenantUuid(),
                target.realmUuid(),
                target.worldSlug(),
                target.realmSlug(),
                target.gameInstanceUuid().toString(),
                target.catalogRevision(),
                target.canonicalVersionUuid(),
                target.publishedPolicyDigest(),
                target.playableStateNamespaceId(),
                target.publishedReleaseBundleRef(),
                target.playableStateScope()));
    var evidence =
        new PreseededActorAssignmentOwnerEvidence(
            assignment,
            binding.canonicalAccountUuid(),
            PreseededActorAssignmentOwnerEvidence.Eligibility.ELIGIBLE,
            Instant.now(),
            4,
            "1".repeat(64),
            target.tenantUuid(),
            target.realmUuid(),
            target.worldSlug(),
            target.realmSlug(),
            target.gameInstanceUuid().toString(),
            target.catalogRevision(),
            target.canonicalVersionUuid(),
            target.publishedPolicyDigest(),
            target.playableStateNamespaceId(),
            target.playableStateScope(),
            PreseededActorAssignmentOwnerEvidence.PublishedEntryPolicy.PRESEEDED_ONLY,
            PreseededActorAssignmentOwnerEvidence.AccountPurpose.PRESEEDED_ACTOR_STAGING,
            PreseededActorAssignmentOwnerEvidence.AccountCurrentness.CURRENT_AT_REVALIDATION,
            "2".repeat(64),
            target.publishedOwnerProofDigest(),
            target.publishedReleaseBundleRef());
    UUID actor =
        new CharacterRepository(dsl)
            .assignPreseededActor(
                request,
                evidence,
                new RuntimeAccountIdentityEvidence(
                    1,
                    "dev",
                    assignment,
                    binding.canonicalAccountUuid(),
                    51L,
                    "ACCOUNT_DATABASE_INSERT"),
                request.mutationIntentDigest())
            .characterUuid();
    dsl.execute(
        "INSERT INTO entity_canonical_gameplay_roster_snapshots "
            + "(snapshot_uuid, snapshot_digest, canonical_account_uuid, tenant_uuid, realm_uuid, "
            + "world_slug, realm_slug, game_instance_uuid, catalog_revision, canonical_version_uuid, "
            + "pointer_version, active_world_epoch, published_policy_digest, published_release_bundle_ref, "
            + "admission_pointer_snapshot_digest, published_owner_proof_digest, "
            + "playable_state_namespace_uuid, playable_state_scope, entry_policy, roster_count) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
        binding.snapshot().snapshotUuid(),
        binding.snapshot().snapshotDigest(),
        binding.canonicalAccountUuid(),
        target.tenantUuid(),
        target.realmUuid(),
        target.worldSlug(),
        target.realmSlug(),
        target.gameInstanceUuid(),
        target.catalogRevision(),
        target.canonicalVersionUuid(),
        target.pointerVersion(),
        target.activeWorldEpoch(),
        target.publishedPolicyDigest(),
        target.publishedReleaseBundleRef(),
        target.admissionPointerSnapshotDigest(),
        target.publishedOwnerProofDigest(),
        target.playableStateNamespaceId(),
        target.playableStateScope().name(),
        target.entryPolicy().name());
    dsl.execute(
        "INSERT INTO entity_canonical_gameplay_roster_snapshot_actors "
            + "(snapshot_uuid, ordinal_position, character_uuid, display_name, actor_kind) "
            + "VALUES (?, 0, ?, 'Retained Actor', 'PLAYER')",
        binding.snapshot().snapshotUuid(),
        actor);
    dsl.execute(
        "UPDATE entity_canonical_gameplay_roster_snapshots SET construction_state = 'SEALED' "
            + "WHERE snapshot_uuid = ?",
        binding.snapshot().snapshotUuid());
  }

  private List<String> retainedRows() {
    return List.of(
            "characters", "entity_preseeded_actor_assignment_operations",
            "entity_canonical_gameplay_roster_snapshots",
                "entity_canonical_gameplay_roster_snapshot_actors")
        .stream()
        .flatMap(
            table ->
                dsl
                    .fetch(
                        "SELECT to_jsonb(t)::TEXT || ':' || xmin::TEXT FROM "
                            + table
                            + " t ORDER BY to_jsonb(t)::TEXT")
                    .getValues(0, String.class)
                    .stream())
        .toList();
  }

  private static CanonicalGameplayRosterTarget target() {
    return new CanonicalGameplayRosterTarget(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "world",
        "realm",
        UUID.randomUUID(),
        7,
        13,
        19,
        UUID.randomUUID(),
        "3".repeat(64),
        "release/retention-test",
        "4".repeat(64),
        "5".repeat(64),
        UUID.randomUUID(),
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        CanonicalGameplayRosterEntryPolicy.PRESEEDED_ONLY);
  }

  private void migrate(DriverManagerDataSource dataSource, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(
                "filesystem:"
                    + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize(),
                "classpath:db/migration/saga")
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .target(target);
    configuration
        .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    configuration.load().migrate();
  }

  private DriverManagerDataSource dataSource(String selectedSchema) {
    var dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    String baseUrl = postgres.getJdbcUrl();
    dataSource.setUrl(
        selectedSchema == null
            ? baseUrl
            : baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + selectedSchema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("controlled concurrent fixture timed out");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}
