package unit.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamesession.client.WorldManagementClient;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogEntry;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogEntry.NamespaceResolution;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindOwnerService;
import net.firedevops.firemud.gamesession.service.PublishedRealmCatalogOwnerService;
import net.firedevops.firemud.gamesession.service.PublishedRealmInitialAdmissionBindCommand;
import net.firedevops.firemud.gamesession.service.PublishedRealmInitialAdmissionBindCoordinator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PublishedRealmInitialAdmissionBindCoordinatorTest {
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID NAMESPACE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final long LOCAL_GAME_SESSION_TENANT_ID = 41L;
  private static final long SOURCE_GAME_ROW_ID = 731L;
  private static final long PUBLISHED_VERSION_ID = 902L;
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void distinctSourceAndLocalTenantIdsFailBeforeAuthoredLaunchOrWorldHold() {
    PublishedRealmCatalogOwnerService catalogOwner = mock(PublishedRealmCatalogOwnerService.class);
    GameInstanceService gameInstanceService = mock(GameInstanceService.class);
    InitialAdmissionBindOwnerService ownerService = mock(InitialAdmissionBindOwnerService.class);
    WorldManagementClient worldManagementClient = mock(WorldManagementClient.class);
    when(catalogOwner.materializePublishedSnapshot(CANONICAL_TENANT_ID, PUBLISHED_VERSION_ID))
        .thenReturn(snapshotWithDistinctSourceAndLocalIds());
    PublishedRealmInitialAdmissionBindCoordinator coordinator =
        new PublishedRealmInitialAdmissionBindCoordinator(
            catalogOwner, gameInstanceService, ownerService, worldManagementClient);

    assertThatThrownBy(
            () ->
                coordinator.coordinate(
                    new PublishedRealmInitialAdmissionBindCommand(
                        CANONICAL_TENANT_ID,
                        PUBLISHED_VERSION_ID,
                        12L,
                        88L,
                        "earth",
                        "main",
                        "44444444-4444-4444-8444-444444444444")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("INITIAL_ADMISSION_LAUNCH_TENANT_IDENTITY_UNAVAILABLE")
        .hasMessageContaining("source/local identity adapter is required");

    verifyNoInteractions(gameInstanceService, ownerService, worldManagementClient);
  }

  private static PublishedRealmCatalogSnapshot snapshotWithDistinctSourceAndLocalIds() {
    String workflow = "publish:published-bind-unit-test";
    String manifest = "published-bind-manifest";
    long versionId = PUBLISHED_VERSION_ID;
    int versionNumber = 3;
    RealmEntryPolicy policy =
        RealmEntryPolicy.parse(
            "{\"schemaVersion\":1,\"worldSlug\":\"earth\",\"worldDisplayName\":\"Earth\","
                + "\"realmSlug\":\"main\",\"realmDisplayName\":\"Main\",\"visible\":true,"
                + "\"publicProduction\":true,\"stateScope\":\"SHARED\","
                + "\"entryPolicy\":\"PRESEEDED_ONLY\"}",
            JSON);
    String releaseIdentity =
        PublishedRealmEntryPolicyEvidence.releaseBundleIdentity(
            CANONICAL_TENANT_ID, versionId, workflow, manifest, JSON);
    PublishedRealmEntryPolicyEvidence policyEvidence =
        PublishedRealmEntryPolicyEvidence.create(
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            CANONICAL_TENANT_ID,
            "RETAINED_GAME_V29",
            SOURCE_GAME_ROW_ID,
            "gd-source-731",
            versionId,
            versionNumber,
            903L,
            releaseIdentity,
            workflow,
            manifest,
            policy,
            JSON);
    PublishedRealmEntryPolicySetEvidence policySet =
        PublishedRealmEntryPolicySetEvidence.create(
            CANONICAL_TENANT_ID,
            versionId,
            versionNumber,
            releaseIdentity,
            workflow,
            manifest,
            List.of(policyEvidence),
            JSON);
    return new PublishedRealmCatalogSnapshot(
        LOCAL_GAME_SESSION_TENANT_ID,
        "gameplay-test",
        CANONICAL_TENANT_ID,
        SOURCE_GAME_ROW_ID,
        "gd-source-731",
        "RETAINED_GAME_V29",
        5L,
        policySet,
        List.of(
            new PublishedRealmCatalogEntry(
                LOCAL_GAME_SESSION_TENANT_ID,
                5L,
                REALM_ID,
                NAMESPACE_ID,
                NamespaceResolution.RESOLVED,
                policyEvidence)),
        Instant.parse("2026-01-01T00:00:00Z"));
  }
}
