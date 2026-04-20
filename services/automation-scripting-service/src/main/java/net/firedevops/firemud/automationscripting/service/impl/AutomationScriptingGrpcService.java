package net.firedevops.firemud.automationscripting.service.impl;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.automationscripting.dto.ScriptDefinitionDto;
import net.firedevops.firemud.automationscripting.model.FormationType;
import net.firedevops.firemud.automationscripting.service.NpcFormationService;
import net.firedevops.firemud.automationscripting.service.PingService;
import net.firedevops.firemud.automationscripting.service.ScriptDefinitionService;
import net.firedevops.firemud.automationscripting.service.ScriptDesignDigestService;
import net.firedevops.firemud.automationscripting.service.ScriptVersionService;
import net.firedevops.firemud.automationscripting.v1.AddFormationMemberRequest;
import net.firedevops.firemud.automationscripting.v1.AddFormationMemberResponse;
import net.firedevops.firemud.automationscripting.v1.AutomationScriptingServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.CreateFormationRequest;
import net.firedevops.firemud.automationscripting.v1.CreateFormationResponse;
import net.firedevops.firemud.automationscripting.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.automationscripting.v1.GetDraftDesignDigestResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptStatusResponse;
import net.firedevops.firemud.automationscripting.v1.ListFormationMembersRequest;
import net.firedevops.firemud.automationscripting.v1.ListFormationMembersResponse;
import net.firedevops.firemud.automationscripting.v1.NotifyScriptVersionUpdateRequest;
import net.firedevops.firemud.automationscripting.v1.NotifyScriptVersionUpdateResponse;
import net.firedevops.firemud.automationscripting.v1.PingRequest;
import net.firedevops.firemud.automationscripting.v1.PingResponse;
import net.firedevops.firemud.automationscripting.v1.TriggerAdmissionOutcome;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventResponse;
import net.firedevops.firemud.automationscripting.v1.UpdateScriptRequest;
import net.firedevops.firemud.automationscripting.v1.UpdateScriptResponse;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.AdminRoleGuard;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public class AutomationScriptingGrpcService
    extends AutomationScriptingServiceGrpc.AutomationScriptingServiceImplBase {
  private static final Logger logger =
      LoggerFactory.getLogger(AutomationScriptingGrpcService.class);
  private final PingService pingService;
  private final ScriptDefinitionService scriptService;
  private final ScriptDesignDigestService scriptDesignDigestService;
  private final ScriptVersionService scriptVersionService;
  private final NpcFormationService formationService;
  private final MeterRegistry meterRegistry;

  public AutomationScriptingGrpcService(
      PingService pingService,
      ScriptDefinitionService scriptService,
      ScriptDesignDigestService scriptDesignDigestService,
      ScriptVersionService scriptVersionService,
      NpcFormationService formationService,
      MeterRegistry meterRegistry) {
    this.pingService = pingService;
    this.scriptService = scriptService;
    this.scriptDesignDigestService = scriptDesignDigestService;
    this.scriptVersionService = scriptVersionService;
    this.formationService = Objects.requireNonNull(formationService);
    this.meterRegistry = meterRegistry;
  }

  @Override
  @Timed(value = "automationGrpc.ping")
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    try {
      String msg = pingService.ping();
      PingResponse response = PingResponse.newBuilder().setMessage(msg).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      PingResponse response =
          PingResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "Ping", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      PingResponse response =
          PingResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "Ping", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "automationGrpc.triggerScriptEvent")
  public void triggerScriptEvent(
      TriggerScriptEventRequest request,
      StreamObserver<TriggerScriptEventResponse> responseObserver) {
    TriggerScriptEventResponse.Builder response =
        TriggerScriptEventResponse.newBuilder()
            .setAdmitted(false)
            .setAdmissionOutcome(TriggerAdmissionOutcome.TRIGGER_ADMISSION_OUTCOME_UNSPECIFIED);
    try {
      requireAdminRole();
      response
          .setAdmissionReason("not_implemented")
          .setError(
              GrpcAppErrors.error(
                  meterRegistry,
                  logger,
                  "TriggerScriptEvent",
                  "NOT_IMPLEMENTED",
                  "TriggerScriptEvent is not implemented yet"));
    } catch (AdminAuthorizationException ex) {
      response
          .setAdmissionReason("permission_denied")
          .setError(authorizationError("TriggerScriptEvent", ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "formationGrpc.createFormation")
  public void createFormation(
      CreateFormationRequest request, StreamObserver<CreateFormationResponse> responseObserver) {
    try {
      requireAdminRole();
      Long id =
          formationService.createFormation(
              Long.parseLong(request.getTenantId()),
              request.getName(),
              Long.parseLong(request.getLeaderNpcId()),
              FormationType.valueOf(request.getFormationType()));
      CreateFormationResponse resp =
          CreateFormationResponse.newBuilder().setFormationId(id.toString()).build();
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      CreateFormationResponse resp =
          CreateFormationResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "CreateFormation",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      CreateFormationResponse resp =
          CreateFormationResponse.newBuilder()
              .setError(authorizationError("CreateFormation", ex))
              .build();
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      CreateFormationResponse resp =
          CreateFormationResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "CreateFormation", ex))
              .build();
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "formationGrpc.addFormationMember")
  public void addFormationMember(
      AddFormationMemberRequest request,
      StreamObserver<AddFormationMemberResponse> responseObserver) {
    try {
      requireAdminRole();
      formationService.addMember(
          Long.parseLong(request.getTenantId()),
          Long.parseLong(request.getFormationId()),
          Long.parseLong(request.getNpcId()));
      AddFormationMemberResponse resp =
          AddFormationMemberResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      AddFormationMemberResponse resp =
          AddFormationMemberResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "AddFormationMember",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      AddFormationMemberResponse resp =
          AddFormationMemberResponse.newBuilder()
              .setSuccess(false)
              .setError(authorizationError("AddFormationMember", ex))
              .build();
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      AddFormationMemberResponse resp =
          AddFormationMemberResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "AddFormationMember", ex))
              .build();
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "formationGrpc.listFormationMembers")
  public void listFormationMembers(
      ListFormationMembersRequest request,
      StreamObserver<ListFormationMembersResponse> responseObserver) {
    try {
      requireAdminRole();
      List<Long> members =
          formationService.getMembers(
              Long.parseLong(request.getTenantId()), Long.parseLong(request.getFormationId()));
      ListFormationMembersResponse resp =
          ListFormationMembersResponse.newBuilder()
              .addAllNpcIds(members.stream().map(Object::toString).toList())
              .build();
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      ListFormationMembersResponse resp =
          ListFormationMembersResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "ListFormationMembers",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build();
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      ListFormationMembersResponse resp =
          ListFormationMembersResponse.newBuilder()
              .setError(authorizationError("ListFormationMembers", ex))
              .build();
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      ListFormationMembersResponse resp =
          ListFormationMembersResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "ListFormationMembers", ex))
              .build();
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "automationGrpc.updateScript")
  public void updateScript(
      UpdateScriptRequest request, StreamObserver<UpdateScriptResponse> responseObserver) {
    try {
      requireAdminRole();
      ScriptDefinitionDto dto =
          new ScriptDefinitionDto(
              null,
              Long.parseLong(request.getTenantId()),
              request.getName(),
              request.getVersion(),
              request.getDefinition());
      scriptService.updateScript(dto);
      UpdateScriptResponse resp = UpdateScriptResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      UpdateScriptResponse resp =
          UpdateScriptResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "UpdateScript", "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      UpdateScriptResponse resp =
          UpdateScriptResponse.newBuilder()
              .setSuccess(false)
              .setError(authorizationError("UpdateScript", ex))
              .build();
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      UpdateScriptResponse resp =
          UpdateScriptResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "UpdateScript", ex))
              .build();
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "automationGrpc.getDraftDesignDigest")
  public void getDraftDesignDigest(
      GetDraftDesignDigestRequest request,
      StreamObserver<GetDraftDesignDigestResponse> responseObserver) {
    try {
      requireAdminRole();
      var digest =
          request.getScopeCase() == GetDraftDesignDigestRequest.ScopeCase.VERSION_ID
              ? scriptDesignDigestService.getDraftDesignDigestForVersion(
                  request.getTenantId(), request.getVersionId())
              : scriptDesignDigestService.getDraftDesignDigestForScriptPatch(
                  request.getTenantId(), request.getScriptPatchVersion());
      responseObserver.onNext(
          GetDraftDesignDigestResponse.newBuilder()
              .setTenantId(digest.tenantId())
              .setScopeValue(digest.scopeValue())
              .setAppliedCommitId(digest.appliedCommitId())
              .setContentDigest(digest.contentDigest())
              .setDigestSchemaVersion(digest.digestSchemaVersion())
              .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          GetDraftDesignDigestResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "GetDraftDesignDigest",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException ex) {
      responseObserver.onNext(
          GetDraftDesignDigestResponse.newBuilder()
              .setError(authorizationError("GetDraftDesignDigest", ex))
              .build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onNext(
          GetDraftDesignDigestResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "GetDraftDesignDigest", ex))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "automationGrpc.getScriptStatus")
  public void getScriptStatus(
      GetScriptStatusRequest request, StreamObserver<GetScriptStatusResponse> responseObserver) {
    GetScriptStatusResponse.Builder response = GetScriptStatusResponse.newBuilder();
    try {
      requireAdminRole();
      // Placeholder implementation; scripts execute asynchronously
      response.setQueued(false).setRunning(false);
    } catch (AdminAuthorizationException ex) {
      response.setError(authorizationError("GetScriptStatus", ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.notifyScriptVersionUpdate")
  public void notifyScriptVersionUpdate(
      NotifyScriptVersionUpdateRequest request,
      StreamObserver<NotifyScriptVersionUpdateResponse> responseObserver) {
    NotifyScriptVersionUpdateResponse.Builder response =
        NotifyScriptVersionUpdateResponse.newBuilder();
    try {
      requireAdminRole();
      scriptVersionService.notifyUpdate(
          request.getTenantId(), request.getScriptPatchVersion(), request.getAffectedScriptsList());
      response.setSuccess(true);
    } catch (AdminAuthorizationException ex) {
      response.setSuccess(false).setError(authorizationError("NotifyScriptVersionUpdate", ex));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  private void requireAdminRole() {
    AdminRoleGuard.requireAdminRole();
  }

  private ErrorDetail authorizationError(String operation, AdminAuthorizationException ex) {
    return GrpcAppErrors.error(
        meterRegistry, logger, operation, "PERMISSION_DENIED", ex.getMessage());
  }
}
