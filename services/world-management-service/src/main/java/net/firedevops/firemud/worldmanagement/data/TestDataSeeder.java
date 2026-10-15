package net.firedevops.firemud.worldmanagement.data;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.worldmanagement.entity.Region;
import net.firedevops.firemud.worldmanagement.entity.Room;
import net.firedevops.firemud.worldmanagement.entity.RoomExit;
import net.firedevops.firemud.worldmanagement.entity.Zone;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Seeds deterministic versioned world fixtures when local compose explicitly enables them. */
@Component
@ConditionalOnProperty(
    prefix = "firemud.smoke.seed-demo-runtime",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
@RequiredArgsConstructor
public class TestDataSeeder implements ApplicationRunner {
  private static final long DEMO_TENANT_ID = 1L;
  private static final long DEMO_VERSION_ID = 1L;
  private static final String DEMO_REGION_NAME = "Demo Region";
  private static final String DEMO_ZONE_NAME = "Demo Zone";
  private static final long STARTER_ROOM_ID = 1021L;
  private static final String STARTER_ROOM_NAME = "Candle-lit Antechamber";
  private static final String STARTER_ROOM_DESCRIPTION =
      "Stalactites drip along the northern wall while a faint draft carries the smell of damp earth from the lower tunnels. Torches flicker in alcoves, casting motion into the shadowy archway to the north.";
  private static final long SECONDARY_ROOM_ID = 2045L;
  private static final String SECONDARY_ROOM_NAME = "Smith's Annex";
  private static final String SECONDARY_ROOM_DESCRIPTION =
      "An anvil, banked coals, and orderly tool racks mark this alcove as a working annex off the starter chamber.";

  private final RegionRepository regionRepository;
  private final ZoneRepository zoneRepository;
  private final RoomRepository roomRepository;
  private final RoomExitRepository roomExitRepository;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    ensureDemoTopology();
  }

  private void ensureDemoTopology() {
    Region region = ensureDemoRegion();
    Zone zone = ensureDemoZone(region);
    Room starterRoom =
        ensureRoom(zone, STARTER_ROOM_ID, STARTER_ROOM_NAME, STARTER_ROOM_DESCRIPTION);
    Room secondaryRoom =
        ensureRoom(zone, SECONDARY_ROOM_ID, SECONDARY_ROOM_NAME, SECONDARY_ROOM_DESCRIPTION);
    ensureRoomExit(starterRoom, secondaryRoom, "NORTH");
    ensureRoomExit(secondaryRoom, starterRoom, "SOUTH");
  }

  private Region ensureDemoRegion() {
    Region region =
        regionRepository
            .findFirstByTenantIdAndVersionIdAndShardIdAndName(
                DEMO_TENANT_ID, DEMO_VERSION_ID, 0, DEMO_REGION_NAME)
            .orElseGet(Region::new);
    region.setTenantId(DEMO_TENANT_ID);
    region.setVersionId(DEMO_VERSION_ID);
    region.setShardId(0);
    region.setName(DEMO_REGION_NAME);
    if (region.getSpacingMultiplier() == null) {
      region.setSpacingMultiplier(1.0);
    }
    if (region.getGenerationSeed() == null) {
      region.setGenerationSeed(0L);
    }
    return regionRepository.save(region);
  }

  private Zone ensureDemoZone(Region region) {
    Zone zone =
        zoneRepository
            .findFirstByTenantIdAndVersionIdAndRegionIdAndName(
                DEMO_TENANT_ID, DEMO_VERSION_ID, region.getId(), DEMO_ZONE_NAME)
            .orElseGet(Zone::new);
    zone.setTenantId(DEMO_TENANT_ID);
    zone.setVersionId(DEMO_VERSION_ID);
    zone.setRegion(region);
    zone.setName(DEMO_ZONE_NAME);
    return zoneRepository.save(zone);
  }

  private Room ensureRoom(Zone zone, long roomId, String name, String description) {
    Room room =
        roomRepository
            .findFirstByTenantIdAndVersionIdAndZoneIdAndName(
                DEMO_TENANT_ID, DEMO_VERSION_ID, zone.getId(), name)
            .orElse(null);
    if (room != null && !Long.valueOf(roomId).equals(room.getId())) {
      throw new IllegalStateException(
          "DEMO_TEMPLATE_ROOM_ID_MISMATCH: "
              + name
              + " is persisted with room id "
              + room.getId()
              + " instead of "
              + roomId);
    }
    if (room == null) {
      room = new Room();
      room.setId(roomId);
    }
    room.setTenantId(DEMO_TENANT_ID);
    room.setVersionId(DEMO_VERSION_ID);
    room.setZone(zone);
    room.setName(name);
    room.setDescription(description);
    room.setNameLocalizedVariantsJson(null);
    room.setDescriptionLocalizedVariantsJson(null);
    return roomRepository.saveSeededWithExplicitId(room);
  }

  private void ensureRoomExit(Room fromRoom, Room toRoom, String direction) {
    RoomExit exit =
        roomExitRepository
            .findFirstByTenantIdAndVersionIdAndFromRoomIdAndToRoomIdAndDirection(
                DEMO_TENANT_ID, DEMO_VERSION_ID, fromRoom.getId(), toRoom.getId(), direction)
            .orElseGet(RoomExit::new);
    exit.setTenantId(DEMO_TENANT_ID);
    exit.setVersionId(DEMO_VERSION_ID);
    exit.setFromRoom(fromRoom);
    exit.setToRoom(toRoom);
    exit.setDirection(direction);
    exit.setCost(1);
    roomExitRepository.save(exit);
  }
}
