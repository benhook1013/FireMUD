package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
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
import net.firedevops.firemud.gamedesign.publication.SelectedDraftGameLogicReceipt;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftGameLogicReceiptRepository;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftPublicationDigestReadService;
import net.firedevops.firemud.gamedesign.service.ControlPlaneDigestService;
import net.firedevops.firemud.gamedesign.service.PublicationFailureClassifier;
import net.firedevops.firemud.gamedesign.service.PublishGateFailureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class PublishGateServiceImplTest {
  @Mock private ControlPlaneDigestService controlPlaneDigestService;
  @Mock private WorldManagementClient worldManagementClient;
  @Mock private EntityManagementClient entityManagementClient;
  @Mock private GameLogicClient gameLogicClient;
  @Mock private AutomationScriptingClient automationScriptingClient;
  @Mock private SelectedDraftGameLogicReceiptRepository selectedGameLogicReceiptRepository;
  @Mock private SelectedDraftPublicationDigestReadService selectedPublicationDigestReader;

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
            automationScriptingClient,
            selectedGameLogicReceiptRepository,
            selectedPublicationDigestReader,
            "test");
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
                "WORLD_MANAGEMENT", "7", "version:7", "digest-world", 3, null, null));
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
                "AUTOMATION_SCRIPTING", "7", "version:7", "digest-script", 5, null, null));
    when(controlPlaneDigestService.getDigestForVersion(version))
        .thenReturn(new DesignControlPlaneDigestDto("tenant-1", "7", "version:7", "digest-1", 1));

    List<PublishParticipantDigestDto> digests =
        service.collectFullVersionParticipantDigests(
            version, "publish-request-1", "publish:tenant-1:publish-request:publish-request-1");

    assertEquals(5, digests.size());
    assertEquals("WORLD_MANAGEMENT", digests.get(0).participantKey());
    assertEquals(
        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
            "WORLD_MANAGEMENT"),
        digests.get(0).digestSchemaVersion());
    assertDoesNotThrow(() -> service.assertGatePassed(version, digests));

    PublicationDigestRequestBinding expectedBinding =
        PublicationDigestRequestBinding.full("tenant-1", "7", "publish-request-1");
    assertBinding(captureVersionBinding(worldManagementClient), expectedBinding);
    assertBinding(captureVersionBinding(entityManagementClient), expectedBinding);
    assertBinding(captureVersionBinding(gameLogicClient), expectedBinding);
    assertBinding(captureVersionBinding(automationScriptingClient), expectedBinding);
  }

  @Test
  void selectedFullVersionGateUsesCanonicalBindingAndExactReceiptForOwnerReads() {
    VersionDto version = selectedVersion();
    PublicationDigestRequestBinding binding = selectedBinding(version);
    SelectedDraftGameLogicReceipt receipt =
        org.mockito.Mockito.mock(SelectedDraftGameLogicReceipt.class);
    stubSelectedParticipantDigests(version, binding, receipt);

    List<PublishParticipantDigestDto> digests =
        service.collectSelectedFullVersionParticipantDigests(
            version, binding, binding.derivedWorkflowIdentity());

    assertEquals(5, digests.size());
    assertDoesNotThrow(() -> service.assertSelectedGatePassed(version, digests));
    assertEquals(2, digests.get(4).digestSchemaVersion());
    assertEquals(6, digests.get(3).digestSchemaVersion());
    assertEquals("b".repeat(64), digests.getFirst().contentDigest());
    assertEquals("c".repeat(64), digests.get(4).contentDigest());
    verify(selectedPublicationDigestReader).read("test", binding);
    org.mockito.Mockito.verifyNoInteractions(worldManagementClient, controlPlaneDigestService);
    assertBinding(captureVersionBinding(entityManagementClient), binding);
    ArgumentCaptor<PublicationDigestRequestBinding> gameLogicBinding =
        ArgumentCaptor.forClass(PublicationDigestRequestBinding.class);
    verify(gameLogicClient)
        .getDraftDesignDigestForVersion(gameLogicBinding.capture(), same(receipt));
    assertBinding(gameLogicBinding.getValue(), binding);
    assertBinding(captureVersionBinding(automationScriptingClient), binding);

    var publicationOrder =
        org.mockito.Mockito.inOrder(selectedGameLogicReceiptRepository, gameLogicClient);
    publicationOrder.verify(selectedGameLogicReceiptRepository).readForPublication(binding);
    publicationOrder.verify(gameLogicClient).getDraftDesignDigestForVersion(binding, receipt);
    verify(gameLogicClient, org.mockito.Mockito.never())
        .getDraftDesignDigestForVersion(any(PublicationDigestRequestBinding.class));
  }

  @Test
  void selectedFullGateRejectsEveryMixedParticipantProfile() {
    var version = selectedVersion();
    var binding = selectedBinding(version);
    var receipt = org.mockito.Mockito.mock(SelectedDraftGameLogicReceipt.class);
    stubSelectedParticipantDigests(version, binding, receipt);
    var participants =
        service.collectSelectedFullVersionParticipantDigests(
            version, binding, binding.derivedWorkflowIdentity());
    for (int index = 0; index < participants.size(); index++) {
      var mixed = new java.util.ArrayList<>(participants);
      var participant = mixed.get(index);
      mixed.set(
          index,
          new PublishParticipantDigestDto(
              participant.participantKey(),
              participant.scopeValue(),
              participant.baseVersionId(),
              participant.appliedCommitId(),
              participant.contentDigest(),
              participant.digestSchemaVersion() == 1 ? 2 : participant.digestSchemaVersion() - 1,
              participant.abilitySchemaDigest(),
              participant.errorCode(),
              participant.errorMessage()));
      assertThrows(
          PublishGateFailureException.class,
          () -> service.assertSelectedGatePassed(version, mixed),
          "mixed profile for " + participant.participantKey());
    }
  }

  @Test
  void closureQualifiedSelectedGateRequiresWorldDigestSchemaFour() {
    VersionDto version = selectedVersion();
    PublicationDigestRequestBinding binding = selectedBinding(version);
    SelectedDraftGameLogicReceipt receipt =
        org.mockito.Mockito.mock(SelectedDraftGameLogicReceipt.class);
    stubSelectedParticipantDigests(version, binding, receipt);
    when(selectedPublicationDigestReader.read("test", binding))
        .thenReturn(
            new SelectedDraftPublicationDigestReadService.ReadResult(
                binding,
                binding.requestDigest(),
                new DesignControlPlaneDigestDto(
                    binding.tenantId(), "7", "selected-commit", "c".repeat(64), 2),
                new PublishParticipantDigestDto(
                    "WORLD_MANAGEMENT", "7", "selected-commit", "b".repeat(64), 4, null, null)));

    List<PublishParticipantDigestDto> digests =
        service.collectSelectedFullVersionParticipantDigests(
            version, binding, binding.derivedWorkflowIdentity());

    assertEquals(4, digests.getFirst().digestSchemaVersion());
    assertEquals(2, digests.getLast().digestSchemaVersion());
    assertDoesNotThrow(() -> service.assertSelectedGatePassed(version, digests));
  }

  @Test
  void selectedFullVersionGateKeepsMissingReceiptPendingAndNeverUsesLegacyRead() {
    VersionDto version = selectedVersion();
    PublicationDigestRequestBinding binding = selectedBinding(version);
    when(selectedGameLogicReceiptRepository.readForPublication(binding))
        .thenReturn(Optional.empty());
    stubNonGameLogicParticipants(version, binding);

    List<PublishParticipantDigestDto> digests =
        service.collectSelectedFullVersionParticipantDigests(
            version, binding, binding.derivedWorkflowIdentity());

    assertEquals("PARTICIPANT_UNAVAILABLE", digests.get(2).errorCode());
    PublishGateFailureException thrown =
        assertThrows(
            PublishGateFailureException.class,
            () -> service.assertSelectedGatePassed(version, digests));
    assertTrue(PublicationFailureClassifier.isRetryableParticipantDependencyFailure(thrown));
    verify(gameLogicClient, org.mockito.Mockito.never())
        .getDraftDesignDigestForVersion(any(PublicationDigestRequestBinding.class));
    verify(gameLogicClient, org.mockito.Mockito.never())
        .getDraftDesignDigestForVersion(
            any(PublicationDigestRequestBinding.class), any(SelectedDraftGameLogicReceipt.class));
  }

  @Test
  void selectedReceiptStorageInterruptionRemainsRetryable() {
    VersionDto version = selectedVersion();
    PublicationDigestRequestBinding binding = selectedBinding(version);
    when(selectedGameLogicReceiptRepository.readForPublication(binding))
        .thenThrow(new org.jooq.exception.DataAccessException("database unavailable"));
    stubNonGameLogicParticipants(version, binding);

    List<PublishParticipantDigestDto> digests =
        service.collectSelectedFullVersionParticipantDigests(
            version, binding, binding.derivedWorkflowIdentity());

    assertEquals("UNAVAILABLE", digests.get(2).errorCode());
    PublishGateFailureException thrown =
        assertThrows(
            PublishGateFailureException.class, () -> service.assertGatePassed(version, digests));
    assertTrue(PublicationFailureClassifier.isRetryableParticipantDependencyFailure(thrown));
  }

  @Test
  void selectedFullVersionGateRejectsPrivateTenantKeyAsPublicationIdentity() {
    VersionDto version = selectedVersion();
    PublicationDigestRequestBinding privateTenantBinding =
        PublicationDigestRequestBinding.full("private-tenant-key", "7", "selected-request-1");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.collectSelectedFullVersionParticipantDigests(
                version, privateTenantBinding, privateTenantBinding.derivedWorkflowIdentity()));
    org.mockito.Mockito.verifyNoInteractions(
        worldManagementClient,
        entityManagementClient,
        gameLogicClient,
        automationScriptingClient,
        selectedGameLogicReceiptRepository);
  }

  @Test
  void fullVersionGateRejectsUnavailableGameLogicManifest() {
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
                "GAME_LOGIC", "7", null, null, null, "INTERNAL", "Internal error"),
            new PublishParticipantDigestDto(
                "AUTOMATION_SCRIPTING", "7", "version:7", "digest-script", 4, null, null),
            new PublishParticipantDigestDto(
                "GAME_DESIGN_CONTROL_PLANE", "7", "version:7", "digest-design", 1, null, null));

    PublishGateFailureException thrown =
        assertThrows(
            PublishGateFailureException.class, () -> service.assertGatePassed(version, digests));

    assertEquals(PublishGateFailureCode.PARTICIPANT_UNAVAILABLE, thrown.failureCode());
    assertEquals("INTERNAL", thrown.participantFailureCode());
    assertTrue(thrown.getMessage().contains("GAME_LOGIC"));
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 4})
  void fullVersionGateRejectsObsoleteAndUnknownWorldSchemas(int worldSchema) {
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
                "WORLD_MANAGEMENT", "7", "version:7", "digest-world", worldSchema, null, null),
            new PublishParticipantDigestDto(
                "ENTITY_MANAGEMENT", "7", "version:7", "digest-entity", 1, null, null),
            new PublishParticipantDigestDto(
                "GAME_LOGIC", "7", "version:7", "digest-logic", 1, null, null),
            new PublishParticipantDigestDto(
                "AUTOMATION_SCRIPTING", "7", "version:7", "digest-script", 5, null, null),
            new PublishParticipantDigestDto(
                "GAME_DESIGN_CONTROL_PLANE", "7", "version:7", "digest-design", 1, null, null));

    PublishGateFailureException thrown =
        assertThrows(
            PublishGateFailureException.class, () -> service.assertGatePassed(version, digests));
    assertEquals(PublishGateFailureCode.UNSUPPORTED_DIGEST_SCHEMA, thrown.failureCode());
    assertTrue(thrown.getMessage().contains("WORLD_MANAGEMENT"));
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
                "WORLD_MANAGEMENT", "7", "version:7", "digest-world", 3, null, null),
            new PublishParticipantDigestDto(
                "AUTOMATION_SCRIPTING", "7", "version:7", "digest-script", 5, null, null),
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
  void fullVersionGateRejectsObsoleteAutomationDigestSchema() {
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
                "WORLD_MANAGEMENT", "7", "version:7", "digest-world", 3, null, null),
            new PublishParticipantDigestDto(
                "WORLD_MANAGEMENT", "7", "version:7", "digest-world-2", 3, null, null),
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
                "WORLD_MANAGEMENT", "7", "version:7", "digest-world", 3, null, null),
            null,
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
                7L,
                "script-patch:patch-1",
                "digest-1",
                5,
                null,
                null));

    List<PublishParticipantDigestDto> digests =
        service.collectScriptPatchParticipantDigests(
            version,
            "publish-request-1",
            "publish-script-patch:tenant-1:publish-request:publish-request-1");

    assertEquals(2, digests.size());
    assertDoesNotThrow(() -> service.assertGatePassed(version, digests));

    ArgumentCaptor<PublicationDigestRequestBinding> bindingCaptor =
        ArgumentCaptor.forClass(PublicationDigestRequestBinding.class);
    verify(automationScriptingClient).getDraftDesignDigestForScriptPatch(bindingCaptor.capture());
    assertBinding(
        bindingCaptor.getValue(),
        PublicationDigestRequestBinding.patch("tenant-1", "7", "patch-1", "publish-request-1"));
  }

  @Test
  void unavailableSelectedReaderFailsBothOwnersWithoutLegacyFallback() {
    VersionDto version = selectedVersion();
    var binding = selectedBinding(version);
    var receipt = org.mockito.Mockito.mock(SelectedDraftGameLogicReceipt.class);
    stubSelectedParticipantDigests(version, binding, receipt);
    when(selectedPublicationDigestReader.read("test", binding))
        .thenThrow(io.grpc.Status.UNAVAILABLE.asRuntimeException());

    var digests =
        service.collectSelectedFullVersionParticipantDigests(
            version, binding, binding.derivedWorkflowIdentity());

    assertEquals(5, digests.size());
    assertEquals("UNAVAILABLE", digests.getFirst().errorCode());
    assertEquals("UNAVAILABLE", digests.get(4).errorCode());
    assertThrows(
        PublishGateFailureException.class,
        () -> service.assertSelectedGatePassed(version, digests));
    org.mockito.Mockito.verifyNoInteractions(worldManagementClient, controlPlaneDigestService);
    verify(gameLogicClient).getDraftDesignDigestForVersion(binding, receipt);
  }

  @Test
  void missingSelectedReaderFailsClosed() {
    VersionDto version = selectedVersion();
    var binding = selectedBinding(version);
    service =
        new PublishGateServiceImpl(
            controlPlaneDigestService,
            worldManagementClient,
            entityManagementClient,
            gameLogicClient,
            automationScriptingClient,
            selectedGameLogicReceiptRepository);
    when(selectedGameLogicReceiptRepository.readForPublication(binding))
        .thenReturn(Optional.empty());
    when(entityManagementClient.getDraftDesignDigestForVersion(binding))
        .thenReturn(
            new PublishParticipantDigestDto(
                "ENTITY_MANAGEMENT",
                "7",
                null,
                null,
                null,
                "UNSUPPORTED_SCOPE",
                "selected Entity evidence unavailable"));
    when(automationScriptingClient.getDraftDesignDigestForVersion(binding))
        .thenReturn(
            new PublishParticipantDigestDto(
                "AUTOMATION_SCRIPTING",
                "7",
                null,
                null,
                null,
                "UNSUPPORTED_SCOPE",
                "selected Automation evidence unavailable"));

    var digests =
        service.collectSelectedFullVersionParticipantDigests(
            version, binding, binding.derivedWorkflowIdentity());

    assertEquals(5, digests.size());
    assertEquals("PARTICIPANT_UNAVAILABLE", digests.getFirst().errorCode());
    assertEquals("PARTICIPANT_UNAVAILABLE", digests.get(4).errorCode());
    assertThrows(
        PublishGateFailureException.class,
        () -> service.assertSelectedGatePassed(version, digests));
    org.mockito.Mockito.verifyNoInteractions(worldManagementClient, controlPlaneDigestService);
  }

  @Test
  void selectedSnapshotForAnotherRequestCannotSatisfyEitherOwner() {
    VersionDto version = selectedVersion();
    var binding = selectedBinding(version);
    var other = PublicationDigestRequestBinding.full(binding.tenantId(), "7", "other-request");
    when(selectedPublicationDigestReader.read("test", binding)).thenReturn(selectedSnapshot(other));
    when(selectedGameLogicReceiptRepository.readForPublication(binding))
        .thenReturn(Optional.empty());

    var digests =
        service.collectSelectedFullVersionParticipantDigests(
            version, binding, binding.derivedWorkflowIdentity());

    assertEquals("FAILED_PRECONDITION", digests.getFirst().errorCode());
    assertEquals("FAILED_PRECONDITION", digests.get(4).errorCode());
    org.mockito.Mockito.verifyNoInteractions(worldManagementClient, controlPlaneDigestService);
  }

  @Test
  void selectedGateRequiresSchemaTwoAndPreservesUnsupportedOwnerDenial() {
    VersionDto version = selectedVersion();
    var binding = selectedBinding(version);
    var receipt = org.mockito.Mockito.mock(SelectedDraftGameLogicReceipt.class);
    stubSelectedParticipantDigests(version, binding, receipt);
    var digests =
        new java.util.ArrayList<>(
            service.collectSelectedFullVersionParticipantDigests(
                version, binding, binding.derivedWorkflowIdentity()));
    var selected = digests.get(4);
    digests.set(
        4,
        new PublishParticipantDigestDto(
            selected.participantKey(),
            selected.scopeValue(),
            selected.appliedCommitId(),
            selected.contentDigest(),
            1,
            null,
            null));
    var unsupported =
        assertThrows(
            PublishGateFailureException.class,
            () -> service.assertSelectedGatePassed(version, digests));
    assertEquals(PublishGateFailureCode.UNSUPPORTED_DIGEST_SCHEMA, unsupported.failureCode());
    assertTrue(unsupported.getMessage().contains("GAME_DESIGN_CONTROL_PLANE"));

    when(entityManagementClient.getDraftDesignDigestForVersion(binding))
        .thenReturn(
            new PublishParticipantDigestDto(
                "ENTITY_MANAGEMENT",
                "7",
                null,
                null,
                null,
                "UNSUPPORTED_SCOPE",
                "selected owner evidence unavailable"));
    var denied =
        service.collectSelectedFullVersionParticipantDigests(
            version, binding, binding.derivedWorkflowIdentity());
    assertEquals(5, denied.size());
    var failure =
        assertThrows(
            PublishGateFailureException.class,
            () -> service.assertSelectedGatePassed(version, denied));
    assertEquals(PublishGateFailureCode.PARTICIPANT_UNAVAILABLE, failure.failureCode());
    assertEquals("UNSUPPORTED_SCOPE", failure.participantFailureCode());

    when(entityManagementClient.getDraftDesignDigestForVersion(binding))
        .thenReturn(
            new PublishParticipantDigestDto(
                "ENTITY_MANAGEMENT", "7", "selected-commit", "entity-digest", 3, null, null));
    when(automationScriptingClient.getDraftDesignDigestForVersion(binding))
        .thenReturn(
            new PublishParticipantDigestDto(
                "AUTOMATION_SCRIPTING",
                "7",
                null,
                null,
                null,
                "UNSUPPORTED_SCOPE",
                "selected Automation evidence unavailable"));
    var automationDenied =
        service.collectSelectedFullVersionParticipantDigests(
            version, binding, binding.derivedWorkflowIdentity());
    var automationFailure =
        assertThrows(
            PublishGateFailureException.class,
            () -> service.assertSelectedGatePassed(version, automationDenied));
    assertEquals("UNSUPPORTED_SCOPE", automationFailure.participantFailureCode());
  }

  private void stubSelectedParticipantDigests(
      VersionDto version,
      PublicationDigestRequestBinding binding,
      SelectedDraftGameLogicReceipt receipt) {
    stubNonGameLogicParticipants(version, binding);
    when(selectedGameLogicReceiptRepository.readForPublication(binding))
        .thenReturn(Optional.of(receipt));
    when(gameLogicClient.getDraftDesignDigestForVersion(binding, receipt))
        .thenReturn(
            new PublishParticipantDigestDto(
                "GAME_LOGIC", "7", "selected-commit", "logic-digest", 1, null, null));
  }

  private void stubNonGameLogicParticipants(
      VersionDto version, PublicationDigestRequestBinding binding) {
    when(selectedPublicationDigestReader.read("test", binding))
        .thenReturn(selectedSnapshot(binding));
    when(entityManagementClient.getDraftDesignDigestForVersion(binding))
        .thenReturn(
            new PublishParticipantDigestDto(
                "ENTITY_MANAGEMENT", "7", "selected-commit", "entity-digest", 3, null, null));
    when(automationScriptingClient.getDraftDesignDigestForVersion(binding))
        .thenReturn(
            new PublishParticipantDigestDto(
                "AUTOMATION_SCRIPTING",
                "7",
                "selected-commit",
                "automation-digest",
                6,
                null,
                null));
  }

  private static SelectedDraftPublicationDigestReadService.ReadResult selectedSnapshot(
      PublicationDigestRequestBinding binding) {
    return new SelectedDraftPublicationDigestReadService.ReadResult(
        binding,
        binding.requestDigest(),
        new DesignControlPlaneDigestDto(
            binding.tenantId(), "7", "selected-commit", "c".repeat(64), 2),
        new PublishParticipantDigestDto(
            "WORLD_MANAGEMENT", "7", "selected-commit", "b".repeat(64), 4, null, null));
  }

  private static VersionDto selectedVersion() {
    return new VersionDto(
        7L,
        "private-tenant-key",
        8,
        VersionLifecycleState.DRAFT,
        2L,
        null,
        null,
        false,
        "selected notes",
        LocalDateTime.now(),
        LocalDateTime.now());
  }

  private static PublicationDigestRequestBinding selectedBinding(VersionDto version) {
    return PublicationDigestRequestBinding.full(
        "11111111-1111-4111-8111-111111111111", String.valueOf(version.id()), "selected-request-1");
  }

  private static PublicationDigestRequestBinding captureVersionBinding(
      WorldManagementClient client) {
    ArgumentCaptor<PublicationDigestRequestBinding> captor =
        ArgumentCaptor.forClass(PublicationDigestRequestBinding.class);
    verify(client).getDraftDesignDigestForVersion(captor.capture());
    return captor.getValue();
  }

  private static PublicationDigestRequestBinding captureVersionBinding(
      EntityManagementClient client) {
    ArgumentCaptor<PublicationDigestRequestBinding> captor =
        ArgumentCaptor.forClass(PublicationDigestRequestBinding.class);
    verify(client).getDraftDesignDigestForVersion(captor.capture());
    return captor.getValue();
  }

  private static PublicationDigestRequestBinding captureVersionBinding(GameLogicClient client) {
    ArgumentCaptor<PublicationDigestRequestBinding> captor =
        ArgumentCaptor.forClass(PublicationDigestRequestBinding.class);
    verify(client).getDraftDesignDigestForVersion(captor.capture());
    return captor.getValue();
  }

  private static PublicationDigestRequestBinding captureVersionBinding(
      AutomationScriptingClient client) {
    ArgumentCaptor<PublicationDigestRequestBinding> captor =
        ArgumentCaptor.forClass(PublicationDigestRequestBinding.class);
    verify(client).getDraftDesignDigestForVersion(captor.capture());
    return captor.getValue();
  }

  private static void assertBinding(
      PublicationDigestRequestBinding actual, PublicationDigestRequestBinding expected) {
    assertEquals(expected.tenantId(), actual.tenantId());
    assertEquals(expected.scopeKind(), actual.scopeKind());
    assertEquals(expected.versionId(), actual.versionId());
    assertEquals(expected.baseVersionId(), actual.baseVersionId());
    assertEquals(expected.scriptPatchVersion(), actual.scriptPatchVersion());
    assertEquals(expected.publishRequestId(), actual.publishRequestId());
    assertEquals(expected.derivedWorkflowIdentity(), actual.derivedWorkflowIdentity());
    assertEquals(expected.requestDigest(), actual.requestDigest());
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
                7L,
                "script-patch:patch-1",
                "digest-1",
                4,
                null,
                null),
            new PublishParticipantDigestDto(
                "WORLD_MANAGEMENT",
                "patch-1",
                7L,
                "script-patch:patch-1",
                "digest-world",
                3,
                null,
                null));

    PublishGateFailureException thrown =
        assertThrows(
            PublishGateFailureException.class, () -> service.assertGatePassed(version, digests));

    assertEquals(PublishGateFailureCode.PARTICIPANT_SET_MISMATCH, thrown.failureCode());
  }

  @Test
  void scriptPatchGateRejectsObsoleteAutomationDigestSchema() {
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
                7L,
                "script-patch:patch-1",
                "digest-1",
                4,
                null,
                null),
            new PublishParticipantDigestDto(
                "GAME_DESIGN_CONTROL_PLANE",
                "patch-1",
                7L,
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

  @Test
  void scriptPatchGateRejectsWrongBaseScope() {
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
                8L,
                "script-patch:patch-1",
                "digest-1",
                5,
                null,
                null),
            new PublishParticipantDigestDto(
                "GAME_DESIGN_CONTROL_PLANE",
                "patch-1",
                7L,
                "script-patch:patch-1",
                "digest-2",
                1,
                null,
                null));

    PublishGateFailureException thrown =
        assertThrows(
            PublishGateFailureException.class, () -> service.assertGatePassed(version, digests));

    assertEquals(PublishGateFailureCode.PARTICIPANT_SCOPE_MISMATCH, thrown.failureCode());
  }
}
