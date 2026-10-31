package net.firedevops.firemud.accountservice.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.account.v1.AccountJwtReadinessProbeOwnerServiceGrpc;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerRequest;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerResponse;
import net.firedevops.firemud.account.v1.GetCurrentReadinessReceiverMetadataRequest;
import net.firedevops.firemud.account.v1.GetCurrentReadinessReceiverMetadataResponse;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessProbeOwnerWorkloadGuard;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeOwnerProtoMapper;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeOwnerService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverMetadataService;

/**
 * Manually composed, unregistered mTLS owner lookup adapter.
 *
 * <p>The composition owner must install the peer identity interceptor; method-local guards require
 * that authenticated peer context before reading Account owner state.
 */
public final class AccountJwtReadinessProbeOwnerGrpcService
    extends AccountJwtReadinessProbeOwnerServiceGrpc.AccountJwtReadinessProbeOwnerServiceImplBase {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(AccountJwtReadinessProbeOwnerGrpcService.class);
  private static final String UNAVAILABLE = "Account readiness probe owner is unavailable";

  private final AccountJwtReadinessProbeOwnerService owner;
  private final AccountJwtReadinessReceiverMetadataService metadata;

  public AccountJwtReadinessProbeOwnerGrpcService(
      AccountJwtReadinessProbeOwnerService owner,
      AccountJwtReadinessReceiverMetadataService metadata) {
    this.owner = owner;
    this.metadata = metadata;
  }

  @Override
  public void getCurrentReadinessProbeOwner(
      GetCurrentReadinessProbeOwnerRequest request,
      StreamObserver<GetCurrentReadinessProbeOwnerResponse> responseObserver) {
    try {
      responseObserver.onNext(owner.getCurrentReadinessProbeOwner(request));
      responseObserver.onCompleted();
    } catch (AccountJwtReadinessProbeOwnerWorkloadGuard.OwnerReadDeniedException denied) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Authorized Game Session workload is required")
              .asRuntimeException());
    } catch (AccountJwtReadinessProbeOwnerProtoMapper.InvalidOwnerReadRequestException invalid) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Readiness owner request is invalid")
              .asRuntimeException());
    } catch (AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException unavailable) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Current readiness owner evidence is unavailable")
              .asRuntimeException());
    } catch (RuntimeException unavailable) {
      LOG.warn("Readiness owner lookup failed: {}", unavailable.getClass().getName());
      responseObserver.onError(
          Status.UNAVAILABLE.withDescription(UNAVAILABLE).asRuntimeException());
    }
  }

  @Override
  public void getCurrentReadinessReceiverMetadata(
      GetCurrentReadinessReceiverMetadataRequest request,
      StreamObserver<GetCurrentReadinessReceiverMetadataResponse> responseObserver) {
    try {
      responseObserver.onNext(metadata.getCurrentReadinessReceiverMetadata(request));
      responseObserver.onCompleted();
    } catch (AccountJwtReadinessProbeOwnerWorkloadGuard.OwnerReadDeniedException denied) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Authorized Game Session workload is required")
              .asRuntimeException());
    } catch (AccountJwtReadinessProbeOwnerProtoMapper.InvalidOwnerReadRequestException invalid) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Readiness owner request is invalid")
              .asRuntimeException());
    } catch (AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException unavailable) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Current readiness owner evidence is unavailable")
              .asRuntimeException());
    } catch (RuntimeException unavailable) {
      LOG.warn("Readiness receiver metadata lookup failed: {}", unavailable.getClass().getName());
      responseObserver.onError(
          Status.UNAVAILABLE.withDescription(UNAVAILABLE).asRuntimeException());
    }
  }
}
