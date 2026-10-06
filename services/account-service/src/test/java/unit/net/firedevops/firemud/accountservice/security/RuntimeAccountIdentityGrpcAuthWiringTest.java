package unit.net.firedevops.firemud.accountservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.account.v1.GetProfileRequest;
import net.firedevops.firemud.account.v1.GetProfileResponse;
import net.firedevops.firemud.account.v1.ResolveRuntimeAccountIdentityRequest;
import net.firedevops.firemud.account.v1.ResolveRuntimeAccountIdentityResponse;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.impl.RuntimeAccountIdentityGrpcService;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.GrpcAuthProperties;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionClaims;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

class RuntimeAccountIdentityGrpcAuthWiringTest {
  private static final String IDENTITY_METHOD =
      "account.v1.RuntimeAccountIdentityService/ResolveRuntimeAccountIdentity";
  private static final String NONPUBLIC_METHOD = "account.v1.AccountService/GetProfile";
  private static final String ACCOUNT_UUID = "87426bb3-a733-43f0-9c8e-2e379cbdf7ec";
  private static final String REQUEST_UUID = "11111111-1111-4111-8111-111111111111";
  private static final String GAME_SESSION_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String ENTITY_MANAGEMENT_URI =
      "spiffe://firemud/ns/test/sa/entity-management-service";
  private static final MethodDescriptor<
          ResolveRuntimeAccountIdentityRequest, ResolveRuntimeAccountIdentityResponse>
      IDENTITY_METHOD_DESCRIPTOR =
          unaryMethod(
              IDENTITY_METHOD,
              ResolveRuntimeAccountIdentityRequest.getDefaultInstance(),
              ResolveRuntimeAccountIdentityResponse.getDefaultInstance());
  private static final MethodDescriptor<GetProfileRequest, GetProfileResponse>
      NONPUBLIC_METHOD_DESCRIPTOR =
          unaryMethod(
              NONPUBLIC_METHOD,
              GetProfileRequest.getDefaultInstance(),
              GetProfileResponse.getDefaultInstance());

  @Test
  void exactSameNamespaceRuntimePeersReachReadOnlyHandlerUnderBaseAndProdYaml() {
    for (boolean productionLayer : List.of(false, true)) {
      withConfiguredInterceptor(
          productionLayer,
          (interceptor, ignored) -> {
            for (String peerUri : List.of(GAME_SESSION_URI, ENTITY_MANAGEMENT_URI)) {
              AccountRepository repository = mock(AccountRepository.class);
              Account account = persistedAccount();
              when(repository.findByAccountUuid(UUID.fromString(ACCOUNT_UUID)))
                  .thenReturn(Optional.of(account));
              RuntimeAccountIdentityGrpcService service =
                  new RuntimeAccountIdentityGrpcService(repository, "test");

              DispatchResult result = dispatchIdentityMethod(interceptor, service, peerUri, null);

              assertThat(result.observer().failure).isFalse();
              assertThat(result.observer().completed).isTrue();
              assertThat(result.observer().response.getCanonicalAccountId())
                  .isEqualTo(ACCOUNT_UUID);
              assertThat(result.observer().response.getSourceAccountRowId()).isEqualTo(42L);
              assertThat(result.handlerDispatched()).isTrue();
              verify(repository).findByAccountUuid(UUID.fromString(ACCOUNT_UUID));
            }
          });
    }
  }

  @Test
  void absentWrongAndWrongNamespacePeersAreDeniedBeforeRepositoryReadUnderBothYamlLayers() {
    for (boolean productionLayer : List.of(false, true)) {
      withConfiguredInterceptor(
          productionLayer,
          (interceptor, jwtUtil) -> {
            AccountRepository repository = mock(AccountRepository.class);
            RuntimeAccountIdentityGrpcService service =
                new RuntimeAccountIdentityGrpcService(repository, "test");
            String privilegedBearer =
                jwtUtil.generateToken(
                    ACCOUNT_UUID,
                    Map.of(
                        "accountId",
                        ACCOUNT_UUID,
                        "globalRoles",
                        List.of("platformAdmin"),
                        "scopedRoles",
                        Map.of()));
            var validatedBearer = jwtUtil.parseToken(privilegedBearer);
            assertThat(SessionClaims.fromJwt(validatedBearer).hasPrivilegedRole()).isTrue();

            DispatchResult absent = dispatchIdentityMethod(interceptor, service, null, null);
            DispatchResult wrongService =
                dispatchIdentityMethod(
                    interceptor, service, "spiffe://firemud/ns/test/sa/account-service", null);
            DispatchResult wrongNamespace =
                dispatchIdentityMethod(
                    interceptor,
                    service,
                    "spiffe://firemud/ns/other/sa/game-session-service",
                    null);
            DispatchResult bearerWithoutPeer =
                dispatchIdentityMethod(interceptor, service, null, privilegedBearer);

            for (DispatchResult denied :
                List.of(absent, wrongService, wrongNamespace, bearerWithoutPeer)) {
              assertThat(denied.observer().failure).isTrue();
              assertThat(denied.observer().statusCode).isEqualTo(Status.Code.PERMISSION_DENIED);
              assertThat(denied.handlerDispatched()).isTrue();
            }
            verifyNoInteractions(repository);
          });
    }
  }

