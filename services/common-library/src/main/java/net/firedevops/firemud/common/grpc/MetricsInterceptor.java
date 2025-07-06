package net.firedevops.firemud.common.grpc;

import io.grpc.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** Records gRPC call metrics using Micrometer. */
public class MetricsInterceptor implements ServerInterceptor {
  private final MeterRegistry registry;

  public MetricsInterceptor(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    String method = call.getMethodDescriptor().getFullMethodName();
    Timer.Sample sample = Timer.start(registry);
    return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(
        next.startCall(call, headers)) {
      @Override
      public void onComplete() {
        sample.stop(registry.timer("grpc.server.requests", "method", method, "status", "OK"));
        super.onComplete();
      }

      @Override
      public void onCancel() {
        sample.stop(
            registry.timer("grpc.server.requests", "method", method, "status", "CANCELLED"));
        super.onCancel();
      }
    };
  }
}
