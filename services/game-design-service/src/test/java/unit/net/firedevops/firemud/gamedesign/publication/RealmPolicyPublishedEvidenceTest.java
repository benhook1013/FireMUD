package unit.net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamedesign.publication.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RealmPolicyPublishedEvidenceTest {
  @Test
  void policyDigestMatchesIndependentCanonicalKnownVector() {
    var target =
        new TargetProof(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            42,
            "tenant-42",
            7,
            "tenant-42",
            "NEW_GAME_ROW");
    var source =
        new RealmPolicySource.Policy(
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            "main-policy-v1",
            RealmEntryPolicy.parse(
                "{\"schemaVersion\":1,\"worldSlug\":\"world\",\"worldDisplayName\":\"World\","
                    + "\"realmSlug\":\"main\",\"realmDisplayName\":\"Main\",\"visible\":true,"
                    + "\"publicProduction\":true,\"stateScope\":\"SHARED\","
                    + "\"entryPolicy\":\"PRESEEDED_ONLY\"}",
                new ObjectMapper()));
    UUID policyId = UUID.fromString("33333333-3333-4333-8333-333333333333");

    String digest =
        RealmPolicyPublishedEvidence.policyDigest(
            policyId,
            target,
            12,
            "bundle:exact",
            "sha256:" + "a".repeat(64),
            "workflow-123",
            "sha256:" + "b".repeat(64),
            source);

    assertThat(digest)
        .isEqualTo("sha256:1cb51d94ec2825d1d598fa30c197e6ce52d23b8a62106b04babcaafc92be2f47");
    var changedPolicy =
        new RealmPolicySource.Policy(
            source.commitId(),
            source.revisionId(),
            source.logicalRevisionId(),
            RealmEntryPolicy.parse(
                "{\"schemaVersion\":1,\"worldSlug\":\"world\",\"worldDisplayName\":\"World\","
                    + "\"realmSlug\":\"main\",\"realmDisplayName\":\"Main Changed\",\"visible\":true,"
                    + "\"publicProduction\":true,\"stateScope\":\"SHARED\","
                    + "\"entryPolicy\":\"PRESEEDED_ONLY\"}",
                new ObjectMapper()));
    assertThat(
            RealmPolicyPublishedEvidence.policyDigest(
                policyId,
                target,
                12,
                "bundle:exact",
                "sha256:" + "a".repeat(64),
                "workflow-123",
                "sha256:" + "b".repeat(64),
                changedPolicy))
        .isNotEqualTo(digest);
  }

  @Test
  void targetProofUsesCompleteStableIdentityAndExactDecimalRowIds() {
    var target = target();
    String encoded = RealmPolicyPublishedEvidence.targetProofJson(target);
    var decoded = new ObjectMapper().readTree(encoded);

    assertThat(decoded.size()).isEqualTo(7);
    assertThat(decoded.path("canonicalTenantId").asText())
        .isEqualTo(target.canonicalTenantId().toString());
    assertThat(decoded.path("canonicalVersionId").asText())
        .isEqualTo(target.canonicalVersionId().toString());
    assertThat(decoded.path("gameDesignVersionRowId").asText())
        .isEqualTo(Long.toString(target.gameDesignVersionRowId()));
    assertThat(decoded.path("sourceGameRowId").asText())
        .isEqualTo(Long.toString(target.sourceGameRowId()));
    assertThat(RealmPolicyPublishedEvidence.targetProofJson(target))
        .isEqualTo(RealmPolicyPublishedEvidence.targetProofJson(target));
  }

  @Test
  void policyAndCompleteSetDigestsBindOriginalSourceAndActualReleaseEvidence() {
    var target = target();
    var main = source("main", true, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    var privateRealm =
        source("private", false, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    UUID policyId = UUID.randomUUID();
    String bundleRef = "actual-owner-bundle-reference";
    String bundleDigest = digest('a');
    String workflow = "publish-workflow-original";
    String manifest = digest('b');

    String originalPolicyDigest =
        RealmPolicyPublishedEvidence.policyDigest(
            policyId, target, 12, bundleRef, bundleDigest, workflow, manifest, main);
    assertThat(
            RealmPolicyPublishedEvidence.policyDigest(
                policyId, target, 12, bundleRef, bundleDigest, workflow, manifest, main))
        .isEqualTo(originalPolicyDigest);
    assertThat(
            RealmPolicyPublishedEvidence.policyDigest(
                policyId, target, 12, "different-bundle", bundleDigest, workflow, manifest, main))
        .isNotEqualTo(originalPolicyDigest);
    assertThat(
            RealmPolicyPublishedEvidence.policyDigest(
                policyId, target, 12, bundleRef, digest('c'), workflow, manifest, main))
        .isNotEqualTo(originalPolicyDigest);
    var changedRevision =
        source("main", true, main.commitId(), UUID.randomUUID(), UUID.randomUUID());
    assertThat(
            RealmPolicyPublishedEvidence.policyDigest(
                policyId, target, 12, bundleRef, bundleDigest, workflow, manifest, changedRevision))
        .isNotEqualTo(originalPolicyDigest);

    // Use stable row identities so the set comparison covers only the requested byte change.
    var secondId = UUID.randomUUID();
    var rows =
        List.of(
            new RealmPolicyPublishedEvidence.Policy(policyId, main, originalPolicyDigest),
            new RealmPolicyPublishedEvidence.Policy(
                secondId,
                privateRealm,
                RealmPolicyPublishedEvidence.policyDigest(
                    secondId,
                    target,
                    12,
                    bundleRef,
                    bundleDigest,
                    workflow,
                    manifest,
                    privateRealm)));
    String originalSet =
        setDigest(target, main, bundleRef, bundleDigest, workflow, manifest, rows, new byte[] {1});
    assertThat(
            setDigest(
                target,
                main,
                bundleRef,
                bundleDigest,
                workflow,
                manifest,
                List.of(rows.get(1), rows.get(0)),
                new byte[] {1}))
        .isEqualTo(originalSet);
    assertThat(
            setDigest(
                target, main, bundleRef, bundleDigest, workflow, manifest, rows, new byte[] {2}))
        .isNotEqualTo(originalSet);
    assertThat(
            setDigest(
                target, main, bundleRef, digest('c'), workflow, manifest, rows, new byte[] {1}))
        .isNotEqualTo(originalSet);
  }

  private String setDigest(
      TargetProof target,
      RealmPolicySource.Policy main,
      String bundleRef,
      String bundleDigest,
      String workflow,
      String manifest,
      List<RealmPolicyPublishedEvidence.Policy> rows,
      byte[] terminal) {
    return RealmPolicyPublishedEvidence.computePolicySetDigest(
        target,
        12,
        main.commitId(),
        "43",
        bundleRef,
        bundleDigest,
        workflow,
        manifest,
        19,
        new byte[] {3},
        new byte[] {4},
        terminal,
        rows);
  }

  private static RealmPolicySource.Policy source(
      String realm,
      boolean production,
      UUID commitId,
      UUID revisionId,
      UUID logicalRevisionSuffix) {
    var policy =
        RealmEntryPolicy.parse(
            "{\"schemaVersion\":1,\"worldSlug\":\"world\",\"worldDisplayName\":\"World\","
                + "\"realmSlug\":\""
                + realm
                + "\",\"realmDisplayName\":\"Realm\",\"visible\":true,\"publicProduction\":"
                + production
                + ",\"stateScope\":\"SHARED\",\"entryPolicy\":\"PRESEEDED_ONLY\"}",
            new ObjectMapper());
    return new RealmPolicySource.Policy(
        commitId, revisionId, "source-" + logicalRevisionSuffix, policy);
  }

  private static TargetProof target() {
    return new TargetProof(
        UUID.randomUUID(),
        UUID.randomUUID(),
        Long.MAX_VALUE - 3,
        "ISOLATED-tenant",
        Long.MAX_VALUE - 7,
        "ISOLATED-tenant",
        "NEW_GAME_ROW");
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }
}
