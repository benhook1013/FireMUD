package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.CanonicalWorldInstancePreparationGrpcCodec;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence.Request;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparation.Input;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparation.Result;
import net.firedevops.firemud.worldmanagement.v1.PrepareCanonicalWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.PrepareCanonicalWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalInstancePreparationServiceGrpc;
import org.jooq.exception.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Standalone authenticated canonical preparation adapter; intentionally not runtime-registered. */
public final class WorldCanonicalInstancePreparationGrpcService
    extends WorldCanonicalInstancePreparationServiceGrpc
        .WorldCanonicalInstancePreparationServiceImplBase {
  private final WorldCompleteLaunchBindingService launchBindingService;
  private final WorldCanonicalInstancePreparationAssemblyService assemblyService;
  private final WorldCanonicalInstancePreparationService preparationService;
  private final WorldCanonicalInstancePreparationRepository preparationRepository;
  private final WorldCanonicalInstanceLifecycleReadRepository lifecycleRepository;
  private final String trustedNamespace;

  public WorldCanonicalInstancePreparationGrpcService(
      WorldCompleteLaunchBindingService launchBindingService,
      WorldCanonicalInstancePreparationAssemblyService assemblyService,
      WorldCanonicalInstancePreparationService preparationService,
      WorldCanonicalInstancePreparationRepository preparationRepository,
      WorldCanonicalInstanceLifecycleReadRepository lifecycleRepository,
      String trustedNamespace) {
    this.launchBindingService =
        Objects.requireNonNull(launchBindingService, "launchBindingService");
    this.assemblyService = Objects.requireNonNull(assemblyService, "assemblyService");
    this.preparationService = Objects.requireNonNull(preparationService, "preparationService");
    this.preparationRepository =
        Objects.requireNonNull(preparationRepository, "preparationRepository");
    this.lifecycleRepository = Objects.requireNonNull(lifecycleRepository, "lifecycleRepository");
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.trustedNamespace = trustedNamespace;
  }

  @Override
  public void prepareCanonicalWorldInstance(
      PrepareCanonicalWorldInstanceRequest request,
      StreamObserver<PrepareCanonicalWorldInstanceResponse> responseObserver) {
    if (!requireAuthenticatedGameSessionPeer(responseObserver)) return;
    if (hasAmbientTransaction()) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World preparation requires independent owner operations");
      return;
    }

    CanonicalGameInstanceLaunchAssociationReadEvidence.Request selector;
    try {
      selector = CanonicalWorldInstancePreparationGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException invalid) {
      fail(
          responseObserver,
          Status.INVALID_ARGUMENT,
          "A complete canonical World preparation selector is required");
      return;
    }
    if (!trustedNamespace.equals(selector.targetNamespace())) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "Canonical World preparation namespace must match the authenticated peer");
      return;
    }

    try {
      WorldCompleteLaunchBindingReceipt bound = launchBindingService.bindCommittedLaunch(selector);
      WorldCanonicalInstancePreparationAssemblyService.Selector assemblySelector =
          new WorldCanonicalInstancePreparationAssemblyService.Selector(
              selector.canonicalTenantId(),
              selector.gameInstanceUuid(),
              selector.controlPlaneRequestId());
      Input input = assemblyService.assemble(assemblySelector);
      requireAssembledInputMatches(selector, bound, input);

      // This independent read proves only that the exact immutable input was previously retained.
      // New materialization still requires STARTING and always passes through the held verifier.
      Optional<Result> retained = preparationRepository.readOwnerPreparation(input);
      if (retained.isEmpty()
          && !"STARTING".equals(input.gameSessionReadEvidence().currentGameSessionStatus())) {
        throw new PreparationPreconditionException(
            "Fresh canonical World preparation requires the actual Game Session STARTING state");
      }

      // Exact retries deliberately re-enter prepare so its current source verifier and continuously
      // held commit authority remain mandatory through the real owner transaction.
      Result prepared = preparationService.prepare(input);
      if (prepared == null) {
        throw new PreparationPreconditionException(
            "Canonical World preparation returned no committed materialization result");
      }
      requirePreparedResultMatchesInput(input, prepared);

      Request lifecycleRequest = lifecycleRequest(selector, input);
      WorldCanonicalInstanceLifecycleEvidence lifecycle =
          lifecycleRepository
              .read(lifecycleRequest)
              .orElseThrow(
                  () ->
                      new PreparationPreconditionException(
                          "Canonical World lifecycle readback is required after materialization"));
      requireLifecycleMatchesPreparation(lifecycleRequest, input, prepared, lifecycle);

      PrepareCanonicalWorldInstanceResponse response =
          CanonicalWorldInstancePreparationGrpcCodec.toResponse(selector, lifecycle);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (SecurityException denied) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "Canonical World preparation producer or namespace evidence was denied");
    } catch (WorldCanonicalInstancePreparationService.PreparationDeniedException denied) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "Canonical World preparation authority was denied");
    } catch (TransientDataAccessException | DataAccessException unavailable) {
      fail(
          responseObserver,
          Status.UNAVAILABLE,
          "Canonical World preparation storage is temporarily unavailable");
    } catch (StatusRuntimeException ownerFailure) {
      failForOwnerRpcFailure(responseObserver, ownerFailure);
    } catch (IllegalArgumentException | IllegalStateException inconsistent) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World preparation evidence is unavailable or inconsistent");
    } catch (RuntimeException failure) {
      fail(responseObserver, Status.INTERNAL, "Canonical World preparation failed");
    }
  }

  private static void requireAssembledInputMatches(
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request selector,
      WorldCompleteLaunchBindingReceipt bound,
      Input input) {
    if (bound == null || input == null) {
      throw new PreparationPreconditionException(
          "Canonical World preparation requires exact bound and assembled launch evidence");
    }
    var request = input.gameSessionReadRequest();
    var gameSession = input.gameSessionReadEvidence();
    var completeBinding = input.completeLaunchBinding();
    var pair = completeBinding.evidence();
    var descriptor = pair.descriptor();
    var release = pair.releaseAttestation();

    // The assembly performs fresh internal Game Session reads and therefore owns distinct read
    // UUIDs. Every immutable selector, original pair and digest must still match the outer call.
    boolean requestMatches =
        selector.targetNamespace().equals(request.targetNamespace())
            && selector.canonicalTenantId().equals(request.canonicalTenantId())
            && selector.worldSlug().equals(request.worldSlug())
            && selector.gameInstanceUuid().equals(request.canonicalGameInstanceId())
            && selector.controlPlaneRequestId().equals(request.controlPlaneRequestId())
            && selector.launchDescriptorId().equals(request.launchDescriptorId())
            && selector
                .expectedDescriptorRequestDigest()
                .equals(request.expectedDescriptorRequestDigest())
            && selector
                .expectedDescriptorResultDigest()
                .equals(request.expectedDescriptorResultDigest())
            && selector
                .expectedReleaseAttestationEvidenceDigest()
                .equals(request.expectedReleaseAttestationEvidenceDigest());
    boolean gameSessionMatches =
        selector.targetNamespace().equals(gameSession.targetNamespace())
            && selector.canonicalTenantId().equals(gameSession.canonicalTenantId())
            && selector.worldSlug().equals(gameSession.worldSlug())
            && selector.gameInstanceUuid().equals(gameSession.canonicalGameInstanceId())
            && selector.controlPlaneRequestId().equals(gameSession.controlPlaneRequestId())
            && selector.launchDescriptorId().equals(gameSession.launchDescriptorId())
            && selector
                .expectedDescriptorRequestDigest()
                .equals(gameSession.descriptorRequestDigest())
            && selector
                .expectedDescriptorResultDigest()
                .equals(gameSession.descriptorResultDigest())
            && selector
                .expectedReleaseAttestationEvidenceDigest()
                .equals(gameSession.releaseAttestationEvidenceDigest())
            && request.readRequestId().equals(gameSession.readRequestId())
            && descriptor.equals(gameSession.descriptor())
            && release.equals(gameSession.releaseAttestation());
    boolean bindingMatches =
        selector.targetNamespace().equals(bound.targetNamespace())
            && selector.canonicalTenantId().equals(bound.canonicalTenantId())
            && selector.worldSlug().equals(bound.worldSlug())
            && selector.controlPlaneRequestId().equals(bound.controlPlaneRequestId())
            && bound.equals(completeBinding)
            && bound.evidence().equals(pair)
            && selector.targetNamespace().equals(descriptor.targetNamespace())
            && selector.canonicalTenantId().equals(descriptor.canonicalTenantId())
            && selector.worldSlug().equals(descriptor.worldSlug())
            && selector.controlPlaneRequestId().equals(descriptor.controlPlaneRequestId())
            && selector.launchDescriptorId().equals(descriptor.launchDescriptorId())
            && selector.expectedDescriptorRequestDigest().equals(descriptor.requestDigest())
            && selector.expectedDescriptorResultDigest().equals(descriptor.resultDigest())
            && selector.targetNamespace().equals(release.targetNamespace())
            && selector.canonicalTenantId().equals(release.canonicalTenantId())
            && selector.worldSlug().equals(release.worldSlug())
            && selector.launchDescriptorId().equals(release.launchDescriptorId())
            && selector.expectedDescriptorResultDigest().equals(release.descriptorResultDigest())
            && selector.expectedReleaseAttestationEvidenceDigest().equals(release.evidenceDigest());

    if (!requestMatches || !gameSessionMatches || !bindingMatches) {
      throw new PreparationPreconditionException(
          "Assembled canonical World input differs from the complete outer launch selector");
    }
  }

  private static void requirePreparedResultMatchesInput(Input input, Result prepared) {
    var association = prepared.association();
    if (!input.captureId().equals(prepared.captureId())
        || prepared.startLocation() == null
        || prepared.runtimeRoomInstanceId() == null
        || !input.gameSessionReadEvidence().canonicalIdentity().equals(association.identity())
        || !input.completeLaunchBinding().equals(association.completeLaunchBinding())
        || !input.versionIdentity().equals(association.versionIdentity())
        || !"MATERIALIZED_UNVERIFIED".equals(prepared.storageStatus())) {
      throw new PreparationPreconditionException(
          "Canonical World materialization result differs from its immutable preparation input");
    }
  }

  private static Request lifecycleRequest(
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request selector, Input input) {
    var gameSession = input.gameSessionReadEvidence();
    var descriptor = input.completeLaunchBinding().descriptor();
    var release = input.completeLaunchBinding().evidence().releaseAttestation();
    return new Request(
        WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
        selector.readRequestId(),
        gameSession.targetNamespace(),
        gameSession.canonicalTenantId(),
        gameSession.worldSlug(),
        gameSession.canonicalGameInstanceId(),
        gameSession.playableStateNamespaceId(),
        gameSession.playableStateScope(),
        gameSession.publicProduction(),
        gameSession.controlPlaneRequestId(),
        release.canonicalVersionId(),
        descriptor.requestDigest(),
        descriptor.resultDigest(),
        release.evidenceDigest());
  }

  private static void requireLifecycleMatchesPreparation(
      Request request,
      Input input,
      Result prepared,
      WorldCanonicalInstanceLifecycleEvidence lifecycle) {
    var association = prepared.association();
    if (!request.equals(lifecycle.request())
        || !input.completeLaunchBinding().evidence().equals(lifecycle.launchBinding())
        || !prepared.captureId().equals(lifecycle.captureId())
        || !prepared.graphSha256().equals(lifecycle.graphSha256())
        || !prepared.inputDigest().equals(lifecycle.preparationInputDigest())
        || !prepared.startLocation().equals(lifecycle.startLocation())
        || prepared.runtimeRoomInstanceId().longValue() != lifecycle.runtimeRoomInstanceId()
        || !input.gameSessionReadEvidence().canonicalIdentity().equals(association.identity())
        || !input.completeLaunchBinding().equals(association.completeLaunchBinding())
        || !input.versionIdentity().equals(association.versionIdentity())) {
      throw new PreparationPreconditionException(
          "Independent World lifecycle readback differs from the prepared immutable input");
    }
  }

  private boolean requireAuthenticatedGameSessionPeer(StreamObserver<?> responseObserver) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer != null
        && peer.isService("game-session-service")
        && peer.isInNamespace(trustedNamespace)
        && !SessionContext.hasAuthenticatedCallerContext()) {
      return true;
    }
    fail(
        responseObserver,
        Status.PERMISSION_DENIED,
        "Only the verified same-namespace Game Session workload without end-user context is allowed");
    return false;
  }

  private static boolean hasAmbientTransaction() {
    return TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive();
  }

  private static void fail(StreamObserver<?> responseObserver, Status status, String description) {
    responseObserver.onError(status.withDescription(description).asRuntimeException());
  }

  private static void failForOwnerRpcFailure(
      StreamObserver<?> responseObserver, StatusRuntimeException ownerFailure) {
    switch (Status.fromThrowable(ownerFailure).getCode()) {
      case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED ->
          fail(
              responseObserver,
              Status.UNAVAILABLE,
              "A required canonical owner read is temporarily unavailable");
      case PERMISSION_DENIED, UNAUTHENTICATED ->
          fail(
              responseObserver,
              Status.PERMISSION_DENIED,
              "A required canonical owner denied the authenticated producer");
      case INVALID_ARGUMENT ->
          fail(
              responseObserver,
              Status.INVALID_ARGUMENT,
              "A required canonical owner rejected the request selector");
      case FAILED_PRECONDITION, NOT_FOUND, ALREADY_EXISTS, ABORTED ->
          fail(
              responseObserver,
              Status.FAILED_PRECONDITION,
              "Required canonical owner evidence is unavailable or inconsistent");
      default -> fail(responseObserver, Status.INTERNAL, "Canonical owner read failed");
    }
  }

  private static final class PreparationPreconditionException extends IllegalStateException {
    private PreparationPreconditionException(String message) {
      super(message);
    }
  }
}
