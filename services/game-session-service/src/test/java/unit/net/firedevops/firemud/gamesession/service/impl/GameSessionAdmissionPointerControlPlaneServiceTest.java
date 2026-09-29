package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointer;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointerEvent;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.service.VersionUpgradePreparationService;
import org.junit.jupiter.api.Test;

class GameSessionAdmissionPointerControlPlaneServiceTest {
  @Test
  void listRejectsRetainedPreV7AuditWithUnknownRevisionAndIdentity() {
    GameplayAdmissionPointerRepository pointerRepository =
        mock(GameplayAdmissionPointerRepository.class);
    GameplayAdmissionPointerEventRepository eventRepository =
        mock(GameplayAdmissionPointerEventRepository.class);

    UUID realmId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID namespaceId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    GameplayAdmissionPointer currentPointer = new GameplayAdmissionPointer();
    currentPointer.setWorldSlug("demo");
    currentPointer.setWorldDisplayName("Demo World");
    currentPointer.setRealmSlug("production");
    currentPointer.setRealmDisplayName("Live Realm");
    currentPointer.setTenantId(1L);
    currentPointer.setGameInstanceId(7L);
    currentPointer.setPointerVersion(1L);
    currentPointer.setCatalogRevision(1L);
    currentPointer.setRealmId(realmId);
    currentPointer.setPlayableStateNamespaceId(namespaceId);
    currentPointer.setVisible(true);
    currentPointer.setPublicProductionRealm(true);
    currentPointer.setRequiresCharacterSelection(false);
    currentPointer.setStateScope("SHARED");
    currentPointer.setCharacterCreationPolicy("ALLOW_NEW");

    GameplayAdmissionPointerEvent retainedPreV7Event = new GameplayAdmissionPointerEvent();
    retainedPreV7Event.setWorldSlug("demo");
    retainedPreV7Event.setWorldDisplayName("Demo World");
    retainedPreV7Event.setRealmSlug("production");
    retainedPreV7Event.setRealmDisplayName("Live Realm");
    retainedPreV7Event.setTenantId(1L);
    retainedPreV7Event.setGameInstanceId(7L);
    retainedPreV7Event.setPointerVersion(1L);
    retainedPreV7Event.setVisible(true);
    retainedPreV7Event.setPublicProductionRealm(true);
    retainedPreV7Event.setRequiresCharacterSelection(false);
    retainedPreV7Event.setStateScope("SHARED");
    retainedPreV7Event.setCharacterCreationPolicy("ALLOW_NEW");
    retainedPreV7Event.setActorPrincipal("legacy-operator");
    retainedPreV7Event.setReason("retained pre-V7 event");
    retainedPreV7Event.setControlPlaneRequestId("legacy-request");
    retainedPreV7Event.setOccurredAt(Instant.parse("2026-09-29T00:00:00Z"));
    // Pre-V7 history has no evidence for these fields; it must not be upgraded by inference.

    when(pointerRepository.findAllByOrderByWorldSlugAscRealmSlugAsc())
        .thenReturn(List.of(currentPointer));
    when(
            eventRepository.findByTenantIdAndWorldSlugAndRealmSlugOrderByIdDesc(
                1L, "demo", "production"))
        .thenReturn(List.of(retainedPreV7Event));

    DatabaseGameplayAdmissionPointerAuthorityService authorityService =
        new DatabaseGameplayAdmissionPointerAuthorityService(pointerRepository, eventRepository);
    GameSessionAdmissionPointerControlPlaneService controlPlaneService =
        new GameSessionAdmissionPointerControlPlaneService(
            mock(GameInstanceRepository.class),
            authorityService,
            mock(VersionUpgradePreparationService.class));

    assertThrows(
        AdmissionPointerAuditUnavailableException.class,
        controlPlaneService::listAdmissionPointers);
  }
}
