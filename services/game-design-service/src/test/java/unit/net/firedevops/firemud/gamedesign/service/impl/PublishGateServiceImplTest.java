package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.client.AutomationScriptingClient;
import net.firedevops.firemud.gamedesign.client.EntityManagementClient;
import net.firedevops.firemud.gamedesign.client.GameLogicClient;
import net.firedevops.firemud.gamedesign.client.WorldManagementClient;
import net.firedevops.firemud.gamedesign.dto.DesignControlPlaneDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.model.PublishGateFailureCode;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.service.ControlPlaneDigestService;
import net.firedevops.firemud.gamedesign.service.PublishGateFailureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class PublishGateServiceImplTest {
  @Mock private ControlPlaneDigestService controlPlaneDigestService;
  @Mock private WorldManagementClient worldManagementClient;
  @Mock private EntityManagementClient entityManagementClient;
  @Mock private GameLogicClient gameLogicClient;
  @Mock private AutomationScriptingClient automationScriptingClient;

  private PublishGateServiceImpl service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service =
        new PublishGateServiceImpl(
            controlPlaneDigestService,
            worldManagementClient,
            entityManagementClient,
            gameLogicClient,
            automationScriptingClient);
  }

  @Test
  void collectFullVersionParticipantDigestsPassesWhenParticipantsConverge() {
    VersionDto version =
        new VersionDto(
            7L,
            "tenant-1",
            8,
            VersionLifecycleState.PUBLISHED,
            2L,
            null,
            null,
            false,
            "notes",
            LocalDateTime.now(),
            LocalDateTime.now());
    when(worldManagementClient.getDraftDesignDigestForVersion(
            any(PublicationDigestRequestBinding.class)))
        .thenReturn(
            new PublishParticipantDigestDto(
                "WORLD_MANAGEMENT", "7", "version:7", "digest-world", 2, null, null));
    when(entityManagementClient.getDraftDesignDigestForVersion(
            any(PublicationDigestRequestBinding.class)))
        .thenReturn(
            new PublishParticipantDigestDto(
                "ENTITY_MANAGEMENT", "7", "version:7", "digest-entity", 1, null, null));
    when(gameLogicClient.getDraftDesignDigestForVersion(any(PublicationDigestRequestBinding.class)))
        .thenReturn(
            new PublishParticipantDigestDto(
                "GAME_LOGIC", "7", "version:7", "digest-logic", 1, null, null));
    when(automationScriptingClient.getDraftDesignDigestForVersion(
            any(PublicationDigestRequestBinding.class)))
        .thenReturn(
            new PublishParticipantDigestDto(
                "AUTOMATION_SCRIPTING", "7", "version:7", "digest-script", 4, null, null));
    when(controlPlaneDigestService.getDigestForVersion(version))
        .thenReturn(new DesignControlPlaneDigestDto("tenant-1", "7", "version:7", "digest-1", 1));

    List<PublishParticipantDigestDto> digests =
        service.collectFullVersionParticipantDigests(
            version, "publish-request-1", "publish:tenant-1:publish-request:publish-request-1");

    assertEquals(5, digests.size());
    assertEquals("WORLD_MANAGEMENT", digests.get(0).participantKey());
    assertDoesNotThrow(() -> service.assertGatePassed(version, digests));
  }

  @Test
  void fullVersionGateFailsClosedForUnsupportedSchema() {
    VersionDto version =
        new VersionDto(
            7L,
            "tenant-1",
            8,
            VersionLifecycleState.PUBLISHED,
            2L,
            null,
            null,
            false,
            "notes",
            LocalDateTime.now(),
            LocalDateTime.now());
    List<PublishParticipantDigestDto> digests =
        List.of(
            new PublishParticipantDigestDto(
                "WORLD_MANAGEMENT", "7", "version:7", "digest-world", 3, null, null),
            new PublishParticipantDigestDto(
                "ENTITY_MANAGEMENT", "7", "version:7", "digest-entity", 1, null, null),
            new PublishParticipantDigestDto(
                "GAME_LOGIC", "7", "version:7", "digest-logic", 1, null, null),
            new PublishParticipantDigestDto(
                "AUTOMATION_SCRIPTING", "7", "version:7", "digest-script", 4, null, null),
            new PublishParticipantDigestDto(
                "GAME_DESIGN_CONTROL_PLANE", "7", "version:7", "digest-design", 1, null, null));

    PublishGateFailureException thrown =
        assertThrows(
            PublishGateFailureException.class, () -> service.assertGatePassed(version, digests));
    assertEquals(PublishGateFailureCode.UNSUPPORTED_DIGEST_SCHEMA, thrown.failureCode());
  }

  @Test
  void fullVersionGateChecksEachParticipantAgainstItsOwnDigestSchema() {
    VersionDto version =
        new VersionDto(
            7L,
            "tenant-1",
            8,
            VersionLifecycleState.PUBLISHED,
            2L,
            null,
            null,
            false,
            "notes",
            LocalDateTime.now(),
            LocalDateTime.now());
    List<PublishParticipantDigestDto> digests =
        List.of(
            new PublishParticipantDigestDto(
                "WORLD_MANAGEMENT", "7", "version:7", "digest-world", 2, null, null),
            new PublishParticipantDigestDto(
                "AUTOMATION_SCRIPTING", "7", "version:7", "digest-script", 4, null, null),
            new PublishParticipantDigestDto(
                "ENTITY_MANAGEMENT", "7", "version:7", "digest-entity", 4, null, null),
            new PublishParticipantDigestDto(
                "GAME_LOGIC", "7", "version:7", "digest-logic", 1, null, null),
            new PublishParticipantDigestDto(
                "GAME_DESIGN_CONTROL_PLANE", "7", "version:7", "digest-design", 1, null, null));

    PublishGateFailureException thrown =
        assertThrows(
            PublishGateFailureException.class, () -> service.assertGatePassed(version, digests));

    assertEquals(PublishGateFailureCode.UNSUPPORTED_DIGEST_SCHEMA, thrown.failureCode());
    assertTrue(thrown.getMessage().contains("ENTITY_MANAGEMENT"));
  }

  @Test
  void fullVersionGateFailsClosedForUnknownParticipantKey() {
    VersionDto version =
        new VersionDto(
            7L,
            "tenant-1",
            8,
            VersionLifecycleState.PUBLISHED,
            2L,
            null,
            null,
            false,
            "notes",
            LocalDateTime.now(),
            LocalDateTime.now());
    List<PublishParticipantDigestDto> digests =
        List.of(
            new PublishParticipantDigestDto(
                "UNKNOWN_PARTICIPANT", "7", "version:7", "digest-unknown", 1, null, null));

    PublishGateFailureException thrown =
        assertThrows(
            PublishGateFailureException.class, () -> service.assertGatePassed(version, digests));

    assertEquals(PublishGateFailureCode.PARTICIPANT_SET_MISMATCH, thrown.failureCode());
  }

  @Test
  void fullVersionGateFailsClosedForMissingParticipantKey() {
    VersionDto version =
        new VersionDto(
            7L,
            "tenant-1",
            8,
            VersionLifecycleState.PUBLISHED,
            2L,
            null,
            null,
            false,
            "notes",
            LocalDateTime.now(),
            LocalDateTime.now());
    List<PublishParticipantDigestDto> digests =
        java.util.Collections.singletonList(
            new PublishParticipantDigestDto(
                null, "7", "version:7", "digest-unknown", 1, null, null));

    PublishGateFailureException thrown =
        assertThrows(
            PublishGateFailureException.class, () -> service.assertGatePassed(version, digests));

    assertEquals(PublishGateFailureCode.PARTICIPANT_SET_MISMATCH, thrown.failureCode());
  }

  @Test
  void fullVersionGateFailsClosedForDuplicateParticipantKey() {
    VersionDto version =
        new VersionDto(
            7L,
            "tenant-1",
            8,
            VersionLifecycleState.PUBLISHED,
            2L,
            null,
            null,
            false,
            "notes",
            LocalDateTime.now(),
            LocalDateTime.now());
    List<PublishParticipantDigestDto> digests =
        List.of(
            new PublishParticipantDigestDto(
                "WORLD_MANAGEMENT", "7", "version:7", "digest-world", 2, null, null),
            new PublishParticipantDigestDto(
                "WORLD_MANAGEMENT", "7", "version:7", "digest-world-2", 2, null, null),
            new PublishParticipantDigestDto(
                "ENTITY_MANAGEMENT", "7", "version:7", "digest-entity", 1, null, null),
            new PublishParticipantDigestDto(
                "GAME_LOGIC", "7", "version:7", "digest-logic", 1, null, null),
            new PublishParticipantDigestDto(
                "AUTOMATION_SCRIPTING", "7", "version:7", "digest-script", 4, null, null));

    PublishGateFailureException thrown =
        assertThrows(
            PublishGateFailureException.class, () -> service.assertGatePassed(version, digests));

    assertEquals(PublishGateFailureCode.PARTICIPANT_SET_MISMATCH, thrown.failureCode());
  }

  @Test
  void fullVersionGateFailsClosedForNullParticipantObservation() {
    VersionDto version =
        new VersionDto(
            7L,
            "tenant-1",
            8,
            VersionLifecycleState.PUBLISHED,
            2L,
            null,
            null,
            false,
            "notes",
            LocalDateTime.now(),
            LocalDateTime.now());
    List<PublishParticipantDigestDto> digests =
        java.util.Arrays.asList(
            new PublishParticipantDigestDto(
                "WORLD_MANAGEMENT", "7", "version:7", "digest-world", 2, null, null),
            null,
            new PublishParticipantDigestDto(
                "ENTITY_MANAGEMENT", "7", "version:7", "digest-entity", 1, null, null),
            new PublishParticipantDigestDto(
                "GAME_LOGIC", "7", "version:7", "digest-logic", 1, null, null),
            new PublishParticipantDigestDto(
                "AUTOMATION_SCRIPTING", "7", "version:7", "digest-script", 4, null, null));

    PublishGateFailureException thrown =
        assertThrows(
            PublishGateFailureException.class, () -> service.assertGatePassed(version, digests));

    assertEquals(PublishGateFailureCode.PARTICIPANT_SET_MISMATCH, thrown.failureCode());
  }

  @Test
  void fullVersionGateFailsClosedForMissingSchemaVersion() {
    VersionDto version =
        new VersionDto(
            7L,
            "tenant-1",
            8,
            VersionLifecycleState.PUBLISHED,
            2L,
            null,
            null,
            false,
            "notes",
            LocalDateTime.now(),
            LocalDateTime.now());
    List<PublishParticipantDigestDto> digests =
        List.of(
            new PublishParticipantDigestDto(
                "WORLD_MANAGEMENT", "7", "version:7", "digest-world", null, null, null),
            new PublishParticipantDigestDto(
                "ENTITY_MANAGEMENT", "7", "version:7", "digest-entity", 1, null, null),
            new PublishParticipantDigestDto(
                "GAME_LOGIC", "7", "version:7", "digest-logic", 1, null, null),
            new PublishParticipantDigestDto(
                "AUTOMATION_SCRIPTING", "7", "version:7", "digest-script", 4, null, null),
            new PublishParticipantDigestDto(
                "GAME_DESIGN_CONTROL_PLANE", "7", "version:7", "digest-design", 1, null, null));

    PublishGateFailureException thrown =
        assertThrows(
            PublishGateFailureException.class, () -> service.assertGatePassed(version, digests));

    assertEquals(PublishGateFailureCode.UNSUPPORTED_DIGEST_SCHEMA, thrown.failureCode());
  }

  @Test
  void scriptPatchGatePassesWhenParticipantsConverge() {
    VersionDto version =
        new VersionDto(
            9L,
            "tenant-1",
            10,
            VersionLifecycleState.PUBLISHED,
            2L,
            "patch-1",
            7L,
            true,
            "notes",
            LocalDateTime.now(),
            LocalDateTime.now());
    when(controlPlaneDigestService.getDigestForScriptPatch(version))
        .thenReturn(
            new DesignControlPlaneDigestDto(
                "tenant-1", "patch-1", "script-patch:patch-1", "digest-2", 1));
    when(automationScriptingClient.getDraftDesignDigestForScriptPatch(
            any(PublicationDigestRequestBinding.class)))
        .thenReturn(
            new PublishParticipantDigestDto(
                "AUTOMATION_SCRIPTING",
                "patch-1",
                "script-patch:patch-1",
                "digest-1",
                4,
                null,
                null));

    List<PublishParticipantDigestDto> digests =
        service.collectScriptPatchParticipantDigests(
            version,
            "publish-request-1",
            "publish-script-patch:tenant-1:publish-request:publish-request-1");

    assertEquals(2, digests.size());
    assertDoesNotThrow(() -> service.assertGatePassed(version, digests));
  }

  @Test
  void scriptPatchGateFailsClosedForWrongParticipantSet() {
    VersionDto version =
        new VersionDto(
            9L,
            "tenant-1",
            10,
            VersionLifecycleState.PUBLISHED,
            2L,
            "patch-1",
            7L,
            true,
            "notes",
            LocalDateTime.now(),
            LocalDateTime.now());
    List<PublishParticipantDigestDto> digests =
        List.of(
            new PublishParticipantDigestDto(
                "AUTOMATION_SCRIPTING",
                "patch-1",
                "script-patch:patch-1",
                "digest-1",
                4,
                null,
                null),
            new PublishParticipantDigestDto(
                "WORLD_MANAGEMENT",
                "patch-1",
                "script-patch:patch-1",
                "digest-world",
                2,
                null,
                null));

    PublishGateFailureException thrown =
        assertThrows(
            PublishGateFailureException.class, () -> service.assertGatePassed(version, digests));

    assertEquals(PublishGateFailureCode.PARTICIPANT_SET_MISMATCH, thrown.failureCode());
  }

  @Test
  void scriptPatchGateRejectsLegacyAutomationDigestSchema() {
    VersionDto version =
        new VersionDto(
            9L,
            "tenant-1",
            10,
            VersionLifecycleState.PUBLISHED,
            2L,
            "patch-1",
            7L,
            true,
            "notes",
            LocalDateTime.now(),
            LocalDateTime.now());
    List<PublishParticipantDigestDto> digests =
        List.of(
            new PublishParticipantDigestDto(
                "AUTOMATION_SCRIPTING",
                "patch-1",
                "script-patch:patch-1",
                "digest-1",
                3,
                null,
                null),
            new PublishParticipantDigestDto(
                "GAME_DESIGN_CONTROL_PLANE",
                "patch-1",
                "script-patch:patch-1",
                "digest-2",
                1,
                null,
                null));

    PublishGateFailureException thrown =
        assertThrows(
            PublishGateFailureException.class, () -> service.assertGatePassed(version, digests));

    assertEquals(PublishGateFailureCode.UNSUPPORTED_DIGEST_SCHEMA, thrown.failureCode());
  }
}
