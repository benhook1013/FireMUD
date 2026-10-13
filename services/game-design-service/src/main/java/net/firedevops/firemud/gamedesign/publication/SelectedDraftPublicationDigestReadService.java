package net.firedevops.firemud.gamedesign.publication;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.dto.DesignControlPlaneDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.model.PublishParticipantKey;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered exact selected-publication source read; it does not authorize publication itself.
 */
public final class SelectedDraftPublicationDigestReadService {
  private final OwnerRead owner;
  private final TransactionTemplate snapshot;
  private final String workloadNamespace;

  public SelectedDraftPublicationDigestReadService(
      DSLContext dsl, PlatformTransactionManager transactions, String workloadNamespace) {
    this(
        new RepositoryOwnerRead(
            new GameDesignPublicationOperationRepository(Objects.requireNonNull(dsl, "dsl")),
            new GameDesignSourceRepository(dsl)),
        transactions,
        workloadNamespace);
  }

  SelectedDraftPublicationDigestReadService(
      OwnerRead owner, PlatformTransactionManager transactions, String workloadNamespace) {
    this.owner = Objects.requireNonNull(owner, "owner");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Design workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    snapshot = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
    snapshot.setName("game-design-selected-publication-digest-read");
    snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    snapshot.setReadOnly(true);
  }

