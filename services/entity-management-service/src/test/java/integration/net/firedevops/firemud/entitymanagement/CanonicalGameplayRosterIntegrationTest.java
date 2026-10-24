package net.firedevops.firemud.entitymanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.account.RuntimeAccountIdentityEvidence;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterActor;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterExecutionContext;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterOwnerEvidence;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterReadRequest;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSnapshot;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSnapshotReference;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidence;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentResult;
import net.firedevops.firemud.entitymanagement.service.PreseededActorCorePayload;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = WebEnvironment.NONE,
    classes = EntityManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class CanonicalGameplayRosterIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "entity_management_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private CharacterRepository characterRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private org.jooq.DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;
  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  private UUID accountUuid;
  private UUID tenantUuid;
  private UUID realmUuid;
  private UUID namespaceUuid;

  @BeforeEach
  void createIsolatedTarget() {
    accountUuid = UUID.randomUUID();
    tenantUuid = UUID.randomUUID();
    realmUuid = UUID.randomUUID();
    namespaceUuid = UUID.randomUUID();
  }

  @Test
  void originalSelectionBindsAtomicallyAndReplaysItsExactHistoricalSnapshot() {
    var target = target(namespaceUuid, UUID.randomUUID());
    UUID actor = createPersistedActor(accountUuid, target, 49L, "Original Actor");
    UUID operation = UUID.randomUUID();
    UUID session = UUID.randomUUID();
    var request =
        new CanonicalGameplayRosterReadRequest(
            operation,
            accountUuid,
            target,
            new CanonicalGameplayRosterExecutionContext(
                accountUuid,
                target.tenantUuid(),
                target.playableStateNamespaceId(),
                target.gameInstanceUuid(),
                null,
                session,
                target.realmUuid(),
                operation,
                target.playableStateScope()));
    UUID localAdmission = characterRepository.reserveOriginalRosterAdmission(request);
    var sourceRequest =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.request(
            operation, accountUuid, session, "gameplay", target.toWire());
    var source =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.response(
            sourceRequest,
            1L,
            java.util.UUID.fromString("12121212-1212-4212-8212-121212121212"),
            null);
    var evidence =
        new CanonicalGameplayRosterOwnerEvidence(operation, accountUuid, target, Instant.now());
    var actorBefore =
        jdbcTemplate.queryForMap(
            "SELECT to_jsonb(c)::text AS bytes, xmin::text AS xmin FROM characters c WHERE character_uuid = ?",
            actor);
    var assignmentsBefore =
        jdbcTemplate.queryForList(
            "SELECT to_jsonb(a)::text AS bytes, xmin::text AS xmin FROM entity_preseeded_actor_assignment_operations a ORDER BY assignment_uuid");
    var original =
        characterRepository.captureAndBindCanonicalGameplayRosterSnapshot(
            request, evidence, source, localAdmission);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM entity_canonical_roster_original_selections WHERE operation_uuid = ?",
                Long.class,
                operation))
        .isEqualTo(1L);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT reference_kind FROM entity_canonical_roster_retention_references WHERE reference_uuid = ?",
                String.class,
                operation))
        .isEqualTo("ORIGINAL_SELECTION");
    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT to_jsonb(c)::text AS bytes, xmin::text AS xmin FROM characters c WHERE character_uuid = ?",
                actor))
        .isEqualTo(actorBefore);
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT to_jsonb(a)::text AS bytes, xmin::text AS xmin FROM entity_preseeded_actor_assignment_operations a ORDER BY assignment_uuid"))
        .isEqualTo(assignmentsBefore);
    jdbcTemplate.update(
        "UPDATE characters SET name = ? WHERE character_uuid = ?", "Later Name", actor);
    var recorded =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.response(
            sourceRequest,
            1L,
            java.util.UUID.fromString("12121212-1212-4212-8212-121212121212"),
            original.toWire());
    assertThat(
            characterRepository.captureAndBindCanonicalGameplayRosterSnapshot(
                request, evidence, recorded, localAdmission))
        .isEqualTo(original);
    assertThat(original.actors())
        .containsExactly(new CanonicalGameplayRosterActor(actor, "Original Actor"));
    var changedSource =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.response(
            sourceRequest.toBuilder().setSessionUuid(UUID.randomUUID().toString()).build(),
            1L,
            java.util.UUID.fromString("12121212-1212-4212-8212-121212121212"),
            null);
    assertThatThrownBy(
            () ->
                characterRepository.captureAndBindCanonicalGameplayRosterSnapshot(
                    request, evidence, changedSource, localAdmission))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void originalSelectionReferenceAndNewSnapshotRollBackTogether() {
    var target = target(namespaceUuid, UUID.randomUUID());
    createPersistedActor(accountUuid, target, 50L, "Rollback Actor");
    UUID operation = UUID.randomUUID();
    UUID session = UUID.randomUUID();
    var request =
        new CanonicalGameplayRosterReadRequest(
            operation,
            accountUuid,
            target,
            new CanonicalGameplayRosterExecutionContext(
                accountUuid,
                target.tenantUuid(),
                target.playableStateNamespaceId(),
                target.gameInstanceUuid(),
                null,
                session,
                target.realmUuid(),
                operation,
                target.playableStateScope()));
    UUID localAdmission = characterRepository.reserveOriginalRosterAdmission(request);
    var sourceRequest =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.request(
            operation, accountUuid, session, "gameplay", target.toWire());
    var source =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.response(
            sourceRequest,
            1L,
            java.util.UUID.fromString("12121212-1212-4212-8212-121212121212"),
            null);
    Long snapshotsBefore =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM entity_canonical_gameplay_roster_snapshots", Long.class);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        ignored -> {
                          characterRepository.captureAndBindCanonicalGameplayRosterSnapshot(
                              request,
                              new CanonicalGameplayRosterOwnerEvidence(
                                  operation, accountUuid, target, Instant.now()),
                              source,
                              localAdmission);
                          throw new IllegalStateException("controlled original bind rollback");
                        }))
        .hasMessage("controlled original bind rollback");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM entity_canonical_gameplay_roster_snapshots", Long.class))
        .isEqualTo(snapshotsBefore);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM entity_canonical_roster_original_selections WHERE operation_uuid = ?",
                Long.class,
                operation))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM entity_canonical_roster_retention_references WHERE reference_uuid = ?",
                Long.class,
                operation))
        .isZero();
  }

  @Test
  void exactOwnerRetirementHoldsEpochDisposalRollbackAndDelayedDuplicateRemainCoupled()
      throws Exception {
    var target = target(namespaceUuid, UUID.randomUUID());
    UUID actor = createPersistedActor(accountUuid, target, 59L, "Retained Actor");
    UUID operation = UUID.randomUUID();
    UUID session = UUID.randomUUID();
    var request =
        new CanonicalGameplayRosterReadRequest(
            operation,
            accountUuid,
            target,
            new CanonicalGameplayRosterExecutionContext(
                accountUuid,
                target.tenantUuid(),
                target.playableStateNamespaceId(),
                target.gameInstanceUuid(),
                null,
                session,
                target.realmUuid(),
                operation,
                target.playableStateScope()));
    UUID admission = characterRepository.reserveOriginalRosterAdmission(request);
    var ownerRequest =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.request(
            operation, accountUuid, session, "gameplay", target.toWire());
    UUID targetFence = UUID.randomUUID();
    var reserved =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.response(
            ownerRequest, 1L, targetFence, null);
    var evidence =
        new CanonicalGameplayRosterOwnerEvidence(operation, accountUuid, target, Instant.now());
    var snapshot =
        characterRepository.captureAndBindCanonicalGameplayRosterSnapshot(
            request, evidence, reserved, admission);
    var binding =
        new net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRetentionBinding(
            accountUuid,
            new CanonicalGameplayRosterSnapshotReference(
                snapshot.snapshotUuid(), snapshot.snapshotDigest()),
            target);
    var repository =
        new net.firedevops.firemud.entitymanagement.repository
            .CanonicalGameplayRosterRetentionRepository(dsl);
    var transactions = new TransactionTemplate(transactionManager);
    var policy =
        new net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRetentionPolicy(
            java.time.Duration.ofMillis(1),
            java.time.Duration.ofMillis(1),
            java.time.Duration.ofMillis(1),
            java.time.Duration.ofSeconds(1),
            8);
    UUID recovery = UUID.randomUUID();
    UUID hold = UUID.randomUUID();
    transactions.executeWithoutResult(
        ignored -> {
          repository.acquire(
              binding,
              UUID.randomUUID(),
              recovery,
              net.firedevops.firemud.entitymanagement.repository
                  .CanonicalGameplayRosterRetentionRepository.ReferenceKind.RECOVERY);
          repository.acquire(
              binding,
              UUID.randomUUID(),
              hold,
              net.firedevops.firemud.entitymanagement.repository
                  .CanonicalGameplayRosterRetentionRepository.ReferenceKind.HOLD);
        });
    var proof =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.sealRetirement(
            net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.response(
                    ownerRequest, 1L, targetFence, snapshot.toWire())
                .toBuilder()
                .setSelectionInput(
                    net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput
                        .newBuilder()
                        .setSchemaVersion(1)
                        .setSelector("1"))
                .setTerminalOutcome("DENIED")
                .setState(
                    net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState
                        .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RETIRED)
                .setRetirementUuid(UUID.randomUUID().toString())
                .setSupersedingPointerDigest("f".repeat(64))
                .setSupersedingPointerVersion(target.pointerVersion() + 1L)
                .setSupersedingCatalogRevision(target.catalogRevision() + 1L)
                .setSupersedingWorldEpoch(target.activeWorldEpoch() + 1L)
                .build());
    var protectedCandidate = retentionCandidate(snapshot, ownerRequest);
    assertThat(
            transactions.<Boolean>execute(
                ignored -> repository.observeRetirement(protectedCandidate, proof, policy)))
        .isTrue();
    var preparationCandidate = retentionCandidate(snapshot, ownerRequest);
    assertThat(
            transactions.<Integer>execute(
                ignored -> repository.prepareRetirement(preparationCandidate, 8)))
        .isEqualTo(1);
    // Preparation advances the local epoch; final-disposal checks use the reloaded candidate.
    var retired = retentionCandidate(snapshot, ownerRequest);
    assertThat(transactions.<Boolean>execute(ignored -> repository.dispose(retired))).isFalse();
    transactions.executeWithoutResult(
        ignored -> {
          assertThat(repository.protection(binding).liveReferences()).isEqualTo(1L);
          assertThat(repository.protection(binding).holds()).isEqualTo(1L);
          repository.release(binding, UUID.randomUUID(), recovery);
          repository.release(binding, UUID.randomUUID(), hold);
        });
    assertThat(transactions.<Boolean>execute(ignored -> repository.dispose(retired))).isFalse();
    Thread.sleep(5L);
    var ready = retentionCandidate(snapshot, ownerRequest);
    var actorBefore =
        jdbcTemplate.queryForMap(
            "SELECT to_jsonb(c)::text AS bytes, xmin::text AS xmin FROM characters c WHERE character_uuid = ?",
            actor);
    var assignmentsBefore =
        jdbcTemplate.queryForList(
            "SELECT to_jsonb(a)::text AS bytes, xmin::text AS xmin FROM entity_preseeded_actor_assignment_operations a ORDER BY assignment_uuid");
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    ignored -> {
                      assertThat(repository.dispose(ready)).isTrue();
                      throw new IllegalStateException("controlled disposal rollback");
                    }))
        .hasMessage("controlled disposal rollback");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ?",
                Long.class,
                snapshot.snapshotUuid()))
        .isEqualTo(1L);
    assertThat(transactions.<Boolean>execute(ignored -> repository.dispose(ready))).isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ?",
                Long.class,
                snapshot.snapshotUuid()))
        .isZero();
    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT to_jsonb(c)::text AS bytes, xmin::text AS xmin FROM characters c WHERE character_uuid = ?",
                actor))
        .isEqualTo(actorBefore);
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT to_jsonb(a)::text AS bytes, xmin::text AS xmin FROM entity_preseeded_actor_assignment_operations a ORDER BY assignment_uuid"))
        .isEqualTo(assignmentsBefore);
    assertThatThrownBy(
            () ->
                characterRepository.captureAndBindCanonicalGameplayRosterSnapshot(
                    request, evidence, reserved, admission))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LOCAL_ADMISSION_FENCE_CLOSED");
    Thread.sleep(5L);
    transactions.executeWithoutResult(ignored -> repository.purgeDisposedTombstones(8));
    assertThatThrownBy(() -> characterRepository.reserveOriginalRosterAdmission(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LOCAL_ADMISSION_FENCE_CLOSED");
    var pendingTransfer =
        jdbcTemplate.queryForMap(
            "SELECT transfer_state, admission_uuid, original_request, snapshot_uuid, snapshot_digest FROM entity_canonical_roster_retirement_transfers WHERE operation_uuid = ?",
            operation);
    assertThat(pendingTransfer.get("transfer_state")).isEqualTo("PENDING_ACK");
    assertThat(pendingTransfer.get("admission_uuid")).isEqualTo(admission);
    assertThat((byte[]) pendingTransfer.get("original_request"))
        .isEqualTo(ownerRequest.toByteArray());
    assertThat(pendingTransfer.get("snapshot_uuid")).isEqualTo(snapshot.snapshotUuid());
    assertThat(pendingTransfer.get("snapshot_digest")).isEqualTo(snapshot.snapshotDigest());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM entity_canonical_roster_admissions WHERE operation_uuid = ?",
                Long.class,
                operation))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM entity_canonical_gameplay_roster_snapshots WHERE canonical_account_uuid = ?",
                Long.class,
                accountUuid))
        .isZero();
    assertThatThrownBy(
            () ->
                characterRepository.captureAndBindCanonicalGameplayRosterSnapshot(
                    request, evidence, reserved, admission))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LOCAL_ADMISSION_FENCE_CLOSED");
    assertThatThrownBy(
            () ->
                net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence
                    .requireDereference(proof))
        .isInstanceOf(IllegalStateException.class);
  }

  private net.firedevops.firemud.entitymanagement.repository
          .CanonicalGameplayRosterRetentionRepository.Candidate
      retentionCandidate(
          CanonicalGameplayRosterSnapshot snapshot,
          net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest
              ownerRequest) {
    var row =
        jdbcTemplate.queryForMap(
            "SELECT local_epoch, lifecycle_state, owner_retirement_proof_digest FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = ?",
            snapshot.snapshotUuid());
    return new net.firedevops.firemud.entitymanagement.repository
        .CanonicalGameplayRosterRetentionRepository.Candidate(
        new net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRetentionBinding(
            snapshot.canonicalAccountUuid(),
            new CanonicalGameplayRosterSnapshotReference(
                snapshot.snapshotUuid(), snapshot.snapshotDigest()),
            snapshot.target()),
        ownerRequest,
        ((Number) row.get("local_epoch")).longValue(),
        (String) row.get("lifecycle_state"),
        (String) row.get("owner_retirement_proof_digest"));
  }

  @Test
  void independentlyRetiresSameTargetSiblingSnapshotsAndRejectsLateCapture() throws Exception {
    // Existing controlled owner evidence exercises local sibling state, not an upstream producer.
    CanonicalGameplayRosterTarget exactTarget = target(namespaceUuid, UUID.randomUUID());
    UUID actor = createPersistedActor(accountUuid, exactTarget, 58L, "First Roster Name");
    UUID targetFence = UUID.randomUUID();
    UUID retirementId = UUID.randomUUID();
    BoundRosterOriginal first = captureOriginalSelection(exactTarget, targetFence);

    jdbcTemplate.update(
        "UPDATE characters SET name = ? WHERE character_uuid = ?", "Second Roster Name", actor);
    BoundRosterOriginal second = captureOriginalSelection(exactTarget, targetFence);
    jdbcTemplate.update(
        "UPDATE characters SET name = ? WHERE character_uuid = ?", "Unbound Direct Roster", actor);
    CanonicalGameplayRosterReadRequest directRequest = originalRosterRequest(exactTarget);
    CanonicalGameplayRosterSnapshot directSnapshot =
        characterRepository.captureCanonicalGameplayRosterSnapshot(
            directRequest,
            new CanonicalGameplayRosterOwnerEvidence(
                directRequest.requestUuid(), accountUuid, exactTarget, Instant.now()));
    assertThat(directSnapshot.snapshotUuid())
        .isNotIn(first.snapshot().snapshotUuid(), second.snapshot().snapshotUuid());
    assertThat(second.localRequest().requestUuid())
        .isNotEqualTo(first.localRequest().requestUuid());
    assertThat(second.admission()).isNotEqualTo(first.admission());
    assertThat(second.snapshot().snapshotUuid()).isNotEqualTo(first.snapshot().snapshotUuid());
    assertThat(second.snapshot().snapshotDigest()).isNotEqualTo(first.snapshot().snapshotDigest());
    assertThat(second.snapshot().target()).isEqualTo(first.snapshot().target());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT snapshot_uuid FROM entity_canonical_roster_original_selections WHERE operation_uuid = ?",
                UUID.class,
                first.localRequest().requestUuid()))
        .isEqualTo(first.snapshot().snapshotUuid());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT snapshot_uuid FROM entity_canonical_roster_original_selections WHERE operation_uuid = ?",
                UUID.class,
                second.localRequest().requestUuid()))
        .isEqualTo(second.snapshot().snapshotUuid());

    CanonicalGameplayRosterReadRequest delayedRequest = originalRosterRequest(exactTarget);
    UUID delayedAdmission = characterRepository.reserveOriginalRosterAdmission(delayedRequest);
    var delayedOwnerRequest = ownerRequest(delayedRequest);
    var delayedOwnerResponse =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.response(
            delayedOwnerRequest, 1L, targetFence, null);
    var delayedEvidence =
        new CanonicalGameplayRosterOwnerEvidence(
            delayedRequest.requestUuid(), accountUuid, exactTarget, Instant.now());

    var repository =
        new net.firedevops.firemud.entitymanagement.repository
            .CanonicalGameplayRosterRetentionRepository(dsl);
    var transactions = new TransactionTemplate(transactionManager);
    var siblingRetention = java.time.Duration.ofSeconds(2);
    var policy =
        new net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRetentionPolicy(
            siblingRetention,
            siblingRetention,
            siblingRetention,
            java.time.Duration.ofSeconds(1),
            8);
    List<BoundRosterOriginal> siblings = List.of(first, second);
    var secondBinding =
        new net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRetentionBinding(
            accountUuid,
            new CanonicalGameplayRosterSnapshotReference(
                second.snapshot().snapshotUuid(), second.snapshot().snapshotDigest()),
            exactTarget);
    UUID secondHold = UUID.randomUUID();
    transactions.executeWithoutResult(
        ignored ->
            repository.acquire(
                secondBinding,
                UUID.randomUUID(),
                secondHold,
                net.firedevops.firemud.entitymanagement.repository
                    .CanonicalGameplayRosterRetentionRepository.ReferenceKind.HOLD));

    var firstProof = retirementProof(first, targetFence, retirementId);
    var secondProof = retirementProof(second, targetFence, retirementId);
    CountDownLatch firstSealed = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    CountDownLatch allowFirstCommit = new CountDownLatch(1);
    AtomicInteger firstSealPid = new AtomicInteger();
    AtomicInteger secondSealPid = new AtomicInteger();
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<Boolean> firstSeal =
          executor.submit(
              () ->
                  transactions.execute(
                      ignored -> {
                        firstSealPid.set(
                            java.util.Objects.requireNonNull(
                                jdbcTemplate.queryForObject(
                                    "SELECT pg_backend_pid()", Integer.class)));
                        assertThat(
                                repository.observeRetirement(
                                    retentionCandidate(first.snapshot(), first.ownerRequest()),
                                    firstProof,
                                    policy))
                            .isTrue();
                        assertThat(
                                jdbcTemplate.queryForObject(
                                    "SELECT retirement_observed_at + minimum_retention_millis * interval '1 millisecond' > clock_timestamp() "
                                        + "FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = ?",
                                    Boolean.class,
                                    first.snapshot().snapshotUuid()))
                            .as("the stored sibling seal horizon has not elapsed")
                            .isTrue();
                        assertThat(
                                repository.dispose(
                                    retentionCandidate(first.snapshot(), first.ownerRequest())))
                            .as("a current epoch cannot bypass the persisted seal horizon")
                            .isFalse();
                        firstSealed.countDown();
                        awaitSiblingLatch(allowFirstCommit, "first sibling seal commit");
                        return true;
                      }));
      assertThat(firstSealed.await(10, TimeUnit.SECONDS)).isTrue();
      Future<Boolean> secondSeal =
          executor.submit(
              () ->
                  transactions.execute(
                      ignored -> {
                        secondSealPid.set(
                            java.util.Objects.requireNonNull(
                                jdbcTemplate.queryForObject(
                                    "SELECT pg_backend_pid()", Integer.class)));
                        secondStarted.countDown();
                        return repository.observeRetirement(
                            retentionCandidate(second.snapshot(), second.ownerRequest()),
                            secondProof,
                            policy);
                      }));
      assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
      awaitBackendBlockedBy(secondSealPid.get(), firstSealPid.get());
      allowFirstCommit.countDown();
      assertThat(firstSeal.get(15, TimeUnit.SECONDS)).isTrue();
      assertThat(secondSeal.get(15, TimeUnit.SECONDS))
          .as("the shared target fence admits an independent sibling seal after the first commits")
          .isTrue();
    } finally {
      allowFirstCommit.countDown();
    }

    assertThat(
            transactions.<Boolean>execute(
                ignored ->
                    repository.observeRetirement(
                        retentionCandidate(first.snapshot(), first.ownerRequest()),
                        firstProof,
                        policy)))
        .as("exact replay preserves the first snapshot's persisted seal")
        .isTrue();
    assertThat(
            transactions.<Boolean>execute(
                ignored ->
                    repository.observeRetirement(
                        retentionCandidate(second.snapshot(), second.ownerRequest()),
                        secondProof,
                        policy)))
        .as("exact replay preserves the sibling snapshot's independent seal")
        .isTrue();
    assertThat(
            transactions.<Boolean>execute(
                ignored ->
                    repository.observeRetirement(
                        retentionCandidate(first.snapshot(), first.ownerRequest()),
                        retirementProof(first, targetFence, UUID.randomUUID()),
                        policy)))
        .as("a changed retirement proof cannot replace an existing snapshot seal")
        .isFalse();

    jdbcTemplate.update(
        "UPDATE characters SET name = ? WHERE character_uuid = ?", "Changed After Seal", actor);
    CanonicalGameplayRosterReadRequest changedDirectRequest = originalRosterRequest(exactTarget);
    assertThatThrownBy(
            () ->
                characterRepository.captureCanonicalGameplayRosterSnapshot(
                    changedDirectRequest,
                    new CanonicalGameplayRosterOwnerEvidence(
                        changedDirectRequest.requestUuid(),
                        accountUuid,
                        exactTarget,
                        Instant.now())))
        .as("direct capture cannot create or reuse an alternate snapshot after the target seal")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LOCAL_ADMISSION_FENCE_CLOSED");
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    ignored ->
                        repository.acquire(
                            secondBinding,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            net.firedevops.firemud.entitymanagement.repository
                                .CanonicalGameplayRosterRetentionRepository.ReferenceKind
                                .RECOVERY)))
        .as("recovery acquisition is covered by the same target fence")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LOCAL_ADMISSION_FENCE_CLOSED");

    assertThatThrownBy(
            () ->
                characterRepository.captureAndBindCanonicalGameplayRosterSnapshot(
                    delayedRequest, delayedEvidence, delayedOwnerResponse, delayedAdmission))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LOCAL_ADMISSION_FENCE_CLOSED");
    assertThatThrownBy(
            () ->
                characterRepository.reserveOriginalRosterAdmission(
                    originalRosterRequest(exactTarget)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LOCAL_ADMISSION_FENCE_CLOSED");

    CanonicalGameplayRosterTarget unrelatedTarget = target(namespaceUuid, UUID.randomUUID());
    BoundRosterOriginal unrelated = captureOriginalSelection(unrelatedTarget, UUID.randomUUID());
    assertThat(unrelated.snapshot().target()).isEqualTo(unrelatedTarget);

    for (BoundRosterOriginal sibling : siblings) {
      var preparation = retentionCandidate(sibling.snapshot(), sibling.ownerRequest());
      assertThat(
              transactions.<Integer>execute(
                  ignored -> repository.prepareRetirement(preparation, 8)))
          .isEqualTo(1);
    }
    Thread.sleep(2_100L);
    var firstReady = retentionCandidate(first.snapshot(), first.ownerRequest());
    assertThat(transactions.<Boolean>execute(ignored -> repository.dispose(firstReady))).isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ?",
                Long.class,
                first.snapshot().snapshotUuid()))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ?",
                Long.class,
                second.snapshot().snapshotUuid()))
        .isEqualTo(1L);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT transfer_state FROM entity_canonical_roster_retirement_transfers WHERE operation_uuid = ?",
                String.class,
                first.localRequest().requestUuid()))
        .isEqualTo("PENDING_ACK");
    assertThatThrownBy(
            () ->
                characterRepository.reserveOriginalRosterAdmission(
                    originalRosterRequest(exactTarget)))
        .as("disposing one sibling leaves the shared target fence closed")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LOCAL_ADMISSION_FENCE_CLOSED");

    var heldSecond = retentionCandidate(second.snapshot(), second.ownerRequest());
    assertThat(transactions.<Boolean>execute(ignored -> repository.dispose(heldSecond)))
        .as("the sibling's independent hold prevents its final disposal")
        .isFalse();
    assertThat(transactions.<Integer>execute(ignored -> repository.purgeRetiredTargets(8)))
        .as(
            "the shared seal rows cannot be cleaned while the sibling hold and direct snapshot remain")
        .isZero();

    transactions.executeWithoutResult(
        ignored -> repository.release(secondBinding, UUID.randomUUID(), secondHold));
    var secondReady = retentionCandidate(second.snapshot(), second.ownerRequest());
    assertThat(transactions.<Boolean>execute(ignored -> repository.dispose(secondReady)))
        .as("releasing the hold restarts the stored local retirement horizon")
        .isFalse();
    Thread.sleep(2_100L);
    assertThat(transactions.<Boolean>execute(ignored -> repository.dispose(secondReady))).isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ?",
                Long.class,
                second.snapshot().snapshotUuid()))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT transfer_state FROM entity_canonical_roster_retirement_transfers WHERE operation_uuid = ?",
                String.class,
                second.localRequest().requestUuid()))
        .isEqualTo("PENDING_ACK");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ?",
                Long.class,
                directSnapshot.snapshotUuid()))
        .isEqualTo(1L);
    assertThat(transactions.<Integer>execute(ignored -> repository.purgeRetiredTargets(8)))
        .as(
            "pending transfers, the pre-existing unbound direct snapshot, and retained nonce keep seals")
        .isZero();
  }

  private BoundRosterOriginal captureOriginalSelection(
      CanonicalGameplayRosterTarget target, UUID targetFence) {
    CanonicalGameplayRosterReadRequest request = originalRosterRequest(target);
    UUID admission = characterRepository.reserveOriginalRosterAdmission(request);
    var ownerRequest = ownerRequest(request);
    var ownerResponse =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.response(
            ownerRequest, 1L, targetFence, null);
    var evidence =
        new CanonicalGameplayRosterOwnerEvidence(
            request.requestUuid(), accountUuid, target, Instant.now());
    var snapshot =
        characterRepository.captureAndBindCanonicalGameplayRosterSnapshot(
            request, evidence, ownerResponse, admission);
    return new BoundRosterOriginal(request, admission, ownerRequest, snapshot);
  }

  private net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest
      ownerRequest(CanonicalGameplayRosterReadRequest request) {
    return net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.request(
        request.requestUuid(),
        request.canonicalAccountUuid(),
        request.playerExecutionContext().sessionUuid(),
        "gameplay",
        request.expectedTarget().toWire());
  }

  private net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse
      retirementProof(BoundRosterOriginal original, UUID targetFence, UUID retirementId) {
    CanonicalGameplayRosterTarget target = original.snapshot().target();
    return net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence
        .sealRetirement(
            net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.response(
                    original.ownerRequest(), 1L, targetFence, original.snapshot().toWire())
                .toBuilder()
                .setSelectionInput(
                    net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput
                        .newBuilder()
                        .setSchemaVersion(1)
                        .setSelector("1"))
                .setTerminalOutcome("DENIED")
                .setState(
                    net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState
                        .CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RETIRED)
                .setRetirementUuid(retirementId.toString())
                .setSupersedingPointerDigest("f".repeat(64))
                .setSupersedingPointerVersion(target.pointerVersion() + 1L)
                .setSupersedingCatalogRevision(target.catalogRevision() + 1L)
                .setSupersedingWorldEpoch(target.activeWorldEpoch() + 1L)
                .build());
  }

  private record BoundRosterOriginal(
      CanonicalGameplayRosterReadRequest localRequest,
      UUID admission,
      net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest ownerRequest,
      CanonicalGameplayRosterSnapshot snapshot) {}

  @Test
  void returnsOnlyFreshOwnerResolvedActorsForExactAccountTenantAndNamespace() {
    CanonicalGameplayRosterTarget exactTarget = target(namespaceUuid, UUID.randomUUID());
    UUID selected = createPersistedActor(accountUuid, exactTarget, 41L, "Canonical Actor");
    createPersistedActor(UUID.randomUUID(), exactTarget, 42L, "Different Account Actor");
    CanonicalGameplayRosterTarget otherNamespaceTarget =
        target(UUID.randomUUID(), exactTarget.gameInstanceUuid());
    UUID crossNamespace =
        createPersistedActor(accountUuid, otherNamespaceTarget, 41L, "Other Namespace Actor");
    jdbcTemplate.update(
        "INSERT INTO characters (account_id, name, tenant_id, playable_state_key) "
            + "VALUES (?, ?, ?, ?)",
        41L,
        "Legacy numeric actor",
        71L,
        "shared-live");

    CanonicalGameplayRosterSnapshot roster = readRoster(accountUuid, exactTarget);
    CanonicalGameplayRosterSnapshot otherNamespaceRoster =
        readRoster(accountUuid, otherNamespaceTarget);

    assertThat(roster.target()).isEqualTo(exactTarget);
    assertThat(roster.actors())
        .containsExactly(new CanonicalGameplayRosterActor(selected, "Canonical Actor"));
    assertThat(roster.actors()).noneMatch(actor -> actor.characterUuid().equals(crossNamespace));
    assertThat(otherNamespaceRoster.actors())
        .containsExactly(new CanonicalGameplayRosterActor(crossNamespace, "Other Namespace Actor"));

    jdbcTemplate.update(
        "UPDATE characters SET actor_identity_status = 'QUARANTINED', "
            + "actor_identity_quarantine_reason = 'OWNER_PROVENANCE_MISSING' "
            + "WHERE character_uuid = ?",
        selected);
    CanonicalGameplayRosterSnapshot afterQuarantine = readRoster(accountUuid, exactTarget);
    assertThat(afterQuarantine.actors()).isEmpty();
    assertThat(afterQuarantine.snapshotDigest()).isNotEqualTo(roster.snapshotDigest());
  }

  @Test
  void snapshotsPersistedPreseededDisplayNameAtOneHundredUnicodeCodePoints() {
    CanonicalGameplayRosterTarget exactTarget = target(namespaceUuid, UUID.randomUUID());
    String displayName = "\uD83D\uDE00".repeat(100);
    UUID characterUuid = createPersistedActor(accountUuid, exactTarget, 43L, displayName);

    CanonicalGameplayRosterSnapshot roster = readRoster(accountUuid, exactTarget);

    assertThat(roster.actors())
        .containsExactly(new CanonicalGameplayRosterActor(characterUuid, displayName));
  }

  @Test
  void reusesExactSnapshotAndChangesIdentityForRosterOrTargetChanges() {
    CanonicalGameplayRosterTarget exactTarget = target(namespaceUuid, UUID.randomUUID());
    UUID actor = createPersistedActor(accountUuid, exactTarget, 51L, "Before Rename");

    CanonicalGameplayRosterSnapshot first = readRoster(accountUuid, exactTarget);
    CanonicalGameplayRosterSnapshot identical = readRoster(accountUuid, exactTarget);
    assertThat(identical.snapshotUuid()).isEqualTo(first.snapshotUuid());
    assertThat(identical.snapshotDigest()).isEqualTo(first.snapshotDigest());
    CanonicalGameplayRosterSnapshotReference expectedReference =
        new CanonicalGameplayRosterSnapshotReference(first.snapshotUuid(), first.snapshotDigest());
    assertThat(
            characterRepository.readCurrentCanonicalGameplayRosterSnapshot(
                accountUuid, exactTarget, expectedReference))
        .isEqualTo(first);
    assertThatThrownBy(
            () ->
                characterRepository.readCurrentCanonicalGameplayRosterSnapshot(
                    accountUuid,
                    exactTarget,
                    new CanonicalGameplayRosterSnapshotReference(
                        UUID.randomUUID(), first.snapshotDigest())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_GAMEPLAY_ROSTER_SNAPSHOT_READBACK_UNAVAILABLE");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT canonical_version_uuid FROM entity_canonical_gameplay_roster_snapshots "
                    + "WHERE snapshot_uuid = ?",
                UUID.class,
                first.snapshotUuid()))
        .isEqualTo(exactTarget.canonicalVersionUuid());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT publication_evidence_format FROM entity_canonical_gameplay_roster_snapshots "
                    + "WHERE snapshot_uuid = ?",
                String.class,
                first.snapshotUuid()))
        .isEqualTo("CANONICAL_UUID_V2");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT pointer_version FROM entity_canonical_gameplay_roster_snapshots "
                    + "WHERE snapshot_uuid = ?",
                Long.class,
                first.snapshotUuid()))
        .isEqualTo(exactTarget.pointerVersion());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT active_world_epoch FROM entity_canonical_gameplay_roster_snapshots "
                    + "WHERE snapshot_uuid = ?",
                Long.class,
                first.snapshotUuid()))
        .isEqualTo(exactTarget.activeWorldEpoch());
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE entity_canonical_gameplay_roster_snapshots "
                        + "SET canonical_version_uuid = ? WHERE snapshot_uuid = ?",
                    UUID.randomUUID(),
                    first.snapshotUuid()))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE entity_canonical_gameplay_roster_snapshots "
                        + "SET pointer_version = ? WHERE snapshot_uuid = ?",
                    exactTarget.pointerVersion() + 1,
                    first.snapshotUuid()))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE entity_canonical_gameplay_roster_snapshots "
                        + "SET active_world_epoch = ? WHERE snapshot_uuid = ?",
                    exactTarget.activeWorldEpoch() + 1,
                    first.snapshotUuid()))
        .isInstanceOf(RuntimeException.class);

    jdbcTemplate.update(
        "UPDATE characters SET name = ? WHERE character_uuid = ?", "After Rename", actor);
    CanonicalGameplayRosterSnapshot changedRoster = readRoster(accountUuid, exactTarget);
    assertThat(changedRoster.snapshotUuid()).isNotEqualTo(first.snapshotUuid());
    assertThat(changedRoster.snapshotDigest()).isNotEqualTo(first.snapshotDigest());
    assertThat(changedRoster.actors())
        .containsExactly(new CanonicalGameplayRosterActor(actor, "After Rename"));
    assertThatThrownBy(
            () ->
                characterRepository.readCurrentCanonicalGameplayRosterSnapshot(
                    accountUuid, exactTarget, expectedReference))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_GAMEPLAY_ROSTER_SNAPSHOT_STALE");

    CanonicalGameplayRosterTarget changedRuntimeFence = target(namespaceUuid, UUID.randomUUID());
    CanonicalGameplayRosterSnapshot changedTarget = readRoster(accountUuid, changedRuntimeFence);
    assertThat(changedTarget.snapshotUuid()).isNotEqualTo(changedRoster.snapshotUuid());
    assertThat(changedTarget.snapshotDigest()).isNotEqualTo(changedRoster.snapshotDigest());

    CanonicalGameplayRosterTarget changedPointer =
        withCounters(exactTarget, exactTarget.pointerVersion() + 1, exactTarget.activeWorldEpoch());
    CanonicalGameplayRosterSnapshot changedPointerSnapshot =
        readRoster(accountUuid, changedPointer);
    assertThat(changedPointerSnapshot.snapshotUuid()).isNotEqualTo(first.snapshotUuid());
    assertThat(changedPointerSnapshot.snapshotDigest()).isNotEqualTo(first.snapshotDigest());
    assertThat(changedPointerSnapshot.target().pointerVersion())
        .isEqualTo(changedPointer.pointerVersion());

    CanonicalGameplayRosterTarget changedEpoch =
        withCounters(exactTarget, exactTarget.pointerVersion(), exactTarget.activeWorldEpoch() + 1);
    CanonicalGameplayRosterSnapshot changedEpochSnapshot = readRoster(accountUuid, changedEpoch);
    assertThat(changedEpochSnapshot.snapshotUuid()).isNotEqualTo(first.snapshotUuid());
    assertThat(changedEpochSnapshot.snapshotDigest()).isNotEqualTo(first.snapshotDigest());
    assertThat(changedEpochSnapshot.target().activeWorldEpoch())
        .isEqualTo(changedEpoch.activeWorldEpoch());

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE entity_canonical_gameplay_roster_snapshots "
                        + "SET world_slug = 'changed' WHERE snapshot_uuid = ?",
                    first.snapshotUuid()))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE entity_canonical_gameplay_roster_snapshot_actors "
                        + "SET display_name = 'tampered' "
                        + "WHERE snapshot_uuid = ? AND character_uuid = ?",
                    first.snapshotUuid(),
                    actor))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void concurrentDifferentOriginalOperationsBindTheSameExistingSealedSnapshotWithoutLockUpgrade()
      throws Exception {
    var target = target(namespaceUuid, UUID.randomUUID());
    UUID actor = createPersistedActor(accountUuid, target, 62L, "Shared Existing Actor");
    var existing = readRoster(accountUuid, target);
    var requests = List.of(originalRosterRequest(target), originalRosterRequest(target));
    var admissions =
        requests.stream().map(characterRepository::reserveOriginalRosterAdmission).toList();
    UUID targetFence = UUID.randomUUID();
    var sources =
        requests.stream()
            .map(
                request ->
                    net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence
                        .response(
                            net.firedevops.firemud.common.world
                                .CanonicalGameplayRosterReferenceEvidence.request(
                                request.requestUuid(),
                                request.canonicalAccountUuid(),
                                request.playerExecutionContext().sessionUuid(),
                                "gameplay",
                                target.toWire()),
                            1L,
                            targetFence,
                            null))
            .toList();
    var retention =
        new net.firedevops.firemud.entitymanagement.repository
            .CanonicalGameplayRosterRetentionRepository(dsl);
    CountDownLatch firstCaptured = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    AtomicInteger firstPid = new AtomicInteger();
    AtomicInteger secondPid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<CanonicalGameplayRosterSnapshot> first =
          executor.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .execute(
                          ignored -> {
                            firstPid.set(
                                java.util.Objects.requireNonNull(
                                    jdbcTemplate.queryForObject(
                                        "SELECT pg_backend_pid()", Integer.class)));
                            var request = requests.getFirst();
                            retention.requireAdmission(request, admissions.getFirst());
                            assertThat(retention.originalReceipt(sources.getFirst())).isEmpty();
                            // Pause the actual capture/bind sequence after capture, while all its
                            // locks remain held.
                            var snapshot =
                                characterRepository.captureCanonicalGameplayRosterSnapshot(
                                    request,
                                    new CanonicalGameplayRosterOwnerEvidence(
                                        request.requestUuid(), accountUuid, target, Instant.now()));
                            firstCaptured.countDown();
                            try {
                              if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException(
                                    "First existing snapshot capture was not released");
                              }
                            } catch (InterruptedException interrupted) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(interrupted);
                            }
                            retention.bindOriginal(sources.getFirst(), snapshot);
                            retention.bindAdmission(
                                request.requestUuid(),
                                admissions.getFirst(),
                                snapshot.snapshotUuid());
                            return snapshot;
                          }));
      assertThat(firstCaptured.await(10, TimeUnit.SECONDS)).isTrue();
      Future<CanonicalGameplayRosterSnapshot> second =
          executor.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .execute(
                          ignored -> {
                            secondPid.set(
                                java.util.Objects.requireNonNull(
                                    jdbcTemplate.queryForObject(
                                        "SELECT pg_backend_pid()", Integer.class)));
                            secondStarted.countDown();
                            var request = requests.get(1);
                            return characterRepository
                                .captureAndBindCanonicalGameplayRosterSnapshot(
                                    request,
                                    new CanonicalGameplayRosterOwnerEvidence(
                                        request.requestUuid(), accountUuid, target, Instant.now()),
                                    sources.get(1),
                                    admissions.get(1));
                          }));
      assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
      awaitBackendBlockedBy(secondPid.get(), firstPid.get());
      assertActorWriterBlocked(actor);
      releaseFirst.countDown();
      assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(existing);
      assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(existing);
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT count(*) FROM entity_canonical_roster_retention_references "
                      + "WHERE snapshot_uuid = ? AND snapshot_digest = ? AND reference_kind = 'ORIGINAL_SELECTION' "
                      + "AND reference_uuid IN (?, ?) AND acquire_operation_uuid = reference_uuid",
                  Integer.class,
                  existing.snapshotUuid(),
                  existing.snapshotDigest(),
                  requests.getFirst().requestUuid(),
                  requests.get(1).requestUuid()))
          .isEqualTo(2);
      for (int index = 0; index < requests.size(); index++) {
        UUID operation = requests.get(index).requestUuid();
        assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT owner_request FROM entity_canonical_roster_original_selections WHERE operation_uuid = ?",
                    byte[].class,
                    operation))
            .isEqualTo(sources.get(index).getRequest().toByteArray());
        assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT canonical_roster_receipt FROM entity_canonical_roster_original_selections WHERE operation_uuid = ?",
                    byte[].class,
                    operation))
            .isEqualTo(existing.toWire().toByteArray());
        assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT owner_target_fence_uuid FROM entity_canonical_roster_original_selections WHERE operation_uuid = ?",
                    UUID.class,
                    operation))
            .isEqualTo(targetFence);
        assertThat(
                jdbcTemplate.queryForObject(
                    "SELECT snapshot_uuid FROM entity_canonical_roster_admissions WHERE operation_uuid = ? AND admission_uuid = ? AND admission_state = 'OPEN'",
                    UUID.class,
                    operation,
                    admissions.get(index)))
            .isEqualTo(existing.snapshotUuid());
      }
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT count(*) FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_digest = ?",
                  Integer.class,
                  existing.snapshotDigest()))
          .isEqualTo(1);
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT name FROM characters WHERE character_uuid = ?", String.class, actor))
          .isEqualTo("Shared Existing Actor");
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void currentSnapshotReadersStillShareTheirRowsAndExcludeActorWriters() throws Exception {
    var target = target(namespaceUuid, UUID.randomUUID());
    UUID actor = createPersistedActor(accountUuid, target, 63L, "Shared Read Actor");
    var snapshot = readRoster(accountUuid, target);
    var reference =
        new CanonicalGameplayRosterSnapshotReference(
            snapshot.snapshotUuid(), snapshot.snapshotDigest());
    CountDownLatch firstRead = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<CanonicalGameplayRosterSnapshot> first =
          executor.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .execute(
                          ignored -> {
                            var result =
                                characterRepository.readCurrentCanonicalGameplayRosterSnapshot(
                                    accountUuid, target, reference);
                            firstRead.countDown();
                            try {
                              if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException(
                                    "First current snapshot reader was not released");
                              }
                            } catch (InterruptedException interrupted) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(interrupted);
                            }
                            return result;
                          }));
      assertThat(firstRead.await(10, TimeUnit.SECONDS)).isTrue();
      Future<CanonicalGameplayRosterSnapshot> second =
          executor.submit(
              () ->
                  characterRepository.readCurrentCanonicalGameplayRosterSnapshot(
                      accountUuid, target, reference));
      assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(snapshot);
      assertActorWriterBlocked(actor);
      releaseFirst.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(snapshot);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }
  }

  private CanonicalGameplayRosterReadRequest originalRosterRequest(
      CanonicalGameplayRosterTarget target) {
    UUID operation = UUID.randomUUID();
    return new CanonicalGameplayRosterReadRequest(
        operation,
        accountUuid,
        target,
        new CanonicalGameplayRosterExecutionContext(
            accountUuid,
            target.tenantUuid(),
            target.playableStateNamespaceId(),
            target.gameInstanceUuid(),
            null,
            UUID.randomUUID(),
            target.realmUuid(),
            operation,
            target.playableStateScope()));
  }

  private void awaitSiblingLatch(CountDownLatch latch, String operation) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting to release " + operation);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private void awaitBackendBlockedBy(int waitingPid, int holderPid) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    boolean blocked = false;
    while (System.nanoTime() < deadline) {
      blocked =
          Boolean.TRUE.equals(
              jdbcTemplate.queryForObject(
                  "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, holderPid, waitingPid));
      if (blocked) break;
      TimeUnit.MILLISECONDS.sleep(10);
    }
    assertThat(blocked)
        .as("Second existing snapshot operation waits for the first transaction")
        .isTrue();
  }

  private void assertActorWriterBlocked(UUID actor) {
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        ignored -> {
                          jdbcTemplate.execute("SET LOCAL lock_timeout = '250ms'");
                          jdbcTemplate.update(
                              "UPDATE characters SET name = 'Blocked Actor Rename' WHERE character_uuid = ?",
                              actor);
                        }))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("lock timeout");
  }

  @Test
  void concurrentIdenticalReadsReuseOnePersistedSnapshotIdentity() throws Exception {
    CanonicalGameplayRosterTarget exactTarget = target(namespaceUuid, UUID.randomUUID());
    createPersistedActor(accountUuid, exactTarget, 61L, "Concurrent Actor");
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<CanonicalGameplayRosterSnapshot> first =
          executor.submit(
              () -> {
                start.await();
                return readRoster(accountUuid, exactTarget);
              });
      Future<CanonicalGameplayRosterSnapshot> second =
          executor.submit(
              () -> {
                start.await();
                return readRoster(accountUuid, exactTarget);
              });
      start.countDown();

      CanonicalGameplayRosterSnapshot firstResult = first.get(15, TimeUnit.SECONDS);
      CanonicalGameplayRosterSnapshot secondResult = second.get(15, TimeUnit.SECONDS);
      assertThat(secondResult.snapshotUuid()).isEqualTo(firstResult.snapshotUuid());
      assertThat(secondResult.snapshotDigest()).isEqualTo(firstResult.snapshotDigest());
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT count(*) FROM entity_canonical_gameplay_roster_snapshots "
                      + "WHERE snapshot_digest = ?",
                  Integer.class,
                  firstResult.snapshotDigest()))
          .isEqualTo(1);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void differentAccountRosterCaptureCanFinishWhileAnotherReaderHoldsItsTransaction()
      throws Exception {
    CanonicalGameplayRosterTarget exactTarget = target(namespaceUuid, UUID.randomUUID());
    UUID otherAccountUuid = UUID.randomUUID();
    createPersistedActor(accountUuid, exactTarget, 71L, "First Actor");
    createPersistedActor(otherAccountUuid, exactTarget, 72L, "Second Actor");
    CountDownLatch firstRead = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<CanonicalGameplayRosterSnapshot> first =
          executor.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .execute(
                          ignored -> {
                            CanonicalGameplayRosterSnapshot snapshot =
                                readRoster(accountUuid, exactTarget);
                            firstRead.countDown();
                            try {
                              if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException(
                                    "First roster transaction was not released");
                              }
                            } catch (InterruptedException interrupted) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(interrupted);
                            }
                            return snapshot;
                          }));
      assertThat(firstRead.await(10, TimeUnit.SECONDS)).isTrue();

      Future<CanonicalGameplayRosterSnapshot> second =
          executor.submit(() -> readRoster(otherAccountUuid, exactTarget));
      CanonicalGameplayRosterSnapshot secondResult = second.get(5, TimeUnit.SECONDS);
      assertThat(secondResult.actors()).hasSize(1);
      releaseFirst.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS).actors()).hasSize(1);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }
  }

  private CanonicalGameplayRosterSnapshot readRoster(
      UUID canonicalAccountUuid, CanonicalGameplayRosterTarget target) {
    UUID requestUuid = UUID.randomUUID();
    CanonicalGameplayRosterReadRequest request =
        new CanonicalGameplayRosterReadRequest(
            requestUuid,
            canonicalAccountUuid,
            target,
            new CanonicalGameplayRosterExecutionContext(
                canonicalAccountUuid,
                target.tenantUuid(),
                target.playableStateNamespaceId(),
                target.gameInstanceUuid(),
                null,
                UUID.randomUUID(),
                target.realmUuid(),
                requestUuid,
                target.playableStateScope()));
    return characterRepository.captureCanonicalGameplayRosterSnapshot(
        request,
        new CanonicalGameplayRosterOwnerEvidence(
            request.requestUuid(), canonicalAccountUuid, target, Instant.now()));
  }

  private UUID createPersistedActor(
      UUID canonicalAccountUuid,
      CanonicalGameplayRosterTarget target,
      long accountRowId,
      String displayName) {
    UUID assignmentUuid = UUID.randomUUID();
    PreseededActorAssignmentOwnerEvidence ownerEvidence =
        new PreseededActorAssignmentOwnerEvidence(
            assignmentUuid,
            canonicalAccountUuid,
            PreseededActorAssignmentOwnerEvidence.Eligibility.ELIGIBLE,
            Instant.parse("2026-10-04T00:00:00Z"),
            4L,
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
    PreseededActorAssignmentRequest request =
        new PreseededActorAssignmentRequest(
            assignmentUuid,
            canonicalAccountUuid,
            new PreseededActorCorePayload(PreseededActorCorePayload.ActorKind.PLAYER, displayName),
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
    PreseededActorAssignmentResult result =
        characterRepository.assignPreseededActor(
            request,
            ownerEvidence,
            new RuntimeAccountIdentityEvidence(
                1,
                "dev",
                assignmentUuid,
                canonicalAccountUuid,
                accountRowId,
                "ACCOUNT_DATABASE_INSERT"),
            request.mutationIntentDigest());
    assertThat(result.outcome()).isEqualTo(PreseededActorAssignmentResult.Outcome.ASSIGNED);
    return result.characterUuid();
  }

  private CanonicalGameplayRosterTarget target(UUID namespace, UUID instance) {
    return new CanonicalGameplayRosterTarget(
        tenantUuid,
        realmUuid,
        "world",
        "realm",
        instance,
        7L,
        13L,
        19L,
        UUID.fromString("21000000-0000-4000-8000-000000000021"),
        "3".repeat(64),
        "release/roster-test",
        "4".repeat(64),
        "5".repeat(64),
        namespace,
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        CanonicalGameplayRosterEntryPolicy.PRESEEDED_ONLY);
  }

  private CanonicalGameplayRosterTarget withCounters(
      CanonicalGameplayRosterTarget target, long pointerVersion, long activeWorldEpoch) {
    return new CanonicalGameplayRosterTarget(
        target.tenantUuid(),
        target.realmUuid(),
        target.worldSlug(),
        target.realmSlug(),
        target.gameInstanceUuid(),
        target.catalogRevision(),
        pointerVersion,
        activeWorldEpoch,
        target.canonicalVersionUuid(),
        target.publishedPolicyDigest(),
        target.publishedReleaseBundleRef(),
        target.admissionPointerSnapshotDigest(),
        target.publishedOwnerProofDigest(),
        target.playableStateNamespaceId(),
        target.playableStateScope(),
        target.entryPolicy());
  }
}
