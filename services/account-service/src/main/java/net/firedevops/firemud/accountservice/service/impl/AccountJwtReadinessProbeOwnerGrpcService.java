package net.firedevops.firemud.accountservice.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.account.v1.AccountJwtReadinessProbeOwnerServiceGrpc;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerRequest;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerResponse;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessPrerequisiteConfiguration;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessProbeOwnerWorkloadGuard;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeOwnerProtoMapper;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeOwnerService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Lazy;
import org.springframework.grpc.server.service.GrpcService;

/** Isolated, default-inactive mTLS owner lookup; no bearer or readiness-harness route is reused. */
@GrpcService
@ConditionalOnProperty(
    prefix = "firemud.account.jwt-readiness.probe-owner",
    name = "enabled",
    havingValue = "true")
@Conditional(
    AccountJwtReadinessPrerequisiteConfiguration.ProtectedReadinessConfigurationPresent.class)
@Lazy
public final class AccountJwtReadinessProbeOwnerGrpcService
    extends AccountJwtReadinessProbeOwnerServiceGrpc.AccountJwtReadinessProbeOwnerServiceImplBase {
  private static final String UNAVAILABLE = "Account readiness probe owner is unavailable";

  private final AccountJwtReadinessProbeOwnerService owner;

  public AccountJwtReadinessProbeOwnerGrpcService(AccountJwtReadinessProbeOwnerService owner) {
    this.owner = owner;
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
      responseObserver.onError(
          Status.UNAVAILABLE.withDescription(UNAVAILABLE).asRuntimeException());
    }
  }
}
