package net.firedevops.firemud.automationscripting.security;

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
import net.firedevops.firemud.common.security.JwtUtil;

/** gRPC interceptor validating JWT tokens and roles. */
public class GrpcJwtAuthInterceptor implements ServerInterceptor {
  private static final Metadata.Key<String> AUTH_HEADER =
      Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

  private final JwtUtil jwtUtil;

  public GrpcJwtAuthInterceptor(JwtUtil jwtUtil) {
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
      List<?> rawGlobalRoles = payload.get("globalRoles", List.class);
      List<String> globalRoles =
          rawGlobalRoles == null
              ? List.of()
              : rawGlobalRoles.stream()
                  .filter(String.class::isInstance)
                  .map(String.class::cast)
                  .toList();
      Map<?, ?> rawScopedRoles = payload.get("scopedRoles", Map.class);
      Map<String, List<String>> scopedRoles = new HashMap<>();
      if (rawScopedRoles != null) {
        for (Map.Entry<?, ?> entry : rawScopedRoles.entrySet()) {
          if (entry.getKey() instanceof String key
              && entry.getValue() instanceof List<?> rolesList) {
            List<String> roles =
                rolesList.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
            scopedRoles.put(key, roles);
          }
        }
      }
      boolean hasGlobal =
          globalRoles.contains("platformAdmin") || globalRoles.contains("moderator");
      boolean hasScoped =
          scopedRoles.values().stream()
              .anyMatch(roles -> roles.contains("admin") || roles.contains("moderator"));
      if (!hasGlobal && !hasScoped) {
        call.close(Status.PERMISSION_DENIED.withDescription("Insufficient role"), new Metadata());
        return new ServerCall.Listener<>() {};
      }
    } catch (JwtException ex) {
      call.close(Status.UNAUTHENTICATED.withDescription("Invalid token"), new Metadata());
      return new ServerCall.Listener<>() {};
    }
    return next.startCall(call, headers);
  }
}
