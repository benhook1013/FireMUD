package net.firedevops.firemud.common.grpc;

import io.grpc.*;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.Objects;

/** gRPC interceptor that creates an OpenTelemetry span for each call. */
public class TracingInterceptor implements ServerInterceptor {
  private final Tracer tracer;

  public TracingInterceptor(Tracer tracer) {
    this.tracer = Objects.requireNonNull(tracer);
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    String method = Objects.requireNonNull(call.getMethodDescriptor().getFullMethodName());
    Span span = tracer.spanBuilder(method).startSpan();
    Scope scope = span.makeCurrent();
    return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(
        next.startCall(call, headers)) {
      @Override
      public void onComplete() {
        span.setStatus(StatusCode.OK);
        span.end();
        scope.close();
        super.onComplete();
      }

      @Override
      public void onCancel() {
        span.setStatus(StatusCode.ERROR, "cancelled");
        span.end();
        scope.close();
        super.onCancel();
      }
    };
  }
}
