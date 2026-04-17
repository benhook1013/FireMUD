package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.AdminRoleGuard;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.AdmissionPointerVersionMismatchException;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuditEntry;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerMutation;
import net.firedevops.firemud.gamesession.service.TickService;
import net.firedevops.firemud.gamesession.v1.AdmissionPointerControlPlaneEntry;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetPinnedScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.GetPinnedScriptPatchVersionResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointerAuditRequest;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointerAuditResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointersRequest;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointersResponse;
import net.firedevops.firemud.gamesession.v1.PauseTicksForScopeRequest;
import net.firedevops.firemud.gamesession.v1.PauseTicksForScopeResponse;
import net.firedevops.firemud.gamesession.v1.ResumeTicksForScopeRequest;
import net.firedevops.firemud.gamesession.v1.ResumeTicksForScopeResponse;
import net.firedevops.firemud.gamesession.v1.RollbackScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.RollbackScriptPatchVersionResponse;
import net.firedevops.firemud.gamesession.v1.SetAdmissionPointerRequest;
import net.firedevops.firemud.gamesession.v1.SetAdmissionPointerResponse;
import net.firedevops.firemud.gamesession.v1.SetPinnedScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.SetPinnedScriptPatchVersionResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService(interceptors = AuthTokenInterceptor.class)
public final class GameSessionControlPlaneGrpcService
    extends GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceImplBase {
  private static final Logger logger =
      LoggerFactory.getLogger(GameSessionControlPlaneGrpcService.class);

  private final GameInstanceRepository gameInstanceRepository;
  private final GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService;
  private final TickService tickService;
  private final MeterRegistry meterRegistry;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected repository/services are internal Spring collaborators")
  public GameSessionControlPlaneGrpcService(
      GameInstanceRepository gameInstanceRepository,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      TickService tickService,
      MeterRegistry meterRegistry) {
    this.gameInstanceRepository = gameInstanceRepository;
    this.gameplayAdmissionPointerAuthorityService = gameplayAdmissionPointerAuthorityService;
    this.tickService = tickService;
    this.meterRegistry = meterRegistry;
  }

  private long parseTenantId(String tenantId) {
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalArgumentException("tenant_id is required");
    }
    try {
      return Long.parseLong(tenantId);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("tenant_id must be a number");
    }
  }

  private long parseGameInstanceId(String gameInstanceId) {
    if (gameInstanceId == null || gameInstanceId.isBlank()) {
      throw new IllegalArgumentException("game_instance_id is required");
    }
    try {
      return Long.parseLong(gameInstanceId);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("game_instance_id must be a number");
    }
  }

  private GameInstance getInstanceOrThrow(long gameInstanceId) {
    return gameInstanceRepository
        .findById(gameInstanceId)
        .orElseThrow(() -> new IllegalArgumentException("Game instance not found"));
  }

  private void requireAdminRole() {
    AdminRoleGuard.requireAdminRole();
  }

  private ErrorDetail authorizationError(String operation, AdminAuthorizationException ex) {
    return GrpcAppErrors.error(
        meterRegistry, logger, operation, "PERMISSION_DENIED", ex.getMessage());
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.listAdmissionPointers")
  public void listAdmissionPointers(
      ListAdmissionPointersRequest request,
      StreamObserver<ListAdmissionPointersResponse> responseObserver) {
    try {
      requireAdminRole();
      ListAdmissionPointersResponse response =
          ListAdmissionPointersResponse.newBuilder()
              .addAllPointers(
                  gameplayAdmissionPointerAuthorityService.listPointers().stream()
                      .flatMap(
                          pointer ->
                              gameplayAdmissionPointerAuthorityService
                                  .listPointerAudit(pointer.worldSlug(), pointer.realmSlug())
                                  .stream()
                                  .limit(1)
                                  .map(this::toEntry))
                      .toList())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      ListAdmissionPointersResponse response =
          ListAdmissionPointersResponse.newBuilder()
              .setError(authorizationError("ListAdmissionPointers", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("ListAdmissionPointers failed", ex);
      ListAdmissionPointersResponse response =
          ListAdmissionPointersResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.listAdmissionPointerAudit")
  public void listAdmissionPointerAudit(
      ListAdmissionPointerAuditRequest request,
      StreamObserver<ListAdmissionPointerAuditResponse> responseObserver) {
    try {
      requireAdminRole();
      ListAdmissionPointerAuditResponse response =
          ListAdmissionPointerAuditResponse.newBuilder()
              .addAllAudit(
                  gameplayAdmissionPointerAuthorityService
                      .listPointerAudit(request.getWorldSlug(), request.getRealmSlug())
                      .stream()
                      .map(this::toEntry)
                      .toList())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      ListAdmissionPointerAuditResponse response =
          ListAdmissionPointerAuditResponse.newBuilder()
              .setError(authorizationError("ListAdmissionPointerAudit", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("ListAdmissionPointerAudit failed", ex);
      ListAdmissionPointerAuditResponse response =
          ListAdmissionPointerAuditResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.setAdmissionPointer")
  public void setAdmissionPointer(
      SetAdmissionPointerRequest request,
      StreamObserver<SetAdmissionPointerResponse> responseObserver) {
    try {
      requireAdminRole();
      gameplayAdmissionPointerAuthorityService.upsertPointer(
          new GameplayAdmissionPointerMutation(
              request.getWorldSlug(),
              request.getWorldDisplayName(),
              request.getRealmSlug(),
              request.getRealmDisplayName(),
              parseTenantId(request.getTenantId()),
              parseGameInstanceId(request.getGameInstanceId()),
              request.getVisible(),
              request.getRequiresCharacterSelection(),
              request.getStateScope(),
              request.getCharacterCreationPolicy(),
              request.getActorPrincipal(),
              request.getReason(),
              request.getControlPlaneRequestId(),
              request.hasExpectedPointerVersion() ? request.getExpectedPointerVersion() : null));
      AdmissionPointerControlPlaneEntry entry =
          gameplayAdmissionPointerAuthorityService
              .listPointerAudit(request.getWorldSlug(), request.getRealmSlug())
              .stream()
              .findFirst()
              .map(this::toEntry)
              .orElseThrow(() -> new IllegalStateException("Admission pointer audit missing"));
      SetAdmissionPointerResponse response =
          SetAdmissionPointerResponse.newBuilder().setPointer(entry).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      SetAdmissionPointerResponse response =
          SetAdmissionPointerResponse.newBuilder()
              .setError(authorizationError("SetAdmissionPointer", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      SetAdmissionPointerResponse response =
          SetAdmissionPointerResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdmissionPointerVersionMismatchException ex) {
      SetAdmissionPointerResponse response =
          SetAdmissionPointerResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(meterRegistry, "POINTER_VERSION_MISMATCH", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("SetAdmissionPointer failed", ex);
      SetAdmissionPointerResponse response =
          SetAdmissionPointerResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.getPinnedScriptPatchVersion")
  public void getPinnedScriptPatchVersion(
      GetPinnedScriptPatchVersionRequest request,
      StreamObserver<GetPinnedScriptPatchVersionResponse> responseObserver) {
    try {
      requireAdminRole();
      long tenantId = parseTenantId(request.getTenantId());
      long gameInstanceId = parseGameInstanceId(request.getGameInstanceId());
      GameInstance instance = getInstanceOrThrow(gameInstanceId);
      if (instance.getTenantId() != tenantId) {
        throw new IllegalArgumentException("tenant_id does not own game_instance_id");
      }
      GetPinnedScriptPatchVersionResponse response =
          GetPinnedScriptPatchVersionResponse.newBuilder()
              .setPinnedScriptPatchVersion(
                  instance.getScriptPatchVersion() == null ? "" : instance.getScriptPatchVersion())
              .setPinnedAtMs(
                  instance.getScriptPatchPinnedAt() == null
                      ? 0
                      : instance.getScriptPatchPinnedAt().toEpochMilli())
              .setPinnedBy(
                  instance.getScriptPatchPinnedBy() == null
                      ? ""
                      : instance.getScriptPatchPinnedBy())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      GetPinnedScriptPatchVersionResponse response =
          GetPinnedScriptPatchVersionResponse.newBuilder()
              .setError(authorizationError("GetPinnedScriptPatchVersion", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      GetPinnedScriptPatchVersionResponse response =
          GetPinnedScriptPatchVersionResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("GetPinnedScriptPatchVersion failed", ex);
      GetPinnedScriptPatchVersionResponse response =
          GetPinnedScriptPatchVersionResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
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
      requireAdminRole();
      long tenantId = parseTenantId(request.getTenantId());
      long gameInstanceId = parseGameInstanceId(request.getGameInstanceId());
      GameInstance instance = getInstanceOrThrow(gameInstanceId);
      if (instance.getTenantId() != tenantId) {
        throw new IllegalArgumentException("tenant_id does not own game_instance_id");
      }

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
    } catch (AdminAuthorizationException ex) {
      SetPinnedScriptPatchVersionResponse response =
          SetPinnedScriptPatchVersionResponse.newBuilder()
              .setError(authorizationError("SetPinnedScriptPatchVersion", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      SetPinnedScriptPatchVersionResponse response =
          SetPinnedScriptPatchVersionResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("SetPinnedScriptPatchVersion failed", ex);
      SetPinnedScriptPatchVersionResponse response =
          SetPinnedScriptPatchVersionResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
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
      requireAdminRole();
      long tenantId = parseTenantId(request.getTenantId());
      long gameInstanceId = parseGameInstanceId(request.getGameInstanceId());
      GameInstance instance = getInstanceOrThrow(gameInstanceId);
      if (instance.getTenantId() != tenantId) {
        throw new IllegalArgumentException("tenant_id does not own game_instance_id");
      }

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
    } catch (AdminAuthorizationException ex) {
      RollbackScriptPatchVersionResponse response =
          RollbackScriptPatchVersionResponse.newBuilder()
              .setError(authorizationError("RollbackScriptPatchVersion", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      RollbackScriptPatchVersionResponse response =
          RollbackScriptPatchVersionResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("RollbackScriptPatchVersion failed", ex);
      RollbackScriptPatchVersionResponse response =
          RollbackScriptPatchVersionResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  private AdmissionPointerControlPlaneEntry toEntry(GameplayAdmissionPointerAuditEntry entry) {
    return AdmissionPointerControlPlaneEntry.newBuilder()
        .setWorldSlug(entry.worldSlug())
        .setWorldDisplayName(entry.worldDisplayName())
        .setRealmSlug(entry.realmSlug())
        .setRealmDisplayName(entry.realmDisplayName())
        .setTenantId(Long.toString(entry.tenantId()))
        .setGameInstanceId(Long.toString(entry.gameInstanceId()))
        .setPointerVersion(entry.pointerVersion())
        .setVisible(entry.visible())
        .setRequiresCharacterSelection(entry.requiresCharacterSelection())
        .setStateScope(entry.stateScope())
        .setCharacterCreationPolicy(entry.characterCreationPolicy())
        .setActorPrincipal(entry.actorPrincipal())
        .setReason(entry.reason())
        .setControlPlaneRequestId(entry.controlPlaneRequestId())
        .setOccurredAtMs(entry.occurredAt().toEpochMilli())
        .build();
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.pauseTicksForScope")
  public void pauseTicksForScope(
      PauseTicksForScopeRequest request,
      StreamObserver<PauseTicksForScopeResponse> responseObserver) {
    try {
      requireAdminRole();
      long tenantId = parseTenantId(request.getTenantId());
      if (!request.getRegionId().isBlank()) {
        throw new IllegalArgumentException("region_id is not supported; set it empty");
      }
      long gameInstanceId = parseGameInstanceId(request.getGameInstanceId());
      GameInstance instance = getInstanceOrThrow(gameInstanceId);
      if (instance.getTenantId() != tenantId) {
        throw new IllegalArgumentException("tenant_id does not own game_instance_id");
      }
      tickService.pauseTicksForGameInstance(gameInstanceId, request.getReason());
      PauseTicksForScopeResponse response =
          PauseTicksForScopeResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      PauseTicksForScopeResponse response =
          PauseTicksForScopeResponse.newBuilder()
              .setSuccess(false)
              .setError(authorizationError("PauseTicksForScope", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      PauseTicksForScopeResponse response =
          PauseTicksForScopeResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("PauseTicksForScope failed", ex);
      PauseTicksForScopeResponse response =
          PauseTicksForScopeResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.controlPlane.resumeTicksForScope")
  public void resumeTicksForScope(
      ResumeTicksForScopeRequest request,
      StreamObserver<ResumeTicksForScopeResponse> responseObserver) {
    try {
      requireAdminRole();
      long tenantId = parseTenantId(request.getTenantId());
      if (!request.getRegionId().isBlank()) {
        throw new IllegalArgumentException("region_id is not supported; set it empty");
      }
      long gameInstanceId = parseGameInstanceId(request.getGameInstanceId());
      GameInstance instance = getInstanceOrThrow(gameInstanceId);
      if (instance.getTenantId() != tenantId) {
        throw new IllegalArgumentException("tenant_id does not own game_instance_id");
      }
      tickService.resumeTicksForGameInstance(gameInstanceId, request.getReason());
      ResumeTicksForScopeResponse response =
          ResumeTicksForScopeResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      ResumeTicksForScopeResponse response =
          ResumeTicksForScopeResponse.newBuilder()
              .setSuccess(false)
              .setError(authorizationError("ResumeTicksForScope", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      ResumeTicksForScopeResponse response =
          ResumeTicksForScopeResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      logger.error("ResumeTicksForScope failed", ex);
      ResumeTicksForScopeResponse response =
          ResumeTicksForScopeResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "INTERNAL", "Internal error"))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }
}
