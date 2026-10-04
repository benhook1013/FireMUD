package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.GrpcAuthProperties;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindHoldBinding;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindOwnerProofReader;
import net.firedevops.firemud.gamesession.service.PublishedRealmAdmissionOwnerReadRequest;
import net.firedevops.firemud.gamesession.service.PublishedRealmAdmissionOwnerReadService;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetInitialAdmissionBindProofRequest;
import net.firedevops.firemud.gamesession.v1.GetInitialAdmissionBindProofResponse;
import net.firedevops.firemud.gamesession.v1.GetPublishedRealmAdmissionOwnerReadRequest;
import net.firedevops.firemud.gamesession.v1.GetPublishedRealmAdmissionOwnerReadResponse;
import net.firedevops.firemud.gamesession.v1.SetPinnedScriptPatchVersionRequest;
import net.firedevops.firemud.gamesession.v1.SetPinnedScriptPatchVersionResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

class InitialAdmissionOwnerReadAuthWiringTest {
  private static final String NAMESPACE = "test";
  private static final String INITIAL_METHOD =
      "game_session.v1.GameSessionControlPlaneService/GetInitialAdmissionBindProof";
  private static final String PUBLISHED_METHOD =
      "game_session.v1.GameSessionControlPlaneService/GetPublishedRealmAdmissionOwnerRead";
  private static final String PING_METHOD = "game_session.v1.GameSessionService/Ping";
  private static final String WORLD_URI = "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String ENTITY_URI = "spiffe://firemud/ns/test/sa/entity-management-service";
  private static final String HOLD_ID = "00000000-0000-0000-0000-000000000003";
  private static final String HOLD_FENCE = "00000000-0000-0000-0000-000000000004";
  private static final String REALM_UUID = "00000000-0000-0000-0000-000000000001";
  private static final String PLAYABLE_NAMESPACE_UUID = "00000000-0000-0000-0000-000000000002";
  private static final String REQUEST_ID = "initial-admission-1";
  private static final String REQUEST_DIGEST = "a".repeat(64);

  @BeforeEach
  void startWithoutSessionContext() {
    SessionContext.clear();
  }

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void noTokenReachesEachExactOwnerHandlerForItsSameNamespacePeer() {
    withConfiguredInterceptor(
        false,
        interceptor -> {
          InitialAdmissionBindOwnerProofReader proofReader =
              mock(InitialAdmissionBindOwnerProofReader.class);
          PublishedRealmAdmissionOwnerReadService ownerReadService =
              mock(PublishedRealmAdmissionOwnerReadService.class);
          GameSessionControlPlaneGrpcService service = service(proofReader, ownerReadService);

          InitialDispatchResult initial = dispatchInitial(interceptor, service, WORLD_URI);
          PublishedDispatchResult published = dispatchPublished(interceptor, service, ENTITY_URI);

          assertThat(initial.handlerDispatched()).isTrue();
          assertThat(initial.observer().response.getError().getCode()).isEqualTo("INTERNAL");
          verify(proofReader).read(binding());

          assertThat(published.handlerDispatched()).isTrue();
          assertThat(published.observer().statusCode).isEqualTo(Status.Code.UNAVAILABLE);
          verify(ownerReadService).read(any(PublishedRealmAdmissionOwnerReadRequest.class));
        });
  }

  @Test
  void absentOrWrongPeerCannotPassEitherExactOwnerHandler() {
    withConfiguredInterceptor(
        false,
        interceptor -> {
          InitialAdmissionBindOwnerProofReader proofReader =
              mock(InitialAdmissionBindOwnerProofReader.class);
          PublishedRealmAdmissionOwnerReadService ownerReadService =
              mock(PublishedRealmAdmissionOwnerReadService.class);
          GameSessionControlPlaneGrpcService service = service(proofReader, ownerReadService);

          InitialDispatchResult absentInitial = dispatchInitial(interceptor, service, null);
          InitialDispatchResult wrongInitial = dispatchInitial(interceptor, service, ENTITY_URI);
          PublishedDispatchResult absentPublished = dispatchPublished(interceptor, service, null);
          PublishedDispatchResult wrongPublished =
              dispatchPublished(
                  interceptor, service, "spiffe://firemud/ns/other/sa/entity-management-service");

          assertThat(absentInitial.observer().response.getError().getCode())
              .isEqualTo("PERMISSION_DENIED");
          assertThat(wrongInitial.observer().response.getError().getCode())
              .isEqualTo("PERMISSION_DENIED");
          assertThat(absentPublished.observer().statusCode)
              .isEqualTo(Status.Code.PERMISSION_DENIED);
          assertThat(wrongPublished.observer().statusCode).isEqualTo(Status.Code.PERMISSION_DENIED);
          verifyNoInteractions(proofReader, ownerReadService);
        });
  }

