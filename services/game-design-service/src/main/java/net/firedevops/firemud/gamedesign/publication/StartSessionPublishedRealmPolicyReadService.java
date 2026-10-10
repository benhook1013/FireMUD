package net.firedevops.firemud.gamedesign.publication;

import io.grpc.Status;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owner-local exact source and immutable published-realm-policy read for a future StartSession
 * transport.
 */
public final class StartSessionPublishedRealmPolicyReadService {
  private final GameAuthoredWorldSourceRepository sources;
  private final RealmPolicyPublicationRepository policies;
  private final TransactionTemplate snapshot;

  public StartSessionPublishedRealmPolicyReadService(
      GameAuthoredWorldSourceRepository sources,
      RealmPolicyPublicationRepository policies,
      PlatformTransactionManager transactions) {
    this.sources = Objects.requireNonNull(sources, "sources");
    this.policies = Objects.requireNonNull(policies, "policies");
    snapshot = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
    snapshot.setName("game-design-start-session-published-realm-policy-read");
    snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    snapshot.setReadOnly(true);
  }

  /**
   * Reads both immutable owner records in one independent snapshot and requires exact agreement
   * with the descriptor, full release attestation, and persisted canonical TargetProof.
   */
  public ReadResult read(ReadRequest request) {
    Objects.requireNonNull(request, "request");
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Independent Game Design REPEATABLE_READ snapshot required")
          .asRuntimeException();
    }

