package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
  void rejectsZeroTenantIdBeforeRepositoryReads() {
    RegionRepository regionRepository = Mockito.mock(RegionRepository.class);
    ZoneRepository zoneRepository = Mockito.mock(ZoneRepository.class);
    RoomRepository roomRepository = Mockito.mock(RoomRepository.class);
    RoomExitRepository roomExitRepository = Mockito.mock(RoomExitRepository.class);
    GenerationRuleRepository generationRuleRepository =
        Mockito.mock(GenerationRuleRepository.class);
    WorldEntitySpawnBindingRepository worldEntitySpawnBindingRepository =
        Mockito.mock(WorldEntitySpawnBindingRepository.class);
    WorldDraftDesignDigestServiceImpl service =
        new WorldDraftDesignDigestServiceImpl(
            regionRepository,
            zoneRepository,
            roomRepository,
            roomExitRepository,
            generationRuleRepository,
            worldEntitySpawnBindingRepository,
            new ObjectMapper());

    assertThrows(IllegalArgumentException.class, () -> service.getDraftDesignDigest("0", "7"));

    Mockito.verifyNoInteractions(
        regionRepository,
        zoneRepository,
        roomRepository,
        roomExitRepository,
        generationRuleRepository,
        worldEntitySpawnBindingRepository);
  }

  @Test
  void rejectsZeroVersionIdBeforeRepositoryReads() {
    RegionRepository regionRepository = Mockito.mock(RegionRepository.class);
    ZoneRepository zoneRepository = Mockito.mock(ZoneRepository.class);
    RoomRepository roomRepository = Mockito.mock(RoomRepository.class);
    RoomExitRepository roomExitRepository = Mockito.mock(RoomExitRepository.class);
    GenerationRuleRepository generationRuleRepository =
        Mockito.mock(GenerationRuleRepository.class);
    WorldEntitySpawnBindingRepository worldEntitySpawnBindingRepository =
        Mockito.mock(WorldEntitySpawnBindingRepository.class);
    WorldDraftDesignDigestServiceImpl service =
        new WorldDraftDesignDigestServiceImpl(
            regionRepository,
            zoneRepository,
            roomRepository,
            roomExitRepository,
            generationRuleRepository,
            worldEntitySpawnBindingRepository,
            new ObjectMapper());

    assertThrows(IllegalArgumentException.class, () -> service.getDraftDesignDigest("1", "0"));

    Mockito.verifyNoInteractions(
        regionRepository,
        zoneRepository,
        roomRepository,
        roomExitRepository,
        generationRuleRepository,
        worldEntitySpawnBindingRepository);
  }

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
    assertEquals(3, digest.digestSchemaVersion());
    Mockito.verify(regionRepository).findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L);
    Mockito.verify(regionRepository, Mockito.never()).findByTenantIdOrderByIdAsc(Mockito.anyLong());
  }

  @Test
  void canonicalSpawnReferenceAffectsSchemaThreeDigestAndMixedReferencesDeny() {
    var regions = Mockito.mock(RegionRepository.class);
    var zones = Mockito.mock(ZoneRepository.class);
    var rooms = Mockito.mock(RoomRepository.class);
    var exits = Mockito.mock(RoomExitRepository.class);
    var rules = Mockito.mock(GenerationRuleRepository.class);
    var spawns = Mockito.mock(WorldEntitySpawnBindingRepository.class);
    var service =
        new WorldDraftDesignDigestServiceImpl(
            regions, zones, rooms, exits, rules, spawns, new ObjectMapper());
    var room = new net.firedevops.firemud.worldmanagement.entity.Room();
    room.setId(11L);
    var spawn = new net.firedevops.firemud.worldmanagement.entity.WorldEntitySpawnBinding();
    spawn.setId(12L);
    spawn.setRoom(room);
    spawn.setEntityTemplateType("NPC");
    spawn.setEntityCanonicalTenantId(java.util.UUID.randomUUID());
    spawn.setEntityCanonicalVersionId(java.util.UUID.randomUUID());
    spawn.setEntityCanonicalTemplateId(java.util.UUID.randomUUID());
    Mockito.when(spawns.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L)).thenReturn(List.of(spawn));
    var before = service.getDraftDesignDigest("1", "7");
    assertEquals(3, before.digestSchemaVersion());
    spawn.setEntityCanonicalTemplateId(java.util.UUID.randomUUID());
    org.junit.jupiter.api.Assertions.assertNotEquals(
        before.contentDigest(), service.getDraftDesignDigest("1", "7").contentDigest());
    spawn.setEntityTemplateId(9L);
    assertThrows(IllegalStateException.class, () -> service.getDraftDesignDigest("1", "7"));
    spawn.setEntityTemplateId(null);
    spawn.setEntityCanonicalVersionId(null);
    assertThrows(IllegalStateException.class, () -> service.getDraftDesignDigest("1", "7"));
  }
}
