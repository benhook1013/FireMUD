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
  private static final RunOwnedWorldFixedPresetResources.Definition DEMO_PRESET =
      RunOwnedWorldFixedPresetResources.definition();
  private static final long STARTER_ROOM_ID = 1021L;
  private static final long SECONDARY_ROOM_ID = 2045L;

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
        ensureRoom(
            zone,
            STARTER_ROOM_ID,
            DEMO_PRESET.rooms().get(0).name(),
            DEMO_PRESET.rooms().get(0).description());
    Room secondaryRoom =
        ensureRoom(
            zone,
            SECONDARY_ROOM_ID,
            DEMO_PRESET.rooms().get(1).name(),
            DEMO_PRESET.rooms().get(1).description());
    ensureRoomExit(starterRoom, secondaryRoom, DEMO_PRESET.exits().get(0).direction());
    ensureRoomExit(secondaryRoom, starterRoom, DEMO_PRESET.exits().get(1).direction());
  }

  private Region ensureDemoRegion() {
    Region region =
        regionRepository
            .findFirstByTenantIdAndVersionIdAndShardIdAndName(
                DEMO_TENANT_ID, DEMO_VERSION_ID, 0, DEMO_PRESET.region().name())
            .orElseGet(Region::new);
    region.setTenantId(DEMO_TENANT_ID);
    region.setVersionId(DEMO_VERSION_ID);
    region.setShardId(0);
    region.setName(DEMO_PRESET.region().name());
    if (region.getSpacingMultiplier() == null) {
      region.setSpacingMultiplier(DEMO_PRESET.region().spacingMultiplier());
    }
    if (region.getGenerationSeed() == null) {
      region.setGenerationSeed(DEMO_PRESET.region().generationSeed());
    }
    return regionRepository.save(region);
  }

  private Zone ensureDemoZone(Region region) {
    Zone zone =
        zoneRepository
            .findFirstByTenantIdAndVersionIdAndRegionIdAndName(
                DEMO_TENANT_ID, DEMO_VERSION_ID, region.getId(), DEMO_PRESET.zone().name())
            .orElseGet(Zone::new);
    zone.setTenantId(DEMO_TENANT_ID);
    zone.setVersionId(DEMO_VERSION_ID);
    zone.setRegion(region);
    zone.setName(DEMO_PRESET.zone().name());
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