  /** Returns exact local GD and retained World digests for one canonical full-version request. */
  public ReadResult read(
      String targetNamespace, PublicationDigestRequestBinding publicationRequest) {
    if (!workloadNamespace.equals(targetNamespace)) {
      throw Status.PERMISSION_DENIED
          .withDescription("Selected publication namespace differs from configured owner")
          .asRuntimeException();
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Independent selected publication digest snapshot required")
          .asRuntimeException();
    }
    validateRequest(publicationRequest);
    try {
      return Objects.requireNonNull(
          snapshot.execute(ignored -> readSnapshot(publicationRequest)),
          "Selected publication digest snapshot returned no result");
    } catch (StatusRuntimeException failure) {
      throw failure;
    } catch (DataAccessException unavailable) {
      throw Status.UNAVAILABLE
          .withDescription("Selected publication source readback unavailable")
          .withCause(unavailable)
          .asRuntimeException();
    } catch (RuntimeException invalid) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Exact selected publication source evidence unavailable")
          .withCause(invalid)
          .asRuntimeException();
    }
  }

  private ReadResult readSnapshot(PublicationDigestRequestBinding publicationRequest) {
    GameDesignPublicationOperationRepository.Readback readback =
        owner
            .read(publicationRequest.derivedWorkflowIdentity())
            .orElseThrow(
                () ->
                    Status.NOT_FOUND
                        .withDescription("Selected publication operation unavailable")
                        .asRuntimeException());
    GameDesignPublicationOperation operation = readback.operation();
    requireOperationCorrelation(operation, publicationRequest);

    // Exact durable selection comparison precedes any source snapshot read.
    owner.requireExactSelection(operation);
    GameDesignSourceRepository.Capture frozen =
        owner
            .readSourceCapture(operation)
            .orElseThrow(
                () ->
                    Status.FAILED_PRECONDITION
                        .withDescription("Complete selected source capture unavailable")
                        .asRuntimeException());
    requireCapture(operation, frozen);

    DraftCommitBinding selected = operation.account().input().selection().selectedCommit();
    GameDesignSourceRepository.SynchronizedSources synchronizedSources =
        owner
            .readSynchronized(selected.target(), selected.commitId())
            .orElseThrow(
                () ->
                    Status.FAILED_PRECONDITION
                        .withDescription("Complete synchronized selected sources unavailable")
                        .asRuntimeException());
    requireFrozenSources(selected, frozen, synchronizedSources);

    DesignControlPlaneDigestDto designDigest =
        SelectedDraftControlPlaneDigest.compute(selected, synchronizedSources).toDto();
    var worldAcknowledgement = operation.inventory().freezeEvidence().acknowledgement();
    if (!selected.commitId().toString().equals(worldAcknowledgement.appliedCommitId())) {
      throw new IllegalStateException("Retained World freeze differs from selected Draft commit");
    }
    var worldDigest =
        new PublishParticipantDigestDto(
            PublishParticipantKey.WORLD_MANAGEMENT.name(),
            publicationRequest.versionId(),
            null,
            worldAcknowledgement.appliedCommitId(),
            worldAcknowledgement.contentDigest(),
            worldAcknowledgement.digestSchemaVersion(),
            null,
            null,
            null);
    return new ReadResult(
        publicationRequest, publicationRequest.requestDigest(), designDigest, worldDigest);
  }

  private void requireOperationCorrelation(
      GameDesignPublicationOperation operation,
      PublicationDigestRequestBinding publicationRequest) {
    var selection = operation.account().input().selection();
    var intent = selection.intent();
    TargetProof target = selection.target();
    DraftCommitBinding selected = selection.selectedCommit();
    var world = operation.world().request();
    UUID tenant;
    long numericVersion;
    try {
      tenant = UUID.fromString(publicationRequest.tenantId());
      numericVersion = Long.parseLong(publicationRequest.versionId());
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException(
          "Canonical tenant UUID and numeric version required", malformed);
    }
    if (!tenant.toString().equals(publicationRequest.tenantId())
        || numericVersion <= 0
        || !tenant.equals(target.canonicalTenantId())
        || !tenant.equals(intent.canonicalTenantId())
        || target.gameDesignVersionRowId() != numericVersion
        || operation.versionId() != numericVersion
        || !operation.tenantKey().equals(target.gameDesignVersionTenantKey())
        || !operation.selectionDigest().equals(selection.digest())
        || !intent.canonicalVersionId().equals(target.canonicalVersionId())
        || !intent.canonicalVersionId().equals(selected.target().canonicalVersionId())
        || !publicationRequest.publishRequestId().equals(intent.publishRequestId())
        || !publicationRequest.derivedWorkflowIdentity().equals(operation.workflowId())
        || !workloadNamespace.equals(world.targetNamespace())
        || !tenant.equals(world.canonicalTenantId())
        || !intent.canonicalVersionId().equals(world.canonicalVersionId())
        || !intent.publishRequestId().equals(world.publicationRequestId())
        || !publicationRequest.derivedWorkflowIdentity().equals(world.publishWorkflowId())
        || !selection.digest().substring("sha256:".length()).equals(world.requestDigest())
        || !intent.expectedVersionStateEpoch().equals(Long.toString(world.versionStateEpoch()))
        || !selected.commitId().toString().equals(world.appliedCommitId())) {
      throw new IllegalStateException(
          "Publication operation differs from canonical selected request");
    }
  }

  private static void requireCapture(
      GameDesignPublicationOperation operation, GameDesignSourceRepository.Capture capture) {
    byte[] operationBytes = operation.canonicalBytes();
    for (byte[] retainedOperation :
        new byte[][] {
          capture.command().operation().canonicalBytes(),
          capture.policy().operation().canonicalBytes(),
          capture.asset().operation().canonicalBytes(),
          capture.branding().operation().canonicalBytes(),
          capture.templateConfig().operation().canonicalBytes()
        }) {
      if (!Arrays.equals(operationBytes, retainedOperation)) {
        throw new IllegalStateException("Frozen source capture differs from exact operation");
      }
    }
  }

  private static void requireFrozenSources(
      DraftCommitBinding selected,
      GameDesignSourceRepository.Capture frozen,
      GameDesignSourceRepository.SynchronizedSources synchronizedSources) {
    BrandingSourceSnapshot branding =
        synchronizedSources
            .branding()
            .orElseThrow(
                () -> new IllegalStateException("Selected branding source snapshot unavailable"));
    TemplateConfigSourceSnapshot templateConfig =
        synchronizedSources
            .templateConfig()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Selected template-config source snapshot unavailable"));
    if (!selected.equals(synchronizedSources.command().binding())
        || !selected.equals(synchronizedSources.policy().binding())
        || !selected.equals(synchronizedSources.asset().binding())
        || !selected.equals(synchronizedSources.gameplay().binding())
        || !selected.equals(branding.binding())
        || !selected.equals(templateConfig.binding())
        || !Arrays.equals(
            frozen.command().snapshot().canonicalBytes(),
            synchronizedSources.command().canonicalBytes())
        || !Arrays.equals(
            frozen.policy().snapshot().canonicalBytes(),
            synchronizedSources.policy().canonicalBytes())
        || !Arrays.equals(
            frozen.asset().snapshot().canonicalBytes(),
            synchronizedSources.asset().canonicalBytes())
        || !Arrays.equals(frozen.branding().snapshot().canonicalBytes(), branding.canonicalBytes())
        || !Arrays.equals(
            frozen.templateConfig().snapshot().canonicalBytes(), templateConfig.canonicalBytes())) {
      throw new IllegalStateException(
          "Frozen and synchronized source snapshots differ from the complete selected commit");
    }
  }

  private static void validateRequest(PublicationDigestRequestBinding request) {
    if (request == null
        || request.scopeKind() != PublicationDigestRequestBinding.ScopeKind.FULL_VERSION) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Canonical full-version publication binding required")
          .asRuntimeException();
    }
    try {
      var canonical =
          PublicationDigestRequestBinding.full(
              request.tenantId(), request.versionId(), request.publishRequestId());
      UUID tenant = UUID.fromString(request.tenantId());
      if (!Arrays.equals(canonical.canonicalPreimage(), request.canonicalPreimage())
          || !canonical.requestDigest().equals(request.requestDigest())
          || !canonical.derivedWorkflowIdentity().equals(request.derivedWorkflowIdentity())
          || !tenant.toString().equals(request.tenantId())) {
        throw new IllegalArgumentException("Publication request binding is not canonical");
      }
    } catch (RuntimeException malformed) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Canonical full-version publication binding required")
          .withCause(malformed)
          .asRuntimeException();
    }
  }

  public record ReadResult(
      PublicationDigestRequestBinding requestBinding,
      String requestDigest,
      DesignControlPlaneDigestDto gameDesignDigest,
      PublishParticipantDigestDto worldManagementDigest) {
    public ReadResult {
      Objects.requireNonNull(requestBinding, "requestBinding");
      Objects.requireNonNull(gameDesignDigest, "gameDesignDigest");
      Objects.requireNonNull(worldManagementDigest, "worldManagementDigest");
      requestBinding.validateSupplied(requestBinding.derivedWorkflowIdentity(), requestDigest);
      requestBinding.requireOwnerScope(gameDesignDigest.tenantId(), gameDesignDigest.scopeValue());
      if (!requestBinding.versionId().equals(worldManagementDigest.scopeValue())
          || !gameDesignDigest.appliedCommitId().equals(worldManagementDigest.appliedCommitId())
          || gameDesignDigest.digestSchemaVersion()
              != SelectedDraftControlPlaneDigest.SCHEMA_VERSION
          || !PublishParticipantKey.WORLD_MANAGEMENT
              .name()
              .equals(worldManagementDigest.participantKey())
          || worldManagementDigest.baseVersionId() != null
          || worldManagementDigest.contentDigest() == null
          || !worldManagementDigest.contentDigest().matches("[0-9a-f]{64}")
          || worldManagementDigest.digestSchemaVersion() == null
          || worldManagementDigest.digestSchemaVersion() <= 0
          || !worldManagementDigest.succeeded()) {
        throw new IllegalArgumentException("Paired selected publication digest scopes differ");
      }
    }
  }

  interface OwnerRead {
    Optional<GameDesignPublicationOperationRepository.Readback> read(String workflowIdentity);

    void requireExactSelection(GameDesignPublicationOperation operation);

    Optional<GameDesignSourceRepository.Capture> readSourceCapture(
        GameDesignPublicationOperation operation);

    Optional<GameDesignSourceRepository.SynchronizedSources> readSynchronized(
        TargetProof target, UUID selectedCommitId);
  }

  private record RepositoryOwnerRead(
      GameDesignPublicationOperationRepository operations, GameDesignSourceRepository sources)
      implements OwnerRead {
    @Override
    public Optional<GameDesignPublicationOperationRepository.Readback> read(
        String workflowIdentity) {
      return operations.read(workflowIdentity);
    }

    @Override
    public void requireExactSelection(GameDesignPublicationOperation operation) {
      operations.requireExactSelection(operation);
    }

    @Override
    public Optional<GameDesignSourceRepository.Capture> readSourceCapture(
        GameDesignPublicationOperation operation) {
      return operations.readSourceCapture(operation);
    }

    @Override
    public Optional<GameDesignSourceRepository.SynchronizedSources> readSynchronized(
        TargetProof target, UUID selectedCommitId) {
      return sources.readSynchronized(target, selectedCommitId);
    }
  }
}
