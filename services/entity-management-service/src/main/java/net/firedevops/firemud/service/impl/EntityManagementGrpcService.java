package net.firedevops.firemud.service.impl;

import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.PingRequest;
import net.firedevops.firemud.entitymanagement.v1.PingResponse;
import net.firedevops.firemud.service.PingService;
import org.lognet.springboot.grpc.GRpcService;

/** Simple gRPC service exposing the Ping RPC. */
@GRpcService
public class EntityManagementGrpcService
    extends EntityManagementServiceGrpc.EntityManagementServiceImplBase {
  private final PingService pingService;

  public EntityManagementGrpcService(PingService pingService) {
    this.pingService = pingService;
  }

  @Override
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    String msg = pingService.ping();
    PingResponse response = PingResponse.newBuilder().setMessage(msg).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }
}
