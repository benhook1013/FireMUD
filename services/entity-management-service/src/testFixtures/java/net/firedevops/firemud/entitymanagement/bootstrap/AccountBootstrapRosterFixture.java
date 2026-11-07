package net.firedevops.firemud.entitymanagement.bootstrap;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.common.testing.PlayerCreatedPublishedPolicyFixtures;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.AccountBootstrapRosterReadRequest;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.test.IsolatedWorldPublicationInventoryFixtures;
import tools.jackson.databind.json.JsonMapper;

/**
 * Complete closed upstream bytes are controlled test input, not executed publication/World custody.
 */
public final class AccountBootstrapRosterFixture {
  public static final String NAMESPACE = "test";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private AccountBootstrapRosterFixture() {}

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "The retained generated protobuf request message is immutable.")
  public record Fixture(
      AccountBootstrapRosterReadRequest request,
      CanonicalGameplayRosterTarget target,
      CanonicalGameplayRosterOwnerReadEvidence evidence) {
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "The retained generated protobuf request message is immutable.")
    public Fixture {}
  }

  public static Fixture create() throws Exception {
    var account = AuthoringFixtures.freshGraphRequest().accountBinding();
    var draft =
        DraftCommitBinding.fromStored(
            new String(account.gameDesignBinding(), StandardCharsets.UTF_8), account.inputDigest());
    var prepared = PlayerCreatedPublishedPolicyFixtures.prepare(draft);
    var operation =
        new GameDesignPublicationOperationBinding(
            prepared.account(),
            prepared.world(),
            IsolatedWorldPublicationInventoryFixtures.stipulated(
                prepared.account(), prepared.world()));
    var world = prepared.world().request();
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new GameDesignPublicationTerminalEvidence.Participant(
                        owner,
                        Long.toString(draft.target().gameDesignVersionRowId()),
                        null,
                        world.appliedCommitId(),
                        owner.equals("WORLD_MANAGEMENT") ? world.contentDigest() : "c".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner, AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION),
                        owner.equals("GAME_LOGIC") ? "sha256:" + "a".repeat(64) : null,
                        null,
                        null))
            .toList();
    var release =
        new GameDesignPublicationTerminalEvidence.ReleaseContent(
            world.canonicalTenantId(),
            world.canonicalVersionId(),
            "bundle:bootstrap-test",
            3,
            "v2",
            world.publishWorkflowId(),
            "sha256:" + "a".repeat(64),
            1,
            List.of(),
            List.of(),
            participants,
            List.of("LOOK"),
            "generation-1",
            prepared.world());
    var terminal =
        new GameDesignPublicationTerminalEvidence(
            operation.canonicalBytes(),
            GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED,
            release,
            6L);
    var policy =
        RealmEntryPolicy.parse(
            """
        {"schemaVersion":1,"worldSlug":"earth","worldDisplayName":"Earth",
        "realmSlug":"main","realmDisplayName":"Main","visible":true,
        "publicProduction":true,"stateScope":"SHARED","entryPolicy":"PRESEEDED_ONLY"}
        """,
            JSON);
    UUID policyId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    String logical = "realm-policy/earth/main";
    var selected =
        new PublishedRealmEntryPolicyEvidence(
            policyId,
            draft.commitId(),
            revisionId,
            logical,
            policy,
            PublishedRealmEntryPolicyEvidence.calculateDigest(
                policyId,
                draft.target(),
                3,
                release.publishedReleaseBundleRef(),
                release.contentDigest(),
                world.publishWorkflowId(),
                release.manifestHash(),
                draft.commitId(),
                revisionId,
                logical,
                policy));
    var snapshot =
        Map.of(
            "schema",
            PublishedRealmEntryPolicySetEvidence.SNAPSHOT_SCHEMA,
            "bindingJson",
            draft.canonicalJson(),
            "bindingDigest",
            draft.digest(),
            "sourceEpoch",
            "1",
            "policies",
            List.of(
                Map.of(
                    "commitId",
                    draft.commitId().toString(),
                    "revisionId",
                    revisionId.toString(),
                    "logicalRevisionId",
                    logical,
                    "policy",
                    JSON.readTree(policy.canonicalJson()))));
    var capture = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(
        capture, PublishedRealmEntryPolicySetEvidence.CAPTURE_SCHEMA);
    DraftAuthorizationFenceBinding.frame(capture, operation.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(
        capture, Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(snapshot)));
    var policies = List.of(selected);
    String digest =
        PublishedRealmEntryPolicySetEvidence.calculatePolicySetDigest(
            draft.target(),
            3,
            draft.commitId(),
            "1",
            release.publishedReleaseBundleRef(),
            release.contentDigest(),
            world.publishWorkflowId(),
            release.manifestHash(),
            6L,
            operation.canonicalBytes(),
            capture.toByteArray(),
            terminal.canonicalBytes(),
            policies);
    var policySet =
        new PublishedRealmEntryPolicySetEvidence(
            draft.target(),
            3,
            draft.commitId(),
            "1",
            release.publishedReleaseBundleRef(),
            release.contentDigest(),
            world.publishWorkflowId(),
            release.manifestHash(),
            6L,
            operation.canonicalBytes(),
            capture.toByteArray(),
            terminal.canonicalBytes(),
            1,
            digest,
            policies);
    UUID realm = UUID.randomUUID();
    UUID namespace = UUID.randomUUID();
    UUID instance = UUID.randomUUID();
    var hold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            new WorldCanonicalInitialAdmissionHold.Request(
                NAMESPACE,
                draft.target().canonicalTenantId(),
                "earth",
                realm,
                namespace,
                "SHARED",
                instance,
                draft.target().canonicalVersionId(),
                82L,
                "controlled-initial-target",
                "d".repeat(64),
                WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
                71L,
                null),
            UUID.randomUUID(),
            UUID.randomUUID());
    var proof =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            hold,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
            73L,
            91L,
            "sha256:" + "b".repeat(64),
            false,
            Instant.parse("2026-10-08T03:04:05Z"));
    var target =
        new CanonicalGameplayRosterTarget(
            draft.target().canonicalTenantId(),
            realm,
            "earth",
            "main",
            instance,
            71L,
            73L,
            82L,
            draft.target().canonicalVersionId(),
            selected.policyDigest().substring(7),
            release.publishedReleaseBundleRef(),
            "a".repeat(64),
            "b".repeat(64),
            namespace,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            CanonicalGameplayRosterEntryPolicy.PRESEEDED_ONLY);
    var request =
        AccountBootstrapRosterReadRequest.newBuilder()
            .setRequestUuid(UUID.randomUUID().toString())
            .setCanonicalAccountUuid(UUID.randomUUID().toString())
            .setTargetNamespace(NAMESPACE)
            .setExpectedTarget(target.toWire())
            .build();
    var ownerRequest =
        new CanonicalGameplayRosterOwnerReadEvidence.Request(
            UUID.fromString(request.getRequestUuid()),
            UUID.fromString(request.getCanonicalAccountUuid()),
            NAMESPACE,
            target.tenantUuid(),
            "earth",
            realm,
            "main",
            namespace,
            "SHARED",
            instance,
            target.canonicalVersionUuid(),
            71L,
            73L,
            82L);
    return new Fixture(
        request,
        target,
        new CanonicalGameplayRosterOwnerReadEvidence(
            ownerRequest, target.admissionPointerSnapshotDigest(), proof, policySet));
  }
}
