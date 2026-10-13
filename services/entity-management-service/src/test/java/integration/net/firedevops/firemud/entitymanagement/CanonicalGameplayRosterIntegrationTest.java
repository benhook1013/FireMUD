package net.firedevops.firemud.entitymanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "entity_management_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private CharacterRepository characterRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
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
