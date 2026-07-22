package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.automationscripting.v1.AutomationScriptingControlPlaneServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPatchRequest;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPatchResponse;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPluginVersionRequest;
import net.firedevops.firemud.automationscripting.v1.CancelPendingWorkItemsForPluginVersionResponse;
import net.firedevops.firemud.automationscripting.v1.DisablePluginRequest;
import net.firedevops.firemud.automationscripting.v1.DisablePluginResponse;
import net.firedevops.firemud.automationscripting.v1.DrainPluginRequest;
import net.firedevops.firemud.automationscripting.v1.DrainPluginResponse;
import net.firedevops.firemud.automationscripting.v1.GetAutomationDrainStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetAutomationDrainStatusResponse;
import net.firedevops.firemud.automationscripting.v1.GetAutomationPinConvergenceRequest;
import net.firedevops.firemud.automationscripting.v1.GetAutomationPinConvergenceResponse;
import net.firedevops.firemud.automationscripting.v1.GetPluginPolicyConvergenceRequest;
import net.firedevops.firemud.automationscripting.v1.GetPluginPolicyConvergenceResponse;
import net.firedevops.firemud.automationscripting.v1.GetPluginStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetPluginStatusResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptEventDefinitionRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptEventDefinitionResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchInstanceRolloutStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchInstanceRolloutStatusResponse;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchStatusRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptPatchStatusResponse;
import net.firedevops.firemud.automationscripting.v1.ListPluginRuntimeEventsRequest;
import net.firedevops.firemud.automationscripting.v1.ListPluginRuntimeEventsResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptDeadLettersRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptDeadLettersResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptEventDefinitionsRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptEventDefinitionsResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptHandoffEventsRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptHandoffEventsResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchInstanceRolloutEventsRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchInstanceRolloutEventsResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchInstanceRolloutsRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchInstanceRolloutsResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchStatusesRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptPatchStatusesResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptScheduleInstancesRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptScheduleInstancesResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptTimerAuditEventsRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptTimerAuditEventsResponse;
import net.firedevops.firemud.automationscripting.v1.ReplayDeadLetteredWorkItemsRequest;
import net.firedevops.firemud.automationscripting.v1.ReplayDeadLetteredWorkItemsResponse;
import net.firedevops.firemud.automationscripting.v1.SetAutomationAdmissionModeRequest;
import net.firedevops.firemud.automationscripting.v1.SetAutomationAdmissionModeResponse;
import net.firedevops.firemud.automationscripting.v1.SetPluginActiveVersionRequest;
import net.firedevops.firemud.automationscripting.v1.SetPluginActiveVersionResponse;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.AdminRoleGuard;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected Spring dependencies are not exposed externally")
public final class AutomationScriptingControlPlaneGrpcService
    extends AutomationScriptingControlPlaneServiceGrpc
        .AutomationScriptingControlPlaneServiceImplBase {

  private final AutomationEventControlPlaneService eventControlPlaneService;
  private final AutomationPatchControlPlaneService patchControlPlaneService;
  private final AutomationPluginControlPlaneService pluginControlPlaneService;

  @org.springframework.beans.factory.annotation.Autowired
  public AutomationScriptingControlPlaneGrpcService(
      AutomationEventControlPlaneService eventControlPlaneService,
      AutomationPatchControlPlaneService patchControlPlaneService,
      AutomationPluginControlPlaneService pluginControlPlaneService) {
    this.eventControlPlaneService = eventControlPlaneService;
    this.patchControlPlaneService = patchControlPlaneService;
    this.pluginControlPlaneService = pluginControlPlaneService;
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.getScriptEventDefinition")
  public void getScriptEventDefinition(
      GetScriptEventDefinitionRequest request,
      StreamObserver<GetScriptEventDefinitionResponse> responseObserver) {
    GetScriptEventDefinitionResponse response =
        GetScriptEventDefinitionResponse.getDefaultInstance();
    try {
      requireAdminRole();
      response = eventControlPlaneService.getScriptEventDefinition(request);
    } catch (AdminAuthorizationException ex) {
      response =
          GetScriptEventDefinitionResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.authorizationError(ex))
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.listScriptEventDefinitions")
  public void listScriptEventDefinitions(
      ListScriptEventDefinitionsRequest request,
      StreamObserver<ListScriptEventDefinitionsResponse> responseObserver) {
    ListScriptEventDefinitionsResponse response =
        ListScriptEventDefinitionsResponse.getDefaultInstance();
    try {
      requireAdminRole();
      response = eventControlPlaneService.listScriptEventDefinitions(request);
    } catch (AdminAuthorizationException ex) {
      response =
          ListScriptEventDefinitionsResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.authorizationError(ex))
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.getScriptPatchStatus")
  public void getScriptPatchStatus(
      GetScriptPatchStatusRequest request,
      StreamObserver<GetScriptPatchStatusResponse> responseObserver) {
    GetScriptPatchStatusResponse response = GetScriptPatchStatusResponse.getDefaultInstance();
    try {
      requireAdminRole();
      response = patchControlPlaneService.getScriptPatchStatus(request);
    } catch (IllegalArgumentException ex) {
      response =
          GetScriptPatchStatusResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.invalidArgument(ex.getMessage()))
              .build();
    } catch (AdminAuthorizationException ex) {
      response =
          GetScriptPatchStatusResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.authorizationError(ex))
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.listScriptPatchStatuses")
  public void listScriptPatchStatuses(
      ListScriptPatchStatusesRequest request,
      StreamObserver<ListScriptPatchStatusesResponse> responseObserver) {
    ListScriptPatchStatusesResponse response = ListScriptPatchStatusesResponse.getDefaultInstance();
    try {
      requireAdminRole();
      response = patchControlPlaneService.listScriptPatchStatuses(request);
    } catch (IllegalArgumentException ex) {
      response =
          ListScriptPatchStatusesResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.invalidArgument(ex.getMessage()))
              .build();
    } catch (AdminAuthorizationException ex) {
      response =
          ListScriptPatchStatusesResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.authorizationError(ex))
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.setAutomationAdmissionMode")
  public void setAutomationAdmissionMode(
      SetAutomationAdmissionModeRequest request,
      StreamObserver<SetAutomationAdmissionModeResponse> responseObserver) {
    SetAutomationAdmissionModeResponse response =
        SetAutomationAdmissionModeResponse.getDefaultInstance();
    try {
      requireAdminRole();
      response = patchControlPlaneService.setAutomationAdmissionMode(request);
    } catch (IllegalArgumentException ex) {
      response =
          SetAutomationAdmissionModeResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.invalidArgument(ex.getMessage()))
              .build();
    } catch (AdminAuthorizationException ex) {
      response =
          SetAutomationAdmissionModeResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.authorizationError(ex))
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.getAutomationDrainStatus")
  public void getAutomationDrainStatus(
      GetAutomationDrainStatusRequest request,
      StreamObserver<GetAutomationDrainStatusResponse> responseObserver) {
    GetAutomationDrainStatusResponse response =
        GetAutomationDrainStatusResponse.getDefaultInstance();
    try {
      requireAdminRole();
      response = patchControlPlaneService.getAutomationDrainStatus(request);
    } catch (IllegalArgumentException ex) {
      response =
          GetAutomationDrainStatusResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.invalidArgument(ex.getMessage()))
              .build();
    } catch (AdminAuthorizationException ex) {
      response =
          GetAutomationDrainStatusResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.authorizationError(ex))
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.getAutomationPinConvergence")
  public void getAutomationPinConvergence(
      GetAutomationPinConvergenceRequest request,
      StreamObserver<GetAutomationPinConvergenceResponse> responseObserver) {
    GetAutomationPinConvergenceResponse response =
        GetAutomationPinConvergenceResponse.getDefaultInstance();
    try {
      requireAdminRole();
      response = patchControlPlaneService.getAutomationPinConvergence(request);
    } catch (IllegalArgumentException ex) {
      response =
          GetAutomationPinConvergenceResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.invalidArgument(ex.getMessage()))
              .build();
    } catch (AdminAuthorizationException ex) {
      response =
          GetAutomationPinConvergenceResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.authorizationError(ex))
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.getScriptPatchInstanceRolloutStatus")
  public void getScriptPatchInstanceRolloutStatus(
      GetScriptPatchInstanceRolloutStatusRequest request,
      StreamObserver<GetScriptPatchInstanceRolloutStatusResponse> responseObserver) {
    GetScriptPatchInstanceRolloutStatusResponse response =
        GetScriptPatchInstanceRolloutStatusResponse.getDefaultInstance();
    try {
      requireAdminRole();
      response = patchControlPlaneService.getScriptPatchInstanceRolloutStatus(request);
    } catch (IllegalArgumentException ex) {
      response =
          GetScriptPatchInstanceRolloutStatusResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.invalidArgument(ex.getMessage()))
              .build();
    } catch (AdminAuthorizationException ex) {
      response =
          GetScriptPatchInstanceRolloutStatusResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.authorizationError(ex))
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.listScriptScheduleInstances")
  public void listScriptScheduleInstances(
      ListScriptScheduleInstancesRequest request,
      StreamObserver<ListScriptScheduleInstancesResponse> responseObserver) {
    ListScriptScheduleInstancesResponse response =
        ListScriptScheduleInstancesResponse.getDefaultInstance();
    try {
      requireAdminRole();
      response = patchControlPlaneService.listScriptScheduleInstances(request);
    } catch (IllegalArgumentException ex) {
      response =
          ListScriptScheduleInstancesResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.invalidArgument(ex.getMessage()))
              .build();
    } catch (AdminAuthorizationException ex) {
      response =
          ListScriptScheduleInstancesResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.authorizationError(ex))
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.listScriptTimerAuditEvents")
  public void listScriptTimerAuditEvents(
      ListScriptTimerAuditEventsRequest request,
      StreamObserver<ListScriptTimerAuditEventsResponse> responseObserver) {
    ListScriptTimerAuditEventsResponse response =
        ListScriptTimerAuditEventsResponse.getDefaultInstance();
    try {
      requireAdminRole();
      response = patchControlPlaneService.listScriptTimerAuditEvents(request);
    } catch (IllegalArgumentException ex) {
      response =
          ListScriptTimerAuditEventsResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.invalidArgument(ex.getMessage()))
              .build();
    } catch (AdminAuthorizationException ex) {
      response =
          ListScriptTimerAuditEventsResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.authorizationError(ex))
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.listScriptPatchInstanceRollouts")
  public void listScriptPatchInstanceRollouts(
      ListScriptPatchInstanceRolloutsRequest request,
      StreamObserver<ListScriptPatchInstanceRolloutsResponse> responseObserver) {
    ListScriptPatchInstanceRolloutsResponse response =
        ListScriptPatchInstanceRolloutsResponse.getDefaultInstance();
    try {
      requireAdminRole();
      response = patchControlPlaneService.listScriptPatchInstanceRollouts(request);
    } catch (IllegalArgumentException ex) {
      response =
          ListScriptPatchInstanceRolloutsResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.invalidArgument(ex.getMessage()))
              .build();
    } catch (AdminAuthorizationException ex) {
      response =
          ListScriptPatchInstanceRolloutsResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.authorizationError(ex))
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.listScriptPatchInstanceRolloutEvents")
  public void listScriptPatchInstanceRolloutEvents(
      ListScriptPatchInstanceRolloutEventsRequest request,
      StreamObserver<ListScriptPatchInstanceRolloutEventsResponse> responseObserver) {
    ListScriptPatchInstanceRolloutEventsResponse response =
        ListScriptPatchInstanceRolloutEventsResponse.getDefaultInstance();
    try {
      requireAdminRole();
      response = patchControlPlaneService.listScriptPatchInstanceRolloutEvents(request);
    } catch (IllegalArgumentException ex) {
      response =
          ListScriptPatchInstanceRolloutEventsResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.invalidArgument(ex.getMessage()))
              .build();
    } catch (AdminAuthorizationException ex) {
      response =
          ListScriptPatchInstanceRolloutEventsResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.authorizationError(ex))
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.listScriptHandoffEvents")
  public void listScriptHandoffEvents(
      ListScriptHandoffEventsRequest request,
      StreamObserver<ListScriptHandoffEventsResponse> responseObserver) {
    ListScriptHandoffEventsResponse response = ListScriptHandoffEventsResponse.getDefaultInstance();
    try {
      requireAdminRole();
      response = patchControlPlaneService.listScriptHandoffEvents(request);
    } catch (IllegalArgumentException ex) {
      response =
          ListScriptHandoffEventsResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.invalidArgument(ex.getMessage()))
              .build();
    } catch (AdminAuthorizationException ex) {
      response =
          ListScriptHandoffEventsResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.authorizationError(ex))
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.listScriptDeadLetters")
  public void listScriptDeadLetters(
      ListScriptDeadLettersRequest request,
      StreamObserver<ListScriptDeadLettersResponse> responseObserver) {
    ListScriptDeadLettersResponse response = ListScriptDeadLettersResponse.getDefaultInstance();
    try {
      requireAdminRole();
      response = patchControlPlaneService.listScriptDeadLetters(request);
    } catch (IllegalArgumentException ex) {
      response =
          ListScriptDeadLettersResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.invalidArgument(ex.getMessage()))
              .build();
    } catch (AdminAuthorizationException ex) {
      response =
          ListScriptDeadLettersResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.authorizationError(ex))
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.replayDeadLetteredWorkItems")
  public void replayDeadLetteredWorkItems(
      ReplayDeadLetteredWorkItemsRequest request,
      StreamObserver<ReplayDeadLetteredWorkItemsResponse> responseObserver) {
    ReplayDeadLetteredWorkItemsResponse response =
        ReplayDeadLetteredWorkItemsResponse.getDefaultInstance();
    try {
      requireAdminRole();
      response = patchControlPlaneService.replayDeadLetteredWorkItems(request);
    } catch (IllegalArgumentException ex) {
      response =
          ReplayDeadLetteredWorkItemsResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.invalidArgument(ex.getMessage()))
              .build();
    } catch (AdminAuthorizationException ex) {
      response =
          ReplayDeadLetteredWorkItemsResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.authorizationError(ex))
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.cancelPendingWorkItemsForPatch")
  public void cancelPendingWorkItemsForPatch(
      CancelPendingWorkItemsForPatchRequest request,
      StreamObserver<CancelPendingWorkItemsForPatchResponse> responseObserver) {
    CancelPendingWorkItemsForPatchResponse response =
        CancelPendingWorkItemsForPatchResponse.getDefaultInstance();
    try {
      requireAdminRole();
      response = patchControlPlaneService.cancelPendingWorkItemsForPatch(request);
    } catch (IllegalArgumentException ex) {
      response =
          CancelPendingWorkItemsForPatchResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.invalidArgument(ex.getMessage()))
              .build();
    } catch (AdminAuthorizationException ex) {
      response =
          CancelPendingWorkItemsForPatchResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.authorizationError(ex))
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.cancelPendingWorkItemsForPluginVersion")
  public void cancelPendingWorkItemsForPluginVersion(
      CancelPendingWorkItemsForPluginVersionRequest request,
      StreamObserver<CancelPendingWorkItemsForPluginVersionResponse> responseObserver) {
    CancelPendingWorkItemsForPluginVersionResponse response =
        CancelPendingWorkItemsForPluginVersionResponse.getDefaultInstance();
    try {
      requireAdminRole();
      response = patchControlPlaneService.cancelPendingWorkItemsForPluginVersion(request);
    } catch (IllegalArgumentException ex) {
      response =
          CancelPendingWorkItemsForPluginVersionResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.invalidArgument(ex.getMessage()))
              .build();
    } catch (AdminAuthorizationException ex) {
      response =
          CancelPendingWorkItemsForPluginVersionResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.authorizationError(ex))
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.getPluginStatus")
  public void getPluginStatus(
      GetPluginStatusRequest request, StreamObserver<GetPluginStatusResponse> responseObserver) {
    GetPluginStatusResponse response = GetPluginStatusResponse.getDefaultInstance();
    try {
      requireInternalServiceOrAdmin();
      response = pluginControlPlaneService.getPluginStatus(request);
    } catch (IllegalArgumentException ex) {
      response =
          GetPluginStatusResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.invalidArgument(ex.getMessage()))
              .build();
    } catch (AdminAuthorizationException ex) {
      response =
          GetPluginStatusResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.authorizationError(ex))
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.listPluginRuntimeEvents")
  public void listPluginRuntimeEvents(
      ListPluginRuntimeEventsRequest request,
      StreamObserver<ListPluginRuntimeEventsResponse> responseObserver) {
    ListPluginRuntimeEventsResponse response = ListPluginRuntimeEventsResponse.getDefaultInstance();
    try {
      requireAdminRole();
      response = pluginControlPlaneService.listPluginRuntimeEvents(request);
    } catch (IllegalArgumentException ex) {
      response =
          ListPluginRuntimeEventsResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.invalidArgument(ex.getMessage()))
              .build();
    } catch (AdminAuthorizationException ex) {
      response =
          ListPluginRuntimeEventsResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.authorizationError(ex))
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.getPluginPolicyConvergence")
  public void getPluginPolicyConvergence(
      GetPluginPolicyConvergenceRequest request,
      StreamObserver<GetPluginPolicyConvergenceResponse> responseObserver) {
    GetPluginPolicyConvergenceResponse response =
        GetPluginPolicyConvergenceResponse.getDefaultInstance();
    try {
      requireAdminRole();
      response = pluginControlPlaneService.getPluginPolicyConvergence(request);
    } catch (IllegalArgumentException ex) {
      response =
          GetPluginPolicyConvergenceResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.invalidArgument(ex.getMessage()))
              .build();
    } catch (AdminAuthorizationException ex) {
      response =
          GetPluginPolicyConvergenceResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.authorizationError(ex))
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.setPluginActiveVersion")
  public void setPluginActiveVersion(
      SetPluginActiveVersionRequest request,
      StreamObserver<SetPluginActiveVersionResponse> responseObserver) {
    SetPluginActiveVersionResponse response = SetPluginActiveVersionResponse.getDefaultInstance();
    try {
      requireAdminRole();
      response = pluginControlPlaneService.setPluginActiveVersion(request);
    } catch (IllegalArgumentException ex) {
      response =
          SetPluginActiveVersionResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.invalidArgument(ex.getMessage()))
              .build();
    } catch (AdminAuthorizationException ex) {
      response =
          SetPluginActiveVersionResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.authorizationError(ex))
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.disablePlugin")
  public void disablePlugin(
      DisablePluginRequest request, StreamObserver<DisablePluginResponse> responseObserver) {
    DisablePluginResponse response = DisablePluginResponse.getDefaultInstance();
    try {
      requireAdminRole();
      response = pluginControlPlaneService.disablePlugin(request);
    } catch (IllegalArgumentException ex) {
      response =
          DisablePluginResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.invalidArgument(ex.getMessage()))
              .build();
    } catch (AdminAuthorizationException ex) {
      response =
          DisablePluginResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.authorizationError(ex))
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "automationGrpc.controlPlane.drainPlugin")
  public void drainPlugin(
      DrainPluginRequest request, StreamObserver<DrainPluginResponse> responseObserver) {
    DrainPluginResponse response = DrainPluginResponse.getDefaultInstance();
    try {
      requireAdminRole();
      response = pluginControlPlaneService.drainPlugin(request);
    } catch (IllegalArgumentException ex) {
      response =
          DrainPluginResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.invalidArgument(ex.getMessage()))
              .build();
    } catch (AdminAuthorizationException ex) {
      response =
          DrainPluginResponse.newBuilder()
              .setError(AutomationControlPlaneSupport.authorizationError(ex))
              .build();
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  private static void requireAdminRole() {
    AdminRoleGuard.requireAdminRole();
  }

  private static void requireInternalServiceOrAdmin() {
    if (SessionContext.isInternalService() || SessionContext.hasGlobalPrivilegedRole()) {
      return;
    }
    throw new AdminAuthorizationException("Internal service or admin role required");
  }
}
