package net.firedevops.firemud.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import net.firedevops.firemud.automationscripting.v1.AddFormationMemberRequest;
import net.firedevops.firemud.automationscripting.v1.AddFormationMemberResponse;
import net.firedevops.firemud.automationscripting.v1.AutomationScriptingServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.CreateFormationRequest;
import net.firedevops.firemud.automationscripting.v1.CreateFormationResponse;
import net.firedevops.firemud.automationscripting.v1.ListFormationMembersRequest;
import net.firedevops.firemud.automationscripting.v1.ListFormationMembersResponse;
import net.firedevops.firemud.model.FormationType;
import net.firedevops.firemud.service.NpcFormationService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.lognet.springboot.grpc.GRpcService;

@GRpcService
public class NpcFormationGrpcService
    extends AutomationScriptingServiceGrpc.AutomationScriptingServiceImplBase {
  private final NpcFormationService formationService;

  public NpcFormationGrpcService(NpcFormationService formationService) {
    this.formationService = formationService;
  }

  @Override
  public void createFormation(
      CreateFormationRequest request, StreamObserver<CreateFormationResponse> responseObserver) {
    try {
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
                  ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription(ex.getMessage()).asRuntimeException());
    }
  }

  @Override
  public void addFormationMember(
      AddFormationMemberRequest request,
      StreamObserver<AddFormationMemberResponse> responseObserver) {
    try {
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
                  ErrorDetail.newBuilder()
                      .setCode("INVALID_ARGUMENT")
                      .setMessage(ex.getMessage())
                      .build())
              .build();
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription(ex.getMessage()).asRuntimeException());
    }
  }

  @Override
  public void listFormationMembers(
      ListFormationMembersRequest request,
      StreamObserver<ListFormationMembersResponse> responseObserver) {
    try {
      List<Long> members =
          formationService.getMembers(
              Long.parseLong(request.getTenantId()), Long.parseLong(request.getFormationId()));
      ListFormationMembersResponse resp =
          ListFormationMembersResponse.newBuilder()
              .addAllNpcIds(members.stream().map(Object::toString).toList())
              .build();
      responseObserver.onNext(resp);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onError(
          Status.INTERNAL.withDescription(ex.getMessage()).asRuntimeException());
    }
  }
}