  @Test
  void unrelatedAccountMethodRemainsBearerProtectedUnderBothYamlLayers() {
    for (boolean productionLayer : List.of(false, true)) {
      withConfiguredInterceptor(
          productionLayer,
          (interceptor, ignored) -> {
            @SuppressWarnings({"rawtypes", "unchecked"})
            ServerCall<GetProfileRequest, GetProfileResponse> call = mock(ServerCall.class);
            when(call.getMethodDescriptor()).thenReturn(NONPUBLIC_METHOD_DESCRIPTOR);
            @SuppressWarnings({"rawtypes", "unchecked"})
            ServerCallHandler<GetProfileRequest, GetProfileResponse> next =
                mock(ServerCallHandler.class);

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
  }

  private static Account persistedAccount() {
    Account account = new Account();
    account.setId(42L);
    account.setAccountUuid(UUID.fromString(ACCOUNT_UUID));
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_V29_MIGRATION);
    account.setAccountUuidSourceNumericId(42L);
    return account;
  }

  private static DispatchResult dispatchIdentityMethod(
      AuthTokenInterceptor interceptor,
      RuntimeAccountIdentityGrpcService service,
      String peerUri,
      String bearerToken) {
    @SuppressWarnings({"rawtypes", "unchecked"})
    ServerCall<ResolveRuntimeAccountIdentityRequest, ResolveRuntimeAccountIdentityResponse> call =
        mock(ServerCall.class);
    when(call.getMethodDescriptor()).thenReturn(IDENTITY_METHOD_DESCRIPTOR);
    AtomicBoolean handlerDispatched = new AtomicBoolean();
    TestObserver observer = new TestObserver();
    ServerCallHandler<ResolveRuntimeAccountIdentityRequest, ResolveRuntimeAccountIdentityResponse>
        next =
            new ServerCallHandler<>() {
              @Override
              public ServerCall.Listener<ResolveRuntimeAccountIdentityRequest> startCall(
                  ServerCall<
                          ResolveRuntimeAccountIdentityRequest,
                          ResolveRuntimeAccountIdentityResponse>
                      serverCall,
                  Metadata headers) {
                handlerDispatched.set(true);
                return new ServerCall.Listener<>() {
                  @Override
                  public void onMessage(ResolveRuntimeAccountIdentityRequest request) {
                    service.resolveRuntimeAccountIdentity(request, observer);
                  }
                };
              }
            };
    ResolveRuntimeAccountIdentityRequest request =
        ResolveRuntimeAccountIdentityRequest.newBuilder()
            .setCanonicalAccountId(ACCOUNT_UUID)
            .setRequestId(REQUEST_UUID)
            .build();
    Metadata headers = new Metadata();
    if (bearerToken != null) {
      headers.put(
          Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER),
          "Bearer " + bearerToken);
    }
    Runnable dispatch =
        () -> {
          ServerCall.Listener<ResolveRuntimeAccountIdentityRequest> listener =
              interceptor.interceptCall(call, headers, next);
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
      boolean productionLayer,
      java.util.function.BiConsumer<AuthTokenInterceptor, JwtUtil> action) {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class,
                CommonSecurityAutoConfiguration.class))
        .withInitializer(
            context -> {
              try {
                YamlPropertySourceLoader yamlLoader = new YamlPropertySourceLoader();
                PropertySource<?> accountBase =
                    yamlLoader
                        .load(
                            "account-application",
                            new FileSystemResource("src/main/resources/application.yml"))
                        .get(0);
                context.getEnvironment().getPropertySources().addLast(accountBase);
                if (productionLayer) {
                  PropertySource<?> accountProduction =
                      yamlLoader
                          .load(
                              "account-application-prod",
                              new FileSystemResource("src/main/resources/application-prod.yml"))
                          .get(0);
                  context.getEnvironment().getPropertySources().addFirst(accountProduction);
                }
              } catch (IOException exception) {
                throw new IllegalStateException(
                    "Could not load Account application config", exception);
              }
            })
        .withPropertyValues("firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234")
        .run(
            context -> {
              assertThat(context).hasSingleBean(AuthTokenInterceptor.class);
              assertThat(context.getBean(GrpcAuthProperties.class).getPublicMethods())
                  .contains(IDENTITY_METHOD)
                  .doesNotContain(NONPUBLIC_METHOD);
              action.accept(
                  context.getBean(AuthTokenInterceptor.class), context.getBean(JwtUtil.class));
            });
  }

  private static <
          ReqT extends com.google.protobuf.Message, RespT extends com.google.protobuf.Message>
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
      implements StreamObserver<ResolveRuntimeAccountIdentityResponse> {
    private ResolveRuntimeAccountIdentityResponse response;
    private Status.Code statusCode;
    private boolean failure;
    private boolean completed;

    @Override
    public void onNext(ResolveRuntimeAccountIdentityResponse value) {
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
