package net.firedevops.firemud.accountservice.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.account.v1.AccountPublicJwksReadServiceGrpc;
import net.firedevops.firemud.account.v1.GetCurrentAccountPublicJwksRequest;
import net.firedevops.firemud.account.v1.GetCurrentAccountPublicJwksResponse;
import net.firedevops.firemud.accountservice.config.AccountPublicJwksReadWorkloadGuard;
import net.firedevops.firemud.accountservice.service.session.AccountPublicJwksReadService;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.AccountPublicJwksReadCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.grpc.server.service.GrpcService;

/** Registered on the ordinary listener only under the separate, default-off public-read opt-in. */
@GrpcService
@ConditionalOnProperty(
    prefix = "firemud.account.public-jwks-read",
    name = "enabled",
    havingValue = "true")
public final class AccountPublicJwksReadGrpcService
    extends AccountPublicJwksReadServiceGrpc.AccountPublicJwksReadServiceImplBase {
  private final AccountPublicJwksReadService owner;

  public AccountPublicJwksReadGrpcService(AccountPublicJwksReadService owner) {
    this.owner = java.util.Objects.requireNonNull(owner);
  }

  @Override
  public void getCurrentPublicJwks(
      GetCurrentAccountPublicJwksRequest request,
      StreamObserver<GetCurrentAccountPublicJwksResponse> observer) {
    try {
      observer.onNext(owner.read(request));
      observer.onCompleted();
    } catch (AccountPublicJwksReadWorkloadGuard.ReadDeniedException denied) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("Authorized Game Session workload required")
              .asRuntimeException());
    } catch (AccountPublicJwksReadCodec.InvalidFrameException invalid) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Current public JWKS request invalid")
              .asRuntimeException());
    } catch (AccountPublicJwksCache.SourceUnavailableException unavailable) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Current Account public JWKS unavailable")
              .asRuntimeException());
    } catch (AccountPublicJwksCache.InvalidJwksException rejected) {
      // Typed custody/provenance and producer-frame failures remain hard denials.
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Current Account public JWKS rejected")
              .asRuntimeException());
    } catch (RuntimeException unavailable) {
      // An unclassified handler fault supplies no evidence of custody rejection.
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Current Account public JWKS unavailable")
              .asRuntimeException());
    }
  }
}
