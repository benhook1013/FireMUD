package unit.net.firedevops.firemud.gamedesign.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.GrpcAuthProperties;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.service.impl.TenantIdentityGrpcService;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

class TenantIdentityGrpcAuthWiringTest {
  private static final String FRESH_CREATION_METHOD =
      "gamedesign.v1.TenantIdentityService/ResolveFreshTenantCreation";
  private static final String OTHER_METHOD = "gamedesign.v1.TenantIdentityService/UnlistedMethod";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String GAME_SESSION_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String SOURCE_KEY = "fresh-owner-key-91";
  private static final UUID REQUEST_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID OPERATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID TENANT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final String REQUEST_DIGEST =
      GameTenantCreationDigest.requestDigest("test", REQUEST_ID, SOURCE_KEY, "Fresh Realm", null);

  @Test
  void defaultAndProductionProfilesAllowOnlyFreshReadAndRequireClientTls() throws IOException {
    for (String file : List.of("application.yml", "application-prod.yml")) {
      GrpcConfiguration config = load(file);
      assertThat(config.publicMethods()).containsExactly(FRESH_CREATION_METHOD);
      assertThat(config.clientAuth()).isEqualTo("REQUIRE");
    }
  }

  @Test
  void allowlistedNoTokenReadStillRequiresExactAccountPeerBeforeOwnerRead() {
    withConfiguredInterceptor(
        interceptor -> {
          GameTenantCreationRepository repository = mock(GameTenantCreationRepository.class);
          TenantIdentityGrpcService service = new TenantIdentityGrpcService(repository, "test");
          FreshTenantCreationEvidence evidence = evidence("test", REQUEST_DIGEST);
          when(repository.read(REQUEST_ID, "test")).thenReturn(Optional.of(evidence));

          DispatchResult account = dispatch(interceptor, service, ACCOUNT_URI);
          assertThat(account.observer().failure).isNull();
          assertThat(account.observer().completed).isTrue();
          assertThat(account.observer().response.getEvidenceDigest())
              .isEqualTo(evidence.evidenceDigest());
          assertThat(account.handlerDispatched()).isTrue();
          verify(repository).read(REQUEST_ID, "test");

          GameTenantCreationRepository wrongPeerRepository =
              mock(GameTenantCreationRepository.class);
          TenantIdentityGrpcService wrongPeerService =
              new TenantIdentityGrpcService(wrongPeerRepository, "test");
          DispatchResult gameSession = dispatch(interceptor, wrongPeerService, GAME_SESSION_URI);
          assertThat(gameSession.observer().failure).isEqualTo(Status.Code.PERMISSION_DENIED);
          assertThat(gameSession.observer().response).isNull();
          assertThat(gameSession.observer().completed).isFalse();
          assertThat(gameSession.handlerDispatched()).isTrue();
          verifyNoInteractions(wrongPeerRepository);
        });
  }

  @Test
  void jwtBypassAppliesOnlyToTheConfiguredFreshReadMethod() throws IOException {
    AuthTokenInterceptor interceptor =
        new AuthTokenInterceptor(
            mock(JwtUtil.class), Set.copyOf(load("application.yml").publicMethods()));

    AuthDispatchResult fresh = dispatchWithoutPeer(interceptor, FRESH_CREATION_METHOD);
    assertThat(fresh.dispatched()).isTrue();
    assertThat(fresh.closedStatus()).isNull();

    AuthDispatchResult other = dispatchWithoutPeer(interceptor, OTHER_METHOD);
    assertThat(other.dispatched()).isFalse();
    assertThat(other.closedStatus()).isEqualTo(Status.Code.UNAUTHENTICATED);
  }

  private static GrpcConfiguration load(String file) throws IOException {
    ConfigurableEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(propertySource(file));
    Binder binder = Binder.get(environment);
    List<String> publicMethods =
        binder
            .bind("firemud.auth.grpc.public-methods", Bindable.listOf(String.class))
            .orElseThrow(() -> new AssertionError("Configured public methods are required"));
    String clientAuth =
        binder
            .bind("spring.grpc.server.ssl.client-auth", String.class)
            .orElseThrow(
                () -> new AssertionError("Configured TLS client authentication is required"));
    return new GrpcConfiguration(publicMethods, clientAuth);
  }

  private static PropertySource<?> propertySource(String file) throws IOException {
    return new YamlPropertySourceLoader()
        .load(
            "game-design-application",
            new FileSystemResource(Path.of("src/main/resources").resolve(file)))
        .get(0);
  }

