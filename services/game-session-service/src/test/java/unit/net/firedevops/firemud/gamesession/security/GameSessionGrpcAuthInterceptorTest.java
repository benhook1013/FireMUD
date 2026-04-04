package net.firedevops.firemud.gamesession.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.Status;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GameSessionServiceGrpc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameSessionGrpcAuthInterceptorTest {
  private JwtUtil jwtUtil;
  private AuthTokenInterceptor interceptor;

  @BeforeEach
  void setUp() {
    jwtUtil = new JwtUtil("testsecretkeytestsecretkeytest1234", 3600000L);
    interceptor =
        new AuthTokenInterceptor(
            jwtUtil, Set.of(GameSessionServiceGrpc.getPingMethod().getFullMethodName()));
  }

  @Test
  void rejectsMissingTokenForMutableGameSessionMethod() {
    TestServerCall call =
        new TestServerCall(GameSessionServiceGrpc.getStartSessionMethod().getFullMethodName());
    Metadata headers = new Metadata();

    interceptor.interceptCall(call, headers, (c, h) -> new ServerCall.Listener<>() {});

    assertEquals(Status.UNAUTHENTICATED.getCode(), call.status.getCode());
  }

  @Test
  void allowsPingWithoutToken() {
    TestServerCall call =
        new TestServerCall(GameSessionServiceGrpc.getPingMethod().getFullMethodName());
    Metadata headers = new Metadata();

    ServerCall.Listener<?> listener =
        interceptor.interceptCall(call, headers, (c, h) -> new ServerCall.Listener<>() {});

    assertNotNull(listener);
    assertEquals(null, call.status);
  }

  @Test
  void rejectsMissingTokenForControlPlaneMutation() {
    TestServerCall call =
        new TestServerCall(
            GameSessionControlPlaneServiceGrpc.getSetPinnedScriptPatchVersionMethod()
                .getFullMethodName());
    Metadata headers = new Metadata();

    interceptor.interceptCall(call, headers, (c, h) -> new ServerCall.Listener<>() {});

    assertEquals(Status.UNAUTHENTICATED.getCode(), call.status.getCode());
  }

  @Test
  void allowsValidTokenForMutableMethods() {
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("player")));
    TestServerCall call =
        new TestServerCall(GameSessionServiceGrpc.getStartSessionMethod().getFullMethodName());
    Metadata headers = new Metadata();
    headers.put(
        Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);

    ServerCall.Listener<?> listener =
        interceptor.interceptCall(call, headers, (c, h) -> new ServerCall.Listener<>() {});

    assertNotNull(listener);
    assertEquals(null, call.status);
  }

  private static final class TestServerCall extends ServerCall<Object, Object> {
    private static final MethodDescriptor.Marshaller<Object> NOOP_MARSHALLER =
        new MethodDescriptor.Marshaller<>() {
          @Override
          public java.io.InputStream stream(Object value) {
            return new java.io.ByteArrayInputStream(new byte[0]);
          }

          @Override
          public Object parse(java.io.InputStream stream) {
            return new Object();
          }
        };

    private final String fullMethodName;
    private Status status;

    private TestServerCall(String fullMethodName) {
      this.fullMethodName = fullMethodName;
    }

    @Override
    public void request(int numMessages) {}

    @Override
    public void sendHeaders(Metadata headers) {}

    @Override
    public void sendMessage(Object message) {}

    @Override
    public void close(Status status, Metadata trailers) {
      this.status = status;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public MethodDescriptor<Object, Object> getMethodDescriptor() {
      return MethodDescriptor.<Object, Object>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName(fullMethodName)
          .setRequestMarshaller(NOOP_MARSHALLER)
          .setResponseMarshaller(NOOP_MARSHALLER)
          .build();
    }
  }
}
