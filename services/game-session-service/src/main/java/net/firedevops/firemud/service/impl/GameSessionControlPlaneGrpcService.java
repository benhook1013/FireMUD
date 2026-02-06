package net.firedevops.firemud.service.impl;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import net.firedevops.firemud.entity.GameInstance;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetPinnedScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.GetPinnedScriptPatchVersionResponse;
import net.firedevops.firemud.gamesession.v1.PauseTicksForScopeRequest;
import net.firedevops.firemud.gamesession.v1.PauseTicksForScopeResponse;
import net.firedevops.firemud.gamesession.v1.ResumeTicksForScopeRequest;
import net.firedevops.firemud.gamesession.v1.ResumeTicksForScopeResponse;
import net.firedevops.firemud.gamesession.v1.RollbackScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.RollbackScriptPatchVersionResponse;
import net.firedevops.firemud.gamesession.v1.SetPinnedScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.SetPinnedScriptPatchVersionResponse;
import net.firedevops.firemud.repository.GameInstanceRepository;
import net.firedevops.firemud.service.TickService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.lognet.springboot.grpc.GRpcService;

@GRpcService
public final class GameSessionControlPlaneGrpcService
    extends GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceImplBase {
  private final GameInstanceRepository gameInstanceRepository;
  private final TickService tickService;
  private final MeterRegistry meterRegistry;

  public GameSessionControlPlaneGrpcService(
      GameInstanceRepository gameInstanceRepository,
      TickService tickService,
      MeterRegistry meterRegistry) {
    this.gameInstanceRepository = gameInstanceRepository;
    this.tickService = tickService;
    this.meterRegistry = meterRegistry;
  }

  private ErrorDetail error(String code, String message) {
    meterRegistry.counter("grpc.app_error", "code", code).increment();
    return ErrorDetail.newBuilder().setCode(code).setMessage(message).build();
  }

  private long parseSessionId(String gameInstanceId) {
    if (gameInstanceId == null || gameInstanceId.isBlank()) {
      throw new IllegalArgumentException("game_instance_id is required");
    }
    return Long.parseLong(gameInstanceId);
  }

  private GameInstance getInstanceOrThrow(long sessionId) {
    return gameInstanceRepository
        .findById(sessionId)
        .orElseThrow(() -> new IllegalArgumentException("Session not found"));
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.getPinnedScriptPatchVersion")
  public void getPinnedScriptPatchVersion(
      GetPinnedScriptPatchVersionRequest request,
      StreamObserver<GetPinnedScriptPatchVersionResponse> responseObserver) {
    try {
      long sessionId = parseSessionId(request.getGameInstanceId());
      GameInstance instance = getInstanceOrThrow(sessionId);
      GetPinnedScriptPatchVersionResponse response =
          GetPinnedScriptPatchVersionResponse.newBuilder()
              .setPinnedScriptPatchVersion(
                  instance.getScriptPatchVersion() == null ? "" : instance.getScriptPatchVersion())
              .setPinnedAtMs(
                  instance.getScriptPatchPinnedAt() == null
                      ? 0
                      : instance.getScriptPatchPinnedAt().toEpochMilli())
              .setPinnedBy(
                  instance.getScriptPatchPinnedBy() == null ? "" : instance.getScriptPatchPinnedBy())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      GetPinnedScriptPatchVersionResponse response =
          GetPinnedScriptPatchVersionResponse.newBuilder()
              .setError(error("INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.setPinnedScriptPatchVersion")
  public void setPinnedScriptPatchVersion(
      SetPinnedScriptPatchVersionRequest request,
      StreamObserver<SetPinnedScriptPatchVersionResponse> responseObserver) {
    try {
      long sessionId = parseSessionId(request.getGameInstanceId());
      GameInstance instance = getInstanceOrThrow(sessionId);

      String previous = instance.getScriptPatchVersion();
      instance.setScriptPatchVersion(request.getTargetScriptPatchVersion());
      instance.setScriptPatchPinnedAt(Instant.now());
      instance.setScriptPatchPinnedBy(request.getActorPrincipal());
      instance.setScriptPatchPinnedReason(request.getReason());
      gameInstanceRepository.save(instance);

      SetPinnedScriptPatchVersionResponse response =
          SetPinnedScriptPatchVersionResponse.newBuilder()
              .setPreviousScriptPatchVersion(previous == null ? "" : previous)
              .setPinnedScriptPatchVersion(
                  instance.getScriptPatchVersion() == null ? "" : instance.getScriptPatchVersion())
              .setControlPlaneRequestId(request.getControlPlaneRequestId())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      SetPinnedScriptPatchVersionResponse response =
          SetPinnedScriptPatchVersionResponse.newBuilder()
              .setError(error("INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.rollbackScriptPatchVersion")
  public void rollbackScriptPatchVersion(
      RollbackScriptPatchVersionRequest request,
      StreamObserver<RollbackScriptPatchVersionResponse> responseObserver) {
    try {
      long sessionId = parseSessionId(request.getGameInstanceId());
      GameInstance instance = getInstanceOrThrow(sessionId);

      String previous = instance.getScriptPatchVersion();
      instance.setScriptPatchVersion(request.getTargetScriptPatchVersion());
      instance.setScriptPatchPinnedAt(Instant.now());
      instance.setScriptPatchPinnedBy(request.getActorPrincipal());
      instance.setScriptPatchPinnedReason(request.getReason());
      gameInstanceRepository.save(instance);

      RollbackScriptPatchVersionResponse response =
          RollbackScriptPatchVersionResponse.newBuilder()
              .setPreviousScriptPatchVersion(previous == null ? "" : previous)
              .setPinnedScriptPatchVersion(
                  instance.getScriptPatchVersion() == null ? "" : instance.getScriptPatchVersion())
              .setControlPlaneRequestId(request.getControlPlaneRequestId())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      RollbackScriptPatchVersionResponse response =
          RollbackScriptPatchVersionResponse.newBuilder()
              .setError(error("INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.pauseTicksForScope")
  public void pauseTicksForScope(
      PauseTicksForScopeRequest request,
      StreamObserver<PauseTicksForScopeResponse> responseObserver) {
    tickService.pauseTicks(request.getReason());
    PauseTicksForScopeResponse response =
        PauseTicksForScopeResponse.newBuilder().setSuccess(true).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.resumeTicksForScope")
  public void resumeTicksForScope(
      ResumeTicksForScopeRequest request,
      StreamObserver<ResumeTicksForScopeResponse> responseObserver) {
    tickService.resumeTicks(request.getReason());
    ResumeTicksForScopeResponse response =
        ResumeTicksForScopeResponse.newBuilder().setSuccess(true).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }
}
