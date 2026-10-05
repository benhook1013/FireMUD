package unit.net.firedevops.firemud.gamedesign.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.GrpcAuthProperties;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.service.impl.TenantIdentityGrpcService;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

class TenantIdentityGrpcAuthWiringTest {
  private static final String METHOD =
      "gamedesign.v1.TenantIdentityService/ResolveFreshTenantCreation";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String GAME_SESSION_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String SOURCE_KEY = "fresh-owner-key-91";
  private static final String REQUEST_ID = "22222222-2222-4222-8222-222222222222";
  private static final String OPERATION_ID = "33333333-3333-4333-8333-333333333333";
  private static final String TENANT_ID = "44444444-4444-4444-8444-444444444444";
  private static final String REQUEST_DIGEST =
      GameTenantCreationDigest.requestDigest(
          "test", java.util.UUID.fromString(REQUEST_ID), SOURCE_KEY, "Fresh Realm", null);
  private static final MethodDescriptor<
          ResolveFreshTenantCreationRequest, ResolveFreshTenantCreationResponse>
      METHOD_DESCRIPTOR =
          unaryMethod(
              METHOD,
              ResolveFreshTenantCreationRequest.getDefaultInstance(),
              ResolveFreshTenantCreationResponse.getDefaultInstance());

  @Test
  void configuredTokenExemptionContainsOnlyFreshCreationReadInBothProfiles() throws IOException {
    assertConfiguredMethods("src/main/resources/application.yml");
    assertConfiguredMethods("src/main/resources/application-prod.yml");
  }

  @Test
  void allowlistedNoTokenMethodStillRequiresExactAccountPeerBeforeOwnerRead() {
    withConfiguredInterceptor(
        interceptor -> {
          GameTenantCreationRepository repository = mock(GameTenantCreationRepository.class);
          TenantIdentityGrpcService service = new TenantIdentityGrpcService(repository, "test");
          UUID requestId = java.util.UUID.fromString(REQUEST_ID);
          UUID operationId = java.util.UUID.fromString(OPERATION_ID);
          UUID tenantId = java.util.UUID.fromString(TENANT_ID);
          FreshTenantCreationEvidence evidence =
              new FreshTenantCreationEvidence(
                  1,
                  "test",
                  requestId,
                  operationId,
                  REQUEST_DIGEST,
                  tenantId,
                  91L,
                  SOURCE_KEY,
                  "NEW_GAME_ROW",
                  GameTenantCreationDigest.evidenceDigest(
                      "test",
                      requestId,
                      operationId,
                      REQUEST_DIGEST,
                      tenantId,
                      91L,
                      SOURCE_KEY,
                      "NEW_GAME_ROW"));
          when(repository.read(requestId, "test")).thenReturn(Optional.of(evidence));

          DispatchResult account = dispatch(interceptor, service, ACCOUNT_URI);
          assertThat(account.observer().failure).isNull();
          assertThat(account.observer().completed).isTrue();
          assertThat(account.observer().response.getEvidenceDigest())
              .isEqualTo(evidence.evidenceDigest());
          assertThat(account.handlerDispatched()).isTrue();
          verify(repository).read(requestId, "test");

          GameTenantCreationRepository wrongPeerRepository =
              mock(GameTenantCreationRepository.class);
          TenantIdentityGrpcService wrongPeerService =
              new TenantIdentityGrpcService(wrongPeerRepository, "test");
          DispatchResult gameSession = dispatch(interceptor, wrongPeerService, GAME_SESSION_URI);
          assertThat(gameSession.observer().failure).isEqualTo(Status.Code.PERMISSION_DENIED);
          assertThat(gameSession.observer().completed).isFalse();
          assertThat(gameSession.handlerDispatched()).isTrue();
          verifyNoInteractions(wrongPeerRepository);
        });
  }

  private static void assertConfiguredMethods(String resourcePath) throws IOException {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class,
                CommonSecurityAutoConfiguration.class))
        .withInitializer(
            context -> {
              try {
                PropertySource<?> config =
                    new YamlPropertySourceLoader()
                        .load("game-design-application", new FileSystemResource(resourcePath))
                        .get(0);
                context.getEnvironment().getPropertySources().addLast(config);
              } catch (IOException exception) {
                throw new IllegalStateException(
                    "Could not load Game Design configuration", exception);
              }
            })
        .withPropertyValues("firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234")
        .run(
            context ->
                assertThat(context.getBean(GrpcAuthProperties.class).getPublicMethods())
                    .containsExactly(METHOD));
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
                PropertySource<?> config =
                    new YamlPropertySourceLoader()
                        .load(
                            "game-design-application",
                            new FileSystemResource("src/main/resources/application.yml"))
                        .get(0);
                context.getEnvironment().getPropertySources().addLast(config);
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
                  .containsExactly(METHOD);
              action.accept(context.getBean(AuthTokenInterceptor.class));
            });
  }

  private static DispatchResult dispatch(
      AuthTokenInterceptor interceptor, TenantIdentityGrpcService service, String peerUri) {
    @SuppressWarnings({"rawtypes", "unchecked"})
    ServerCall<ResolveFreshTenantCreationRequest, ResolveFreshTenantCreationResponse> call =
        Mockito.mock(ServerCall.class);
    when(call.getMethodDescriptor()).thenReturn(METHOD_DESCRIPTOR);
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
            .setCreationRequestId(REQUEST_ID)
            .setExpectedRequestDigest(REQUEST_DIGEST)
            .build();
    Runnable invocation =
        () -> {
          ServerCall.Listener<ResolveFreshTenantCreationRequest> listener =
              interceptor.interceptCall(call, new Metadata(), next);
          listener.onMessage(request);
        };
    if (peerUri == null) {
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, null).run(invocation);
    } else {
      GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(peerUri).orElseThrow();
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(invocation);
    }
    return new DispatchResult(observer, dispatched.get());
  }

  private static <RequestT extends Message, ResponseT extends Message>
      MethodDescriptor<RequestT, ResponseT> unaryMethod(
          String fullMethodName, RequestT requestDefault, ResponseT responseDefault) {
    return MethodDescriptor.<RequestT, ResponseT>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName(fullMethodName)
        .setRequestMarshaller(ProtoUtils.marshaller(requestDefault))
        .setResponseMarshaller(ProtoUtils.marshaller(responseDefault))
        .build();
  }

  private record DispatchResult(Observer observer, boolean handlerDispatched) {}

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
