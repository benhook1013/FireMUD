package unit.net.firedevops.firemud.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Empty;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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

  @Test
  void appliesSessionContextDuringCallbacksAndClearsAfterCompletion() {
    AuthTokenInterceptor interceptor = new AuthTokenInterceptor(jwtUtil);
    @SuppressWarnings({"rawtypes", "unchecked"})
    ServerCall<Empty, Empty> call = Mockito.mock(ServerCall.class);
    AtomicReference<ServerCall<Empty, Empty>> forwardedCall = new AtomicReference<>();
    AtomicBoolean internalServiceVisibleDuringCompletion = new AtomicBoolean(false);

    ServerCallHandler<Empty, Empty> next =
        new ServerCallHandler<>() {
          @Override
          public Listener<Empty> startCall(ServerCall<Empty, Empty> serverCall, Metadata headers) {
            forwardedCall.set(serverCall);
            return new Listener<>() {
              @Override
              public void onComplete() {
                internalServiceVisibleDuringCompletion.set(SessionContext.isInternalService());
              }
            };
          }
        };

    Mockito.when(call.getMethodDescriptor()).thenReturn(METHOD);

    Metadata headers = new Metadata();
    String token =
        jwtUtil.generateToken(
            "service:game-logic-service",
            Map.of(
                "accountId",
                "",
                "globalRoles",
                java.util.List.of(),
                "scopedRoles",
                Map.of(),
                "internalService",
                true,
                "serviceName",
                "game-logic-service"));
    headers.put(AUTH_HEADER, "Bearer " + token);

    Listener<Empty> listener = interceptor.interceptCall(call, headers, next);

    assertThat(SessionContext.isInternalService()).isFalse();
    listener.onComplete();
    assertThat(internalServiceVisibleDuringCompletion).isTrue();
    assertThat(SessionContext.isInternalService()).isFalse();

    forwardedCall.get().close(Status.OK, new Metadata());
    assertThat(SessionContext.isInternalService()).isFalse();
  }

  @Test
  void clearsSessionContextOnCancel() {
    AuthTokenInterceptor interceptor = new AuthTokenInterceptor(jwtUtil);
    @SuppressWarnings({"rawtypes", "unchecked"})
    ServerCall<Empty, Empty> call = Mockito.mock(ServerCall.class);
    AtomicBoolean internalServiceVisibleDuringCancel = new AtomicBoolean(false);

    ServerCallHandler<Empty, Empty> next =
        new ServerCallHandler<>() {
          @Override
          public Listener<Empty> startCall(ServerCall<Empty, Empty> serverCall, Metadata headers) {
            return new Listener<>() {
              @Override
              public void onCancel() {
                internalServiceVisibleDuringCancel.set(SessionContext.isInternalService());
              }
            };
          }
        };

    Mockito.when(call.getMethodDescriptor()).thenReturn(METHOD);

    Metadata headers = internalServiceHeaders();

    Listener<Empty> listener = interceptor.interceptCall(call, headers, next);

    assertThat(SessionContext.isInternalService()).isFalse();
    listener.onCancel();
    assertThat(internalServiceVisibleDuringCancel).isTrue();
    assertThat(SessionContext.isInternalService()).isFalse();
  }

  @Test
  void clearsSessionContextAfterErrorClose() {
    AuthTokenInterceptor interceptor = new AuthTokenInterceptor(jwtUtil);
    @SuppressWarnings({"rawtypes", "unchecked"})
    ServerCall<Empty, Empty> call = Mockito.mock(ServerCall.class);
    AtomicReference<ServerCall<Empty, Empty>> forwardedCall = new AtomicReference<>();
    AtomicBoolean internalServiceVisibleDuringHalfClose = new AtomicBoolean(false);

    ServerCallHandler<Empty, Empty> next =
        new ServerCallHandler<>() {
          @Override
          public Listener<Empty> startCall(ServerCall<Empty, Empty> serverCall, Metadata headers) {
            forwardedCall.set(serverCall);
            return new Listener<>() {
              @Override
              public void onHalfClose() {
                internalServiceVisibleDuringHalfClose.set(SessionContext.isInternalService());
                forwardedCall.get().close(Status.INTERNAL, new Metadata());
              }
            };
          }
        };

    Mockito.when(call.getMethodDescriptor()).thenReturn(METHOD);

    Metadata headers = internalServiceHeaders();

    Listener<Empty> listener = interceptor.interceptCall(call, headers, next);

    assertThat(SessionContext.isInternalService()).isFalse();
    listener.onHalfClose();
    assertThat(internalServiceVisibleDuringHalfClose).isTrue();
    assertThat(SessionContext.isInternalService()).isFalse();
    Mockito.verify(call)
        .close(
            Mockito.argThat(status -> status.getCode() == Status.Code.INTERNAL),
            Mockito.any(Metadata.class));
  }

  private Metadata internalServiceHeaders() {
    Metadata headers = new Metadata();
    String token =
        jwtUtil.generateToken(
            "service:game-logic-service",
            Map.of(
                "accountId",
                "",
                "globalRoles",
                java.util.List.of(),
                "scopedRoles",
                Map.of(),
                "internalService",
                true,
                "serviceName",
                "game-logic-service"));
    headers.put(AUTH_HEADER, "Bearer " + token);
    return headers;
  }
}
