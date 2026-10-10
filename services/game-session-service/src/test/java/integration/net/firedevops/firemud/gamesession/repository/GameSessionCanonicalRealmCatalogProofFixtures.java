package net.firedevops.firemud.gamesession.repository;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidenceCodec;
import net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot;
import net.firedevops.firemud.common.gamedesign.RealmPolicySource;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.IsolatedPublicationOperationFixtures;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository.FreshTenantAssociation;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test-only storage evidence. Authenticated Game Design reads, registration completion, and the
 * actual owner publications are synthetic stipulations, so these fixtures prove neither GD
 * transport authentication nor a producer's real persisted row selection.
 */
final class GameSessionCanonicalRealmCatalogProofFixtures {
  private static final String POLICY_DIGEST = "sha256:" + "a".repeat(64);
  private static final String MANIFEST_HASH = "sha256:" + "b".repeat(64);
  private static final String NON_WORLD_CONTENT_DIGEST = "c".repeat(64);
  private static final String GAME_LOGIC_ABILITY_DIGEST = "sha256:" + "d".repeat(64);
  private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder().build();

  private GameSessionCanonicalRealmCatalogProofFixtures() {}

  static Proof forRuntimeTarget(RuntimeTarget target) {
    AuthoredWorldSourceEvidence source =
        source(
            target.targetNamespace(),
            target.canonicalTenantId(),
            target.tenantSlug(),
            target.worldSlug(),
            target.worldDisplayName(),
            target.sourceGameRowId(),
            target.sourceGameTenantKey(),
            target.sourceRegistrationRequestId(),
            target.sourceOperationId());
    return publishedSet(source);
  }

  static Proof forFreshAssociation(FreshTenantAssociation association) {
    String suffix = association.canonicalTenantId().toString().substring(0, 8);
    AuthoredWorldSourceEvidence source =
        source(
            association.targetNamespace(),
            association.canonicalTenantId(),
            "tenant-" + suffix,
            "world-" + suffix,
            "Fixture World " + suffix,
            association.sourceEvidence().sourceGameRowId(),
            association.sourceEvidence().sourceGameTenantKey(),
            UUID.randomUUID(),
            UUID.randomUUID());
    return publishedSet(source, RealmEntryPolicy.StateScope.SHARED);
  }

  static Proof isolatedForSource(AuthoredWorldSourceEvidence source) {
    return publishedSet(source, RealmEntryPolicy.StateScope.ISOLATED);
  }

