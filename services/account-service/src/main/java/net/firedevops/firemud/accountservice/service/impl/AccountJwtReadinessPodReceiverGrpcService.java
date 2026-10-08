package net.firedevops.firemud.accountservice.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.account.v1.AccountJwtReadinessPodReceiverServiceGrpc;
import net.firedevops.firemud.account.v1.ReceiveAccountJwtReadinessProbeRequest;
import net.firedevops.firemud.account.v1.ReceiveAccountJwtReadinessProbeResponse;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessPodReceiverWorkloadGuard;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessPrerequisiteConfiguration;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessPodLocalIdentityProvider;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessPodReceiverEngine;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessPodReceiverProtoMapper;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Lazy;
import org.springframework.grpc.server.service.GrpcService;

/** Isolated default-inactive Account Pod verifier; does not share the V1 harness RPC or guard. */
@GrpcService
@ConditionalOnProperty(
    prefix = "firemud.account.jwt-readiness.pod-receiver",
    name = "enabled",
    havingValue = "true")
@Conditional(
    AccountJwtReadinessPrerequisiteConfiguration.ProtectedReadinessConfigurationPresent.class)
@Lazy
public final class AccountJwtReadinessPodReceiverGrpcService
    extends AccountJwtReadinessPodReceiverServiceGrpc
        .AccountJwtReadinessPodReceiverServiceImplBase {
  private static final String UNAVAILABLE = "Account Pod readiness receiver is unavailable";

  private final AccountJwtReadinessPodReceiverEngine receiver;

  public AccountJwtReadinessPodReceiverGrpcService(AccountJwtReadinessPodReceiverEngine receiver) {
    this.receiver = receiver;
  }

  @Override
  public void receiveReadinessProbe(
      ReceiveAccountJwtReadinessProbeRequest request,
      StreamObserver<ReceiveAccountJwtReadinessProbeResponse> responseObserver) {
    try {
      responseObserver.onNext(receiver.receiveReadinessProbe(request));
      responseObserver.onCompleted();
    } catch (AccountJwtReadinessPodReceiverWorkloadGuard.ReceiverCallerDeniedException denied) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Authorized Account workload is required")
              .asRuntimeException());
    } catch (AccountJwtReadinessPodReceiverProtoMapper.InvalidReceiverRequestException invalid) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Account Pod readiness request is invalid")
              .asRuntimeException());
    } catch (AccountAsymmetricJwtVerifier.VerificationException rejected) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Current pending-key probe was not verified")
              .asRuntimeException());
    } catch (AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException
        | AccountJwtReadinessPodLocalIdentityProvider.IdentityUnavailableException unavailable) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Current Account Pod probe evidence is unavailable")
              .asRuntimeException());
    } catch (RuntimeException unavailable) {
      responseObserver.onError(
          Status.UNAVAILABLE.withDescription(UNAVAILABLE).asRuntimeException());
    }
  }
}
