package unit.net.firedevops.firemud.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Empty;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  void clearsStaleThreadLocalBeforeExemptDispatchAndEveryListenerCallback() {
    AuthTokenInterceptor interceptor =
        new AuthTokenInterceptor(jwtUtil, Set.of(METHOD.getFullMethodName()));
    @SuppressWarnings({"rawtypes", "unchecked"})
    ServerCall<Empty, Empty> call = Mockito.mock(ServerCall.class);
    List<Boolean> callerContextObserved = new ArrayList<>();
    ServerCallHandler<Empty, Empty> next =
        new ServerCallHandler<>() {
          @Override
          public Listener<Empty> startCall(ServerCall<Empty, Empty> serverCall, Metadata headers) {
            callerContextObserved.add(
                AuthTokenInterceptorTest.this.recordCallerContextAndSeedThreadLocal());
            return new Listener<>() {
              @Override
              public void onMessage(Empty message) {
                callerContextObserved.add(
                    AuthTokenInterceptorTest.this.recordCallerContextAndSeedThreadLocal());
              }

              @Override
              public void onHalfClose() {
                callerContextObserved.add(
                    AuthTokenInterceptorTest.this.recordCallerContextAndSeedThreadLocal());
              }

              @Override
              public void onReady() {
                callerContextObserved.add(
                    AuthTokenInterceptorTest.this.recordCallerContextAndSeedThreadLocal());
              }

              @Override
              public void onComplete() {
                callerContextObserved.add(
                    AuthTokenInterceptorTest.this.recordCallerContextAndSeedThreadLocal());
              }

              @Override
              public void onCancel() {
                callerContextObserved.add(
                    AuthTokenInterceptorTest.this.recordCallerContextAndSeedThreadLocal());
              }
            };
          }
        };

    Mockito.when(call.getMethodDescriptor()).thenReturn(METHOD);
    seedStaleCallerContext();

    Listener<Empty> listener = interceptor.interceptCall(call, new Metadata(), next);

    assertThat(callerContextObserved).containsExactly(false);
    assertThat(SessionContext.hasAuthenticatedCallerContext()).isFalse();
    seedStaleCallerContext();
    listener.onMessage(Empty.getDefaultInstance());
    assertThat(SessionContext.hasAuthenticatedCallerContext()).isFalse();
    seedStaleCallerContext();
    listener.onHalfClose();
    assertThat(SessionContext.hasAuthenticatedCallerContext()).isFalse();
    seedStaleCallerContext();
    listener.onReady();
    assertThat(SessionContext.hasAuthenticatedCallerContext()).isFalse();
    seedStaleCallerContext();
    listener.onComplete();
    assertThat(SessionContext.hasAuthenticatedCallerContext()).isFalse();
    seedStaleCallerContext();
    Listener<Empty> cancelListener = interceptor.interceptCall(call, new Metadata(), next);
    assertThat(SessionContext.hasAuthenticatedCallerContext()).isFalse();
    seedStaleCallerContext();
    cancelListener.onCancel();

    assertThat(callerContextObserved)
        .containsExactly(false, false, false, false, false, false, false);
    assertThat(SessionContext.hasAuthenticatedCallerContext()).isFalse();
    assertThat(SessionContext.getAccountId()).isNull();
  }

  @Test
  void preservesAuthenticatedGrpcCallerAndPeerContextForExemptReceiverGuard() {
    AuthTokenInterceptor jwtInterceptor = new AuthTokenInterceptor(jwtUtil);
    AuthTokenInterceptor exemptInterceptor =
        new AuthTokenInterceptor(jwtUtil, Set.of(METHOD.getFullMethodName()));
    @SuppressWarnings({"rawtypes", "unchecked"})
    ServerCall<Empty, Empty> call = Mockito.mock(ServerCall.class);
    // A context sentinel verifies ThreadLocal cleanup does not replace the enclosing gRPC context.
    Context.Key<String> peerContextKey = Context.key("test-peer-context");
    String peerContextValue = "account-peer-context-preserved";
    AtomicReference<String> accountObservedAtStart = new AtomicReference<>();
    AtomicReference<String> peerObservedAtStart = new AtomicReference<>();
    AtomicBoolean callerContextObservedAtStart = new AtomicBoolean(false);
    AtomicReference<String> accountObservedInCallback = new AtomicReference<>();
    AtomicReference<String> peerObservedInCallback = new AtomicReference<>();
    AtomicBoolean callerContextObservedInCallback = new AtomicBoolean(false);
    ServerCallHandler<Empty, Empty> receiver =
        new ServerCallHandler<>() {
          @Override
          public Listener<Empty> startCall(ServerCall<Empty, Empty> serverCall, Metadata headers) {
            accountObservedAtStart.set(SessionContext.getAccountId());
            peerObservedAtStart.set(peerContextKey.get());
            callerContextObservedAtStart.set(SessionContext.hasAuthenticatedCallerContext());
            return new Listener<>() {
              @Override
              public void onHalfClose() {
                accountObservedInCallback.set(SessionContext.getAccountId());
                peerObservedInCallback.set(peerContextKey.get());
                callerContextObservedInCallback.set(SessionContext.hasAuthenticatedCallerContext());
              }
            };
          }
        };
    Mockito.when(call.getMethodDescriptor()).thenReturn(METHOD);

    Metadata headers = new Metadata();
    String token =
        jwtUtil.generateToken(
            "trusted-account",
            Map.of(
                "accountId", "42",
                "globalRoles", List.of(),
                "scopedRoles", Map.of()));
    headers.put(AUTH_HEADER, "Bearer " + token);
    seedStaleCallerContext();
    Context peerContext = Context.current().withValue(peerContextKey, peerContextValue);
    Listener<Empty> listener =
        Contexts.interceptCall(
            peerContext,
            call,
            headers,
            (peerCall, peerHeaders) ->
                jwtInterceptor.interceptCall(
                    peerCall,
                    peerHeaders,
                    (authenticatedCall, authenticatedHeaders) ->
                        exemptInterceptor.interceptCall(
                            authenticatedCall, authenticatedHeaders, receiver)));

    assertThat(accountObservedAtStart).hasValue("42");
    assertThat(callerContextObservedAtStart).isTrue();
    assertThat(peerObservedAtStart).hasValue(peerContextValue);
    listener.onHalfClose();

    // This is the same caller-context predicate used by passive receivers, so the legitimate JWT
    // principal remains visible and would still be denied there despite the method exemption.
    assertThat(accountObservedInCallback).hasValue("42");
    assertThat(callerContextObservedInCallback).isTrue();
    assertThat(peerObservedInCallback).hasValue(peerContextValue);
    assertThat(SessionContext.hasAuthenticatedCallerContext()).isFalse();
  }

  @Test
  void clearsSessionContextWhenExemptCallClosesOrStartCallFails() {
    AuthTokenInterceptor interceptor =
        new AuthTokenInterceptor(jwtUtil, Set.of(METHOD.getFullMethodName()));
    @SuppressWarnings({"rawtypes", "unchecked"})
    ServerCall<Empty, Empty> call = Mockito.mock(ServerCall.class);
    AtomicReference<ServerCall<Empty, Empty>> forwardedCall = new AtomicReference<>();
    AtomicBoolean callerContextObservedDuringClose = new AtomicBoolean(true);
    ServerCallHandler<Empty, Empty> next =
        new ServerCallHandler<>() {
          @Override
          public Listener<Empty> startCall(ServerCall<Empty, Empty> serverCall, Metadata headers) {
            forwardedCall.set(serverCall);
            return new Listener<>() {};
          }
        };
    Mockito.when(call.getMethodDescriptor()).thenReturn(METHOD);
    Mockito.doAnswer(
            invocation -> {
              callerContextObservedDuringClose.set(SessionContext.hasAuthenticatedCallerContext());
              seedStaleCallerContext();
              return null;
            })
        .when(call)
        .close(Mockito.any(Status.class), Mockito.any(Metadata.class));

    interceptor.interceptCall(call, new Metadata(), next);
    seedStaleCallerContext();
    forwardedCall.get().close(Status.OK, new Metadata());

    assertThat(callerContextObservedDuringClose).isFalse();
    assertThat(SessionContext.hasAuthenticatedCallerContext()).isFalse();

    seedStaleCallerContext();
    assertThatThrownBy(
            () ->
                interceptor.interceptCall(
                    call,
                    new Metadata(),
                    (serverCall, headers) -> {
                      assertThat(SessionContext.hasAuthenticatedCallerContext()).isFalse();
                      seedStaleCallerContext();
                      throw new IllegalStateException("failed startCall");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("failed startCall");
    assertThat(SessionContext.hasAuthenticatedCallerContext()).isFalse();
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

    seedThreadLocalInternalServiceState();
    assertThat(SessionContext.isInternalService()).isTrue();
    listener.onComplete();
    assertThat(internalServiceVisibleDuringCompletion).isTrue();
    assertThat(SessionContext.isInternalService()).isFalse();
    assertThat(SessionContext.getServiceName()).isNull();

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

    seedThreadLocalInternalServiceState();
    assertThat(SessionContext.isInternalService()).isTrue();
    listener.onCancel();
    assertThat(internalServiceVisibleDuringCancel).isTrue();
    assertThat(SessionContext.isInternalService()).isFalse();
    assertThat(SessionContext.getServiceName()).isNull();
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

    seedThreadLocalInternalServiceState();
    assertThat(SessionContext.isInternalService()).isTrue();
    listener.onHalfClose();
    assertThat(internalServiceVisibleDuringHalfClose).isTrue();
    assertThat(SessionContext.isInternalService()).isFalse();
    assertThat(SessionContext.getServiceName()).isNull();
    Mockito.verify(call)
        .close(
            Mockito.argThat(status -> status.getCode() == Status.Code.INTERNAL),
            Mockito.any(Metadata.class));
  }

  private void seedThreadLocalInternalServiceState() {
    SessionContext.setContext(
        "", java.util.List.of(), Map.of(), true, "seeded-service", "seeded-instance");
  }

  private void seedStaleCallerContext() {
    SessionContext.setContext("stale-account", List.of("platformAdmin"), Map.of());
  }

  private boolean recordCallerContextAndSeedThreadLocal() {
    boolean callerContextPresent = SessionContext.hasAuthenticatedCallerContext();
    seedStaleCallerContext();
    return callerContextPresent;
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
