package net.firedevops.firemud.common.security;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Intercepts gRPC calls to extract and validate JWT tokens. */
public class AuthTokenInterceptor implements ServerInterceptor {
  private static final Metadata.Key<String> AUTH_HEADER =
      Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

  private final JwtUtil jwtUtil;

  public AuthTokenInterceptor(JwtUtil jwtUtil) {
    this.jwtUtil = jwtUtil;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    String authHeader = headers.get(AUTH_HEADER);
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      call.close(Status.UNAUTHENTICATED.withDescription("Missing token"), new Metadata());
      return new ServerCall.Listener<>() {};
    }
    String token = authHeader.substring(7);
    try {
      Jws<Claims> claims = jwtUtil.parseToken(token);
      Claims payload = claims.getPayload();
      String accountId = payload.get("accountId", String.class);
      List<?> globalRaw = payload.get("globalRoles", List.class);
      List<String> globalRoles =
          globalRaw == null ? List.of() : globalRaw.stream().map(String::valueOf).toList();
      Map<?, ?> scopedRaw = payload.get("scopedRoles", Map.class);
      Map<String, List<String>> scopedRoles = new HashMap<>();
      if (scopedRaw != null) {
        for (Map.Entry<?, ?> e : scopedRaw.entrySet()) {
          List<?> rolesRaw = e.getValue() instanceof List<?> list ? list : List.of();
          scopedRoles.put(
              String.valueOf(e.getKey()), rolesRaw.stream().map(String::valueOf).toList());
        }
      }
      SessionContext.setContext(accountId, globalRoles, scopedRoles);
      ServerCall.Listener<ReqT> listener = next.startCall(call, headers);
      return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
        @Override
        public void onComplete() {
          SessionContext.clear();
          super.onComplete();
        }

        @Override
        public void onCancel() {
          SessionContext.clear();
          super.onCancel();
        }
      };
    } catch (JwtException ex) {
      call.close(Status.UNAUTHENTICATED.withDescription("Invalid token"), new Metadata());
      return new ServerCall.Listener<>() {};
    }
  }
}
