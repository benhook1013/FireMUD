package net.firedevops.firemud.worldmanagement.data;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.worldmanagement.config.SmokeDemoRuntimeSeedProperties;
import net.firedevops.firemud.worldmanagement.entity.Region;
import net.firedevops.firemud.worldmanagement.entity.RegionInstance;
import net.firedevops.firemud.worldmanagement.entity.Room;
import net.firedevops.firemud.worldmanagement.entity.RoomExit;
import net.firedevops.firemud.worldmanagement.entity.RoomInstance;
import net.firedevops.firemud.worldmanagement.entity.RoomInstanceExit;
import net.firedevops.firemud.worldmanagement.entity.WorldInstance;
import net.firedevops.firemud.worldmanagement.entity.Zone;
import net.firedevops.firemud.worldmanagement.entity.ZoneInstance;
import net.firedevops.firemud.worldmanagement.repository.RegionInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomInstanceExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Seeds deterministic world/runtime fixtures when local compose explicitly enables them. */
@Component
@ConditionalOnProperty(
    prefix = "firemud.smoke.seed-demo-runtime",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Repositories are injected for seeding and not exposed")
public class TestDataSeeder implements ApplicationRunner {
  private static final long DEMO_TENANT_ID = 1L;
  private static final long DEMO_VERSION_ID = 1L;
  private static final long STARTER_ROOM_INSTANCE_ID = 1021L;
  private static final long SECONDARY_ROOM_INSTANCE_ID = 2045L;
  private static final String DEMO_REGION_NAME = "Demo Region";
  private static final String DEMO_ZONE_NAME = "Demo Zone";
  private static final String STARTER_ROOM_NAME = "Candle-lit Antechamber";
  private static final String STARTER_ROOM_DESCRIPTION =
      "Stalactites drip along the northern wall while a faint draft carries the smell of damp earth from the lower tunnels. Torches flicker in alcoves, casting motion into the shadowy archway to the north.";
  private static final String SECONDARY_ROOM_NAME = "Smith's Annex";
  private static final String SECONDARY_ROOM_DESCRIPTION =
      "An anvil, banked coals, and orderly tool racks mark this alcove as a working annex off the starter chamber.";

  private final RegionRepository regionRepository;
  private final ZoneRepository zoneRepository;
  private final RoomRepository roomRepository;
  private final RoomExitRepository roomExitRepository;
  private final WorldInstanceRepository worldInstanceRepository;
  private final RegionInstanceRepository regionInstanceRepository;
  private final ZoneInstanceRepository zoneInstanceRepository;
  private final RoomInstanceRepository roomInstanceRepository;
  private final RoomInstanceExitRepository roomInstanceExitRepository;
  private final SmokeDemoRuntimeSeedProperties smokeDemoRuntimeSeedProperties;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    ensureDemoTopology();
    ensureRuntimeTopologyForSeedTargets();
  }

  private void ensureDemoTopology() {
    Region region = ensureDemoRegion();
    Zone zone = ensureDemoZone(region);
    Room starterRoom = ensureRoom(zone, STARTER_ROOM_NAME, STARTER_ROOM_DESCRIPTION);
    Room secondaryRoom = ensureRoom(zone, SECONDARY_ROOM_NAME, SECONDARY_ROOM_DESCRIPTION);
    ensureRoomExit(starterRoom, secondaryRoom, "NORTH");
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

  private Room ensureRoom(Zone zone, String name, String description) {
    Room room =
        roomRepository
            .findFirstByTenantIdAndVersionIdAndZoneIdAndName(
                DEMO_TENANT_ID, DEMO_VERSION_ID, zone.getId(), name)
            .orElseGet(Room::new);
    room.setTenantId(DEMO_TENANT_ID);
    room.setVersionId(DEMO_VERSION_ID);
    room.setZone(zone);
    room.setName(name);
    room.setDescription(description);
    room.setNameLocalizedVariantsJson(null);
    room.setDescriptionLocalizedVariantsJson(null);
    return roomRepository.save(room);
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

  private void ensureRuntimeTopologyForSeedTargets() {
    List<SmokeDemoRuntimeSeedProperties.RuntimeTargetSeed> targets =
        smokeDemoRuntimeSeedProperties.getTargets();
    if (targets == null) {
      return;
    }
    for (SmokeDemoRuntimeSeedProperties.RuntimeTargetSeed target : targets) {
      if (target == null || target.getTenantId() <= 0L || target.getGameInstanceId() <= 0L) {
        continue;
      }
      ensureRuntimeTopology(target.getTenantId(), target.getGameInstanceId());
    }
  }

  private void ensureRuntimeTopology(long tenantId, long gameInstanceId) {
    WorldInstance savedWorldInstance = ensureWorldInstance(tenantId, gameInstanceId);
    RegionInstance savedRegionInstance =
        ensureRegionInstance(tenantId, gameInstanceId, savedWorldInstance);

    Map<Long, ZoneInstance> zoneInstancesByTemplateId = new LinkedHashMap<>();
    for (Zone templateZone :
        zoneRepository.findByTenantIdAndVersionIdOrderByIdAsc(DEMO_TENANT_ID, DEMO_VERSION_ID)) {
      ZoneInstance savedZoneInstance =
          ensureZoneInstance(tenantId, gameInstanceId, savedRegionInstance, templateZone);
      zoneInstancesByTemplateId.put(templateZone.getId(), savedZoneInstance);
    }

    List<Room> templateRooms =
        roomRepository.findByTenantIdAndVersionIdOrderByIdAsc(DEMO_TENANT_ID, DEMO_VERSION_ID);
    Map<Long, RoomInstance> roomInstancesByRoomInstanceId = new HashMap<>();
    for (RoomInstance existingRoomInstance :
        roomInstanceRepository.findByTenantIdAndGameInstanceIdOrderByRoomInstanceIdAsc(
            tenantId, gameInstanceId)) {
      roomInstancesByRoomInstanceId.put(
          existingRoomInstance.getRoomInstanceId(), existingRoomInstance);
    }
    Map<Long, RoomInstance> roomInstancesByTemplateId = new LinkedHashMap<>();
    for (int index = 0; index < templateRooms.size(); index++) {
      Room templateRoom = templateRooms.get(index);
      ZoneInstance zoneInstance = zoneInstancesByTemplateId.get(templateRoom.getZone().getId());
      if (zoneInstance == null) {
        continue;
      }
      RoomInstance savedRoomInstance =
          ensureRoomInstance(
              tenantId,
              gameInstanceId,
              savedRegionInstance,
              zoneInstance,
              templateRoom,
              roomInstanceIdForTemplateOrder(index),
              roomInstancesByRoomInstanceId);
      roomInstancesByTemplateId.put(templateRoom.getId(), savedRoomInstance);
    }

    Map<Long, List<RoomInstanceExit>> exitsByFromRoomInstanceId = new HashMap<>();
    for (RoomExit templateExit :
        roomExitRepository.findByTenantIdAndVersionIdOrderByIdAsc(
            DEMO_TENANT_ID, DEMO_VERSION_ID)) {
      RoomInstance fromRoomInstance =
          roomInstancesByTemplateId.get(templateExit.getFromRoom().getId());
      RoomInstance toRoomInstance = roomInstancesByTemplateId.get(templateExit.getToRoom().getId());
      if (fromRoomInstance == null || toRoomInstance == null) {
        continue;
      }
      ensureRoomInstanceExit(
          tenantId,
          gameInstanceId,
          fromRoomInstance,
          toRoomInstance,
          templateExit,
          exitsByFromRoomInstanceId);
    }
  }

  private WorldInstance ensureWorldInstance(long tenantId, long gameInstanceId) {
    WorldInstance worldInstance =
        worldInstanceRepository
            .findByTenantIdAndGameInstanceId(tenantId, gameInstanceId)
            .orElseGet(WorldInstance::new);
    if (worldInstance.getCreatedAt() == null) {
      worldInstance.setCreatedAt(Instant.now());
    }
    worldInstance.setTenantId(tenantId);
    worldInstance.setGameInstanceId(gameInstanceId);
    worldInstance.setGameTemplateId(1L);
    worldInstance.setControlPlaneRequestId("dev-bootstrap:" + gameInstanceId);
    worldInstance.setLaunchDescriptorId("ld-dev-bootstrap-" + gameInstanceId);
    worldInstance.setVersionId(1L);
    worldInstance.setScriptPatchVersion(null);
    worldInstance.setRuntimeFlagsJson("{}");
    worldInstance.setGenerationConfigRevision("dev-bootstrap");
    worldInstance.setReleaseBundleId(1L);
    worldInstance.setPublishedReleaseBundleRef("prb:dev:" + tenantId + ":1:1");
    worldInstance.setVersionStateEpoch(1L);
    worldInstance.setLifecycleEpoch(2L);
    worldInstance.setStatus("ACTIVE");
    worldInstance.setUpdatedAt(Instant.now());
    if (worldInstance.getRowVersion() == null) {
      worldInstance.setRowVersion(0L);
    }
    return worldInstanceRepository.save(worldInstance);
  }

  private RegionInstance ensureRegionInstance(
      long tenantId, long gameInstanceId, WorldInstance savedWorldInstance) {
    RegionInstance regionInstance =
        regionInstanceRepository.findByTenantIdAndGameInstanceId(tenantId, gameInstanceId).stream()
            .findFirst()
            .orElseGet(RegionInstance::new);
    regionInstance.setTenantId(tenantId);
    regionInstance.setGameInstanceId(gameInstanceId);
    regionInstance.setWorldInstance(savedWorldInstance);
    regionInstance.setShardId(0);
    regionInstance.setName("Starter Region");
    regionInstance.setGenerationSeed(gameInstanceId);
    regionInstance.setGeneratorType("SimpleDungeonGenerator");
    regionInstance.setGeneratorParams("{}");
    regionInstance.setSpacingMultiplier(1.0);
    return regionInstanceRepository.save(regionInstance);
  }

  private ZoneInstance ensureZoneInstance(
      long tenantId, long gameInstanceId, RegionInstance savedRegionInstance, Zone templateZone) {
    ZoneInstance zoneInstance =
        zoneInstanceRepository
            .findByTenantIdAndGameInstanceIdAndZoneInstanceId(
                tenantId, gameInstanceId, templateZone.getId())
            .orElseGet(ZoneInstance::new);
    zoneInstance.setTenantId(tenantId);
    zoneInstance.setGameInstanceId(gameInstanceId);
    zoneInstance.setZoneInstanceId(templateZone.getId());
    zoneInstance.setTemplateZoneId(templateZone.getId());
    zoneInstance.setRegionInstance(savedRegionInstance);
    zoneInstance.setName(templateZone.getName());
    return zoneInstanceRepository.save(zoneInstance);
  }

  private RoomInstance ensureRoomInstance(
      long tenantId,
      long gameInstanceId,
      RegionInstance savedRegionInstance,
      ZoneInstance zoneInstance,
      Room templateRoom,
      long roomInstanceId,
      Map<Long, RoomInstance> roomInstancesByRoomInstanceId) {
    RoomInstance roomInstance =
        roomInstancesByRoomInstanceId.getOrDefault(roomInstanceId, new RoomInstance());
    roomInstance.setTenantId(tenantId);
    roomInstance.setGameInstanceId(gameInstanceId);
    roomInstance.setRoomInstanceId(roomInstanceId);
    roomInstance.setTemplateRoomId(templateRoom.getId());
    roomInstance.setRegionInstance(savedRegionInstance);
    roomInstance.setZoneInstance(zoneInstance);
    roomInstance.setName(templateRoom.getName());
    roomInstance.setDescription(templateRoom.getDescription());
    roomInstance.setNameLocalizedVariantsJson(templateRoom.getNameLocalizedVariantsJson());
    roomInstance.setDescriptionLocalizedVariantsJson(
        templateRoom.getDescriptionLocalizedVariantsJson());
    RoomInstance savedRoomInstance = roomInstanceRepository.save(roomInstance);
    roomInstancesByRoomInstanceId.put(roomInstanceId, savedRoomInstance);
    return savedRoomInstance;
  }

  private void ensureRoomInstanceExit(
      long tenantId,
      long gameInstanceId,
      RoomInstance fromRoomInstance,
      RoomInstance toRoomInstance,
      RoomExit templateExit,
      Map<Long, List<RoomInstanceExit>> exitsByFromRoomInstanceId) {
    List<RoomInstanceExit> existingExits =
        exitsByFromRoomInstanceId.computeIfAbsent(
            fromRoomInstance.getId(),
            ignored ->
                roomInstanceExitRepository.findByTenantIdAndGameInstanceIdAndFromRoomInstanceId(
                    tenantId, gameInstanceId, fromRoomInstance.getId()));
    RoomInstanceExit roomInstanceExit =
        existingExits.stream()
            .filter(
                existingExit ->
                    templateExit.getDirection().equals(existingExit.getDirection())
                        && existingExit.getToRoomInstance() != null
                        && toRoomInstance.getId().equals(existingExit.getToRoomInstance().getId()))
            .findFirst()
            .orElseGet(RoomInstanceExit::new);
    roomInstanceExit.setTenantId(tenantId);
    roomInstanceExit.setGameInstanceId(gameInstanceId);
    roomInstanceExit.setFromRoomInstance(fromRoomInstance);
    roomInstanceExit.setToRoomInstance(toRoomInstance);
    roomInstanceExit.setDirection(templateExit.getDirection());
    roomInstanceExit.setCost(templateExit.getCost());
    RoomInstanceExit savedRoomInstanceExit = roomInstanceExitRepository.save(roomInstanceExit);
    if (roomInstanceExit.getId() == null) {
      existingExits.add(savedRoomInstanceExit);
    }
  }

  private long roomInstanceIdForTemplateOrder(int index) {
    if (index == 0) {
      return STARTER_ROOM_INSTANCE_ID;
    }
    if (index == 1) {
      return SECONDARY_ROOM_INSTANCE_ID;
    }
    return SECONDARY_ROOM_INSTANCE_ID + index;
  }
}
