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
    migrate(dataSource, "1012");
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
    UUID fresh = transaction.execute(ignored -> repository.reserveAdmission(local));
    assertThat(fresh.toString()).isNotEqualTo(candidate.response().getAdmissionUuid());
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
