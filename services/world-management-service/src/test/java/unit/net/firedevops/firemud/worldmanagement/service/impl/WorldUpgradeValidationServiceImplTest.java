package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.WorldInstance;
import net.firedevops.firemud.worldmanagement.repository.RegionInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomInstanceExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldEventRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneInstanceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WorldUpgradeValidationServiceImplTest {
  @Test
  void validateWorldUpgradeMappingsReportsCompatibleWhenTopologyAndStatusAreEligible() {
    WorldInstanceRepository worldInstanceRepository = Mockito.mock(WorldInstanceRepository.class);
    RegionInstanceRepository regionInstanceRepository =
        Mockito.mock(RegionInstanceRepository.class);
    ZoneInstanceRepository zoneInstanceRepository = Mockito.mock(ZoneInstanceRepository.class);
    RoomInstanceRepository roomInstanceRepository = Mockito.mock(RoomInstanceRepository.class);
    RoomInstanceExitRepository roomInstanceExitRepository =
        Mockito.mock(RoomInstanceExitRepository.class);
    WorldEventRepository worldEventRepository = Mockito.mock(WorldEventRepository.class);
    WorldInstance worldInstance = new WorldInstance();
    worldInstance.setTenantId(1L);
    worldInstance.setGameInstanceId(55L);
    worldInstance.setStatus("ACTIVE");
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(1L, 55L))
        .thenReturn(Optional.of(worldInstance));
    when(regionInstanceRepository.countByTenantIdAndGameInstanceId(1L, 55L)).thenReturn(1L);
    when(zoneInstanceRepository.countByTenantIdAndGameInstanceId(1L, 55L)).thenReturn(2L);
    when(roomInstanceRepository.countByTenantIdAndGameInstanceId(1L, 55L)).thenReturn(3L);
    when(roomInstanceExitRepository.countByTenantIdAndGameInstanceId(1L, 55L)).thenReturn(4L);
    when(worldEventRepository.countByTenantIdAndGameInstanceId(1L, 55L)).thenReturn(5L);

    WorldUpgradeValidationServiceImpl service =
        new WorldUpgradeValidationServiceImpl(
            worldInstanceRepository,
            regionInstanceRepository,
            zoneInstanceRepository,
            roomInstanceRepository,
            roomInstanceExitRepository,
            worldEventRepository);

    var result = service.validateWorldUpgradeMappings(1L, 55L, 11L, "remap-1");

    assertEquals("COMPATIBLE", result.result());
    assertTrue(result.reasons().isEmpty());
    assertEquals(
        List.of(
            "world_instance",
            "region_instance",
            "zone_instance",
            "room_instance",
            "room_instance_exit",
            "world_event"),
        result.checkedFamilies());
  }

  @Test
  void validateWorldUpgradeMappingsReportsIncompatibleWhenTopologyIsIncomplete() {
    WorldInstanceRepository worldInstanceRepository = Mockito.mock(WorldInstanceRepository.class);
    RegionInstanceRepository regionInstanceRepository =
        Mockito.mock(RegionInstanceRepository.class);
    ZoneInstanceRepository zoneInstanceRepository = Mockito.mock(ZoneInstanceRepository.class);
    RoomInstanceRepository roomInstanceRepository = Mockito.mock(RoomInstanceRepository.class);
    RoomInstanceExitRepository roomInstanceExitRepository =
        Mockito.mock(RoomInstanceExitRepository.class);
    WorldEventRepository worldEventRepository = Mockito.mock(WorldEventRepository.class);
    WorldInstance worldInstance = new WorldInstance();
    worldInstance.setTenantId(1L);
    worldInstance.setGameInstanceId(55L);
    worldInstance.setStatus("FAILED_PRE_ACTIVATION");
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(1L, 55L))
        .thenReturn(Optional.of(worldInstance));
    when(regionInstanceRepository.countByTenantIdAndGameInstanceId(1L, 55L)).thenReturn(0L);
    when(zoneInstanceRepository.countByTenantIdAndGameInstanceId(1L, 55L)).thenReturn(0L);
    when(roomInstanceRepository.countByTenantIdAndGameInstanceId(1L, 55L)).thenReturn(0L);
    when(roomInstanceExitRepository.countByTenantIdAndGameInstanceId(1L, 55L)).thenReturn(0L);
    when(worldEventRepository.countByTenantIdAndGameInstanceId(1L, 55L)).thenReturn(0L);

    WorldUpgradeValidationServiceImpl service =
        new WorldUpgradeValidationServiceImpl(
            worldInstanceRepository,
            regionInstanceRepository,
            zoneInstanceRepository,
            roomInstanceRepository,
            roomInstanceExitRepository,
            worldEventRepository);

    var result = service.validateWorldUpgradeMappings(1L, 55L, 11L, null);

    assertEquals("INCOMPATIBLE", result.result());
    assertEquals(2, result.reasons().size());
  }
}
