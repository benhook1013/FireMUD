package net.firedevops.firemud.springcloudgateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.util.Map;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gateway.v1.GatewayManagementServiceGrpc;
import org.junit.jupiter.api.Test;

class GrpcConfigTest {

  @Test
  void pingMethodIsWhitelisted() {
    AuthTokenInterceptor interceptor =
        new GrpcConfig()
            .authTokenInterceptor(new JwtUtil("testsecretkeytestsecretkeytest1234", 3600000L));
    @SuppressWarnings({"rawtypes", "unchecked"})
    ServerCall call = mock(ServerCall.class);
    @SuppressWarnings({"rawtypes", "unchecked"})
    ServerCallHandler next = mock(ServerCallHandler.class);
    when(call.getMethodDescriptor())
        .thenReturn((io.grpc.MethodDescriptor) GatewayManagementServiceGrpc.getPingMethod());
    when(next.startCall(eq(call), any(Metadata.class))).thenReturn(new ServerCall.Listener<>() {});

    interceptor.interceptCall(call, new Metadata(), next);

    verify(next).startCall(eq(call), any(Metadata.class));
  }

  @Test
  void protectedRouteRequiresBearerToken() {
    AuthTokenInterceptor interceptor =
        new GrpcConfig()
            .authTokenInterceptor(new JwtUtil("testsecretkeytestsecretkeytest1234", 3600000L));
    @SuppressWarnings({"rawtypes", "unchecked"})
    ServerCall call = mock(ServerCall.class);
    @SuppressWarnings({"rawtypes", "unchecked"})
    ServerCallHandler next = mock(ServerCallHandler.class);
    when(call.getMethodDescriptor())
        .thenReturn((io.grpc.MethodDescriptor) GatewayManagementServiceGrpc.getUpsertRouteMethod());

    interceptor.interceptCall(call, new Metadata(), next);

    org.mockito.ArgumentCaptor<Status> statusCaptor =
        org.mockito.ArgumentCaptor.forClass(Status.class);
    verify(call).close(statusCaptor.capture(), any(Metadata.class));
    assertEquals(Status.UNAUTHENTICATED.getCode(), statusCaptor.getValue().getCode());
  }

  @Test
  void jwtUtilStillGeneratesValidTokensForRoleClaims() {
    JwtUtil jwtUtil = new JwtUtil("testsecretkeytestsecretkeytest1234", 3600000L);
    String token =
        jwtUtil.generateToken("user", Map.of("globalRoles", java.util.List.of("platformAdmin")));
    Jws<Claims> claims = jwtUtil.parseToken(token);
    assertTrue(claims.getPayload().containsKey("globalRoles"));
    assertFalse(claims.getPayload().containsKey("missing"));
    assertEquals("user", claims.getPayload().getSubject());
  }
}