  private static AuthoredWorldSourceEvidence source(
      String namespace,
      UUID canonicalTenantId,
      String tenantSlug,
      String worldSlug,
      String worldDisplayName,
      long sourceGameRowId,
      String sourceGameTenantKey,
      UUID registrationRequestId,
      UUID operationId) {
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            namespace,
            registrationRequestId,
            canonicalTenantId,
            tenantSlug,
            worldSlug,
            worldDisplayName);
    return new AuthoredWorldSourceEvidence(
        1,
        namespace,
        registrationRequestId,
        operationId,
        requestDigest,
        canonicalTenantId,
        tenantSlug,
        worldSlug,
        worldDisplayName,
        sourceGameRowId,
        sourceGameTenantKey,
        "NEW_GAME_ROW",
        AuthoredWorldSourceDigest.evidenceDigest(
            namespace,
            registrationRequestId,
            operationId,
            requestDigest,
            canonicalTenantId,
            tenantSlug,
            worldSlug,
            worldDisplayName,
            sourceGameRowId,
            sourceGameTenantKey,
            "NEW_GAME_ROW"));
  }

  private static Proof publishedSet(AuthoredWorldSourceEvidence source) {
    return publishedSet(source, RealmEntryPolicy.StateScope.SHARED);
  }

  private static Proof publishedSet(
      AuthoredWorldSourceEvidence source, RealmEntryPolicy.StateScope stateScope) {
    TargetProof target =
        new TargetProof(
            source.canonicalTenantId(),
            UUID.randomUUID(),
            Math.addExact(source.sourceGameRowId(), 1L),
            source.sourceGameTenantKey(),
            source.sourceGameRowId(),
            source.sourceGameTenantKey(),
            source.provenanceKind());
    GameDesignPublicationOperation operation;
    try {
      operation = IsolatedPublicationOperationFixtures.fresh(target);
    } catch (Exception invalidFixture) {
      throw new IllegalStateException(
          "Synthetic publication operation fixture is invalid", invalidFixture);
    }

    var selectedCommit = operation.account().input().selection().selectedCommit();
    String policyJson =
        RealmPolicySource.canonical(
            Map.of(
                "schemaVersion",
                1,
                "worldSlug",
                source.worldSlug(),
                "worldDisplayName",
                source.worldDisplayName(),
                "realmSlug",
                "main",
                "realmDisplayName",
                "Main Realm",
                "visible",
                true,
                "publicProduction",
                true,
                "stateScope",
                stateScope.name(),
                "entryPolicy",
                "PRESEEDED_ONLY"));
    RealmEntryPolicy entryPolicy = RealmEntryPolicy.parse(policyJson, JSON);
    RealmPolicySource.Policy policySource =
        new RealmPolicySource.Policy(
            selectedCommit.commitId(), UUID.randomUUID(), "synthetic-main-policy", entryPolicy);
    RealmPolicySnapshot capture =
        new RealmPolicySnapshot(selectedCommit, "1", List.of(policySource));

    String bundleRef = "fixture-publication/" + source.canonicalTenantId();
    var worldRequest = operation.world().request();
    long publicationEpoch = Math.addExact(worldRequest.versionStateEpoch(), 1L);
    List<GameDesignPublicationTerminalEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new GameDesignPublicationTerminalEvidence.Participant(
                        owner,
                        Long.toString(target.gameDesignVersionRowId()),
                        null,
                        worldRequest.appliedCommitId(),
                        "WORLD_MANAGEMENT".equals(owner)
                            ? worldRequest.contentDigest()
                            : NON_WORLD_CONTENT_DIGEST,
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner, AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION),
                        "GAME_LOGIC".equals(owner) ? GAME_LOGIC_ABILITY_DIGEST : null,
                        null,
                        null))
            .toList();
    var release =
        new GameDesignPublicationTerminalEvidence.ReleaseContent(
            source.canonicalTenantId(),
            target.canonicalVersionId(),
            bundleRef,
            1,
            "v2",
            worldRequest.publishWorkflowId(),
            MANIFEST_HASH,
            1,
            List.of(),
            List.of(),
            participants,
            List.of(),
            "generation-fixture",
            operation.world());
    var terminal =
        new GameDesignPublicationTerminalEvidence(
            operation.canonicalBytes(),
            GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED,
            release,
            publicationEpoch);

    UUID policyId = UUID.randomUUID();
    String policyDigest =
        RealmPolicyPublishedEvidence.policyDigest(
            policyId,
            target,
            release.versionNumber(),
            release.publishedReleaseBundleRef(),
            release.contentDigest(),
            release.publishWorkflowId(),
            release.manifestHash(),
            policySource);
    RealmPolicyPublishedEvidence.Policy publishedPolicy =
        new RealmPolicyPublishedEvidence.Policy(policyId, policySource, policyDigest);
    byte[] operationBytes = operation.canonicalBytes();
    var captureFrame = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(
        captureFrame, "game-design-realm-policy-source-capture/v1");
    DraftAuthorizationFenceBinding.frame(captureFrame, operationBytes);
    DraftAuthorizationFenceBinding.frame(captureFrame, capture.canonicalBytes());
    byte[] captureBytes = captureFrame.toByteArray();
    byte[] terminalBytes = terminal.canonicalBytes();
    List<RealmPolicyPublishedEvidence.Policy> policies = List.of(publishedPolicy);
    String setDigest =
        RealmPolicyPublishedEvidence.computePolicySetDigest(
            target,
            release.versionNumber(),
            policySource.commitId(),
            "1",
            release.publishedReleaseBundleRef(),
            release.contentDigest(),
            release.publishWorkflowId(),
            release.manifestHash(),
            publicationEpoch,
            operationBytes,
            captureBytes,
            terminalBytes,
            policies);
    var publishedSet =
        new RealmPolicyPublishedEvidence.PublishedSet(
            target,
            release.versionNumber(),
            policySource.commitId(),
            "1",
            release.publishedReleaseBundleRef(),
            release.contentDigest(),
            release.publishWorkflowId(),
            release.manifestHash(),
            publicationEpoch,
            operationBytes,
            captureBytes,
            terminalBytes,
            policies.size(),
            setDigest,
            policies);
    return new Proof(source, publishedSet);
  }

  record Proof(
      AuthoredWorldSourceEvidence source, RealmPolicyPublishedEvidence.PublishedSet publishedSet) {
    Proof {
      if (source == null || publishedSet == null) {
        throw new IllegalArgumentException(
            "Complete synthetic source and policy evidence required");
      }
    }

    byte[] completePolicySetBytes() {
      return RealmPolicyPublishedEvidenceCodec.encode(publishedSet);
    }
  }
}