    return Objects.requireNonNull(
        snapshot.execute(ignored -> readSnapshot(request)),
        "Published realm-policy snapshot returned no result");
  }

  private ReadResult readSnapshot(ReadRequest request) {
    AuthoredWorldLaunchDescriptorEvidence descriptor = request.descriptor();
    AuthoredWorldReleaseAttestationEvidence release = request.releaseAttestation();
    TargetProof target = request.target();

    descriptor.requireValid();
    release.requireValid(descriptor);
    if (!descriptor.canonicalTenantId().equals(target.canonicalTenantId())
        || !release.canonicalVersionId().equals(target.canonicalVersionId())
        || descriptor.versionId() != target.gameDesignVersionRowId()) {
      throw new IllegalStateException("START_SESSION_POLICY_REQUEST_BINDING_CONFLICT");
    }

    AuthoredWorldSourceEvidence source =
        sources
            .readExactSourceSnapshot(
                descriptor.targetNamespace(),
                descriptor.authoredWorldSourceOperationId(),
                descriptor.canonicalTenantId(),
                descriptor.worldSlug(),
                descriptor.authoredWorldSourceEvidenceDigest())
            .orElseThrow(() -> new IllegalStateException("AUTHORED_WORLD_SOURCE_UNAVAILABLE"));
    if (!descriptor.targetNamespace().equals(source.targetNamespace())
        || !descriptor.authoredWorldSourceOperationId().equals(source.operationId())
        || !descriptor.authoredWorldSourceEvidenceDigest().equals(source.evidenceDigest())
        || !descriptor.canonicalTenantId().equals(source.canonicalTenantId())
        || !descriptor.worldSlug().equals(source.worldSlug())
        || source.sourceGameRowId() != target.sourceGameRowId()
        || !source.sourceGameTenantKey().equals(target.sourceGameTenantKey())
        || !source.provenanceKind().equals(target.sourceProvenanceKind())) {
      throw new IllegalStateException("START_SESSION_POLICY_SOURCE_TARGET_CONFLICT");
    }

    RealmPolicyPublishedEvidence.PublishedSet published =
        policies
            .readPublishedSet(target)
            .orElseThrow(() -> new IllegalStateException("PUBLISHED_REALM_POLICY_SET_UNAVAILABLE"));
    if (!target.equals(published.target())) {
      throw new IllegalStateException("START_SESSION_POLICY_TARGET_PROOF_CONFLICT");
    }
    GameDesignPublicationTerminalEvidence terminal =
        GameDesignPublicationTerminalEvidence.fromStored(published.terminalEvidenceBytes());
    var content = terminal.releaseContent();
    if (!descriptor.publishedReleaseBundleRef().equals(published.publishedReleaseBundleRef())
        || !release.publishedReleaseBundleRef().equals(published.publishedReleaseBundleRef())
        || !release.publishWorkflowId().equals(published.publishWorkflowId())
        || !release.manifestHash().equals(published.manifestHash())
        || !published.sourceCommitId().toString().equals(release.commitId())
        || published.publicationVersionStateEpoch() > release.versionStateEpoch()
        || terminal.outcome() != GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
        || terminal.publicationVersionStateEpoch() != published.publicationVersionStateEpoch()
        || !terminal.publishedReleaseBundleRef().equals(published.publishedReleaseBundleRef())
        || !terminal.publishedReleaseBundleDigest().equals(published.publishedReleaseBundleDigest())
        || published.versionNumber() != content.versionNumber()
        || !releaseContentMatches(release, content)) {
      throw new IllegalStateException("START_SESSION_POLICY_RELEASE_BINDING_CONFLICT");
    }
    return new ReadResult(source, published);
  }

  private boolean releaseContentMatches(
      AuthoredWorldReleaseAttestationEvidence release,
      GameDesignPublicationTerminalEvidence.ReleaseContent content) {
    int retainedSchemaVersion =
        switch (content.attestationSchemaVersion()) {
          case "v2" -> AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION;
          case "v3" -> AuthoredWorldReleaseAttestationEvidence.CLOSURE_SELECTOR_SCHEMA_VERSION;
          case "v4" -> AuthoredWorldReleaseAttestationEvidence.SELECTED_FULL_SCHEMA_VERSION;
          default -> -1;
        };
    if (release.schemaVersion() != retainedSchemaVersion
        || !release.canonicalTenantId().equals(content.canonicalTenantId())
        || !release.canonicalVersionId().equals(content.canonicalVersionId())
        || !release.publishedReleaseBundleRef().equals(content.publishedReleaseBundleRef())
        || !release.publishWorkflowId().equals(content.publishWorkflowId())
        || !release.manifestHash().equals(content.manifestHash())
        || release.manifestSchemaVersion() != content.manifestSchemaVersion()
        || !release.requiredManifestAssetKeys().equals(content.requiredManifestAssetKeys())
        || !release.artifactDigests().equals(content.artifactDigests())
        || !release.commandDefinitions().equals(content.commandDefinitions())
        || !release.generationConfigRevision().equals(content.generationConfigRevision())
        || !Objects.equals(
            release.worldStartLocationEvidence(), content.worldStartLocationEvidence())
        || release.participantDigests().size() != content.participantDigests().size()) {
      return false;
    }
    for (int index = 0; index < release.participantDigests().size(); index++) {
      var attested = release.participantDigests().get(index);
      var retained = content.participantDigests().get(index);
      if (!attested.participantKey().equals(retained.participantKey())
          || !attested.scopeValue().equals(retained.scopeValue())
          || attested.baseVersionIdPresent() != (retained.baseVersionId() != null)
          || !Objects.equals(attested.baseVersionId(), retained.baseVersionId())
          || !attested.appliedCommitId().equals(retained.appliedCommitId())
          || !attested.contentDigest().equals(retained.contentDigest())
          || attested.digestSchemaVersion() != retained.digestSchemaVersion()
          || attested.abilitySchemaDigestPresent() != (retained.abilitySchemaDigest() != null)
          || !Objects.equals(attested.abilitySchemaDigest(), retained.abilitySchemaDigest())) {
        return false;
      }
    }
    return true;
  }

  /** Exact selector inputs; no source, version, release, or catalog defaults are inferred. */
  public record ReadRequest(
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      AuthoredWorldReleaseAttestationEvidence releaseAttestation,
      TargetProof target) {
    public ReadRequest {
      Objects.requireNonNull(descriptor, "descriptor");
      Objects.requireNonNull(releaseAttestation, "releaseAttestation");
      Objects.requireNonNull(target, "target");
      if (!GrpcPeerIdentity.isValidNamespace(descriptor.targetNamespace())) {
        throw new IllegalArgumentException("Canonical target namespace required");
      }
      if (!net.firedevops.firemud.common.tenant.GameTenantCreationDigest.isDigest(
          descriptor.authoredWorldSourceEvidenceDigest())) {
        throw new IllegalArgumentException("Canonical authored-world source digest required");
      }
    }
  }

  public record ReadResult(
      AuthoredWorldSourceEvidence sourceEvidence,
      RealmPolicyPublishedEvidence.PublishedSet publishedSet) {
    public ReadResult {
      Objects.requireNonNull(sourceEvidence, "sourceEvidence");
      Objects.requireNonNull(publishedSet, "publishedSet");
    }
  }
}
