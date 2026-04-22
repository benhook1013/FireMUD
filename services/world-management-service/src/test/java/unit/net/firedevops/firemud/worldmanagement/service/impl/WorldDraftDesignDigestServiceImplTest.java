package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.firedevops.firemud.worldmanagement.repository.GenerationRuleRepository;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldEntitySpawnBindingRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class WorldDraftDesignDigestServiceImplTest {
  @Test
  void computesDigestFromVersionScopedTemplateRows() {
    RegionRepository regionRepository = Mockito.mock(RegionRepository.class);
    ZoneRepository zoneRepository = Mockito.mock(ZoneRepository.class);
    RoomRepository roomRepository = Mockito.mock(RoomRepository.class);
    RoomExitRepository roomExitRepository = Mockito.mock(RoomExitRepository.class);
    GenerationRuleRepository generationRuleRepository =
        Mockito.mock(GenerationRuleRepository.class);
    WorldEntitySpawnBindingRepository worldEntitySpawnBindingRepository =
        Mockito.mock(WorldEntitySpawnBindingRepository.class);
    Mockito.when(regionRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of());
    Mockito.when(zoneRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of());
    Mockito.when(roomRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of());
    Mockito.when(roomExitRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of());
    Mockito.when(generationRuleRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of());
    Mockito.when(worldEntitySpawnBindingRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of());
    WorldDraftDesignDigestServiceImpl service =
        new WorldDraftDesignDigestServiceImpl(
            regionRepository,
            zoneRepository,
            roomRepository,
            roomExitRepository,
            generationRuleRepository,
            worldEntitySpawnBindingRepository,
            new ObjectMapper());

    var digest = service.getDraftDesignDigest("1", "7");

    assertEquals("1", digest.tenantId());
    assertEquals("7", digest.scopeValue());
    assertEquals("version:7", digest.appliedCommitId());
    Mockito.verify(regionRepository).findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L);
    Mockito.verify(regionRepository, Mockito.never()).findByTenantIdOrderByIdAsc(Mockito.anyLong());
  }
}
