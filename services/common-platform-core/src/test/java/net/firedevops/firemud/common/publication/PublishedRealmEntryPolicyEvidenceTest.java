package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PublishedRealmEntryPolicyEvidenceTest {
  private static final TargetProof TARGET =
      new TargetProof(
          UUID.fromString("11111111-1111-4111-8111-111111111111"),
          UUID.fromString("22222222-2222-4222-8222-222222222222"),
          42,
          "tenant-42",
          7,
          "tenant-42",
          "NEW_GAME_ROW");
  private static final UUID POLICY_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID SOURCE_COMMIT_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID SOURCE_REVISION_ID =
      UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void policyDigestMatchesTheOwnerKnownVectorForTheActualReleaseIdentity() {
    RealmEntryPolicy policy = policy("world", "World", "main", "Main", true, true);

    String digest =
        PublishedRealmEntryPolicyEvidence.calculateDigest(
            POLICY_ID,
            TARGET,
            12,
            "bundle:exact",
            "sha256:" + "a".repeat(64),
            "workflow-123",
            "sha256:" + "b".repeat(64),
            SOURCE_COMMIT_ID,
            SOURCE_REVISION_ID,
            "main-policy-v1",
            policy);

    // Shared carrier vector copied from the independent Game Design owner implementation proof.
    assertThat(digest)
        .isEqualTo("sha256:1cb51d94ec2825d1d598fa30c197e6ce52d23b8a62106b04babcaafc92be2f47");
  }

  @Test
  void policyDigestChangesWhenAnyAuthoredOrActualReleaseIdentityChanges() {
    RealmEntryPolicy policy = policy("world", "World", "main", "Main", true, true);
    String original =
        digest(policy, TARGET, 12, "bundle:exact", "workflow-123", SOURCE_REVISION_ID);

    assertThat(digest(policy, TARGET, 13, "bundle:exact", "workflow-123", SOURCE_REVISION_ID))
        .isNotEqualTo(original);
    assertThat(digest(policy, TARGET, 12, "bundle:changed", "workflow-123", SOURCE_REVISION_ID))
        .isNotEqualTo(original);
    assertThat(digest(policy, TARGET, 12, "bundle:exact", "workflow-changed", SOURCE_REVISION_ID))
        .isNotEqualTo(original);
    var changedTarget =
        new TargetProof(
            TARGET.canonicalTenantId(),
            TARGET.canonicalVersionId(),
            TARGET.gameDesignVersionRowId(),
            TARGET.gameDesignVersionTenantKey(),
            TARGET.sourceGameRowId() + 1,
            TARGET.sourceGameTenantKey(),
            TARGET.sourceProvenanceKind());
    assertThat(
            digest(policy, changedTarget, 12, "bundle:exact", "workflow-123", SOURCE_REVISION_ID))
        .isNotEqualTo(original);
    assertThat(
            PublishedRealmEntryPolicyEvidence.calculateDigest(
                POLICY_ID,
                TARGET,
                12,
                "bundle:exact",
                "sha256:" + "c".repeat(64),
                "workflow-123",
                "sha256:" + "b".repeat(64),
                SOURCE_COMMIT_ID,
                SOURCE_REVISION_ID,
                "main-policy-v1",
                policy))
        .isNotEqualTo(original);
    assertThat(
            PublishedRealmEntryPolicyEvidence.calculateDigest(
                POLICY_ID,
                TARGET,
                12,
                "bundle:exact",
                "sha256:" + "a".repeat(64),
                "workflow-123",
                "sha256:" + "c".repeat(64),
                SOURCE_COMMIT_ID,
                SOURCE_REVISION_ID,
                "main-policy-v1",
                policy))
        .isNotEqualTo(original);
    assertThat(
            PublishedRealmEntryPolicyEvidence.calculateDigest(
                UUID.fromString("66666666-6666-4666-8666-666666666666"),
                TARGET,
                12,
                "bundle:exact",
                "sha256:" + "a".repeat(64),
                "workflow-123",
                "sha256:" + "b".repeat(64),
                SOURCE_COMMIT_ID,
                SOURCE_REVISION_ID,
                "main-policy-v1",
                policy))
        .isNotEqualTo(original);
    assertThat(
            PublishedRealmEntryPolicyEvidence.calculateDigest(
                POLICY_ID,
                TARGET,
                12,
                "bundle:exact",
                "sha256:" + "a".repeat(64),
                "workflow-123",
                "sha256:" + "b".repeat(64),
                UUID.fromString("77777777-7777-4777-8777-777777777777"),
                SOURCE_REVISION_ID,
                "main-policy-v1",
                policy))
        .isNotEqualTo(original);
    assertThat(
            digest(
                policy,
                TARGET,
                12,
                "bundle:exact",
                "workflow-123",
                UUID.fromString("88888888-8888-4888-8888-888888888888")))
        .isNotEqualTo(original);
    assertThat(
            PublishedRealmEntryPolicyEvidence.calculateDigest(
                POLICY_ID,
                TARGET,
                12,
                "bundle:exact",
                "sha256:" + "a".repeat(64),
                "workflow-123",
                "sha256:" + "b".repeat(64),
                SOURCE_COMMIT_ID,
                SOURCE_REVISION_ID,
                "main-policy-v2",
                policy))
        .isNotEqualTo(original);
    assertThat(
            digest(
                policy("world", "World", "main", "Main Changed", true, true),
                TARGET,
                12,
                "bundle:exact",
                "workflow-123",
                SOURCE_REVISION_ID))
        .isNotEqualTo(original);
  }

  @Test
  void childEvidenceRetainsTheCanonicalPolicyAndRejectsMalformedIdentity() {
    RealmEntryPolicy policy = policy("world", "World", "main", "Main", true, true);
    String digest = digest(policy, TARGET, 12, "bundle:exact", "workflow-123", SOURCE_REVISION_ID);
    var evidence =
        new PublishedRealmEntryPolicyEvidence(
            POLICY_ID, SOURCE_COMMIT_ID, SOURCE_REVISION_ID, "main-policy-v1", policy, digest);

    assertThat(evidence.policy().canonicalJson()).isEqualTo(policy.canonicalJson());
    assertThat(evidence.policyId()).isEqualTo(POLICY_ID);
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new PublishedRealmEntryPolicyEvidence(
                    POLICY_ID,
                    SOURCE_COMMIT_ID,
                    SOURCE_REVISION_ID,
                    "main-policy-v1",
                    policy,
                    "not-a-digest"));
  }

  private static String digest(
      RealmEntryPolicy policy,
      TargetProof target,
      int versionNumber,
      String bundleRef,
      String workflow,
      UUID sourceRevisionId) {
    return PublishedRealmEntryPolicyEvidence.calculateDigest(
        POLICY_ID,
        target,
        versionNumber,
        bundleRef,
        "sha256:" + "a".repeat(64),
        workflow,
        "sha256:" + "b".repeat(64),
        SOURCE_COMMIT_ID,
        sourceRevisionId,
        "main-policy-v1",
        policy);
  }

  private static RealmEntryPolicy policy(
      String world,
      String worldName,
      String realm,
      String realmName,
      boolean visible,
      boolean publicProduction) {
    return RealmEntryPolicy.parse(
        "{\"schemaVersion\":1,\"worldSlug\":\""
            + world
            + "\",\"worldDisplayName\":\""
            + worldName
            + "\",\"realmSlug\":\""
            + realm
            + "\",\"realmDisplayName\":\""
            + realmName
            + "\",\"visible\":"
            + visible
            + ",\"publicProduction\":"
            + publicProduction
            + ",\"stateScope\":\"SHARED\",\"entryPolicy\":\"PRESEEDED_ONLY\"}",
        MAPPER);
  }
}
