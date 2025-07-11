package net.firedevops.firemud.security;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
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
      List<String> globalRoles = claims.getBody().get("globalRoles", List.class);
      Map<String, List<String>> scopedRoles = claims.getBody().get("scopedRoles", Map.class);
      boolean hasGlobal =
          globalRoles != null && (globalRoles.contains("platformAdmin") || globalRoles.contains("moderator"));
      boolean hasScoped = false;
      if (scopedRoles != null) {
        for (List<String> roles : scopedRoles.values()) {
          if (roles.contains("admin") || roles.contains("moderator")) {
            hasScoped = true;
            break;
          }
        }
      }
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
