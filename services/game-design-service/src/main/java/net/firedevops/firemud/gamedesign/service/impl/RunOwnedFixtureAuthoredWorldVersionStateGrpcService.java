package net.firedevops.firemud.gamedesign.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateGrpcCodec;
import net.firedevops.firemud.gamedesign.data.RunOwnedFixtureOwnerReadAuthorization;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.v1.AuthoredWorldVersionStateServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.GetAuthoredWorldVersionStateRequest;
import net.firedevops.firemud.gamedesign.v1.GetAuthoredWorldVersionStateResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.PlatformTransactionManager;

/** Fixed-fixture registration of the existing standalone source-qualified Version kernel. */
@GrpcService
@ConditionalOnProperty(
    name = "FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED",
    havingValue = "true")
public final class RunOwnedFixtureAuthoredWorldVersionStateGrpcService
    extends AuthoredWorldVersionStateServiceGrpc.AuthoredWorldVersionStateServiceImplBase {
  private final RunOwnedFixtureOwnerReadAuthorization authorization;
  private final AuthoredWorldVersionStateGrpcService kernel;

  public RunOwnedFixtureAuthoredWorldVersionStateGrpcService(
      RunOwnedFixtureOwnerReadAuthorization authorization,
      PlatformTransactionManager transactions,
      GameAuthoredWorldSourceRepository repository,
      @Value("${firemud.grpc.workload-namespace:}") String namespace) {
    this.authorization = authorization;
    this.kernel =
        new AuthoredWorldVersionStateGrpcService(
            new AuthoredWorldVersionStateService(transactions, repository), namespace);
  }

  @Override
  public void getAuthoredWorldVersionState(
      GetAuthoredWorldVersionStateRequest request,
      StreamObserver<GetAuthoredWorldVersionStateResponse> observer) {
    try {
      var receipt = authorization.authorizeVersion(request);
      var expected = AuthoredWorldVersionStateGrpcCodec.fromRequest(request);
      kernel.getAuthoredWorldVersionState(
          request,
          new StreamObserver<>() {
            private GetAuthoredWorldVersionStateResponse result;

            @Override
            public void onNext(GetAuthoredWorldVersionStateResponse value) {
              result = value;
            }

            @Override
            public void onError(Throwable error) {
              observer.onError(error);
            }

            @Override
            public void onCompleted() {
              try {
                var evidence = AuthoredWorldVersionStateGrpcCodec.fromResponse(expected, result);
                var launch = receipt.launchReadEvidence();
                if (!evidence.sourceEvidence().equals(receipt.sourceEvidence())
                    || !evidence
                        .canonicalVersionId()
                        .equals(
                            UUID.fromString(
                                launch.publishedReleaseBundle().getCanonicalVersionId()))
                    || evidence.versionState() != launch.versionState().getVersionState()
                    || evidence.versionStateEpoch()
                        != launch.versionState().getVersionStateEpoch()) {
                  throw new IllegalStateException(
                      "Committed Version changed after fixture qualification");
                }
                observer.onNext(result);
                observer.onCompleted();
              } catch (RuntimeException invalid) {
                observer.onError(
                    Status.FAILED_PRECONDITION
                        .withDescription("Exact committed fixture Version unavailable")
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
              .withDescription("Closed fixture Version request required")
              .asRuntimeException());
    } catch (RuntimeException unavailable) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Committed fixture authority unavailable")
              .asRuntimeException());
    }
  }
}
