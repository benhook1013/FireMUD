package unit.net.firedevops.firemud.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Empty;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.util.Map;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AuthTokenInterceptorTest {
  private static final Metadata.Key<String> AUTH_HEADER =
      Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
  private static final MethodDescriptor<Empty, Empty> METHOD =
      MethodDescriptor.<Empty, Empty>newBuilder()
          .setFullMethodName("demo.Service/Ping")
          .setType(MethodDescriptor.MethodType.UNARY)
          .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(Empty.getDefaultInstance()))
          .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(Empty.getDefaultInstance()))
          .build();

  private final JwtUtil jwtUtil = new JwtUtil("testsecretkeytestsecretkeytest1234", 3600000L);

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void rejectsMalformedJwtClaims() {
    AuthTokenInterceptor interceptor = new AuthTokenInterceptor(jwtUtil);
    @SuppressWarnings({"rawtypes", "unchecked"})
    ServerCall call = Mockito.mock(ServerCall.class);
    @SuppressWarnings({"rawtypes", "unchecked"})
    ServerCallHandler next = Mockito.mock(ServerCallHandler.class);

    Mockito.when(call.getMethodDescriptor()).thenReturn(METHOD);

    Metadata headers = new Metadata();
    String token =
        jwtUtil.generateToken(
            "account",
            Map.of(
                "accountId", "account", "globalRoles", "platformAdmin", "scopedRoles", Map.of()));
    headers.put(AUTH_HEADER, "Bearer " + token);

    interceptor.interceptCall(call, headers, next);

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    Mockito.verify(call).close(statusCaptor.capture(), Mockito.any(Metadata.class));
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    Mockito.verify(next, Mockito.never()).startCall(Mockito.eq(call), Mockito.any(Metadata.class));
    assertThat(SessionContext.getAccountId()).isNull();
    assertThat(SessionContext.getGlobalRoles()).isEmpty();
    assertThat(SessionContext.getScopedRolesMap()).isEmpty();
  }
}
