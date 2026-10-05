package net.firedevops.firemud.worldmanagement;

import static net.firedevops.firemud.worldmanagement.jooq.tables.WorldDesignAggregateEpoch.WORLD_DESIGN_AGGREGATE_EPOCH;
import static net.firedevops.firemud.worldmanagement.jooq.tables.WorldDesignScopeEpoch.WORLD_DESIGN_SCOPE_EPOCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import net.firedevops.firemud.gamedesign.v1.GetVersionStateResponse;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.dto.WorldDesignMutationRequestDto;
import net.firedevops.firemud.worldmanagement.entity.Region;
import net.firedevops.firemud.worldmanagement.entity.WorldDesignAggregateEpoch;
import net.firedevops.firemud.worldmanagement.entity.WorldDesignScopeEpoch;
import net.firedevops.firemud.worldmanagement.entity.Zone;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldDesignAggregateEpochRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldDesignScopeEpochRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import net.firedevops.firemud.worldmanagement.service.WorldDesignMutationService;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
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
    classes = WorldManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class WorldDesignEpochCasPostgresIntegrationTest {
  private static final LocalDateTime INITIAL_EPOCH_TIME = LocalDateTime.of(2026, 10, 1, 12, 0);
  private static final LocalDateTime ADVANCED_EPOCH_TIME = LocalDateTime.of(2026, 10, 2, 12, 0);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "world_management_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private RegionRepository regionRepository;
  @Autowired private ZoneRepository zoneRepository;
  @Autowired private WorldDesignAggregateEpochRepository aggregateEpochRepository;
  @Autowired private WorldDesignScopeEpochRepository scopeEpochRepository;
  @Autowired private WorldDesignMutationService mutationService;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;
  @MockitoBean private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;
  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  @Test
  void concurrentFirstZeroClaimsHaveExactlyOneWinnerForEachEpochTable() throws Exception {
    long tenantId = uniquePositiveLong();
    long versionId = uniquePositiveLong();
    long aggregateId = uniquePositiveLong();
    String scopeId = Long.toString(uniquePositiveLong());

    assertSingleConcurrentWinner(
        "first aggregate epoch claim",
        () ->
            aggregateEpochRepository.compareAndAdvance(
                tenantId, versionId, "ROOM", aggregateId, 0L, ADVANCED_EPOCH_TIME),
        () ->
            aggregateEpochRepository
                .findByTenantIdAndVersionIdAndAggregateTypeAndAggregateId(
                    tenantId, versionId, "ROOM", aggregateId)
                .orElseThrow()
                .getDraftRevisionEpoch(),
        () -> countAggregateRows(tenantId, versionId, "ROOM", aggregateId),
        1L);
    assertSingleConcurrentWinner(
        "first scope epoch claim",
        () ->
            scopeEpochRepository.compareAndAdvance(
                tenantId, versionId, "ZONE_SUBTREE", scopeId, 0L, ADVANCED_EPOCH_TIME),
        () ->
            scopeEpochRepository
                .findByTenantIdAndVersionIdAndScopeTypeAndScopeId(
                    tenantId, versionId, "ZONE_SUBTREE", scopeId)
                .orElseThrow()
                .getDraftScopeRevisionEpoch(),
        () -> countScopeRows(tenantId, versionId, "ZONE_SUBTREE", scopeId),
        1L);
  }

  @Test
  void concurrentExistingEpochClaimsHaveExactlyOneWinnerForEachEpochTable() throws Exception {
    long tenantId = uniquePositiveLong();
    long versionId = uniquePositiveLong();
    long aggregateId = uniquePositiveLong();
    String scopeId = Long.toString(uniquePositiveLong());
    saveAggregateEpoch(tenantId, versionId, "ROOM", aggregateId, 6L, INITIAL_EPOCH_TIME);
    saveScopeEpoch(tenantId, versionId, "ZONE_SUBTREE", scopeId, 6L, INITIAL_EPOCH_TIME);

    assertSingleConcurrentWinner(
        "existing aggregate epoch advance",
        () ->
            aggregateEpochRepository.compareAndAdvance(
                tenantId, versionId, "ROOM", aggregateId, 6L, ADVANCED_EPOCH_TIME),
        () ->
            aggregateEpochRepository
                .findByTenantIdAndVersionIdAndAggregateTypeAndAggregateId(
                    tenantId, versionId, "ROOM", aggregateId)
                .orElseThrow()
                .getDraftRevisionEpoch(),
        () -> countAggregateRows(tenantId, versionId, "ROOM", aggregateId),
        7L);
    assertSingleConcurrentWinner(
        "existing scope epoch advance",
        () ->
            scopeEpochRepository.compareAndAdvance(
                tenantId, versionId, "ZONE_SUBTREE", scopeId, 6L, ADVANCED_EPOCH_TIME),
        () ->
            scopeEpochRepository
                .findByTenantIdAndVersionIdAndScopeTypeAndScopeId(
                    tenantId, versionId, "ZONE_SUBTREE", scopeId)
                .orElseThrow()
                .getDraftScopeRevisionEpoch(),
        () -> countScopeRows(tenantId, versionId, "ZONE_SUBTREE", scopeId),
        7L);
  }

  @Test
  void tupleDimensionsAndInvalidEpochsCannotChangeNeighboringRows() {
    long tenantId = uniquePositiveLong();
    long otherTenantId = tenantId + 1L;
    long versionId = uniquePositiveLong();
    long otherVersionId = versionId + 1L;
    long aggregateId = uniquePositiveLong();
    String scopeId = Long.toString(uniquePositiveLong());

    List<WorldDesignAggregateEpoch> aggregateRows =
        List.of(
            saveAggregateEpoch(tenantId, versionId, "ROOM", aggregateId, 3L, INITIAL_EPOCH_TIME),
            saveAggregateEpoch(
                otherTenantId, versionId, "ROOM", aggregateId, 3L, INITIAL_EPOCH_TIME),
            saveAggregateEpoch(
                tenantId, otherVersionId, "ROOM", aggregateId, 3L, INITIAL_EPOCH_TIME),
            saveAggregateEpoch(tenantId, versionId, "ZONE", aggregateId, 3L, INITIAL_EPOCH_TIME),
            saveAggregateEpoch(
                tenantId, versionId, "ROOM", aggregateId + 1L, 3L, INITIAL_EPOCH_TIME),
            saveAggregateEpoch(
                tenantId, versionId, "ROOM", aggregateId + 2L, Long.MAX_VALUE, INITIAL_EPOCH_TIME));
    List<Long> aggregateIds = aggregateRows.stream().map(WorldDesignAggregateEpoch::getId).toList();
    String aggregateSnapshotBeforeInvalidCalls = aggregateSnapshot(aggregateIds);
    String aggregateSiblingsBeforeAdvance =
        aggregateSnapshot(aggregateIds.subList(1, aggregateIds.size()));

    assertThat(
            aggregateEpochRepository.compareAndAdvance(
                tenantId, versionId, "ROOM", aggregateId, -1L, ADVANCED_EPOCH_TIME))
        .isFalse();
    assertThat(
            aggregateEpochRepository.compareAndAdvance(
                tenantId, versionId, "ROOM", aggregateId + 2L, Long.MAX_VALUE, ADVANCED_EPOCH_TIME))
        .isFalse();
    assertThat(
            aggregateEpochRepository.compareAndAdvance(
                tenantId, versionId, "ROOM", aggregateId, 2L, ADVANCED_EPOCH_TIME))
        .isFalse();
    assertThat(
            aggregateEpochRepository.compareAndAdvance(
                tenantId, versionId, "ROOM", aggregateId + 3L, 3L, ADVANCED_EPOCH_TIME))
        .isFalse();
    assertThat(aggregateSnapshot(aggregateIds)).isEqualTo(aggregateSnapshotBeforeInvalidCalls);

    assertThat(
            aggregateEpochRepository.compareAndAdvance(
                tenantId, versionId, "ROOM", aggregateId, 3L, ADVANCED_EPOCH_TIME))
        .isTrue();
    assertThat(
            aggregateEpochRepository
                .findByTenantIdAndVersionIdAndAggregateTypeAndAggregateId(
                    tenantId, versionId, "ROOM", aggregateId)
                .orElseThrow())
        .satisfies(
            epoch -> {
              assertThat(epoch.getDraftRevisionEpoch()).isEqualTo(4L);
              assertThat(epoch.getUpdatedAt()).isEqualTo(ADVANCED_EPOCH_TIME);
            });
    assertThat(aggregateSnapshot(aggregateIds.subList(1, aggregateIds.size())))
        .isEqualTo(aggregateSiblingsBeforeAdvance);

    List<WorldDesignScopeEpoch> scopeRows =
        List.of(
            saveScopeEpoch(tenantId, versionId, "ZONE_SUBTREE", scopeId, 3L, INITIAL_EPOCH_TIME),
            saveScopeEpoch(
                otherTenantId, versionId, "ZONE_SUBTREE", scopeId, 3L, INITIAL_EPOCH_TIME),
            saveScopeEpoch(
                tenantId, otherVersionId, "ZONE_SUBTREE", scopeId, 3L, INITIAL_EPOCH_TIME),
            saveScopeEpoch(tenantId, versionId, "REGION_SUBTREE", scopeId, 3L, INITIAL_EPOCH_TIME),
            saveScopeEpoch(
                tenantId,
                versionId,
                "ZONE_SUBTREE",
                Long.toString(Long.parseLong(scopeId) + 1L),
                3L,
                INITIAL_EPOCH_TIME),
            saveScopeEpoch(
                tenantId,
                versionId,
                "ZONE_SUBTREE",
                Long.toString(Long.parseLong(scopeId) + 2L),
                Long.MAX_VALUE,
                INITIAL_EPOCH_TIME));
    List<Long> scopeIds = scopeRows.stream().map(WorldDesignScopeEpoch::getId).toList();
    String scopeSnapshotBeforeInvalidCalls = scopeSnapshot(scopeIds);
    String scopeSiblingsBeforeAdvance = scopeSnapshot(scopeIds.subList(1, scopeIds.size()));

    assertThat(
            scopeEpochRepository.compareAndAdvance(
                tenantId, versionId, "ZONE_SUBTREE", scopeId, -1L, ADVANCED_EPOCH_TIME))
        .isFalse();
    WorldDesignScopeEpoch overflowScope = scopeRows.get(scopeRows.size() - 1);
    assertThat(
            scopeEpochRepository.compareAndAdvance(
                overflowScope.getTenantId(),
                overflowScope.getVersionId(),
                overflowScope.getScopeType(),
                overflowScope.getScopeId(),
                Long.MAX_VALUE,
                ADVANCED_EPOCH_TIME))
        .isFalse();
    assertThat(
            scopeEpochRepository.compareAndAdvance(
                tenantId, versionId, "ZONE_SUBTREE", scopeId, 2L, ADVANCED_EPOCH_TIME))
        .isFalse();
    assertThat(
            scopeEpochRepository.compareAndAdvance(
                tenantId, versionId, "ZONE_SUBTREE", scopeId + "-missing", 3L, ADVANCED_EPOCH_TIME))
        .isFalse();
    assertThat(scopeSnapshot(scopeIds)).isEqualTo(scopeSnapshotBeforeInvalidCalls);

    assertThat(
            scopeEpochRepository.compareAndAdvance(
                tenantId, versionId, "ZONE_SUBTREE", scopeId, 3L, ADVANCED_EPOCH_TIME))
        .isTrue();
    assertThat(
            scopeEpochRepository
                .findByTenantIdAndVersionIdAndScopeTypeAndScopeId(
                    tenantId, versionId, "ZONE_SUBTREE", scopeId)
                .orElseThrow())
        .satisfies(
            epoch -> {
              assertThat(epoch.getDraftScopeRevisionEpoch()).isEqualTo(4L);
              assertThat(epoch.getUpdatedAt()).isEqualTo(ADVANCED_EPOCH_TIME);
            });
    assertThat(scopeSnapshot(scopeIds.subList(1, scopeIds.size())))
        .isEqualTo(scopeSiblingsBeforeAdvance);
  }

  @Test
  void lateScopeConflictRollsBackServiceAuthoredRowAndPriorAggregateAdvance() throws Exception {
    WorldFixture fixture = worldFixture();
    WorldDesignScopeEpoch scopeEpoch =
        saveScopeEpoch(
            fixture.tenantId(),
            fixture.versionId(),
            "ZONE_SUBTREE",
            Long.toString(fixture.zoneId()),
            0L,
            INITIAL_EPOCH_TIME);
    stubDraftVersion(fixture.tenantId(), fixture.versionId());
    WorldDesignMutationRequestDto request = roomMutation(fixture, "late-conflict");

    CountDownLatch blockerChangedScope = new CountDownLatch(1);
    CountDownLatch releaseBlocker = new CountDownLatch(1);
    CountDownLatch mutationStarted = new CountDownLatch(1);
    AtomicInteger blockerBackendPid = new AtomicInteger();
    AtomicInteger mutationBackendPid = new AtomicInteger();

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> blocker =
          executor.submit(
              () ->
                  ownerTransaction()
                      .execute(
                          status -> {
                            blockerBackendPid.set(backendPid());
                            int updated =
                                dsl.execute(
                                    "UPDATE world_design_scope_epoch SET "
                                        + "draft_scope_revision_epoch = ?, updated_at = ? "
                                        + "WHERE id = ? AND draft_scope_revision_epoch = ?",
                                    1L,
                                    ADVANCED_EPOCH_TIME,
                                    scopeEpoch.getId(),
                                    0L);
                            assertThat(updated).isEqualTo(1);
                            blockerChangedScope.countDown();
                            await(releaseBlocker);
                            return null;
                          }));
      await(blockerChangedScope);

      Future<Throwable> mutation =
          executor.submit(
              () -> {
                try {
                  ownerTransaction()
                      .execute(
                          status -> {
                            mutationBackendPid.set(backendPid());
                            mutationStarted.countDown();
                            return mutationService.applyMutation(request);
                          });
                  return null;
                } catch (RuntimeException exception) {
                  return exception;
                }
              });
      await(mutationStarted);
      awaitBackendBlockedBy(mutationBackendPid.get(), blockerBackendPid.get());
      releaseBlocker.countDown();

      blocker.get(10, TimeUnit.SECONDS);
      Throwable conflict = mutation.get(10, TimeUnit.SECONDS);
      assertThat(conflict)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("DRAFT_WRITE_CONFLICT:");
    } finally {
      releaseBlocker.countDown();
      executor.shutdownNow();
    }

    assertThat(roomSnapshot(fixture)).isEmpty();
    assertThat(countRows("world_design_aggregate_epoch", fixture.tenantId(), fixture.versionId()))
        .isZero();
    assertThat(countLedgerRows(fixture.tenantId(), fixture.versionId(), request.commitId()))
        .isZero();
    assertThat(
            scopeEpochRepository
                .findByTenantIdAndVersionIdAndScopeTypeAndScopeId(
                    fixture.tenantId(),
                    fixture.versionId(),
                    "ZONE_SUBTREE",
                    Long.toString(fixture.zoneId()))
                .orElseThrow())
        .satisfies(
            epoch -> {
              assertThat(epoch.getDraftScopeRevisionEpoch()).isEqualTo(1L);
              assertThat(epoch.getUpdatedAt()).isEqualTo(ADVANCED_EPOCH_TIME);
            });
  }

  @Test
  void authoredRowAggregateScopeAndLedgerShareTheOwnerTransaction() {
    WorldFixture fixture = worldFixture();
    stubDraftVersion(fixture.tenantId(), fixture.versionId());
    WorldDesignMutationRequestDto request = roomMutation(fixture, "forced-rollback");

    assertThatThrownBy(
            () ->
                ownerTransaction()
                    .execute(
                        status -> {
                          var result = mutationService.applyMutation(request);
                          assertThat(result.result()).isEqualTo("APPLIED");
                          assertThat(result.aggregateId()).isPositive();
                          List<String> authoredRows = roomSnapshot(fixture);
                          assertThat(authoredRows).hasSize(1);
                          assertThat(authoredRows.get(0))
                              .contains("epoch-cas-room-forced-rollback");
                          assertThat(countRows("room", fixture.tenantId(), fixture.versionId()))
                              .isEqualTo(1L);
                          assertThat(
                                  aggregateEpochRepository
                                      .findByTenantIdAndVersionIdAndAggregateTypeAndAggregateId(
                                          fixture.tenantId(),
                                          fixture.versionId(),
                                          "ROOM",
                                          result.aggregateId())
                                      .orElseThrow()
                                      .getDraftRevisionEpoch())
                              .isEqualTo(1L);
                          assertThat(
                                  scopeEpochRepository
                                      .findByTenantIdAndVersionIdAndScopeTypeAndScopeId(
                                          fixture.tenantId(),
                                          fixture.versionId(),
                                          "ZONE_SUBTREE",
                                          Long.toString(fixture.zoneId()))
                                      .orElseThrow()
                                      .getDraftScopeRevisionEpoch())
                              .isEqualTo(1L);
                          assertThat(
                                  countLedgerRows(
                                      fixture.tenantId(), fixture.versionId(), request.commitId()))
                              .isEqualTo(1L);
                          throw new ForcedRollbackException();
                        }))
        .isInstanceOf(ForcedRollbackException.class);

    assertThat(roomSnapshot(fixture)).isEmpty();
    assertThat(countRows("world_design_aggregate_epoch", fixture.tenantId(), fixture.versionId()))
        .isZero();
    assertThat(countRows("world_design_scope_epoch", fixture.tenantId(), fixture.versionId()))
        .isZero();
    assertThat(countLedgerRows(fixture.tenantId(), fixture.versionId(), request.commitId()))
        .isZero();
  }

  private void assertSingleConcurrentWinner(
      String description,
      BooleanSupplier advance,
      LongSupplier storedEpoch,
      LongSupplier matchingRowCount,
      long expectedFinalEpoch)
      throws Exception {
    CountDownLatch firstAdvanced = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    AtomicInteger firstBackendPid = new AtomicInteger();
    AtomicInteger secondBackendPid = new AtomicInteger();

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> first =
          executor.submit(
              () ->
                  ownerTransaction()
                      .execute(
                          status -> {
                            firstBackendPid.set(backendPid());
                            boolean advanced = advance.getAsBoolean();
                            assertThat(advanced).as(description).isTrue();
                            firstAdvanced.countDown();
                            await(releaseFirst);
                            return advanced;
                          }));
      await(firstAdvanced);

      Future<Boolean> second =
          executor.submit(
              () ->
                  ownerTransaction()
                      .execute(
                          status -> {
                            secondBackendPid.set(backendPid());
                            secondStarted.countDown();
                            return advance.getAsBoolean();
                          }));
      await(secondStarted);
      awaitBackendBlockedBy(secondBackendPid.get(), firstBackendPid.get());
      releaseFirst.countDown();

      assertThat(first.get(10, TimeUnit.SECONDS)).as(description).isTrue();
      assertThat(second.get(10, TimeUnit.SECONDS)).as(description).isFalse();
      assertThat(storedEpoch.getAsLong()).as(description).isEqualTo(expectedFinalEpoch);
      assertThat(matchingRowCount.getAsLong()).as(description).isEqualTo(1L);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }
  }

  private WorldFixture worldFixture() {
    long tenantId = uniquePositiveLong();
    long versionId = uniquePositiveLong();
    Region region = new Region();
    region.setTenantId(tenantId);
    region.setVersionId(versionId);
    region.setShardId(0);
    region.setName("epoch-cas-region-" + UUID.randomUUID());
    region.setGenerationSeed(0L);
    region.setSpacingMultiplier(1.0);
    Region savedRegion = regionRepository.save(region);

    Zone zone = new Zone();
    zone.setTenantId(tenantId);
    zone.setVersionId(versionId);
    zone.setRegion(savedRegion);
    zone.setName("epoch-cas-zone-" + UUID.randomUUID());
    Zone savedZone = zoneRepository.save(zone);
    return new WorldFixture(tenantId, versionId, savedZone.getId());
  }

  private WorldDesignMutationRequestDto roomMutation(WorldFixture fixture, String suffix) {
    return new WorldDesignMutationRequestDto(
        fixture.tenantId(),
        fixture.versionId(),
        "epoch-cas-commit-" + suffix + "-" + UUID.randomUUID(),
        "epoch-cas-revision-" + UUID.randomUUID(),
        "UPSERT",
        "ROOM",
        "",
        0L,
        "ZONE_SUBTREE",
        Long.toString(fixture.zoneId()),
        0L,
        "SEED_APPEND_ONLY",
        null,
        null,
        new WorldDesignMutationRequestDto.RoomMutationDto(
            "epoch-cas-room-" + suffix,
            "Authored by the mutation service",
            Long.toString(fixture.zoneId()),
            null,
            null),
        null,
        null,
        null,
        null);
  }

  private void stubDraftVersion(long tenantId, long versionId) {
    when(gameDesignClient.getVersionState(tenantId, versionId))
        .thenReturn(
            GetVersionStateResponse.newBuilder()
                .setVersionState(
                    VersionStateSnapshot.newBuilder()
                        .setTenantId(Long.toString(tenantId))
                        .setVersionId(versionId)
                        .setVersionState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT)
                        .setVersionStateEpoch(1L)
                        .build())
                .build());
  }

  private WorldDesignAggregateEpoch saveAggregateEpoch(
      long tenantId,
      long versionId,
      String aggregateType,
      long aggregateId,
      long epoch,
      LocalDateTime updatedAt) {
    WorldDesignAggregateEpoch row = new WorldDesignAggregateEpoch();
    row.setTenantId(tenantId);
    row.setVersionId(versionId);
    row.setAggregateType(aggregateType);
    row.setAggregateId(aggregateId);
    row.setDraftRevisionEpoch(epoch);
    row.setUpdatedAt(updatedAt);
    return aggregateEpochRepository.save(row);
  }

  private WorldDesignScopeEpoch saveScopeEpoch(
      long tenantId,
      long versionId,
      String scopeType,
      String scopeId,
      long epoch,
      LocalDateTime updatedAt) {
    WorldDesignScopeEpoch row = new WorldDesignScopeEpoch();
    row.setTenantId(tenantId);
    row.setVersionId(versionId);
    row.setScopeType(scopeType);
    row.setScopeId(scopeId);
    row.setDraftScopeRevisionEpoch(epoch);
    row.setUpdatedAt(updatedAt);
    return scopeEpochRepository.save(row);
  }

  private String aggregateSnapshot(List<Long> ids) {
    return dsl.selectFrom(WORLD_DESIGN_AGGREGATE_EPOCH)
        .where(WORLD_DESIGN_AGGREGATE_EPOCH.ID.in(ids))
        .orderBy(WORLD_DESIGN_AGGREGATE_EPOCH.ID.asc())
        .fetch()
        .formatJSON();
  }

  private String scopeSnapshot(List<Long> ids) {
    return dsl.selectFrom(WORLD_DESIGN_SCOPE_EPOCH)
        .where(WORLD_DESIGN_SCOPE_EPOCH.ID.in(ids))
        .orderBy(WORLD_DESIGN_SCOPE_EPOCH.ID.asc())
        .fetch()
        .formatJSON();
  }

  private List<String> roomSnapshot(WorldFixture fixture) {
    return dsl.fetch(
            "SELECT * FROM room WHERE tenant_id = ? AND version_id = ? ORDER BY id",
            fixture.tenantId(),
            fixture.versionId())
        .map(Record::formatJSON);
  }

  private long countRows(String table, long tenantId, long versionId) {
    Record row =
        dsl.fetchOne(
            "SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ? AND version_id = ?",
            tenantId,
            versionId);
    return Objects.requireNonNull(row, "count query returned no row").get(0, Long.class);
  }

  private long countLedgerRows(long tenantId, long versionId, String commitId) {
    Record row =
        dsl.fetchOne(
            "SELECT COUNT(*) FROM world_design_revision_ledger "
                + "WHERE tenant_id = ? AND version_id = ? AND commit_id = ?",
            tenantId,
            versionId,
            commitId);
    return Objects.requireNonNull(row, "ledger count query returned no row").get(0, Long.class);
  }

  private long countAggregateRows(
      long tenantId, long versionId, String aggregateType, long aggregateId) {
    Record row =
        dsl.fetchOne(
            "SELECT COUNT(*) FROM world_design_aggregate_epoch "
                + "WHERE tenant_id = ? AND version_id = ? AND aggregate_type = ? AND aggregate_id = ?",
            tenantId,
            versionId,
            aggregateType,
            aggregateId);
    return Objects.requireNonNull(row, "aggregate epoch count query returned no row")
        .get(0, Long.class);
  }

  private long countScopeRows(long tenantId, long versionId, String scopeType, String scopeId) {
    Record row =
        dsl.fetchOne(
            "SELECT COUNT(*) FROM world_design_scope_epoch "
                + "WHERE tenant_id = ? AND version_id = ? AND scope_type = ? AND scope_id = ?",
            tenantId,
            versionId,
            scopeType,
            scopeId);
    return Objects.requireNonNull(row, "scope epoch count query returned no row")
        .get(0, Long.class);
  }

  private int backendPid() {
    Record row = Objects.requireNonNull(dsl.fetchOne("SELECT pg_backend_pid()"));
    return Objects.requireNonNull(row.get(0, Integer.class));
  }

  private void awaitBackendBlockedBy(int blockedPid, int blockerPid) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Record row =
          dsl.fetchOne(
              "SELECT wait_event_type = 'Lock' AND ? = ANY(pg_blocking_pids(pid)) "
                  + "FROM pg_stat_activity WHERE pid = ?",
              blockerPid,
              blockedPid);
      if (row != null && Boolean.TRUE.equals(row.get(0, Boolean.class))) {
        return;
      }
      Thread.sleep(25L);
    }
    throw new AssertionError(
        "PostgreSQL backend " + blockedPid + " was not observed blocked by " + blockerPid);
  }

  private TransactionTemplate ownerTransaction() {
    return new TransactionTemplate(transactionManager);
  }

  private static long uniquePositiveLong() {
    return ((UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE) / 1_000_000L) + 10_000L;
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out coordinating the PostgreSQL epoch CAS test");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(
          "Interrupted while coordinating the PostgreSQL epoch CAS test", exception);
    }
  }

  private record WorldFixture(long tenantId, long versionId, long zoneId) {}

  private static final class ForcedRollbackException extends RuntimeException {}
}