  private static void withConfiguredInterceptor(Consumer<AuthTokenInterceptor> action) {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class,
                CommonSecurityAutoConfiguration.class))
        .withInitializer(
            context -> {
              try {
                context
                    .getEnvironment()
                    .getPropertySources()
                    .addFirst(propertySource("application.yml"));
              } catch (IOException exception) {
                throw new IllegalStateException(
                    "Could not load Game Design configuration", exception);
              }
            })
        .withPropertyValues("firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234")
        .run(
            context -> {
              assertThat(context).hasSingleBean(AuthTokenInterceptor.class);
              assertThat(context.getBean(GrpcAuthProperties.class).getPublicMethods())
                  .containsExactly(FRESH_CREATION_METHOD);
              action.accept(context.getBean(AuthTokenInterceptor.class));
            });
  }

  private static DispatchResult dispatch(
      AuthTokenInterceptor interceptor, TenantIdentityGrpcService service, String peerUri) {
    ServerCall<ResolveFreshTenantCreationRequest, ResolveFreshTenantCreationResponse> call = mock();
    when(call.getMethodDescriptor()).thenReturn(method(FRESH_CREATION_METHOD));
    AtomicBoolean dispatched = new AtomicBoolean();
    Observer observer = new Observer();
    ServerCallHandler<ResolveFreshTenantCreationRequest, ResolveFreshTenantCreationResponse> next =
        (serverCall, headers) -> {
          dispatched.set(true);
          return new ServerCall.Listener<>() {
            @Override
            public void onMessage(ResolveFreshTenantCreationRequest request) {
              service.resolveFreshTenantCreation(request, observer);
            }
          };
        };
    ResolveFreshTenantCreationRequest request =
        ResolveFreshTenantCreationRequest.newBuilder()
            .setCreationRequestId(REQUEST_ID.toString())
            .setExpectedRequestDigest(REQUEST_DIGEST)
            .build();
    Runnable invocation =
        () -> {
          ServerCall.Listener<ResolveFreshTenantCreationRequest> listener =
              interceptor.interceptCall(call, new Metadata(), next);
          listener.onMessage(request);
        };
    if (peerUri == null) {
      invocation.run();
    } else {
      GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(peerUri).orElseThrow();
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(invocation);
    }
    return new DispatchResult(observer, dispatched.get());
  }

  private static AuthDispatchResult dispatchWithoutPeer(
      AuthTokenInterceptor interceptor, String fullMethodName) {
    ServerCall<ResolveFreshTenantCreationRequest, ResolveFreshTenantCreationResponse> call = mock();
    when(call.getMethodDescriptor()).thenReturn(method(fullMethodName));
    AtomicReference<Status.Code> closedStatus = new AtomicReference<>();
    doAnswer(
            invocation -> {
              Status status = invocation.getArgument(0);
              closedStatus.set(status.getCode());
              return null;
            })
        .when(call)
        .close(ArgumentMatchers.any(), ArgumentMatchers.any());
    AtomicBoolean dispatched = new AtomicBoolean();
    ServerCallHandler<ResolveFreshTenantCreationRequest, ResolveFreshTenantCreationResponse> next =
        (serverCall, headers) -> {
          dispatched.set(true);
          return new ServerCall.Listener<>() {};
        };

    interceptor.interceptCall(call, new Metadata(), next);
    return new AuthDispatchResult(dispatched.get(), closedStatus.get());
  }

  private static MethodDescriptor<
          ResolveFreshTenantCreationRequest, ResolveFreshTenantCreationResponse>
      method(String fullMethodName) {
    return MethodDescriptor
        .<ResolveFreshTenantCreationRequest, ResolveFreshTenantCreationResponse>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName(fullMethodName)
        .setRequestMarshaller(
            ProtoUtils.marshaller(ResolveFreshTenantCreationRequest.getDefaultInstance()))
        .setResponseMarshaller(
            ProtoUtils.marshaller(ResolveFreshTenantCreationResponse.getDefaultInstance()))
        .build();
  }

  private static FreshTenantCreationEvidence evidence(String namespace, String requestDigest) {
    return new FreshTenantCreationEvidence(
        1,
        namespace,
        REQUEST_ID,
        OPERATION_ID,
        requestDigest,
        TENANT_ID,
        91L,
        SOURCE_KEY,
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            namespace,
            REQUEST_ID,
            OPERATION_ID,
            requestDigest,
            TENANT_ID,
            91L,
            SOURCE_KEY,
            "NEW_GAME_ROW"));
  }

  private record DispatchResult(Observer observer, boolean handlerDispatched) {}

  private record AuthDispatchResult(boolean dispatched, Status.Code closedStatus) {}

  private record GrpcConfiguration(List<String> publicMethods, String clientAuth) {}

  private static final class Observer
      implements StreamObserver<ResolveFreshTenantCreationResponse> {
    private ResolveFreshTenantCreationResponse response;
    private Status.Code failure;
    private boolean completed;

    @Override
    public void onNext(ResolveFreshTenantCreationResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable throwable) {
      failure = Status.fromThrowable(throwable).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
