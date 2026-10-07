package net.firedevops.firemud.worldmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.entity.Region;
import net.firedevops.firemud.worldmanagement.entity.Room;
import net.firedevops.firemud.worldmanagement.entity.Zone;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import org.jooq.DSLContext;
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

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = {"spring.grpc.server.port=0", "firemud.smoke.seed-demo-runtime.enabled=false"})
class RoomRepositoryPostgresIntegrationTest {
  private static final long TENANT_ID = 82001L;
  private static final long VERSION_ID = 77L;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(
          PostgresBackedServiceTestSupport.postgresImage("postgres:16-alpine"));

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "world_management_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private RegionRepository regionRepository;
  @Autowired private RoomRepository roomRepository;
  @Autowired private ZoneRepository zoneRepository;
  @Autowired private DSLContext dsl;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;
  @MockitoBean private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;
  @MockitoBean private EntityManagementClient entityManagementClient;
  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;

  @Test
  void seededRoomIdentityInsertAdvancesRenamedSequenceAndRejectsForeignCollision() {
    dsl.execute("ALTER SEQUENCE room_id_seq RENAME TO room_id_seq_fixture_rename");
    Region region = new Region();
    region.setTenantId(TENANT_ID);
    region.setVersionId(VERSION_ID);
    region.setShardId(0);
    region.setName("Explicit Room ID Fixture");
    region.setGenerationSeed(0L);
    region.setSpacingMultiplier(1.0);
    Region savedRegion = regionRepository.save(region);

    Zone zone = new Zone();
    zone.setTenantId(TENANT_ID);
    zone.setVersionId(VERSION_ID);
    zone.setRegion(savedRegion);
    zone.setName("Explicit Room ID Fixture Zone");
    Zone savedZone = zoneRepository.save(zone);

    Room starter = room(1021L, TENANT_ID, savedZone, "Candle-lit Antechamber");
    Room secondary = room(2045L, TENANT_ID, savedZone, "Smith's Annex");
    Room savedStarter = roomRepository.saveSeededWithExplicitId(starter);
    Room savedSecondary = roomRepository.saveSeededWithExplicitId(secondary);

    assertThat(savedStarter.getId()).isEqualTo(1021L);
    assertThat(savedStarter.getTenantId()).isEqualTo(TENANT_ID);
    assertThat(savedStarter.getVersionId()).isEqualTo(VERSION_ID);
    assertThat(savedStarter.getZone().getId()).isEqualTo(savedZone.getId());
    assertThat(savedSecondary.getId()).isEqualTo(2045L);
    assertThat(savedSecondary.getTenantId()).isEqualTo(TENANT_ID);
    assertThat(savedSecondary.getVersionId()).isEqualTo(VERSION_ID);
    assertThat(savedSecondary.getZone().getId()).isEqualTo(savedZone.getId());

    Room ordinaryRoom = room(null, TENANT_ID, savedZone, "Ordinary Future Room");
    Room savedOrdinaryRoom = roomRepository.save(ordinaryRoom);
    assertThat(savedOrdinaryRoom.getId()).isGreaterThan(2045L);

    Room conflictingRoom = room(1021L, TENANT_ID + 1, savedZone, "Foreign Template Room");
    assertThatThrownBy(() -> roomRepository.saveSeededWithExplicitId(conflictingRoom))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("SEED_ROOM_ID_CONFLICT:");
    assertThat(roomRepository.findById(1021L))
        .get()
        .extracting(Room::getTenantId, Room::getVersionId, Room::getName)
        .containsExactly(TENANT_ID, VERSION_ID, "Candle-lit Antechamber");
  }

  private Room room(Long id, long tenantId, Zone zone, String name) {
    Room room = new Room();
    room.setId(id);
    room.setTenantId(tenantId);
    room.setVersionId(VERSION_ID);
    room.setZone(zone);
    room.setName(name);
    room.setDescription("Integration fixture room.");
    return room;
  }
}
