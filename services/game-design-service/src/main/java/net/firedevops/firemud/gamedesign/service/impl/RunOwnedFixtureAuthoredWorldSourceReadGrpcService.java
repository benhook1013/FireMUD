package net.firedevops.firemud.gamedesign.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceGrpcCodec;
import net.firedevops.firemud.gamedesign.data.RunOwnedFixtureOwnerReadAuthorization;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.v1.AuthoredWorldSourceReadServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.grpc.server.service.GrpcService;

/** Fixed-fixture registration of the existing standalone source owner kernel. */
@GrpcService
@ConditionalOnProperty(
    name = "FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED",
    havingValue = "true")
public final class RunOwnedFixtureAuthoredWorldSourceReadGrpcService
    extends AuthoredWorldSourceReadServiceGrpc.AuthoredWorldSourceReadServiceImplBase {
  private final RunOwnedFixtureOwnerReadAuthorization authorization;
  private final AuthoredWorldSourceReadGrpcService kernel;
  private final String namespace;

  public RunOwnedFixtureAuthoredWorldSourceReadGrpcService(
      RunOwnedFixtureOwnerReadAuthorization authorization,
      GameAuthoredWorldSourceRepository repository,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    this.authorization = authorization;
    this.namespace = namespace;
    this.kernel = new AuthoredWorldSourceReadGrpcService(repository, namespace);
  }

  @Override
  public void resolveAuthoredWorldSource(
      ResolveAuthoredWorldSourceRequest request,
      StreamObserver<ResolveAuthoredWorldSourceResponse> observer) {
    try {
      var receipt = authorization.authorizeSource(request);
      var expected = AuthoredWorldSourceGrpcCodec.fromReadRequest(namespace, request);
      kernel.resolveAuthoredWorldSource(
          request,
          new StreamObserver<>() {
            private ResolveAuthoredWorldSourceResponse result;

            @Override
            public void onNext(ResolveAuthoredWorldSourceResponse value) {
              result = value;
            }

            @Override
            public void onError(Throwable error) {
              observer.onError(error);
            }

            @Override
            public void onCompleted() {
              try {
                var source = AuthoredWorldSourceGrpcCodec.fromReadResponse(expected, result);
                if (!source.equals(receipt.sourceEvidence())) {
                  throw new IllegalStateException(
                      "Committed source changed after fixture qualification");
                }
                observer.onNext(result);
                observer.onCompleted();
              } catch (RuntimeException invalid) {
                observer.onError(
                    Status.FAILED_PRECONDITION
                        .withDescription("Exact committed fixture source unavailable")
                        .asRuntimeException());
              }
            }
          });
    } catch (SecurityException denied) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("Fixed World fixture read denied")
              .asRuntimeException());
    } catch (IllegalArgumentException invalid) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Closed fixture source request required")
              .asRuntimeException());
    } catch (RuntimeException unavailable) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Committed fixture authority unavailable")
              .asRuntimeException());
    }
  }
}
