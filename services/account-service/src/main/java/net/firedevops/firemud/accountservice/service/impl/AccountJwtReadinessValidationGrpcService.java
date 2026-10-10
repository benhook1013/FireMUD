package net.firedevops.firemud.accountservice.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.account.v1.AccountJwtReadinessValidationServiceGrpc;
import net.firedevops.firemud.account.v1.IssueReadinessProbesRequest;
import net.firedevops.firemud.account.v1.IssueReadinessProbesResponse;
import net.firedevops.firemud.account.v1.ValidateReadinessProbeRequest;
import net.firedevops.firemud.account.v1.ValidateReadinessProbeResponse;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessPrerequisiteConfiguration;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.security.AccountJwtReadinessTlsInterceptor;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessTransportOwner;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessValidationService;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Lazy;
import org.springframework.grpc.server.service.GrpcService;

/** Isolated, exact-mTLS transport for Account's non-authorizing JWT readiness probes. */
@GrpcService(
    interceptorNames = "accountJwtReadinessTlsInterceptor",
    blendWithGlobalInterceptors = false)
@ConditionalOnProperty(
    prefix = "firemud.account.jwt-readiness.validation",
    name = "enabled",
    havingValue = "true")
@Conditional(
    AccountJwtReadinessPrerequisiteConfiguration.ProtectedReadinessConfigurationPresent.class)
@Lazy
public final class AccountJwtReadinessValidationGrpcService
    extends AccountJwtReadinessValidationServiceGrpc.AccountJwtReadinessValidationServiceImplBase {
  private static final String UNAVAILABLE = "Account JWT readiness service is unavailable";

  private final AccountJwtReadinessTransportOwner owner;

  public AccountJwtReadinessValidationGrpcService(AccountJwtReadinessTransportOwner owner) {
    this.owner = owner;
  }

  @Override
  public void issueReadinessProbes(
      IssueReadinessProbesRequest request,
      StreamObserver<IssueReadinessProbesResponse> responseObserver) {
    var authenticatedCaller = AccountJwtReadinessTlsInterceptor.authenticatedCaller();
    if (authenticatedCaller == null) {
      deny(responseObserver);
      return;
    }
    try {
      responseObserver.onNext(owner.issue(request, authenticatedCaller));
      responseObserver.onCompleted();
    } catch (AccountJwtReadinessValidationService.InvalidReadinessRequestException invalid) {
      invalid(responseObserver);
    } catch (SecurityException denied) {
      deny(responseObserver);
    } catch (AccountJwtReadinessProbeRepository.CacheAgeNotElapsedException
        | AccountJwtReadinessProbeRepository.DeliveryAlreadyClaimedException
        | AccountJwtReadinessProbeRepository.ProbeExpiredException
        | AccountJwtReadinessValidationService.ProbeNotIssuedException precondition) {
      precondition(responseObserver);
    } catch (AccountAsymmetricJwtVerifier.VerificationException
        | AccountJwtReadinessProbeRepository.IdempotencyConflictException invalid) {
      invalid(responseObserver);
    } catch (RuntimeException unavailable) {
      unavailable(responseObserver);
    }
  }

  @Override
  public void validateReadinessProbe(
      ValidateReadinessProbeRequest request,
      StreamObserver<ValidateReadinessProbeResponse> responseObserver) {
    var authenticatedCaller = AccountJwtReadinessTlsInterceptor.authenticatedCaller();
    if (authenticatedCaller == null) {
      deny(responseObserver);
      return;
    }
    try {
      responseObserver.onNext(owner.validate(request, authenticatedCaller));
      responseObserver.onCompleted();
    } catch (AccountJwtReadinessValidationService.InvalidReadinessRequestException invalid) {
      invalid(responseObserver);
    } catch (SecurityException denied) {
      deny(responseObserver);
    } catch (AccountJwtReadinessProbeRepository.CacheAgeNotElapsedException
        | AccountJwtReadinessProbeRepository.DeliveryAlreadyClaimedException
        | AccountJwtReadinessProbeRepository.ProbeExpiredException
        | AccountJwtReadinessValidationService.ProbeNotIssuedException precondition) {
      precondition(responseObserver);
    } catch (AccountAsymmetricJwtVerifier.VerificationException
        | AccountJwtReadinessProbeRepository.IdempotencyConflictException invalid) {
      invalid(responseObserver);
    } catch (RuntimeException unavailable) {
      unavailable(responseObserver);
    }
  }

  private static void invalid(StreamObserver<?> observer) {
    observer.onError(
        Status.INVALID_ARGUMENT
            .withDescription("Readiness request is invalid")
            .asRuntimeException());
  }

  private static void deny(StreamObserver<?> observer) {
    observer.onError(
        Status.PERMISSION_DENIED
            .withDescription("Authenticated Account readiness harness is required")
            .asRuntimeException());
  }

  private static void precondition(StreamObserver<?> observer) {
    observer.onError(
        Status.FAILED_PRECONDITION
            .withDescription("Readiness probe is not currently eligible")
            .asRuntimeException());
  }

  private static void unavailable(StreamObserver<?> observer) {
    observer.onError(Status.UNAVAILABLE.withDescription(UNAVAILABLE).asRuntimeException());
  }
}
