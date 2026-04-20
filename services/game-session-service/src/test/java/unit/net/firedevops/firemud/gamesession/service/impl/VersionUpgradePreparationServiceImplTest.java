package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.gamesession.dto.CutoverParticipantCompatibilityDto;
import net.firedevops.firemud.gamesession.dto.InstanceCutoverCompatibilityDto;
import net.firedevops.firemud.gamesession.entity.PreparedVersionUpgrade;
import net.firedevops.firemud.gamesession.repository.PreparedVersionUpgradeRepository;
import net.firedevops.firemud.gamesession.service.InstanceCutoverCompatibilityService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class VersionUpgradePreparationServiceImplTest {
  @Test
  void prepareVersionUpgradePersistsPreparationRecord() {
    InstanceCutoverCompatibilityService compatibilityService =
        Mockito.mock(InstanceCutoverCompatibilityService.class);
    PreparedVersionUpgradeRepository repository =
        Mockito.mock(PreparedVersionUpgradeRepository.class);
    Mockito.when(compatibilityService.validateInstanceCutoverCompatibility(1L, 7L, 9L))
        .thenReturn(
            new InstanceCutoverCompatibilityDto(
                7L,
                9L,
                "ld-9",
                "COMPATIBLE",
                List.of(),
                List.of("GAME_DESIGN", "WORLD", "ENTITY"),
                Instant.parse("2026-04-20T10:15:00Z"),
                "remap-1",
                List.of(
                    new CutoverParticipantCompatibilityDto(
                        "WORLD",
                        List.of("S3"),
                        List.of("world_instance"),
                        false,
                        "COMPATIBLE",
                        List.of()))));
    VersionUpgradePreparationServiceImpl service =
        new VersionUpgradePreparationServiceImpl(
            compatibilityService, repository, new ObjectMapper());

    var prepared = service.prepareVersionUpgrade(1L, 7L, 9L, "pvu-req-1");

    assertNotNull(prepared.preparationId());
    assertEquals("pvu-req-1", prepared.controlPlaneRequestId());
    assertEquals("ld-9", prepared.targetLaunchDescriptorId());
    assertEquals("remap-1", prepared.remapSetId());
    ArgumentCaptor<PreparedVersionUpgrade> captor =
        ArgumentCaptor.forClass(PreparedVersionUpgrade.class);
    Mockito.verify(repository).save(captor.capture());
    assertEquals("ld-9", captor.getValue().getTargetLaunchDescriptorId());
    assertEquals("COMPATIBLE", captor.getValue().getResult());
  }

  @Test
  void prepareVersionUpgradeReusesExistingControlPlaneRequestId() throws Exception {
    InstanceCutoverCompatibilityService compatibilityService =
        Mockito.mock(InstanceCutoverCompatibilityService.class);
    PreparedVersionUpgradeRepository repository =
        Mockito.mock(PreparedVersionUpgradeRepository.class);
    ObjectMapper objectMapper = new ObjectMapper();
    PreparedVersionUpgrade existing = new PreparedVersionUpgrade();
    existing.setPreparationId("pvu-1");
    existing.setControlPlaneRequestId("pvu-req-1");
    existing.setTenantId(1L);
    existing.setSourceGameInstanceId(7L);
    existing.setSourceVersionId(7L);
    existing.setTargetVersionId(9L);
    existing.setTargetLaunchDescriptorId("ld-9");
    existing.setRemapSetId("remap-1");
    existing.setResult("COMPATIBLE");
    existing.setReasonsJson(objectMapper.writeValueAsString(List.of()));
    existing.setCheckedParticipantsJson(
        objectMapper.writeValueAsString(List.of("GAME_DESIGN", "WORLD", "ENTITY")));
    existing.setParticipantResultsJson(objectMapper.writeValueAsString(List.of()));
    existing.setCheckedAt(Instant.parse("2026-04-20T10:20:00Z"));
    Mockito.when(repository.findByTenantIdAndControlPlaneRequestId(1L, "pvu-req-1"))
        .thenReturn(java.util.Optional.of(existing));
    VersionUpgradePreparationServiceImpl service =
        new VersionUpgradePreparationServiceImpl(compatibilityService, repository, objectMapper);

    var prepared = service.prepareVersionUpgrade(1L, 7L, 9L, "pvu-req-1");

    assertEquals("pvu-1", prepared.preparationId());
    assertEquals("pvu-req-1", prepared.controlPlaneRequestId());
    Mockito.verifyNoInteractions(compatibilityService);
  }

  @Test
  void getPreparedVersionUpgradeReturnsPersistedPreparation() throws Exception {
    InstanceCutoverCompatibilityService compatibilityService =
        Mockito.mock(InstanceCutoverCompatibilityService.class);
    PreparedVersionUpgradeRepository repository =
        Mockito.mock(PreparedVersionUpgradeRepository.class);
    ObjectMapper objectMapper = new ObjectMapper();
    PreparedVersionUpgrade existing = new PreparedVersionUpgrade();
    existing.setPreparationId("pvu-1");
    existing.setControlPlaneRequestId("pvu-req-1");
    existing.setTenantId(1L);
    existing.setSourceGameInstanceId(7L);
    existing.setSourceVersionId(7L);
    existing.setTargetVersionId(9L);
    existing.setTargetLaunchDescriptorId("ld-9");
    existing.setRemapSetId("remap-1");
    existing.setResult("COMPATIBLE");
    existing.setReasonsJson(objectMapper.writeValueAsString(List.of()));
    existing.setCheckedParticipantsJson(
        objectMapper.writeValueAsString(List.of("GAME_DESIGN", "WORLD", "ENTITY")));
    existing.setParticipantResultsJson(objectMapper.writeValueAsString(List.of()));
    existing.setCheckedAt(Instant.parse("2026-04-20T10:20:00Z"));
    Mockito.when(repository.findByPreparationId("pvu-1"))
        .thenReturn(java.util.Optional.of(existing));
    VersionUpgradePreparationServiceImpl service =
        new VersionUpgradePreparationServiceImpl(compatibilityService, repository, objectMapper);

    var prepared = service.getPreparedVersionUpgrade(1L, "pvu-1");

    assertEquals("pvu-1", prepared.preparationId());
    assertEquals("pvu-req-1", prepared.controlPlaneRequestId());
    assertEquals("ld-9", prepared.targetLaunchDescriptorId());
  }
}
