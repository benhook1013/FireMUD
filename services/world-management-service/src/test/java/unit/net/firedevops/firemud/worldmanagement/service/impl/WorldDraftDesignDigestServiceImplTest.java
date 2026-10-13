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
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTopologyInputGraph;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTopologyInputGraph.InboundSourceClosureDeclaration;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTopologyInputGraph.InboundSourceFamilyCount;
import net.firedevops.firemud.worldmanagement.v1.WorldInboundSourceFamily;
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
  void selectedDigestSchema4BindsTheExactExplicitInboundClosureWithoutChangingGenericSchema3()
      throws Exception {
    var service = emptyDigestService();

    var generic = service.getDraftDesignDigest("1", "7");
    var selected = service.getSelectedDraftDesignDigest("1", "7", emptyInboundClosure());

    assertEquals(3, generic.digestSchemaVersion());
    assertEquals(4, selected.digestSchemaVersion());
    assertEquals(selectedEmptyDigestVector(), selected.contentDigest());
    org.junit.jupiter.api.Assertions.assertNotEquals(
        generic.contentDigest(), selected.contentDigest());
  }

  @Test
  void selectedDigestRejectsNonemptyUnknownAndUnorderedInboundClosure() {
    var service = emptyDigestService();
    var empty = emptyInboundClosure();
    var families = new java.util.ArrayList<>(empty.familyCounts());
    var nonempty = new java.util.ArrayList<>(families);
    nonempty.set(0, new InboundSourceFamilyCount(nonempty.getFirst().family(), 1));
    var unknown = new java.util.ArrayList<>(families);
    unknown.set(
        0,
        new InboundSourceFamilyCount(
            WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_UNSPECIFIED, 0));
    var unordered = new java.util.ArrayList<>(families);
    java.util.Collections.swap(unordered, 0, 1);

    for (var counts : List.of(nonempty, unknown, unordered)) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              service.getSelectedDraftDesignDigest(
                  "1", "7", new InboundSourceClosureDeclaration(1, counts)));
    }
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

  private WorldDraftDesignDigestServiceImpl emptyDigestService() {
    var regions = Mockito.mock(RegionRepository.class);
    var zones = Mockito.mock(ZoneRepository.class);
    var rooms = Mockito.mock(RoomRepository.class);
    var exits = Mockito.mock(RoomExitRepository.class);
    var rules = Mockito.mock(GenerationRuleRepository.class);
    var spawns = Mockito.mock(WorldEntitySpawnBindingRepository.class);
    Mockito.when(regions.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L)).thenReturn(List.of());
    Mockito.when(zones.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L)).thenReturn(List.of());
    Mockito.when(rooms.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L)).thenReturn(List.of());
    Mockito.when(exits.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L)).thenReturn(List.of());
    Mockito.when(rules.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L)).thenReturn(List.of());
    Mockito.when(spawns.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L)).thenReturn(List.of());
    return new WorldDraftDesignDigestServiceImpl(
        regions, zones, rooms, exits, rules, spawns, new ObjectMapper());
  }

  private InboundSourceClosureDeclaration emptyInboundClosure() {
    return new InboundSourceClosureDeclaration(
        1,
        WorldDraftTopologyInputGraph.INBOUND_SOURCE_FAMILY_ORDER.stream()
            .map(family -> new InboundSourceFamilyCount(family, 0))
            .toList());
  }

  private String digestJson(String json) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
  }

  private String selectedEmptyDigestVector() throws Exception {
    String familyCounts =
        "{\"family\":\"WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ROOT\",\"count\":0},"
            + "{\"family\":\"WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ATTACHMENT\",\"count\":0},"
            + "{\"family\":\"WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_SELECTION\",\"count\":0},"
            + "{\"family\":\"WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_BINDING\",\"count\":0},"
            + "{\"family\":\"WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_HOOK\",\"count\":0},"
            + "{\"family\":\"WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_SCRIPT_REFERENCE\",\"count\":0},"
            + "{\"family\":\"WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING\",\"count\":0}";
    return digestJson(
        "{\"regions\":[],\"zones\":[],\"rooms\":[],\"roomExits\":[],"
            + "\"generationRules\":[],\"worldEntitySpawnBindings\":[],"
            + "\"inboundSourceClosure\":{\"schemaVersion\":1,\"familyCounts\":["
            + familyCounts
            + "]}}");
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
