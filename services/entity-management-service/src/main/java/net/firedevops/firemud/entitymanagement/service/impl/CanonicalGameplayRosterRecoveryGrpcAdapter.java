package net.firedevops.firemud.entitymanagement.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.security.CanonicalGameplayRosterPeerInterceptor;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRecoveryService;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryServiceGrpc;

/** Dedicated certificate-guarded recovery boundary; deliberately has no runtime registration. */
public final class CanonicalGameplayRosterRecoveryGrpcAdapter
    extends CanonicalGameplayRosterRecoveryServiceGrpc
        .CanonicalGameplayRosterRecoveryServiceImplBase {
  private final CanonicalGameplayRosterRecoveryService service;

  public CanonicalGameplayRosterRecoveryGrpcAdapter(
      CanonicalGameplayRosterRecoveryService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  @Override
  public void recoverOriginalSelection(
      CanonicalGameplayRosterRecoveryRequest request,
      StreamObserver<CanonicalGameplayRosterRecoveryResponse> observer) {
    if (CanonicalGameplayRosterPeerInterceptor.currentVerifiedPeer() == null
        || SessionContext.hasAuthenticatedCallerContext()) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("Trusted Game Session peer required")
              .asRuntimeException());
      return;
    }
    CanonicalGameplayRosterRecoveryResponse response;
    try {
      response = service.recover(request);
    } catch (IllegalArgumentException invalid) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Exact recovery evidence required")
              .asRuntimeException());
      return;
    } catch (IllegalStateException conflict) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Original selection recovery unavailable")
              .asRuntimeException());
      return;
    } catch (RuntimeException unavailable) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Original selection recovery unavailable")
              .asRuntimeException());
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }
}
