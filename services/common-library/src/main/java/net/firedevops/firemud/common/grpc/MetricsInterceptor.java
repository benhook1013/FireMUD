package net.firedevops.firemud.common.grpc;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;

/** Records gRPC call metrics using Micrometer. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "MeterRegistry is injected and safe to store")
public class MetricsInterceptor implements ServerInterceptor {
  private final MeterRegistry registry;

  public MetricsInterceptor(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry);
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
