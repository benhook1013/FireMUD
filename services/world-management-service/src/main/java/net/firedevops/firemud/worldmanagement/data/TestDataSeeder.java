package net.firedevops.firemud.worldmanagement.data;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
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
  private static final long STARTER_ROOM_INSTANCE_ID = 1021L;
  private static final long SECONDARY_ROOM_INSTANCE_ID = 2045L;

  private final RegionRepository regionRepository;
  private final ZoneRepository zoneRepository;
  private final RoomRepository roomRepository;
  private final RoomExitRepository roomExitRepository;
  private final WorldInstanceRepository worldInstanceRepository;
  private final RegionInstanceRepository regionInstanceRepository;
  private final ZoneInstanceRepository zoneInstanceRepository;
  private final RoomInstanceRepository roomInstanceRepository;
  private final RoomInstanceExitRepository roomInstanceExitRepository;
  private final GameplayCatalogProperties gameplayCatalogProperties;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (regionRepository.count() == 0) {
      Region region = new Region();
      region.setTenantId(1L);
      region.setVersionId(1L);
      region.setShardId(0);
      region.setName("Demo Region");
      regionRepository.save(region);

      Zone zone = new Zone();
      zone.setTenantId(1L);
      zone.setVersionId(1L);
      zone.setRegion(region);
      zone.setName("Demo Zone");
      zoneRepository.save(zone);

      Room room1 = new Room();
      room1.setTenantId(1L);
      room1.setVersionId(1L);
      room1.setZone(zone);
      room1.setName("Candle-lit Antechamber");
      room1.setDescription(
          "Stalactites drip along the northern wall while a faint draft carries the smell of damp earth from the lower tunnels. Torches flicker in alcoves, casting motion into the shadowy archway to the north.");
      roomRepository.save(room1);

      Room room2 = new Room();
      room2.setTenantId(1L);
      room2.setVersionId(1L);
      room2.setZone(zone);
      room2.setName("Smith's Annex");
      room2.setDescription(
          "An anvil, banked coals, and orderly tool racks mark this alcove as a working annex off the starter chamber.");
      roomRepository.save(room2);

      RoomExit exit = new RoomExit();
      exit.setTenantId(1L);
      exit.setVersionId(1L);
      exit.setFromRoom(room1);
      exit.setToRoom(room2);
      exit.setDirection("NORTH");
      roomExitRepository.save(exit);
    }

    ensureRuntimeTopologyForCatalogTargets();
  }

  private void ensureRuntimeTopologyForCatalogTargets() {
    List<GameplayCatalogProperties.World> worlds = gameplayCatalogProperties.getWorlds();
    if (worlds == null) {
      return;
    }
    for (GameplayCatalogProperties.World world : worlds) {
      if (world == null || world.getRealms() == null) {
        continue;
      }
      for (GameplayCatalogProperties.Realm realm : world.getRealms()) {
        if (realm == null || realm.getTenantId() <= 0L || realm.getGameInstanceId() <= 0L) {
          continue;
        }
        ensureRuntimeTopology(realm.getTenantId(), realm.getGameInstanceId());
      }
    }
  }

  private void ensureRuntimeTopology(long tenantId, long gameInstanceId) {
    if (worldInstanceRepository
        .findByTenantIdAndGameInstanceId(tenantId, gameInstanceId)
        .isPresent()) {
      return;
    }

    WorldInstance worldInstance = new WorldInstance();
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
    worldInstance.setCreatedAt(Instant.now());
    worldInstance.setUpdatedAt(Instant.now());
    WorldInstance savedWorldInstance = worldInstanceRepository.save(worldInstance);

    RegionInstance regionInstance = new RegionInstance();
    regionInstance.setTenantId(tenantId);
    regionInstance.setGameInstanceId(gameInstanceId);
    regionInstance.setWorldInstance(savedWorldInstance);
    regionInstance.setShardId(0);
    regionInstance.setName("Starter Region");
    regionInstance.setGenerationSeed(gameInstanceId);
    regionInstance.setGeneratorType("SimpleDungeonGenerator");
    regionInstance.setGeneratorParams("{}");
    regionInstance.setSpacingMultiplier(1.0);
    RegionInstance savedRegionInstance = regionInstanceRepository.save(regionInstance);

    Map<Long, ZoneInstance> zoneInstancesByTemplateId = new LinkedHashMap<>();
    for (Zone templateZone : zoneRepository.findByTenantIdAndVersionIdOrderByIdAsc(tenantId, 1L)) {
      ZoneInstance zoneInstance = new ZoneInstance();
      zoneInstance.setTenantId(tenantId);
      zoneInstance.setGameInstanceId(gameInstanceId);
      zoneInstance.setZoneInstanceId(templateZone.getId());
      zoneInstance.setTemplateZoneId(templateZone.getId());
      zoneInstance.setRegionInstance(savedRegionInstance);
      zoneInstance.setName(templateZone.getName());
      ZoneInstance savedZoneInstance = zoneInstanceRepository.save(zoneInstance);
      zoneInstancesByTemplateId.put(templateZone.getId(), savedZoneInstance);
    }

    List<Room> templateRooms = roomRepository.findByTenantIdAndVersionIdOrderByIdAsc(tenantId, 1L);
    Map<Long, RoomInstance> roomInstancesByTemplateId = new LinkedHashMap<>();
    for (Room templateRoom : templateRooms) {
      ZoneInstance zoneInstance = zoneInstancesByTemplateId.get(templateRoom.getZone().getId());
      if (zoneInstance == null) {
        continue;
      }
      RoomInstance roomInstance = new RoomInstance();
      roomInstance.setTenantId(tenantId);
      roomInstance.setGameInstanceId(gameInstanceId);
      roomInstance.setRoomInstanceId(
          roomInstanceIdForTemplateOrder(roomInstancesByTemplateId.size()));
      roomInstance.setTemplateRoomId(templateRoom.getId());
      roomInstance.setRegionInstance(savedRegionInstance);
      roomInstance.setZoneInstance(zoneInstance);
      roomInstance.setName(templateRoom.getName());
      roomInstance.setDescription(templateRoom.getDescription());
      roomInstance.setNameLocalizedVariantsJson(templateRoom.getNameLocalizedVariantsJson());
      roomInstance.setDescriptionLocalizedVariantsJson(
          templateRoom.getDescriptionLocalizedVariantsJson());
      RoomInstance savedRoomInstance = roomInstanceRepository.save(roomInstance);
      roomInstancesByTemplateId.put(templateRoom.getId(), savedRoomInstance);
    }

    for (RoomExit templateExit :
        roomExitRepository.findByTenantIdAndVersionIdOrderByIdAsc(tenantId, 1L)) {
      RoomInstance fromRoomInstance =
          roomInstancesByTemplateId.get(templateExit.getFromRoom().getId());
      RoomInstance toRoomInstance = roomInstancesByTemplateId.get(templateExit.getToRoom().getId());
      if (fromRoomInstance == null || toRoomInstance == null) {
        continue;
      }
      RoomInstanceExit roomInstanceExit = new RoomInstanceExit();
      roomInstanceExit.setTenantId(tenantId);
      roomInstanceExit.setGameInstanceId(gameInstanceId);
      roomInstanceExit.setFromRoomInstance(fromRoomInstance);
      roomInstanceExit.setToRoomInstance(toRoomInstance);
      roomInstanceExit.setDirection(templateExit.getDirection());
      roomInstanceExit.setCost(templateExit.getCost());
      roomInstanceExitRepository.save(roomInstanceExit);
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
