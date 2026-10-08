package net.firedevops.firemud.gamesession.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.gamesession.config.GameSessionJwtReadinessProbeOwnerWorkloadGuard;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessReceiverEngine;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessReceiverProtoMapper;
import net.firedevops.firemud.gamesession.v1.GameSessionJwtReadinessReceiverServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReceiveReadinessProbeRequest;
import net.firedevops.firemud.gamesession.v1.ReceiveReadinessProbeResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.grpc.server.service.GrpcService;

/** Default-inactive, non-authorizing receiver for one exact Game Session Pod. */
@GrpcService
@Lazy
@ConditionalOnProperty(
    prefix = "firemud.game-session.jwt-readiness.receiver",
    name = "enabled",
    havingValue = "true")
@ConditionalOnBean(GameSessionJwtReadinessReceiverEngine.class)
public final class GameSessionJwtReadinessReceiverGrpcService
    extends GameSessionJwtReadinessReceiverServiceGrpc
        .GameSessionJwtReadinessReceiverServiceImplBase {
  private static final String UNAVAILABLE = "Game Session readiness receiver is unavailable";

  private final GameSessionJwtReadinessReceiverEngine receiver;

  public GameSessionJwtReadinessReceiverGrpcService(
      GameSessionJwtReadinessReceiverEngine receiver) {
    this.receiver = receiver;
  }

  @Override
  public void receiveReadinessProbe(
      ReceiveReadinessProbeRequest request,
      StreamObserver<ReceiveReadinessProbeResponse> responseObserver) {
    try {
      responseObserver.onNext(receiver.receiveReadinessProbe(request));
      responseObserver.onCompleted();
    } catch (GameSessionJwtReadinessProbeOwnerWorkloadGuard.ReceiverCallerDeniedException denied) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Authorized Account workload is required")
              .asRuntimeException());
    } catch (GameSessionJwtReadinessReceiverProtoMapper.InvalidReceiverRequestException invalid) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Game Session readiness request is invalid")
              .asRuntimeException());
    } catch (AccountAsymmetricJwtVerifier.VerificationException rejected) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Current Account readiness probe was not verified")
              .asRuntimeException());
    } catch (RuntimeException unavailable) {
      responseObserver.onError(
          Status.UNAVAILABLE.withDescription(UNAVAILABLE).asRuntimeException());
    }
  }
}
