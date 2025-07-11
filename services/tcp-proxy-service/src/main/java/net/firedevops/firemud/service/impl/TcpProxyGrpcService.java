package net.firedevops.firemud.service.impl;

import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.service.PingService;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectResponse;
import net.firedevops.firemud.tcpproxy.v1.PingRequest;
import net.firedevops.firemud.tcpproxy.v1.PingResponse;
import net.firedevops.firemud.tcpproxy.v1.PushBufferedInputRequest;
import net.firedevops.firemud.tcpproxy.v1.PushBufferedInputResponse;
import net.firedevops.firemud.tcpproxy.v1.TcpProxyServiceGrpc;
import org.lognet.springboot.grpc.GRpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** gRPC endpoints for the TCP Proxy Service. */
@GRpcService
public class TcpProxyGrpcService extends TcpProxyServiceGrpc.TcpProxyServiceImplBase {
  private static final Logger logger = LoggerFactory.getLogger(TcpProxyGrpcService.class);
  private final PingService pingService;

  public TcpProxyGrpcService(PingService pingService) {
    this.pingService = pingService;
  }

  @Override
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    String msg = pingService.ping();
    PingResponse response = PingResponse.newBuilder().setMessage(msg).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void notifyDisconnect(
      NotifyDisconnectRequest request, StreamObserver<NotifyDisconnectResponse> responseObserver) {
    logger.info(
        "NotifyDisconnect for session {} tenant {}", request.getSessionId(), request.getTenantId());
    NotifyDisconnectResponse response = NotifyDisconnectResponse.newBuilder().build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void pushBufferedInput(
      PushBufferedInputRequest request,
      StreamObserver<PushBufferedInputResponse> responseObserver) {
    logger.info(
        "PushBufferedInput for session {} commands {}",
        request.getSessionId(),
        request.getCommandsCount());
    PushBufferedInputResponse response = PushBufferedInputResponse.newBuilder().build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }
}
