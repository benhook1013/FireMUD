package net.firedevops.firemud.common.security;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.stub.AbstractStub;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;

/** Helper for attaching bearer auth metadata to internal gRPC clients. */
public final class GrpcClientAuth {
  private static final Metadata.Key<String> AUTH_HEADER =
      Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
  private static final String INTERNAL_SUBJECT_PREFIX = "service:";

  private GrpcClientAuth() {}

  public static <T extends AbstractStub<T>> T attach(
      T stub, JwtUtil jwtUtil, RuntimeIdentity runtimeIdentity) {
    return stub.withInterceptors(
        authInterceptor(() -> createBearerToken(jwtUtil, runtimeIdentity)));
  }

  public static <T extends AbstractStub<T>> T attachInternal(
      T stub, JwtUtil jwtUtil, RuntimeIdentity runtimeIdentity) {
    return stub.withInterceptors(
        authInterceptor(() -> createInternalBearerToken(jwtUtil, runtimeIdentity)));
  }

  private static ClientInterceptor authInterceptor(TokenSupplier tokenSupplier) {
    return new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        ClientCall<ReqT, RespT> delegate = next.newCall(method, callOptions);
        return new ForwardingClientCall.SimpleForwardingClientCall<>(delegate) {
          @Override
          public void start(Listener<RespT> responseListener, Metadata headers) {
            Metadata outboundHeaders = new Metadata();
            outboundHeaders.merge(headers);
            outboundHeaders.put(AUTH_HEADER, "Bearer " + tokenSupplier.get());
            super.start(responseListener, outboundHeaders);
          }
        };
      }
    };
  }

  static String createBearerToken(JwtUtil jwtUtil, RuntimeIdentity runtimeIdentity) {
    String accountId = SessionContext.getAccountId();
    List<String> globalRoles = SessionContext.getGlobalRoles();
    Map<String, List<String>> scopedRoles = SessionContext.getScopedRolesMap();
    if (accountId == null || accountId.isBlank()) {
      return createInternalBearerToken(jwtUtil, runtimeIdentity);
    }

    Map<String, Object> claims = new HashMap<>();
    claims.put("accountId", accountId);
    claims.put("globalRoles", globalRoles == null ? List.of() : globalRoles);
    claims.put("scopedRoles", scopedRoles == null ? Map.of() : scopedRoles);
    return jwtUtil.generateToken(accountId, claims);
  }

  static String createInternalBearerToken(JwtUtil jwtUtil, RuntimeIdentity runtimeIdentity) {
    String serviceName =
        runtimeIdentity != null && runtimeIdentity.service() != null
            ? runtimeIdentity.service()
            : "unknown-service";
    String serviceInstanceId = runtimeIdentity != null ? runtimeIdentity.serviceInstanceId() : null;

    Map<String, Object> claims = new HashMap<>();
    claims.put("globalRoles", List.of());
    claims.put("scopedRoles", Map.of());
    claims.put("internalService", true);
    claims.put("serviceName", serviceName);
    if (serviceInstanceId != null && !serviceInstanceId.isBlank()) {
      claims.put("serviceInstanceId", serviceInstanceId);
    }
    return jwtUtil.generateToken(INTERNAL_SUBJECT_PREFIX + serviceName, claims);
  }

  @FunctionalInterface
  private interface TokenSupplier {
    String get();
  }
}
