package net.firedevops.firemud.common.security;

import io.grpc.Contexts;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.jsonwebtoken.JwtException;
import java.util.HashSet;
import java.util.Set;

/** Intercepts gRPC calls to extract and validate JWT tokens. */
public class AuthTokenInterceptor implements ServerInterceptor {
  private static final Metadata.Key<String> AUTH_HEADER =
      Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

  private final JwtUtil jwtUtil;
  private final Set<String> unauthenticatedMethods;

  public AuthTokenInterceptor(JwtUtil jwtUtil) {
    this(jwtUtil, Set.of());
  }

  public AuthTokenInterceptor(JwtUtil jwtUtil, Set<String> unauthenticatedMethods) {
    this.jwtUtil = jwtUtil;
    this.unauthenticatedMethods = new HashSet<>(unauthenticatedMethods);
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    if (unauthenticatedMethods.contains(call.getMethodDescriptor().getFullMethodName())) {
      return next.startCall(call, headers);
    }
    String authHeader = headers.get(AUTH_HEADER);
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      call.close(Status.UNAUTHENTICATED.withDescription("Missing token"), new Metadata());
      return new ServerCall.Listener<>() {};
    }
    String token = authHeader.substring(7);
    try {
      SessionClaims sessionClaims = SessionClaims.fromJwt(jwtUtil.parseToken(token));
      ServerCall<ReqT, RespT> clearingCall =
          new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
              try {
                super.close(status, trailers);
              } finally {
                SessionContext.clear();
              }
            }
          };
      ServerCall.Listener<ReqT> listener =
          Contexts.interceptCall(
              SessionContext.grpcContextWith(sessionClaims), clearingCall, headers, next);
      return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
        @Override
        public void onComplete() {
          try {
            super.onComplete();
          } finally {
            SessionContext.clear();
          }
        }

        @Override
        public void onCancel() {
          try {
            super.onCancel();
          } finally {
            SessionContext.clear();
          }
        }
      };
    } catch (JwtException | IllegalArgumentException ex) {
      SessionContext.clear();
      call.close(Status.UNAUTHENTICATED.withDescription("Invalid token"), new Metadata());
      return new ServerCall.Listener<>() {};
    }
  }
}
