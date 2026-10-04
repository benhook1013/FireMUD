package unit.net.firedevops.firemud.gamedesign.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.Message;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.GrpcAuthProperties;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameSessionTenantAssociationRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantIdentity;
import net.firedevops.firemud.gamedesign.service.impl.TenantIdentityGrpcService;
import net.firedevops.firemud.gamedesign.v1.PingRequest;
import net.firedevops.firemud.gamedesign.v1.PingResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveRuntimeTenantIdentityRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveRuntimeTenantIdentityResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

class TenantIdentityGrpcAuthWiringTest {
  private static final String TENANT_METHOD =
      "gamedesign.v1.TenantIdentityService/ResolveRuntimeTenantIdentity";
  private static final String ASSOCIATION_METHOD =
      "gamedesign.v1.TenantIdentityService/ResolveLegacyGameSessionTenantAssociation";
  private static final String PING_METHOD = "gamedesign.v1.GameDesignService/Ping";
  private static final String TENANT_UUID = "87426bb3-a733-43f0-9c8e-2e379cbdf7ec";
  private static final String REQUEST_UUID = "11111111-1111-4111-8111-111111111111";
  private static final String GAME_SESSION_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final MethodDescriptor<
          ResolveRuntimeTenantIdentityRequest, ResolveRuntimeTenantIdentityResponse>
      TENANT_METHOD_DESCRIPTOR =
          unaryMethod(
              TENANT_METHOD,
              ResolveRuntimeTenantIdentityRequest.getDefaultInstance(),
              ResolveRuntimeTenantIdentityResponse.getDefaultInstance());
  private static final MethodDescriptor<PingRequest, PingResponse> PING_METHOD_DESCRIPTOR =
      unaryMethod(PING_METHOD, PingRequest.getDefaultInstance(), PingResponse.getDefaultInstance());

  @Test
  void noTokenWithValidSameNamespacePeerReachesTenantIdentityService() {
    withConfiguredInterceptor(
        interceptor -> {
          GameRepository repository = mock(GameRepository.class);
          UUID tenantId = UUID.fromString(TENANT_UUID);
          when(repository.findRuntimeTenantIdentityByCanonicalTenantId(tenantId))
              .thenReturn(
                  Optional.of(
                      new GameTenantIdentity(
                          tenantId,
                          GameTenantIdentity.ProvenanceKind.NEW_GAME_ROW,
                          42L,
                          "legacy-owner-key-42")));
          TenantIdentityGrpcService service =
              new TenantIdentityGrpcService(
                  repository, mock(GameSessionTenantAssociationRepository.class), "test");

          DispatchResult result = dispatchTenantMethod(interceptor, service, GAME_SESSION_URI);

          assertThat(result.observer().failure).isFalse();
          assertThat(result.observer().completed).isTrue();
          assertThat(result.observer().response.getCanonicalTenantId()).isEqualTo(TENANT_UUID);
          assertThat(result.handlerDispatched()).isTrue();
          verify(repository).findRuntimeTenantIdentityByCanonicalTenantId(tenantId);
        });
  }

  @Test
  void noTokenWithAbsentOrWrongPeerIsDeniedBeforeOwnerRead() {
    withConfiguredInterceptor(
        interceptor -> {
          GameRepository repository = mock(GameRepository.class);
          TenantIdentityGrpcService service =
              new TenantIdentityGrpcService(
                  repository, mock(GameSessionTenantAssociationRepository.class), "test");

          DispatchResult absent = dispatchTenantMethod(interceptor, service, null);
          DispatchResult wrong =
              dispatchTenantMethod(
                  interceptor, service, "spiffe://firemud/ns/test/sa/account-service");

          assertThat(absent.observer().failure).isTrue();
          assertThat(absent.observer().statusCode).isEqualTo(Status.Code.PERMISSION_DENIED);
          assertThat(wrong.observer().failure).isTrue();
          assertThat(wrong.observer().statusCode).isEqualTo(Status.Code.PERMISSION_DENIED);
          assertThat(absent.handlerDispatched()).isTrue();
          assertThat(wrong.handlerDispatched()).isTrue();
          org.mockito.Mockito.verifyNoInteractions(repository);
        });
  }

