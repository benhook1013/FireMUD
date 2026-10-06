package net.firedevops.firemud.worldmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.v1.ArtifactState;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionAssetArtifactStateResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionStateResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.service.WorldLifecycleCommandService;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL proof for the legacy numeric lifecycle prepare component and its local transaction.
 * The Game Design release and version responses below are synthetic test evidence; this test does
 * not establish authored publication authority or PREPARING eligibility.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = {"spring.grpc.server.port=0"})
class WorldLifecyclePreparationPostgresIntegrationTest {
  private static final long RELEASE_BUNDLE_ID = 7301L;
  private static final long VERSION_STATE_EPOCH = 91L;
  private static final String RELEASE_BUNDLE_REF = "synthetic-release-bundle-ref";
  private static final String MANIFEST_HASH = "synthetic-manifest-hash";
  private static final String GENERATION_CONFIG_REVISION = "synthetic-generation-revision";

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
  @Autowired private WorldLifecycleCommandService lifecycleCommandService;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @MockitoBean(enforceOverride = true)
  private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;

  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  @Test
  void materializesEverySelectedLegacyRowAndExactRetryAddsNoRows() {
    long tenantId = uniquePositiveKey();
    long versionId = uniquePositiveKey();
    long gameInstanceId = uniquePositiveKey();
    TopologyFixture topology = insertSelectedTopology(tenantId, versionId);
    insertOutOfScopeTopology(tenantId, versionId + 1L);
    AuthoredRows authoredBefore = authoredRows(tenantId);
    PreparedWorldInstanceRequest request = request(tenantId, versionId, gameInstanceId);
    stubPublishedEvidence(tenantId, versionId);

    var prepared = lifecycleCommandService.prepareWorldInstance(request);

    assertThat(prepared.status()).isEqualTo("PREPARING");
    assertThat(prepared.lifecycleEpoch()).isEqualTo(1L);
    long worldInstanceId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "SELECT id FROM world_instance WHERE tenant_id = ? AND game_instance_id = ?",
                    tenantId,
                    gameInstanceId)
                .fetchOne(0, Long.class));
    long regionInstanceId =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "SELECT id FROM region_instance WHERE tenant_id = ? AND game_instance_id = ?",
                    tenantId,
                    gameInstanceId)
                .fetchOne(0, Long.class));
    assertThat(worldInstanceId).isPositive();
    assertThat(regionInstanceId).isPositive();
    assertThat(
            dsl.resultQuery(
                    "SELECT world_instance_id FROM region_instance WHERE id = ?", regionInstanceId)
                .fetchOne(0, Long.class))
        .isEqualTo(worldInstanceId);

    List<RuntimeZone> actualZones = runtimeZones(tenantId, gameInstanceId);
    assertThat(actualZones)
        .containsExactlyInAnyOrderElementsOf(
            topology.zones().stream()
                .map(
                    zone ->
                        new RuntimeZone(
                            tenantId,
                            gameInstanceId,
                            zone.id(),
                            zone.id(),
                            regionInstanceId,
                            zone.name()))
                .toList());

    List<RuntimeRoom> actualRooms = runtimeRooms(tenantId, gameInstanceId);
    assertThat(actualRooms)
        .containsExactlyInAnyOrderElementsOf(
            topology.rooms().stream()
                .map(
                    room ->
                        new RuntimeRoom(
                            tenantId,
                            gameInstanceId,
                            room.id(),
                            room.id(),
                            room.zoneId(),
                            room.name(),
                            room.description()))
                .toList());

    List<RuntimeExit> actualExits = runtimeExits(tenantId, gameInstanceId);
    assertThat(actualExits)
        .containsExactlyInAnyOrderElementsOf(
            topology.exits().stream()
                .map(
                    exit ->
                        new RuntimeExit(
                            tenantId,
                            gameInstanceId,
                            exit.fromRoomId(),
                            exit.toRoomId(),
                            exit.direction(),
                            exit.cost()))
                .toList());

    RuntimeRows firstRuntimeRows = runtimeRows(tenantId, gameInstanceId);
    assertThat(firstRuntimeRows.worlds()).hasSize(1);
    assertThat(firstRuntimeRows.regions()).hasSize(1);
    var retry = lifecycleCommandService.prepareWorldInstance(request);

    assertThat(retry).isEqualTo(prepared);
    assertThat(runtimeRows(tenantId, gameInstanceId)).isEqualTo(firstRuntimeRows);
    assertThat(authoredRows(tenantId)).isEqualTo(authoredBefore);
    verify(gameDesignClient, times(1)).getPublishedReleaseBundle(tenantId, versionId);
    verify(gameDesignClient, times(1)).getVersionAssetArtifactState(tenantId, versionId);
    verify(gameDesignClient, times(1)).getVersionState(tenantId, versionId);
  }

  @Test
  void rejectsSelectedRoomWhoseZoneBelongsToAnotherVersionBeforeRuntimeWrites() {
    long tenantId = uniquePositiveKey();
    long versionId = uniquePositiveKey();
    long gameInstanceId = uniquePositiveKey();
    long regionId = insertRegion(tenantId, versionId, "selected-region");
    insertZone(tenantId, versionId, regionId, "selected-zone");
    long wrongVersionZoneId = insertZone(tenantId, versionId + 1L, regionId, "other-version-zone");
    insertRoom(tenantId, versionId, wrongVersionZoneId, "mis-scoped-room", "authored");
    AuthoredRows authoredBefore = authoredRows(tenantId);
    stubPublishedEvidence(tenantId, versionId);

    Throwable failure =
        catchThrowable(
            () ->
                lifecycleCommandService.prepareWorldInstance(
                    request(tenantId, versionId, gameInstanceId)));

    assertThat(failure)
        .isNotNull()
        .hasMessageContaining("FAILED_PRECONDITION: INCOMPLETE_WORLD_TOPOLOGY")
        .hasMessageContaining("references a missing selected zone");
    assertNoRuntimeRows(tenantId, gameInstanceId);
    assertThat(authoredRows(tenantId)).isEqualTo(authoredBefore);
  }

  @Test
  void rejectsSelectedExitWhoseEndpointBelongsToAnotherVersionBeforeRuntimeWrites() {
    long tenantId = uniquePositiveKey();
    long versionId = uniquePositiveKey();
    long gameInstanceId = uniquePositiveKey();
    long regionId = insertRegion(tenantId, versionId, "selected-region");
    long zoneId = insertZone(tenantId, versionId, regionId, "selected-zone");
    long selectedRoomId = insertRoom(tenantId, versionId, zoneId, "selected-room", "selected");
    long otherVersionRoomId =
        insertRoom(tenantId, versionId + 1L, zoneId, "other-version-room", "unselected");
    insertExit(tenantId, versionId, selectedRoomId, otherVersionRoomId, "NORTH", 3);
    AuthoredRows authoredBefore = authoredRows(tenantId);
    stubPublishedEvidence(tenantId, versionId);

    Throwable failure =
        catchThrowable(
            () ->
                lifecycleCommandService.prepareWorldInstance(
                    request(tenantId, versionId, gameInstanceId)));

    assertThat(failure)
        .isNotNull()
        .hasMessageContaining("FAILED_PRECONDITION: INCOMPLETE_WORLD_TOPOLOGY")
        .hasMessageContaining("exit to room is not in the selected topology");
    assertNoRuntimeRows(tenantId, gameInstanceId);
    assertThat(authoredRows(tenantId)).isEqualTo(authoredBefore);
  }

  @Test
  void lateExitPersistenceFailureRollsBackWorldAndAllEarlierRuntimeRows() {
    long tenantId = uniquePositiveKey();
    long versionId = uniquePositiveKey();
    long gameInstanceId = uniquePositiveKey();
    TopologyFixture topology = insertSelectedTopology(tenantId, versionId);
    AuthoredRows authoredBefore = authoredRows(tenantId);
    stubPublishedEvidence(tenantId, versionId);
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String functionName = "w_test_late_exit_" + suffix;
    String triggerName = "t_test_late_exit_" + suffix;
    Throwable failure;
    try {
      createLateExitFailureTrigger(functionName, triggerName, tenantId, gameInstanceId, topology);
      failure =
          catchThrowable(
              () ->
                  lifecycleCommandService.prepareWorldInstance(
                      request(tenantId, versionId, gameInstanceId)));
    } finally {
      dsl.execute("DROP TRIGGER IF EXISTS " + triggerName + " ON room_instance_exit");
      dsl.execute("DROP FUNCTION IF EXISTS " + functionName + "()");
    }

    assertThat(failure).isNotNull();
    assertThat(exceptionMessages(failure)).contains("forced late runtime exit persistence failure");
    assertNoRuntimeRows(tenantId, gameInstanceId);
    assertThat(authoredRows(tenantId)).isEqualTo(authoredBefore);
  }

  private void stubPublishedEvidence(long tenantId, long versionId) {
    when(gameDesignClient.getPublishedReleaseBundle(tenantId, versionId))
        .thenReturn(
            GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(
                    PublishedReleaseBundle.newBuilder()
                        .setId(RELEASE_BUNDLE_ID)
                        .setVersionId(versionId)
                        .setAttestationSchemaVersion("v1")
                        .setManifestHash(MANIFEST_HASH)
                        .addRequiredManifestAssetKeys("manifest.json")
                        .setGenerationConfigRevision(GENERATION_CONFIG_REVISION)
                        .setPublishedReleaseBundleRef(RELEASE_BUNDLE_REF)
                        .build())
                .build());
    when(gameDesignClient.getVersionAssetArtifactState(tenantId, versionId))
        .thenReturn(
            GetVersionAssetArtifactStateResponse.newBuilder()
                .setArtifactState(
                    VersionAssetArtifactState.newBuilder()
                        .setTenantId(Long.toString(tenantId))
                        .setVersionId(versionId)
                        .setArtifactState(ArtifactState.ARTIFACT_STATE_PUBLISHED)
                        .setStateEpoch(4L)
                        .setManifestHash(MANIFEST_HASH)
                        .addExportedManifestAssetKeys("manifest.json")
                        .build())
                .build());
    when(gameDesignClient.getVersionState(tenantId, versionId))
        .thenReturn(
            GetVersionStateResponse.newBuilder()
                .setVersionState(
                    VersionStateSnapshot.newBuilder()
                        .setTenantId(Long.toString(tenantId))
                        .setVersionId(versionId)
                        .setVersionState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .setVersionStateEpoch(VERSION_STATE_EPOCH)
                        .build())
                .build());
  }

  private PreparedWorldInstanceRequest request(long tenantId, long versionId, long gameInstanceId) {
    return new PreparedWorldInstanceRequest(
        tenantId,
        gameInstanceId,
        versionId + 100L,
        "prepare-" + gameInstanceId,
        "descriptor-" + gameInstanceId,
        versionId,
        "script-patch-" + versionId,
        "{}",
        GENERATION_CONFIG_REVISION,
        RELEASE_BUNDLE_ID,
        RELEASE_BUNDLE_REF,
        VERSION_STATE_EPOCH);
  }

  private TopologyFixture insertSelectedTopology(long tenantId, long versionId) {
    long regionId = insertRegion(tenantId, versionId, "selected-region");
    long westZoneId = insertZone(tenantId, versionId, regionId, "West Wing");
    long eastZoneId = insertZone(tenantId, versionId, regionId, "East Wing");
    long antechamberId =
        insertRoom(tenantId, versionId, westZoneId, "Antechamber", "A quiet stone room.");
    long smithyId = insertRoom(tenantId, versionId, westZoneId, "Smithy", "A warm forge.");
    long gardenId = insertRoom(tenantId, versionId, eastZoneId, "Garden", "A walled garden.");
    List<ExitSpec> exits =
        List.of(
            new ExitSpec(antechamberId, smithyId, "NORTH", 2),
            new ExitSpec(smithyId, antechamberId, "SOUTH", 2),
            new ExitSpec(gardenId, antechamberId, "WEST", 4));
    for (ExitSpec exit : exits) {
      insertExit(
          tenantId, versionId, exit.fromRoomId(), exit.toRoomId(), exit.direction(), exit.cost());
    }
    return new TopologyFixture(
        List.of(new ZoneSpec(westZoneId, "West Wing"), new ZoneSpec(eastZoneId, "East Wing")),
        List.of(
            new RoomSpec(antechamberId, westZoneId, "Antechamber", "A quiet stone room."),
            new RoomSpec(smithyId, westZoneId, "Smithy", "A warm forge."),
            new RoomSpec(gardenId, eastZoneId, "Garden", "A walled garden.")),
        exits);
  }

  private void insertOutOfScopeTopology(long tenantId, long otherVersionId) {
    long regionId = insertRegion(tenantId, otherVersionId, "other-version-region");
    long zoneId = insertZone(tenantId, otherVersionId, regionId, "Other Version Zone");
    insertRoom(tenantId, otherVersionId, zoneId, "Other Version Room", "out of scope");
  }

  private long insertRegion(long tenantId, long versionId, String name) {
    return insertedId(
        "INSERT INTO region (tenant_id, version_id, name) VALUES (?, ?, ?) RETURNING id",
        tenantId,
        versionId,
        name);
  }

  private long insertZone(long tenantId, long versionId, long regionId, String name) {
    return insertedId(
        "INSERT INTO zone (tenant_id, version_id, region_id, name) VALUES (?, ?, ?, ?) RETURNING id",
        tenantId,
        versionId,
        regionId,
        name);
  }

  private long insertRoom(
      long tenantId, long versionId, long zoneId, String name, String description) {
    return insertedId(
        "INSERT INTO room (tenant_id, version_id, zone_id, name, description) "
            + "VALUES (?, ?, ?, ?, ?) RETURNING id",
        tenantId,
        versionId,
        zoneId,
        name,
        description);
  }

  private long insertExit(
      long tenantId, long versionId, long fromRoomId, long toRoomId, String direction, int cost) {
    return insertedId(
        "INSERT INTO room_exit (tenant_id, version_id, from_room_id, to_room_id, direction, cost) "
            + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
        tenantId,
        versionId,
        fromRoomId,
        toRoomId,
        direction,
        cost);
  }

  private long insertedId(String sql, Object... values) {
    Record row =
        Objects.requireNonNull(dsl.fetchOne(sql, values), "authored insert must return its row");
    return row.get("id", Long.class);
  }

  private AuthoredRows authoredRows(long tenantId) {
    return new AuthoredRows(
        authoredTableRows("region", tenantId),
        authoredTableRows("zone", tenantId),
        authoredTableRows("room", tenantId),
        authoredTableRows("room_exit", tenantId));
  }

  private List<Map<String, Object>> authoredTableRows(String tableName, long tenantId) {
    return dsl.fetch("SELECT * FROM " + tableName + " WHERE tenant_id = ? ORDER BY id", tenantId)
        .intoMaps();
  }

  private List<RuntimeZone> runtimeZones(long tenantId, long gameInstanceId) {
    return dsl.fetch(
            "SELECT tenant_id, game_instance_id, zone_instance_id, template_zone_id, "
                + "region_instance_id, name FROM zone_instance "
                + "WHERE tenant_id = ? OR game_instance_id = ? ORDER BY id",
            tenantId,
            gameInstanceId)
        .map(
            row ->
                new RuntimeZone(
                    row.get("tenant_id", Long.class),
                    row.get("game_instance_id", Long.class),
                    row.get("zone_instance_id", Long.class),
                    row.get("template_zone_id", Long.class),
                    row.get("region_instance_id", Long.class),
                    row.get("name", String.class)));
  }

  private List<RuntimeRoom> runtimeRooms(long tenantId, long gameInstanceId) {
    return dsl.fetch(
            "SELECT r.tenant_id, r.game_instance_id, r.room_instance_row_id, "
                + "r.template_room_id, z.template_zone_id, r.name, r.description "
                + "FROM room_instance r JOIN zone_instance z ON z.id = r.zone_instance_id "
                + "WHERE r.tenant_id = ? OR r.game_instance_id = ? ORDER BY r.id",
            tenantId,
            gameInstanceId)
        .map(
            row ->
                new RuntimeRoom(
                    row.get("tenant_id", Long.class),
                    row.get("game_instance_id", Long.class),
                    row.get("room_instance_row_id", Long.class),
                    row.get("template_room_id", Long.class),
                    row.get("template_zone_id", Long.class),
                    row.get("name", String.class),
                    row.get("description", String.class)));
  }

  private List<RuntimeExit> runtimeExits(long tenantId, long gameInstanceId) {
    return dsl.fetch(
            "SELECT e.tenant_id, e.game_instance_id, f.room_instance_row_id AS from_room_id, "
                + "t.room_instance_row_id AS to_room_id, e.direction, e.cost "
                + "FROM room_instance_exit e "
                + "JOIN room_instance f ON f.id = e.from_room_instance_record_id "
                + "JOIN room_instance t ON t.id = e.to_room_instance_record_id "
                + "WHERE e.tenant_id = ? OR e.game_instance_id = ? ORDER BY e.id",
            tenantId,
            gameInstanceId)
        .map(
            row ->
                new RuntimeExit(
                    row.get("tenant_id", Long.class),
                    row.get("game_instance_id", Long.class),
                    row.get("from_room_id", Long.class),
                    row.get("to_room_id", Long.class),
                    row.get("direction", String.class),
                    row.get("cost", Integer.class)));
  }

  private RuntimeRows runtimeRows(long tenantId, long gameInstanceId) {
    return new RuntimeRows(
        runtimeTableRows("world_instance", tenantId, gameInstanceId),
        runtimeTableRows("region_instance", tenantId, gameInstanceId),
        runtimeTableRows("zone_instance", tenantId, gameInstanceId),
        runtimeTableRows("room_instance", tenantId, gameInstanceId),
        runtimeTableRows("room_instance_exit", tenantId, gameInstanceId));
  }

  private List<Map<String, Object>> runtimeTableRows(
      String tableName, long tenantId, long gameInstanceId) {
    return dsl.fetch(
            "SELECT * FROM "
                + tableName
                + " WHERE tenant_id = ? OR game_instance_id = ? ORDER BY id",
            tenantId,
            gameInstanceId)
        .intoMaps();
  }

  private void assertNoRuntimeRows(long tenantId, long gameInstanceId) {
    RuntimeRows rows = runtimeRows(tenantId, gameInstanceId);
    assertThat(rows.worlds()).isEmpty();
    assertThat(rows.regions()).isEmpty();
    assertThat(rows.zones()).isEmpty();
    assertThat(rows.rooms()).isEmpty();
    assertThat(rows.exits()).isEmpty();
  }

  private void createLateExitFailureTrigger(
      String functionName,
      String triggerName,
      long tenantId,
      long gameInstanceId,
      TopologyFixture topology) {
    String functionSql =
        "CREATE FUNCTION "
            + functionName
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
            + "IF NEW.tenant_id = "
            + tenantId
            + " AND NEW.game_instance_id = "
            + gameInstanceId
            + " THEN "
            + "IF NOT EXISTS (SELECT 1 FROM world_instance WHERE tenant_id = "
            + tenantId
            + " AND game_instance_id = "
            + gameInstanceId
            + ") OR NOT EXISTS (SELECT 1 FROM region_instance WHERE tenant_id = "
            + tenantId
            + " AND game_instance_id = "
            + gameInstanceId
            + ") OR (SELECT count(*) FROM zone_instance WHERE tenant_id = "
            + tenantId
            + " AND game_instance_id = "
            + gameInstanceId
            + ") <> "
            + topology.zones().size()
            + " OR (SELECT count(*) FROM room_instance WHERE tenant_id = "
            + tenantId
            + " AND game_instance_id = "
            + gameInstanceId
            + ") <> "
            + topology.rooms().size()
            + " THEN RAISE EXCEPTION 'late exit trigger did not observe earlier runtime writes'; "
            + "END IF; RAISE EXCEPTION 'forced late runtime exit persistence failure'; "
            + "END IF; RETURN NEW; END; $$";
    dsl.execute(functionSql);
    dsl.execute(
        "CREATE TRIGGER "
            + triggerName
            + " BEFORE INSERT ON room_instance_exit FOR EACH ROW EXECUTE FUNCTION "
            + functionName
            + "()");
  }

  private String exceptionMessages(Throwable failure) {
    StringBuilder messages = new StringBuilder();
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current.getMessage() != null) {
        messages.append(current.getMessage()).append('\n');
      }
    }
    return messages.toString();
  }

  private static long uniquePositiveKey() {
    return 8_000_000_000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L);
  }

  private record ZoneSpec(long id, String name) {}

  private record RoomSpec(long id, long zoneId, String name, String description) {}

  private record ExitSpec(long fromRoomId, long toRoomId, String direction, int cost) {}

  private record TopologyFixture(
      List<ZoneSpec> zones, List<RoomSpec> rooms, List<ExitSpec> exits) {}

  private record AuthoredRows(
      List<Map<String, Object>> regions,
      List<Map<String, Object>> zones,
      List<Map<String, Object>> rooms,
      List<Map<String, Object>> exits) {}

  private record RuntimeZone(
      long tenantId,
      long gameInstanceId,
      long zoneInstanceId,
      long templateZoneId,
      long regionInstanceId,
      String name) {}

  private record RuntimeRoom(
      long tenantId,
      long gameInstanceId,
      long roomInstanceRowId,
      long templateRoomId,
      long templateZoneId,
      String name,
      String description) {}

  private record RuntimeExit(
      long tenantId,
      long gameInstanceId,
      long fromRoomId,
      long toRoomId,
      String direction,
      int cost) {}

  private record RuntimeRows(
      List<Map<String, Object>> worlds,
      List<Map<String, Object>> regions,
      List<Map<String, Object>> zones,
      List<Map<String, Object>> rooms,
      List<Map<String, Object>> exits) {}
}
