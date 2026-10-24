package net.firedevops.firemud.gamesession.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadGrpcCodec;
import net.firedevops.firemud.common.world.PreseededActorAssignmentOwnerReadEvidence;
import net.firedevops.firemud.common.world.PreseededActorAssignmentOwnerReadGrpcCodec;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterOwnerReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterOwnerReadRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterOwnerReadResponse;
import net.firedevops.firemud.gamesession.v1.GetPreseededActorAssignmentOwnerReadRequest;
import net.firedevops.firemud.gamesession.v1.GetPreseededActorAssignmentOwnerReadResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.server.service.GrpcService;

/**
 * Entity-only source read adapter. Its Game Session and Game Design evidence does not replace the
 * Account staging snapshot supplied to Entity and never enables PLAY, actor entry, bootstrap, OPEN,
 * or generation.
 */
@GrpcService
public final class CanonicalGameplayRosterOwnerReadGrpcAdapter
    extends CanonicalGameplayRosterOwnerReadServiceGrpc
        .CanonicalGameplayRosterOwnerReadServiceImplBase {
  private final ObjectProvider<CanonicalPublishedPlayerRouteReadService> sourceReaderProvider;
  private final CanonicalGameplayRosterOwnerReadWorkloadGuard workloadGuard;
  private final ObjectProvider<
          net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner>
      referenceOwnerProvider;

  public CanonicalGameplayRosterOwnerReadGrpcAdapter(
      ObjectProvider<CanonicalPublishedPlayerRouteReadService> sourceReaderProvider,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this(sourceReaderProvider, workloadNamespace, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public CanonicalGameplayRosterOwnerReadGrpcAdapter(
      ObjectProvider<CanonicalPublishedPlayerRouteReadService> sourceReaderProvider,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace,
      ObjectProvider<
              net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner>
          referenceOwnerProvider) {
    this.sourceReaderProvider = sourceReaderProvider;
    this.workloadGuard = new CanonicalGameplayRosterOwnerReadWorkloadGuard(workloadNamespace);
    this.referenceOwnerProvider = referenceOwnerProvider;
  }

  @Override
  public void acknowledgeCanonicalGameplayRosterRetirement(
      net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementAcknowledgementRequest
          request,
      StreamObserver<
              net.firedevops.firemud.gamesession.v1
                  .CanonicalGameplayRosterRetirementAcknowledgementResponse>
          observer) {
    try {
      workloadGuard.requireEntityRetirementAcknowledgementCaller();
      net.firedevops.firemud.common.world.CanonicalGameplayRosterRetirementEvidence
          .requireAcknowledgement(request);
      workloadGuard.requireConfiguredTargetNamespace(
          request.getOriginalSelection().getTargetNamespace());
    } catch (AdminAuthorizationException denied) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("Roster retirement transfer is not authorized")
              .asRuntimeException());
      return;
    } catch (IllegalArgumentException invalid) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Roster retirement transfer request is invalid")
              .asRuntimeException());
      return;
    }
    try {
      var owner = referenceOwnerProvider == null ? null : referenceOwnerProvider.getIfAvailable();
      if (owner == null) {
        throw new SourceUnavailableException();
      }
      var result =
          owner.acknowledgeRetirement(
              request, net.firedevops.firemud.common.grpc.GrpcPeerIdentity.current().uri());
      observer.onNext(
          net.firedevops.firemud.common.world.CanonicalGameplayRosterRetirementEvidence
              .requireAcknowledgementResponse(request, result));
      observer.onCompleted();
    } catch (IllegalArgumentException invalid) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Roster retirement transfer conflicts with retained authority")
              .asRuntimeException());
    } catch (net.firedevops.firemud.gamesession.repository
            .CanonicalGameplayRosterReferenceRepository.OriginalUnavailableException
        | org.jooq.exception.DataAccessException
        | org.springframework.dao.DataAccessException
        | SourceUnavailableException unavailable) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Roster retirement transfer owner is unavailable")
              .asRuntimeException());
    } catch (RuntimeException unexpected) {
      observer.onError(
          Status.INTERNAL
              .withDescription("Roster retirement transfer failed")
              .asRuntimeException());
    }
  }

  @Override
  public void confirmCanonicalGameplayRosterRetirementAcknowledgement(
      net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementConfirmationRequest
          request,
      StreamObserver<
              net.firedevops.firemud.gamesession.v1
                  .CanonicalGameplayRosterRetirementConfirmationResponse>
          observer) {
    try {
      workloadGuard.requireEntityRetirementConfirmationCaller();
      net.firedevops.firemud.common.world.CanonicalGameplayRosterRetirementEvidence
          .requireConfirmation(request);
      workloadGuard.requireConfiguredTargetNamespace(
          request.getAcknowledgementRequest().getOriginalSelection().getTargetNamespace());
    } catch (AdminAuthorizationException denied) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("Roster retirement transfer is not authorized")
              .asRuntimeException());
      return;
    } catch (IllegalArgumentException invalid) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Roster retirement transfer request is invalid")
              .asRuntimeException());
      return;
    }
    try {
      var owner = referenceOwnerProvider == null ? null : referenceOwnerProvider.getIfAvailable();
      if (owner == null) {
        throw new SourceUnavailableException();
      }
      var result =
          owner.confirmRetirementAcknowledgement(
              request, net.firedevops.firemud.common.grpc.GrpcPeerIdentity.current().uri());
      observer.onNext(
          net.firedevops.firemud.common.world.CanonicalGameplayRosterRetirementEvidence
              .requireConfirmationResponse(request, result));
      observer.onCompleted();
    } catch (IllegalArgumentException invalid) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Roster retirement transfer conflicts with retained authority")
              .asRuntimeException());
    } catch (net.firedevops.firemud.gamesession.repository
            .CanonicalGameplayRosterReferenceRepository.OriginalUnavailableException
        | org.jooq.exception.DataAccessException
        | org.springframework.dao.DataAccessException
        | SourceUnavailableException unavailable) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Roster retirement transfer owner is unavailable")
              .asRuntimeException());
    } catch (RuntimeException unexpected) {
      observer.onError(
          Status.INTERNAL
              .withDescription("Roster retirement transfer failed")
              .asRuntimeException());
    }
  }

  @Override
  public void getCanonicalGameplayRosterReference(
      net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest request,
      StreamObserver<
              net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse>
          observer) {
    try {
      workloadGuard.requireEntityReferenceOwnerReadCaller();
      net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence.requireRequest(
          request);
      workloadGuard.requireConfiguredTargetNamespace(request.getTargetNamespace());
    } catch (AdminAuthorizationException denied) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("Original roster selection owner read is not authorized")
              .asRuntimeException());
      return;
    } catch (IllegalArgumentException invalid) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Original roster selection request is invalid")
              .asRuntimeException());
      return;
    }
    try {
      var owner = referenceOwnerProvider == null ? null : referenceOwnerProvider.getIfAvailable();
      if (owner == null) {
        throw new SourceUnavailableException();
      }
      var result = owner.read(request);
      observer.onNext(
          net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence
              .requireResponse(request, result));
      observer.onCompleted();
    } catch (CanonicalPublishedPlayerRouteReadService.InvalidAuthorityException
        | CanonicalPlayerRouteReadService.InvalidAuthorityException
        | IllegalArgumentException invalidEvidence) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Original roster selection authority is stale or invalid")
              .asRuntimeException());
    } catch (net.firedevops.firemud.gamesession.repository
            .CanonicalGameplayRosterReferenceRepository.OriginalUnavailableException
        | CanonicalPublishedPlayerRouteReadService.StorageUnavailableException
        | CanonicalPublishedPlayerRouteReadService.GameDesignUnavailableException
        | CanonicalPlayerRouteReadService.ReadUnavailableException
        | org.jooq.exception.DataAccessException
        | org.springframework.dao.DataAccessException
        | SourceUnavailableException unavailable) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Original roster selection owner is unavailable")
              .asRuntimeException());
    } catch (RuntimeException unexpected) {
      observer.onError(
          Status.INTERNAL
              .withDescription("Original roster selection owner read failed")
              .asRuntimeException());
    }
  }

  @Override
  public void getCanonicalGameplayRosterOwnerRead(
      GetCanonicalGameplayRosterOwnerReadRequest request,
      StreamObserver<GetCanonicalGameplayRosterOwnerReadResponse> responseObserver) {
    CanonicalGameplayRosterOwnerReadEvidence.Request typedRequest;
    try {
      workloadGuard.requireEntityOwnerReadCaller();
      typedRequest = CanonicalGameplayRosterOwnerReadGrpcCodec.fromRequest(request);
      workloadGuard.requireConfiguredTargetNamespace(typedRequest.targetNamespace());
    } catch (AdminAuthorizationException denied) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Canonical gameplay roster owner read is not authorized")
              .asRuntimeException());
      return;
    } catch (IllegalArgumentException invalid) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical gameplay roster owner read request is invalid")
              .asRuntimeException());
      return;
    }
    try {
      CanonicalPublishedPlayerRouteReadService sourceReader = sourceReaderProvider.getIfAvailable();
      if (sourceReader == null) {
        throw new SourceUnavailableException();
      }
      CanonicalGameplayRosterOwnerReadEvidence evidence = sourceReader.readCurrent(typedRequest);
      responseObserver.onNext(
          CanonicalGameplayRosterOwnerReadGrpcCodec.toResponse(typedRequest, evidence));
      responseObserver.onCompleted();
    } catch (CanonicalPublishedPlayerRouteReadService.StaleAuthorityException stale) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Canonical gameplay roster route is stale")
              .asRuntimeException());
    } catch (CanonicalPublishedPlayerRouteReadService.InvalidAuthorityException invalid) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Canonical gameplay roster authority is invalid")
              .asRuntimeException());
    } catch (IllegalArgumentException invalidEvidence) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Canonical gameplay roster authority is invalid")
              .asRuntimeException());
    } catch (CanonicalPublishedPlayerRouteReadService.StorageUnavailableException
        | CanonicalPublishedPlayerRouteReadService.GameDesignUnavailableException
        | CanonicalPlayerRouteReadService.ReadUnavailableException
        | SourceUnavailableException unavailable) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Canonical gameplay roster owner read is unavailable")
              .asRuntimeException());
    } catch (RuntimeException unexpected) {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("Canonical gameplay roster owner read failed")
              .asRuntimeException());
    }
  }

  @Override
  public void getPreseededActorAssignmentOwnerRead(
      GetPreseededActorAssignmentOwnerReadRequest request,
      StreamObserver<GetPreseededActorAssignmentOwnerReadResponse> responseObserver) {
    PreseededActorAssignmentOwnerReadEvidence.Request typedRequest;
    try {
      workloadGuard.requireEntityAssignmentOwnerReadCaller();
      typedRequest = PreseededActorAssignmentOwnerReadGrpcCodec.fromRequest(request);
      workloadGuard.requireConfiguredTargetNamespace(typedRequest.targetNamespace());
    } catch (AdminAuthorizationException denied) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Actor assignment owner read is not authorized")
              .asRuntimeException());
      return;
    } catch (IllegalArgumentException invalid) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Actor assignment owner read request is invalid")
              .asRuntimeException());
      return;
    }
    try {
      CanonicalPublishedPlayerRouteReadService sourceReader = sourceReaderProvider.getIfAvailable();
      if (sourceReader == null) {
        throw new SourceUnavailableException();
      }
      PreseededActorAssignmentOwnerReadEvidence evidence =
          sourceReader.readCurrentForAssignment(typedRequest);
      responseObserver.onNext(
          PreseededActorAssignmentOwnerReadGrpcCodec.toResponse(typedRequest, evidence));
      responseObserver.onCompleted();
    } catch (CanonicalPublishedPlayerRouteReadService.StaleAuthorityException stale) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Canonical actor assignment route is stale")
              .asRuntimeException());
    } catch (CanonicalPublishedPlayerRouteReadService.InvalidAuthorityException invalid) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Canonical actor assignment authority is invalid")
              .asRuntimeException());
    } catch (IllegalArgumentException invalidEvidence) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Canonical actor assignment authority is invalid")
              .asRuntimeException());
    } catch (CanonicalPublishedPlayerRouteReadService.StorageUnavailableException
        | CanonicalPublishedPlayerRouteReadService.GameDesignUnavailableException
        | CanonicalPlayerRouteReadService.ReadUnavailableException
        | SourceUnavailableException unavailable) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("Actor assignment owner read is unavailable")
              .asRuntimeException());
    } catch (RuntimeException unexpected) {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("Actor assignment owner read failed")
              .asRuntimeException());
    }
  }

  private static final class SourceUnavailableException extends RuntimeException {}
}
