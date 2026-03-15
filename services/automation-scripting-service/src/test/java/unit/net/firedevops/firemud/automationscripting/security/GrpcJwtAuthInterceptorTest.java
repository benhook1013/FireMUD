package net.firedevops.firemud.automationscripting.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.Status;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GrpcJwtAuthInterceptorTest {
  private JwtUtil jwtUtil;
  private GrpcJwtAuthInterceptor interceptor;

  @BeforeEach
  void setUp() {
    jwtUtil = new JwtUtil("testsecretkeytestsecretkeytest1234", 3600000L);
    interceptor = new GrpcJwtAuthInterceptor(jwtUtil);
  }

  @Test
  void rejectsMissingToken() {
    TestServerCall call = new TestServerCall();
    Metadata headers = new Metadata();
    interceptor.interceptCall(call, headers, (c, h) -> new ServerCall.Listener<>() {});
    assertEquals(Status.UNAUTHENTICATED.getCode(), call.status.getCode());
  }

  @Test
  void allowsValidToken() {
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));
    TestServerCall call = new TestServerCall();
    Metadata headers = new Metadata();
    headers.put(
        Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
    ServerCall.Listener<?> listener =
        interceptor.interceptCall(call, headers, (c, h) -> new ServerCall.Listener<>() {});
    assertNotNull(listener);
    assertEquals(null, call.status); // call not closed
  }

  private static class TestServerCall extends ServerCall<Object, Object> {
    Status status;

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
      return null;
    }
  }
}
