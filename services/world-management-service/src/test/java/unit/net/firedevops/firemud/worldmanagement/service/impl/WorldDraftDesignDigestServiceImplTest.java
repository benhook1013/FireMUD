package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.worldmanagement.entity.Room;
import net.firedevops.firemud.worldmanagement.entity.WorldEntitySpawnBinding;
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
  void canonicalEntityReferenceMatchesExactSortedSerializationVector() throws Exception {
    WorldEntitySpawnBinding binding = binding();
    canonicalReference(binding);
    assertEquals(
        vector(
            "{\"kind\":\"CANONICAL_UUID\",\"templateId\":\"33333333-3333-4333-8333-333333333333\",\"tenantId\":\"11111111-1111-4111-8111-111111111111\",\"versionId\":\"22222222-2222-4222-8222-222222222222\"}"),
        digest(binding));
  }

  @Test
  void retainedEntityReferenceMatchesExactSortedSerializationVector() throws Exception {
    WorldEntitySpawnBinding binding = binding();
    binding.setEntityTemplateId(Long.MAX_VALUE);
    assertEquals(
        vector("{\"kind\":\"RETAINED_PRIVATE_KEY\",\"templateId\":\"9223372036854775807\"}"),
        digest(binding));
  }

  @Test
  void eachCanonicalEntityUuidContributesToDigest() {
    WorldEntitySpawnBinding binding = binding();
    canonicalReference(binding);
    String original = digest(binding);
    for (int field = 0; field < 3; field++) {
      canonicalReference(binding);
      UUID changed = UUID.fromString("44444444-4444-4444-8444-444444444444");
      if (field == 0) binding.setEntityCanonicalTenantId(changed);
      if (field == 1) binding.setEntityCanonicalVersionId(changed);
      if (field == 2) binding.setEntityCanonicalTemplateId(changed);
      org.junit.jupiter.api.Assertions.assertNotEquals(original, digest(binding));
    }
  }

  @Test
  void rejectsMissingNilMixedAndNonpositiveEntityReferences() {
    WorldEntitySpawnBinding binding = binding();
    assertThrows(IllegalStateException.class, () -> digest(binding));
    for (int field = 0; field < 3; field++) {
      for (UUID invalid : new UUID[] {null, new UUID(0L, 0L)}) {
        canonicalReference(binding);
        if (field == 0) binding.setEntityCanonicalTenantId(invalid);
        if (field == 1) binding.setEntityCanonicalVersionId(invalid);
        if (field == 2) binding.setEntityCanonicalTemplateId(invalid);
        assertThrows(IllegalStateException.class, () -> digest(binding));
      }
    }
    canonicalReference(binding);
    binding.setEntityTemplateId(9L);
    assertThrows(IllegalStateException.class, () -> digest(binding));
    binding.setEntityCanonicalTenantId(null);
    binding.setEntityCanonicalVersionId(null);
    binding.setEntityCanonicalTemplateId(null);
    for (long invalid : new long[] {0L, -1L}) {
      binding.setEntityTemplateId(invalid);
      assertThrows(IllegalStateException.class, () -> digest(binding));
    }
  }

  @Test
  void repeatedDigestsHaveStableFieldOrder() {
    WorldEntitySpawnBinding binding = binding();
    canonicalReference(binding);
    String expected = digest(binding);
    for (int attempt = 0; attempt < 20; attempt++) assertEquals(expected, digest(binding));
  }

  @Test
  void applicationMapperSettingsCannotChangeSchema3Serialization() {
    WorldEntitySpawnBinding binding = binding();
    canonicalReference(binding);
    var configured =
        tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.databind.SerializationFeature.INDENT_OUTPUT)
            .enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();
    assertEquals(digest(binding), digest(binding, configured));
  }

  private String digest(WorldEntitySpawnBinding binding) {
    return digest(binding, new ObjectMapper());
  }

  private String digest(WorldEntitySpawnBinding binding, ObjectMapper mapper) {
    var spawns = Mockito.mock(WorldEntitySpawnBindingRepository.class);
    Mockito.when(spawns.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of(binding));
    return new WorldDraftDesignDigestServiceImpl(
            Mockito.mock(RegionRepository.class),
            Mockito.mock(ZoneRepository.class),
            Mockito.mock(RoomRepository.class),
            Mockito.mock(RoomExitRepository.class),
            Mockito.mock(GenerationRuleRepository.class),
            spawns,
            mapper)
        .getDraftDesignDigest("1", "7")
        .contentDigest();
  }

  private String vector(String entityReference) throws Exception {
    String json =
        "{\"regions\":[],\"zones\":[],\"rooms\":[],\"roomExits\":[],\"generationRules\":[],\"worldEntitySpawnBindings\":[{\"entityReference\":"
            + entityReference
            + ",\"entityTemplateType\":\"NPC\",\"id\":11,\"respawnDelaySeconds\":30,\"roomId\":5,\"spawnCount\":2}]}";
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
  }

  private WorldEntitySpawnBinding binding() {
    var binding = new WorldEntitySpawnBinding();
    var room = new Room();
    room.setId(5L);
    binding.setId(11L);
    binding.setRoom(room);
    binding.setEntityTemplateType("NPC");
    binding.setSpawnCount(2);
    binding.setRespawnDelaySeconds(30);
    return binding;
  }

  private void canonicalReference(WorldEntitySpawnBinding binding) {
    binding.setEntityTemplateId(null);
    binding.setEntityCanonicalTenantId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    binding.setEntityCanonicalVersionId(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    binding.setEntityCanonicalTemplateId(UUID.fromString("33333333-3333-4333-8333-333333333333"));
  }
}
