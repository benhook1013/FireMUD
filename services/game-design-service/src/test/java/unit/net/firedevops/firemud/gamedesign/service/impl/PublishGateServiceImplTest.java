package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import net.firedevops.firemud.gamedesign.client.AutomationScriptingClient;
import net.firedevops.firemud.gamedesign.dto.DesignControlPlaneDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.service.ControlPlaneDigestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class PublishGateServiceImplTest {
  @Mock private ControlPlaneDigestService controlPlaneDigestService;
  @Mock private AutomationScriptingClient automationScriptingClient;

  private PublishGateServiceImpl service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service = new PublishGateServiceImpl(controlPlaneDigestService, automationScriptingClient);
  }

  @Test
  void collectFullVersionParticipantDigestsFailsClosedForMissingParticipants() {
    VersionDto version =
        new VersionDto(7L, "tenant-1", 8, null, null, false, "notes", LocalDateTime.now());
    when(controlPlaneDigestService.getDigestForVersion(version))
        .thenReturn(new DesignControlPlaneDigestDto("tenant-1", "7", "version:7", "digest-1", 1));

    List<PublishParticipantDigestDto> digests =
        service.collectFullVersionParticipantDigests(version);

    assertEquals(5, digests.size());
    assertEquals("UNIMPLEMENTED_DIGEST_PARTICIPANT", digests.get(0).errorCode());
    assertEquals("GAME_DESIGN_CONTROL_PLANE", digests.get(4).participantKey());
    assertThrows(IllegalStateException.class, () -> service.assertGatePassed(version, digests));
  }

  @Test
  void scriptPatchGatePassesWhenParticipantsConverge() {
    VersionDto version =
        new VersionDto(9L, "tenant-1", 10, "patch-1", 7L, true, "notes", LocalDateTime.now());
    when(controlPlaneDigestService.getDigestForScriptPatch(version))
        .thenReturn(
            new DesignControlPlaneDigestDto(
                "tenant-1", "patch-1", "script-patch:patch-1", "digest-2", 1));
    when(automationScriptingClient.getDraftDesignDigestForScriptPatch("tenant-1", "patch-1"))
        .thenReturn(
            new PublishParticipantDigestDto(
                "AUTOMATION_SCRIPTING",
                "patch-1",
                "script-patch:patch-1",
                "digest-1",
                1,
                null,
                null));

    List<PublishParticipantDigestDto> digests =
        service.collectScriptPatchParticipantDigests(version);

    assertEquals(2, digests.size());
    assertDoesNotThrow(() -> service.assertGatePassed(version, digests));
  }
}