  @Test
  void unrelatedGameDesignMethodRemainsBearerProtected() {
    withConfiguredInterceptor(
        interceptor -> {
          @SuppressWarnings({"rawtypes", "unchecked"})
          ServerCall<PingRequest, PingResponse> call = mock(ServerCall.class);
          when(call.getMethodDescriptor()).thenReturn(PING_METHOD_DESCRIPTOR);
          @SuppressWarnings({"rawtypes", "unchecked"})
          ServerCallHandler<PingRequest, PingResponse> next = mock(ServerCallHandler.class);

          interceptor.interceptCall(call, new Metadata(), next);

          ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
          verify(call).close(status.capture(), org.mockito.ArgumentMatchers.any(Metadata.class));
          assertThat(status.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
          verify(next, never())
              .startCall(
                  org.mockito.ArgumentMatchers.eq(call),
                  org.mockito.ArgumentMatchers.any(Metadata.class));
        });
  }

  private static DispatchResult dispatchTenantMethod(
      AuthTokenInterceptor interceptor, TenantIdentityGrpcService service, String peerUri) {
    @SuppressWarnings({"rawtypes", "unchecked"})
    ServerCall<ResolveRuntimeTenantIdentityRequest, ResolveRuntimeTenantIdentityResponse> call =
        mock(ServerCall.class);
    when(call.getMethodDescriptor()).thenReturn(TENANT_METHOD_DESCRIPTOR);
    AtomicBoolean handlerDispatched = new AtomicBoolean();
    TestObserver observer = new TestObserver();
    ServerCallHandler<ResolveRuntimeTenantIdentityRequest, ResolveRuntimeTenantIdentityResponse>
        next =
            new ServerCallHandler<>() {
              @Override
              public ServerCall.Listener<ResolveRuntimeTenantIdentityRequest> startCall(
                  ServerCall<
                          ResolveRuntimeTenantIdentityRequest, ResolveRuntimeTenantIdentityResponse>
                      serverCall,
                  Metadata headers) {
                handlerDispatched.set(true);
                return new ServerCall.Listener<>() {
                  @Override
                  public void onMessage(ResolveRuntimeTenantIdentityRequest request) {
                    service.resolveRuntimeTenantIdentity(request, observer);
                  }
                };
              }
            };
    ResolveRuntimeTenantIdentityRequest request =
        ResolveRuntimeTenantIdentityRequest.newBuilder()
            .setCanonicalTenantId(TENANT_UUID)
            .setRequestId(REQUEST_UUID)
            .build();
    Runnable dispatch =
        () -> {
          ServerCall.Listener<ResolveRuntimeTenantIdentityRequest> listener =
              interceptor.interceptCall(call, new Metadata(), next);
          listener.onMessage(request);
        };

    if (peerUri == null) {
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, null).run(dispatch);
    } else {
      GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(peerUri).orElseThrow();
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(dispatch);
    }
    return new DispatchResult(observer, handlerDispatched.get());
  }

  private static void withConfiguredInterceptor(
      java.util.function.Consumer<AuthTokenInterceptor> action) {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class,
                CommonSecurityAutoConfiguration.class))
        .withInitializer(
            context -> {
              try {
                PropertySource<?> gameDesignApplication =
                    new YamlPropertySourceLoader()
                        .load(
                            "game-design-application",
                            new FileSystemResource("src/main/resources/application.yml"))
                        .get(0);
                context.getEnvironment().getPropertySources().addLast(gameDesignApplication);
              } catch (IOException exception) {
                throw new IllegalStateException(
                    "Could not load Game Design application config", exception);
              }
            })
        .withPropertyValues("firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234")
        .run(
            context -> {
              assertThat(context).hasSingleBean(AuthTokenInterceptor.class);
              assertThat(context.getBean(GrpcAuthProperties.class).getPublicMethods())
                  .containsExactly(TENANT_METHOD, ASSOCIATION_METHOD);
              action.accept(context.getBean(AuthTokenInterceptor.class));
            });
  }

  private static <ReqT extends Message, RespT extends Message>
      MethodDescriptor<ReqT, RespT> unaryMethod(
          String fullMethodName, ReqT requestDefault, RespT responseDefault) {
    return MethodDescriptor.<ReqT, RespT>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName(fullMethodName)
        .setRequestMarshaller(ProtoUtils.marshaller(requestDefault))
        .setResponseMarshaller(ProtoUtils.marshaller(responseDefault))
        .build();
  }

  private record DispatchResult(TestObserver observer, boolean handlerDispatched) {}

  private static final class TestObserver
      implements StreamObserver<ResolveRuntimeTenantIdentityResponse> {
    private ResolveRuntimeTenantIdentityResponse response;
    private Status.Code statusCode;
    private boolean failure;
    private boolean completed;

    @Override
    public void onNext(ResolveRuntimeTenantIdentityResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable throwable) {
      failure = true;
      statusCode = Status.fromThrowable(throwable).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
