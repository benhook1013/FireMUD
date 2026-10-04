package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PublishedRealmEntryPolicyEvidenceTest {
  private static final UUID TENANT_ID = UUID.fromString("e84e0676-77f2-4bac-8be5-e89d4f8b8f01");
  private static final String WORKFLOW = "publish:tenant:publish-request:req";
  private static final String MANIFEST = "manifest-sha256";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void parserAcceptsOnlyClosedV1AndCanonicalReadbackRequiresExactJson() {
    String authored =
        "{ \"schemaVersion\":1, \"worldSlug\":\"earth\", "
            + "\"worldDisplayName\":\"Earth\", \"realmSlug\":\"main\", "
            + "\"realmDisplayName\":\"Main\", \"visible\":true, "
            + "\"publicProduction\":true, \"stateScope\":\"SHARED\", "
            + "\"entryPolicy\":\"PRESEEDED_ONLY\" }";

    RealmEntryPolicy policy = RealmEntryPolicy.parse(authored, MAPPER);

    assertThat(policy.stateScope()).isEqualTo(RealmEntryPolicy.StateScope.SHARED);
    assertThat(policy.entryPolicy()).isEqualTo(RealmEntryPolicy.EntryPolicy.PRESEEDED_ONLY);
    assertThat(policy.canonicalJson()).doesNotContain(": ");
    assertThat(RealmEntryPolicy.isCanonicalSlug("demo--world")).isFalse();
    assertThatIllegalArgumentException()
        .isThrownBy(() -> RealmEntryPolicy.parseCanonical(authored, MAPPER));
    assertThat(RealmEntryPolicy.parseCanonical(policy.canonicalJson(), MAPPER)).isEqualTo(policy);
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                RealmEntryPolicy.parse(
                    policy.canonicalJson().replace("\"visible\":true", "\"visible\":1"), MAPPER));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                RealmEntryPolicy.parse(
                    policy
                        .canonicalJson()
                        .replace("\"worldSlug\":\"earth\"", "\"worldSlug\":\"demo--world\""),
                    MAPPER));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                RealmEntryPolicy.parse(
                    policy.canonicalJson().replace("\"entryPolicy\":\"PRESEEDED_ONLY\"", ""),
                    MAPPER));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                RealmEntryPolicy.parse(
                    policy.canonicalJson().substring(0, policy.canonicalJson().length() - 1)
                        + ",\"descriptor\":{}}",
                    MAPPER));
  }

  @Test
  void policyEvidenceDigestBindsTenantSourceVersionBundleAndCanonicalPolicy() {
    PublishedRealmEntryPolicyEvidence evidence = policyEvidence("main", true, true);

    assertThat(evidence.hasValidDigest(MAPPER)).isTrue();
    assertThat(evidence.requireValidDigest(MAPPER)).isSameAs(evidence);
    assertThat(evidence.policyDigest()).startsWith("sha256:").hasSize(71);
    assertThat(
            PublishedRealmEntryPolicyEvidence.releaseBundleIdentity(
                TENANT_ID, 7L, WORKFLOW, MANIFEST, MAPPER))
        .isEqualTo(evidence.releaseBundleIdentity());
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new PublishedRealmEntryPolicyEvidence(
                        evidence.policyId(),
                        evidence.canonicalTenantId(),
                        evidence.tenantIdentityProvenanceKind(),
                        evidence.sourceGameRowId(),
                        evidence.sourceGameTenantKey(),
                        evidence.versionId() + 1,
                        evidence.versionNumber(),
                        evidence.sourceRevisionId(),
                        evidence.releaseBundleIdentity(),
                        evidence.publishWorkflowId(),
                        evidence.manifestHash(),
                        evidence.policy(),
                        evidence.policyDigest())
                    .requireValidDigest(MAPPER));
  }

  @Test
  void completeSetSortsRowsAndBindsCountAndEveryPolicyDigest() {
    PublishedRealmEntryPolicyEvidence main = policyEvidence("main", true, true);
    PublishedRealmEntryPolicyEvidence side = policyEvidence("side", false, false);

    PublishedRealmEntryPolicySetEvidence set =
        PublishedRealmEntryPolicySetEvidence.create(
            TENANT_ID,
            7L,
            3,
            main.releaseBundleIdentity(),
            WORKFLOW,
            MANIFEST,
            List.of(side, main),
            MAPPER);

    assertThat(set.policies()).containsExactly(main, side);
    assertThat(set.hasValidDigest(MAPPER)).isTrue();
    assertThat(set.requireValidDigest(MAPPER)).isSameAs(set);
    assertThat(set.policySetDigest()).startsWith("sha256:").hasSize(71);
    assertThat(
            PublishedRealmEntryPolicySetEvidence.create(
                    TENANT_ID,
                    7L,
                    3,
                    main.releaseBundleIdentity(),
                    WORKFLOW,
                    MANIFEST,
                    List.of(main, side),
                    MAPPER)
                .policySetDigest())
        .isEqualTo(set.policySetDigest());
  }

  @Test
  void completeSetRejectsMissingMultipleOrOversizedAddressableSets() {
    PublishedRealmEntryPolicyEvidence main = policyEvidence("main", true, true);
    PublishedRealmEntryPolicyEvidence side = policyEvidence("side", true, true);
    String identity = main.releaseBundleIdentity();

    assertThatIllegalArgumentException()
        .isThrownBy(() -> createSet(identity, List.of(policyEvidence("hidden", false, false))));
    assertThatIllegalArgumentException().isThrownBy(() -> createSet(identity, List.of(main, side)));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                createSet(
                    identity,
                    java.util.Collections.nCopies(
                      PublishedRealmEntryPolicySetEvidence.MAX_POLICIES + 1, main)));
  }

  @Test
  void completeSetRejectsRealmSlugRepeatedAcrossDifferentWorlds() {
    PublishedRealmEntryPolicyEvidence publicRealm =
        policyEvidence("main", "earth", true, true);
    PublishedRealmEntryPolicyEvidence duplicateRealm =
        policyEvidence("main", "mars", false, false);

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                createSet(
                    publicRealm.releaseBundleIdentity(), List.of(publicRealm, duplicateRealm)))
        .withMessageContaining("realm slug more than once");
  }

  private static PublishedRealmEntryPolicySetEvidence createSet(
      String releaseBundleIdentity, List<PublishedRealmEntryPolicyEvidence> policies) {
    return PublishedRealmEntryPolicySetEvidence.create(
        TENANT_ID, 7L, 3, releaseBundleIdentity, WORKFLOW, MANIFEST, policies, MAPPER);
  }

  private static PublishedRealmEntryPolicyEvidence policyEvidence(
      String realmSlug, boolean visible, boolean publicProduction) {
    return policyEvidence(realmSlug, "earth", visible, publicProduction);
  }

  private static PublishedRealmEntryPolicyEvidence policyEvidence(
      String realmSlug, String worldSlug, boolean visible, boolean publicProduction) {
    String sourceJson =
        "{\"schemaVersion\":1,\"worldSlug\":\""
            + worldSlug
            + "\",\"worldDisplayName\":\""
            + worldSlug
            + "\","
            + "\"realmSlug\":\""
            + realmSlug
            + "\",\"realmDisplayName\":\""
            + realmSlug
            + "\",\"visible\":"
            + visible
            + ",\"publicProduction\":"
            + publicProduction
            + ",\"stateScope\":\"SHARED\",\"entryPolicy\":\"PRESEEDED_ONLY\"}";
    RealmEntryPolicy policy = RealmEntryPolicy.parse(sourceJson, MAPPER);
    String releaseIdentity =
        PublishedRealmEntryPolicyEvidence.releaseBundleIdentity(
            TENANT_ID, 7L, WORKFLOW, MANIFEST, MAPPER);
    return PublishedRealmEntryPolicyEvidence.create(
        UUID.nameUUIDFromBytes(
            (worldSlug + ":" + realmSlug).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        TENANT_ID,
        "NEW_GAME_ROW",
        12L,
        "tenant-key",
        7L,
        3,
        realmSlug.equals("main") ? 42L : 43L,
        releaseIdentity,
        WORKFLOW,
        MANIFEST,
        policy,
        MAPPER);
  }
}