  @Test
  void eitherOwnerHandlerRejectsAuthenticatedCallerContextEvenWithTheRightPeer() {
    withConfiguredInterceptor(
        false,
        interceptor -> {
          InitialAdmissionBindOwnerProofReader proofReader =
              mock(InitialAdmissionBindOwnerProofReader.class);
          PublishedRealmAdmissionOwnerReadService ownerReadService =
              mock(PublishedRealmAdmissionOwnerReadService.class);
          GameSessionControlPlaneGrpcService service = service(proofReader, ownerReadService);
          SessionContext.setContext("authenticated-user", List.of(), Map.of());

          try {
            InitialDispatchResult initial = dispatchInitial(interceptor, service, WORLD_URI);
            PublishedDispatchResult published = dispatchPublished(interceptor, service, ENTITY_URI);

            assertThat(initial.observer().response.getError().getCode())
                .isEqualTo("PERMISSION_DENIED");
            assertThat(published.observer().statusCode).isEqualTo(Status.Code.PERMISSION_DENIED);
            verifyNoInteractions(proofReader, ownerReadService);
          } finally {
            SessionContext.clear();
          }
        });
  }

  @Test
  void unrelatedControlPlaneMethodStillRequiresBearerToken() {
    withConfiguredInterceptor(
        false,
        interceptor -> {
          @SuppressWarnings({"rawtypes", "unchecked"})
          ServerCall<SetPinnedScriptPatchVersionRequest, SetPinnedScriptPatchVersionResponse> call =
              mock(ServerCall.class);
          when(call.getMethodDescriptor())
              .thenReturn(
                  GameSessionControlPlaneServiceGrpc.getSetPinnedScriptPatchVersionMethod());
          @SuppressWarnings({"rawtypes", "unchecked"})
          ServerCallHandler<SetPinnedScriptPatchVersionRequest, SetPinnedScriptPatchVersionResponse>
              next = mock(ServerCallHandler.class);

          interceptor.interceptCall(call, new Metadata(), next);

          ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
          verify(call).close(status.capture(), any(Metadata.class));
          assertThat(status.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
          verify(next, never()).startCall(eq(call), any(Metadata.class));
        });
  }

  @Test
  void productionConfigurationInheritsOnlyTheExactBaseOwnerReadExemptions() {
    withConfiguredInterceptor(
        true,
        interceptor -> {
          InitialAdmissionBindOwnerProofReader proofReader =
              mock(InitialAdmissionBindOwnerProofReader.class);
          PublishedRealmAdmissionOwnerReadService ownerReadService =
              mock(PublishedRealmAdmissionOwnerReadService.class);
          GameSessionControlPlaneGrpcService service = service(proofReader, ownerReadService);

          InitialDispatchResult initial = dispatchInitial(interceptor, service, WORLD_URI);
          PublishedDispatchResult published = dispatchPublished(interceptor, service, ENTITY_URI);

          assertThat(initial.handlerDispatched()).isTrue();
          assertThat(published.handlerDispatched()).isTrue();
          verify(proofReader).read(binding());
          verify(ownerReadService).read(any(PublishedRealmAdmissionOwnerReadRequest.class));
        });
  }

  private static GameSessionControlPlaneGrpcService service(
      InitialAdmissionBindOwnerProofReader proofReader,
      PublishedRealmAdmissionOwnerReadService ownerReadService) {
    GameSessionControlPlaneGrpcService service =
        new GameSessionControlPlaneGrpcService(
            mock(GameSessionCommandControlPlaneService.class),
            mock(GameSessionRemoteControlPlaneService.class),
            mock(GameSessionRuntimeControlPlaneReadService.class),
            mock(GameSessionAdmissionPointerControlPlaneService.class),
            mock(GameSessionOperatorControlPlaneService.class),
            mock(GameSessionVersionUpgradeControlPlaneService.class),
            new SimpleMeterRegistry());
    service.configureInitialAdmissionBindOwnerReadBoundary(proofReader, NAMESPACE);
    service.configurePublishedRealmAdmissionOwnerReadBoundary(ownerReadService, NAMESPACE);
    return service;
  }

  private static InitialDispatchResult dispatchInitial(
      AuthTokenInterceptor interceptor,
      GameSessionControlPlaneGrpcService service,
      String peerUri) {
    @SuppressWarnings({"rawtypes", "unchecked"})
    ServerCall<GetInitialAdmissionBindProofRequest, GetInitialAdmissionBindProofResponse> call =
        mock(ServerCall.class);
    when(call.getMethodDescriptor())
        .thenReturn(GameSessionControlPlaneServiceGrpc.getGetInitialAdmissionBindProofMethod());
    AtomicBoolean handlerDispatched = new AtomicBoolean();
    InitialObserver observer = new InitialObserver();
    ServerCallHandler<GetInitialAdmissionBindProofRequest, GetInitialAdmissionBindProofResponse>
        next =
            new ServerCallHandler<>() {
              @Override
              public ServerCall.Listener<GetInitialAdmissionBindProofRequest> startCall(
                  ServerCall<
                          GetInitialAdmissionBindProofRequest, GetInitialAdmissionBindProofResponse>
                      serverCall,
                  Metadata headers) {
                handlerDispatched.set(true);
                return new ServerCall.Listener<>() {
                  @Override
                  public void onMessage(GetInitialAdmissionBindProofRequest request) {
                    service.getInitialAdmissionBindProof(request, observer);
                  }
                };
              }
            };
    withPeer(
        peerUri,
        () -> {
          ServerCall.Listener<GetInitialAdmissionBindProofRequest> listener =
              interceptor.interceptCall(call, new Metadata(), next);
          listener.onMessage(initialRequest());
        });
    return new InitialDispatchResult(observer, handlerDispatched.get());
  }

  private static PublishedDispatchResult dispatchPublished(
      AuthTokenInterceptor interceptor,
      GameSessionControlPlaneGrpcService service,
      String peerUri) {
    @SuppressWarnings({"rawtypes", "unchecked"})
    ServerCall<
            GetPublishedRealmAdmissionOwnerReadRequest, GetPublishedRealmAdmissionOwnerReadResponse>
        call = mock(ServerCall.class);
    when(call.getMethodDescriptor())
        .thenReturn(
            GameSessionControlPlaneServiceGrpc.getGetPublishedRealmAdmissionOwnerReadMethod());
    AtomicBoolean handlerDispatched = new AtomicBoolean();
    PublishedObserver observer = new PublishedObserver();
    ServerCallHandler<
            GetPublishedRealmAdmissionOwnerReadRequest, GetPublishedRealmAdmissionOwnerReadResponse>
        next =
            new ServerCallHandler<>() {
              @Override
              public ServerCall.Listener<GetPublishedRealmAdmissionOwnerReadRequest> startCall(
                  ServerCall<
                          GetPublishedRealmAdmissionOwnerReadRequest,
                          GetPublishedRealmAdmissionOwnerReadResponse>
                      serverCall,
                  Metadata headers) {
                handlerDispatched.set(true);
                return new ServerCall.Listener<>() {
                  @Override
                  public void onMessage(GetPublishedRealmAdmissionOwnerReadRequest request) {
                    service.getPublishedRealmAdmissionOwnerRead(request, observer);
                  }
                };
              }
            };
    withPeer(
        peerUri,
        () -> {
          ServerCall.Listener<GetPublishedRealmAdmissionOwnerReadRequest> listener =
              interceptor.interceptCall(call, new Metadata(), next);
          listener.onMessage(publishedRequest());
        });
    return new PublishedDispatchResult(observer, handlerDispatched.get());
  }

  private static void withPeer(String peerUri, Runnable action) {
    if (peerUri == null) {
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, null).run(action);
      return;
    }
    GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(peerUri).orElseThrow();
    Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(action);
  }

