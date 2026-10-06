package unit.net.firedevops.firemud.gamesession.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogEntry;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogEntry.NamespaceResolution;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogSnapshot;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PublishedRealmCatalogSnapshotTest {
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final long GAME_SESSION_TENANT_ID = 70123L;
  private static final long SOURCE_GAME_ROW_ID = 501L;
  private static final String SOURCE_GAME_TENANT_KEY = "source-game-501";
  private static final String PROVENANCE_KIND = "RETAINED_GAME_V29";
  private static final String WORKFLOW = "publish:catalog-unit-test";
  private static final String MANIFEST = "manifest-catalog-unit-test";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void selectedSharedEntryRemainsUsableWhilePrivateLifecycleEvidenceIsMissing() {
    PublishedRealmEntryPolicySetEvidence policySet = policySet("ISOLATED", true);
    UUID sharedNamespaceId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    PublishedRealmCatalogSnapshot snapshot =
        new PublishedRealmCatalogSnapshot(
            GAME_SESSION_TENANT_ID,
            "unit-test",
            CANONICAL_TENANT_ID,
            SOURCE_GAME_ROW_ID,
            SOURCE_GAME_TENANT_KEY,
            PROVENANCE_KIND,
            4L,
            policySet,
            List.of(
                new PublishedRealmCatalogEntry(
                    GAME_SESSION_TENANT_ID,
                    4L,
                    UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                    sharedNamespaceId,
                    NamespaceResolution.RESOLVED,
                    policySet.policies().getFirst()),
                new PublishedRealmCatalogEntry(
                    GAME_SESSION_TENANT_ID,
                    4L,
                    UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                    null,
                    NamespaceResolution.AWAITING_LIFECYCLE_PROOF,
                    policySet.policies().get(1))),
            Instant.parse("2026-01-01T00:00:00Z"));

    assertThat(snapshot.requireVisibleEntryForAdmission("earth", "main").realmId())
        .isEqualTo(snapshot.entries().getFirst().realmId());
    assertThat(snapshot.entries().getFirst().requirePlayableStateNamespaceId())
        .isEqualTo(sharedNamespaceId);
    assertThatThrownBy(() -> snapshot.requireVisibleEntryForAdmission("earth", "playtest"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PUBLISHED_REALM_CATALOG_LIFECYCLE_UNRESOLVED");
    assertThatThrownBy(
            () ->
                new PublishedRealmCatalogEntry(
                    GAME_SESSION_TENANT_ID,
                    4L,
                    UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
                    sharedNamespaceId,
                    NamespaceResolution.RESOLVED,
                    policySet.policies().get(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("isolated catalog entry cannot resolve");
    assertThatThrownBy(snapshot::requireNamespaceResolved)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PUBLISHED_REALM_CATALOG_LIFECYCLE_UNRESOLVED");
  }

  @Test
  void visibilityGatesAdmissionWithoutReassigningStableNamespaceIdentity() {
    PublishedRealmEntryPolicySetEvidence policySet = policySet("SHARED", false);
    UUID sharedNamespaceId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    PublishedRealmCatalogSnapshot snapshot =
        new PublishedRealmCatalogSnapshot(
            GAME_SESSION_TENANT_ID,
            "unit-test",
            CANONICAL_TENANT_ID,
            SOURCE_GAME_ROW_ID,
            SOURCE_GAME_TENANT_KEY,
            PROVENANCE_KIND,
            4L,
            policySet,
            List.of(
                new PublishedRealmCatalogEntry(
                    GAME_SESSION_TENANT_ID,
                    4L,
                    UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                    sharedNamespaceId,
                    NamespaceResolution.RESOLVED,
                    policySet.policies().getFirst()),
                new PublishedRealmCatalogEntry(
                    GAME_SESSION_TENANT_ID,
                    4L,
                    UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                    sharedNamespaceId,
                    NamespaceResolution.RESOLVED,
                    policySet.policies().get(1))),
            Instant.parse("2026-01-01T00:00:00Z"));

    assertThat(snapshot.entries().get(1).playableStateNamespaceId()).isEqualTo(sharedNamespaceId);
    assertThatThrownBy(() -> snapshot.requireVisibleEntryForAdmission("earth", "playtest"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PUBLISHED_REALM_CATALOG_ENTRY_HIDDEN");
  }

  private static PublishedRealmEntryPolicySetEvidence policySet(
      String sideScope, boolean sideVisible) {
    long versionId = 9L;
    int versionNumber = 3;
    String workflow = WORKFLOW;
    String releaseBundleIdentity =
        PublishedRealmEntryPolicyEvidence.releaseBundleIdentity(
            CANONICAL_TENANT_ID, versionId, workflow, MANIFEST, JSON);
    List<PublishedRealmEntryPolicyEvidence> policies =
        List.of(
            evidence(versionId, versionNumber, releaseBundleIdentity, "main", true, true, "SHARED"),
            evidence(
                versionId,
                versionNumber,
                releaseBundleIdentity,
                "playtest",
                sideVisible,
                false,
                sideScope));
    return PublishedRealmEntryPolicySetEvidence.create(
        CANONICAL_TENANT_ID,
        versionId,
        versionNumber,
        releaseBundleIdentity,
        workflow,
        MANIFEST,
        policies,
        JSON);
  }

  private static PublishedRealmEntryPolicyEvidence evidence(
      long versionId,
      int versionNumber,
      String releaseBundleIdentity,
      String realmSlug,
      boolean visible,
      boolean publicProduction,
      String stateScope) {
    String policyJson =
        "{\"schemaVersion\":1,\"worldSlug\":\"earth\",\"worldDisplayName\":\"Earth\","
            + "\"realmSlug\":\""
            + realmSlug
            + "\",\"realmDisplayName\":\""
            + realmSlug
            + "\",\"visible\":"
            + visible
            + ",\"publicProduction\":"
            + publicProduction
            + ",\"stateScope\":\""
            + stateScope
            + "\",\"entryPolicy\":\"PRESEEDED_ONLY\"}";
    return PublishedRealmEntryPolicyEvidence.create(
        UUID.nameUUIDFromBytes((realmSlug + ":policy").getBytes(StandardCharsets.UTF_8)),
        CANONICAL_TENANT_ID,
        PROVENANCE_KIND,
        SOURCE_GAME_ROW_ID,
        SOURCE_GAME_TENANT_KEY,
        versionId,
        versionNumber,
        versionId * 10 + (realmSlug.equals("main") ? 1 : 2),
        releaseBundleIdentity,
        WORKFLOW,
        MANIFEST,
        RealmEntryPolicy.parse(policyJson, JSON),
        JSON);
  }
}