  private static GetInitialAdmissionBindProofRequest initialRequest() {
    return GetInitialAdmissionBindProofRequest.newBuilder()
        .setHoldId(HOLD_ID)
        .setHoldFence(HOLD_FENCE)
        .setTenantId("42")
        .setRealmUuid(REALM_UUID)
        .setPlayableStateNamespaceUuid(PLAYABLE_NAMESPACE_UUID)
        .setPlayableStateScope(
            net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                .PLAYABLE_STATE_SCOPE_SHARED)
        .setGameInstanceId("101")
        .setVersionId("11")
        .setActiveLifecycleEpoch(7L)
        .setInitialAdmissionRequestId(REQUEST_ID)
        .setRequestDigest(REQUEST_DIGEST)
        .setExpectedNoPriorPointer(true)
        .setExpectedCatalogRevision(1L)
        .build();
  }

  private static InitialAdmissionBindHoldBinding binding() {
    return new InitialAdmissionBindHoldBinding(
        HOLD_ID,
        HOLD_FENCE,
        42L,
        REALM_UUID,
        PLAYABLE_NAMESPACE_UUID,
        "SHARED",
        101L,
        11L,
        7L,
        REQUEST_ID,
        REQUEST_DIGEST,
        true,
        1L);
  }

  private static GetPublishedRealmAdmissionOwnerReadRequest publishedRequest() {
    return GetPublishedRealmAdmissionOwnerReadRequest.newBuilder()
        .setTargetNamespace(NAMESPACE)
        .setCanonicalTenantId("22222222-2222-4222-8222-222222222222")
        .setGameSessionTenantId("70123")
        .setWorldSlug("earth")
        .setRealmSlug("main")
        .setExpectedCatalogRevision(8L)
        .setExpectedPointerVersion(1L)
        .build();
  }

  private static void withConfiguredInterceptor(
      boolean productionProfile, java.util.function.Consumer<AuthTokenInterceptor> action) {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class,
                CommonSecurityAutoConfiguration.class))
        .withInitializer(
            context -> {
              try {
                YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
                PropertySource<?> application =
                    loader
                        .load(
                            "game-session-application",
                            new FileSystemResource("src/main/resources/application.yml"))
                        .get(0);
                context.getEnvironment().getPropertySources().addLast(application);
                if (productionProfile) {
                  PropertySource<?> production =
                      loader
                          .load(
                              "game-session-production",
                              new FileSystemResource("src/main/resources/application-prod.yml"))
                          .get(0);
                  context.getEnvironment().getPropertySources().addLast(production);
                }
              } catch (IOException exception) {
                throw new IllegalStateException(
                    "Could not load Game Session auth config", exception);
              }
            })
        .withPropertyValues("firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234")
        .run(
            context -> {
              assertThat(context).hasSingleBean(AuthTokenInterceptor.class);
              assertThat(context.getBean(GrpcAuthProperties.class).getPublicMethods())
                  .containsExactly(PING_METHOD, INITIAL_METHOD, PUBLISHED_METHOD);
              action.accept(context.getBean(AuthTokenInterceptor.class));
            });
  }

  private record InitialDispatchResult(InitialObserver observer, boolean handlerDispatched) {}

  private record PublishedDispatchResult(PublishedObserver observer, boolean handlerDispatched) {}

  private static final class InitialObserver
      implements StreamObserver<GetInitialAdmissionBindProofResponse> {
    private GetInitialAdmissionBindProofResponse response;
    private Status.Code statusCode;

    @Override
    public void onNext(GetInitialAdmissionBindProofResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable throwable) {
      statusCode = Status.fromThrowable(throwable).getCode();
    }

    @Override
    public void onCompleted() {}
  }

  private static final class PublishedObserver
      implements StreamObserver<GetPublishedRealmAdmissionOwnerReadResponse> {
    private GetPublishedRealmAdmissionOwnerReadResponse response;
    private Status.Code statusCode;

    @Override
    public void onNext(GetPublishedRealmAdmissionOwnerReadResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable throwable) {
      statusCode = Status.fromThrowable(throwable).getCode();
    }

    @Override
    public void onCompleted() {}
  }
}
