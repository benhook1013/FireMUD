package unit.net.firedevops.firemud.gamedesign.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamedesign.service.CompleteLaunchBindingService;
import net.firedevops.firemud.gamedesign.service.GameAuthoredHelpTopicService;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.PingService;
import net.firedevops.firemud.gamedesign.service.RevisionService;
import net.firedevops.firemud.gamedesign.service.SettingsAuthorityService;
import net.firedevops.firemud.gamedesign.service.TemplateRemapSetService;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import net.firedevops.firemud.gamedesign.service.VersionService;
import net.firedevops.firemud.gamedesign.service.impl.GameDesignGrpcService;
import net.firedevops.firemud.gamedesign.service.impl.TemporalVersionPublishWorkflowMetadataResolver;
import net.firedevops.firemud.gamedesign.v1.GetCompleteLaunchBindingResponse;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleRequest;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CanonicalLaunchGrpcAuthWiringTest {
  private static final String RESOLVE_LAUNCH_DESCRIPTOR_METHOD =
      "gamedesign.v1.GameDesignService/ResolveLaunchDescriptor";
  private static final String GET_LAUNCH_DESCRIPTOR_METHOD =
      "gamedesign.v1.GameDesignService/GetLaunchDescriptor";
  private static final String GET_COMPLETE_LAUNCH_BINDING_METHOD =
      "gamedesign.v1.GameDesignService/GetCompleteLaunchBinding";
  private static final String UNLISTED_METHOD =
      "gamedesign.v1.GameDesignService/GetPublishedReleaseBundle";
  private static final List<String> ALLOWLISTED_METHODS =
      List.of(
          "gamedesign.v1.TenantIdentityService/ResolveFreshTenantCreation",
          "game_design.v1.PublishedRealmEntryPolicyService/ResolvePublishedRealmEntryPolicy",
          "game_design.v1.PublishedRealmEntryPolicyService/ListPublishedRealmEntryPolicies",
          RESOLVE_LAUNCH_DESCRIPTOR_METHOD,
          GET_LAUNCH_DESCRIPTOR_METHOD,
          GET_COMPLETE_LAUNCH_BINDING_METHOD);
  private static final String GAME_SESSION_URI =
      "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String WORLD_MANAGEMENT_URI =
      "spiffe://firemud/ns/test/sa/world-management-service";
  private static final String ENTITY_MANAGEMENT_URI =
      "spiffe://firemud/ns/test/sa/entity-management-service";
  private static final String OTHER_NAMESPACE_GAME_SESSION_URI =
      "spiffe://firemud/ns/other/sa/game-session-service";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String REQUEST_ID = "22222222-2222-4222-8222-222222222222";
  private static final String TENANT_ID = "33333333-3333-4333-8333-333333333333";
  private static final String SOURCE_OPERATION_ID = "44444444-4444-4444-8444-444444444444";
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void sameNamespaceGameSessionPeerReachesEachRealCanonicalLaunchHandlerWithoutBearer() {
    AuthTokenInterceptor interceptor = interceptor();
    Harness harness = harness();
    when(harness.launchDescriptorService().resolveLaunchDescriptor(any()))
        .thenThrow(new IllegalStateException("intentional owner read probe"));
    when(harness.launchDescriptorService()
            .getLaunchDescriptor(
                any(UUID.class),
                any(UUID.class),
                anyString(),
                anyString(),
                anyString(),
                anyString()))
        .thenThrow(new IllegalArgumentException("intentional exact read probe"));
    when(harness.completeLaunchBindingService()
            .getCompleteLaunchBinding(
                any(UUID.class),
                any(UUID.class),
                anyString(),
                anyString(),
                anyString(),
                anyString()))
        .thenThrow(new IllegalArgumentException("intentional complete read probe"));

    DispatchResult<ResolveLaunchDescriptorResponse> resolve =
        dispatchHandler(
            interceptor,
            harness,
            GAME_SESSION_URI,
            new Metadata(),
            RESOLVE_LAUNCH_DESCRIPTOR_METHOD,
            ResolveLaunchDescriptorRequest.getDefaultInstance(),
            ResolveLaunchDescriptorResponse.getDefaultInstance(),
            resolveRequest(),
            GameDesignGrpcService::resolveLaunchDescriptor);
    DispatchResult<GetLaunchDescriptorResponse> descriptorRead =
        dispatchHandler(
            interceptor,
            harness,
            GAME_SESSION_URI,
            new Metadata(),
            GET_LAUNCH_DESCRIPTOR_METHOD,
            GetLaunchDescriptorRequest.getDefaultInstance(),
            GetLaunchDescriptorResponse.getDefaultInstance(),
            readRequest(),
            GameDesignGrpcService::getLaunchDescriptor);
    DispatchResult<GetCompleteLaunchBindingResponse> completeRead =
        dispatchHandler(
            interceptor,
            harness,
            GAME_SESSION_URI,
            new Metadata(),
            GET_COMPLETE_LAUNCH_BINDING_METHOD,
            GetLaunchDescriptorRequest.getDefaultInstance(),
            GetCompleteLaunchBindingResponse.getDefaultInstance(),
            readRequest(),
            GameDesignGrpcService::getCompleteLaunchBinding);

    assertThat(resolve.dispatched()).isTrue();
    assertThat(resolve.closedStatus()).isNull();
    assertThat(resolve.response()).isNotNull();
    assertThat(descriptorRead.dispatched()).isTrue();
    assertThat(descriptorRead.closedStatus()).isNull();
    assertThat(descriptorRead.response()).isNotNull();
    assertThat(completeRead.dispatched()).isTrue();
    assertThat(completeRead.closedStatus()).isNull();
    assertThat(completeRead.response()).isNotNull();
    verify(harness.launchDescriptorService()).resolveLaunchDescriptor(any());
    verify(harness.launchDescriptorService())
        .getLaunchDescriptor(
            any(UUID.class),
            any(UUID.class),
            anyString(),
            anyString(),
            anyString(),
            anyString());
    verify(harness.completeLaunchBindingService())
        .getCompleteLaunchBinding(
            any(UUID.class),
            any(UUID.class),
            anyString(),
            anyString(),
            anyString(),
            anyString());
  }

  @Test
  void workloadPeerMustMatchNamespaceAndServiceEvenWhenMethodSkipsBearerValidation() {
    AuthTokenInterceptor interceptor = interceptor();
    for (String peerUri : List.of("", OTHER_NAMESPACE_GAME_SESSION_URI, ACCOUNT_URI)) {
      GrpcPeerIdentity peer = peerUri.isEmpty() ? null : identity(peerUri);
      assertAllLaunchHandlersDeny(interceptor, peer, new Metadata());
    }
  }

  @Test
  void worldAndEntityPeersAreLimitedToTheirDeclaredLaunchReadHandlers() {
    AuthTokenInterceptor interceptor = interceptor();
    Harness harness = harness();
    when(harness.launchDescriptorService()
            .getLaunchDescriptor(
                any(UUID.class),
                any(UUID.class),
                anyString(),
                anyString(),
                anyString(),
                anyString()))
        .thenThrow(new IllegalArgumentException("intentional exact read probe"));
    when(harness.completeLaunchBindingService()
            .getCompleteLaunchBinding(
                any(UUID.class),
                any(UUID.class),
                anyString(),
                anyString(),
                anyString(),
                anyString()))
        .thenThrow(new IllegalArgumentException("intentional complete read probe"));

    DispatchResult<GetLaunchDescriptorResponse> worldDescriptorRead =
        dispatchHandler(
            interceptor,
            harness,
            WORLD_MANAGEMENT_URI,
            new Metadata(),
            GET_LAUNCH_DESCRIPTOR_METHOD,
            GetLaunchDescriptorRequest.getDefaultInstance(),
            GetLaunchDescriptorResponse.getDefaultInstance(),
            readRequest(),
            GameDesignGrpcService::getLaunchDescriptor);
    DispatchResult<GetCompleteLaunchBindingResponse> worldCompleteRead =
        dispatchHandler(
            interceptor,
            harness,
            WORLD_MANAGEMENT_URI,
            new Metadata(),
            GET_COMPLETE_LAUNCH_BINDING_METHOD,
            GetLaunchDescriptorRequest.getDefaultInstance(),
            GetCompleteLaunchBindingResponse.getDefaultInstance(),
            readRequest(),
            GameDesignGrpcService::getCompleteLaunchBinding);
    DispatchResult<GetCompleteLaunchBindingResponse> entityCompleteRead =
        dispatchHandler(
            interceptor,
            harness,
            ENTITY_MANAGEMENT_URI,
            new Metadata(),
            GET_COMPLETE_LAUNCH_BINDING_METHOD,
            GetLaunchDescriptorRequest.getDefaultInstance(),
            GetCompleteLaunchBindingResponse.getDefaultInstance(),
            readRequest(),
            GameDesignGrpcService::getCompleteLaunchBinding);

    assertAllowedHandlerReached(worldDescriptorRead);
    assertAllowedHandlerReached(worldCompleteRead);
    assertAllowedHandlerReached(entityCompleteRead);
    verify(harness.launchDescriptorService())
        .getLaunchDescriptor(
            any(UUID.class),
            any(UUID.class),
            anyString(),
            anyString(),
            anyString(),
            anyString());
    verify(harness.completeLaunchBindingService(), org.mockito.Mockito.times(2))
        .getCompleteLaunchBinding(
            any(UUID.class),
            any(UUID.class),
            anyString(),
            anyString(),
            anyString(),
            anyString());

    assertDenied(
        interceptor,
        identity(WORLD_MANAGEMENT_URI),
        new Metadata(),
        RESOLVE_LAUNCH_DESCRIPTOR_METHOD,
        ResolveLaunchDescriptorRequest.getDefaultInstance(),
        ResolveLaunchDescriptorResponse.getDefaultInstance(),
        resolveRequest(),
        GameDesignGrpcService::resolveLaunchDescriptor,
        response -> response.getError().getCode());
    assertDenied(
        interceptor,
        identity(ENTITY_MANAGEMENT_URI),
        new Metadata(),
        RESOLVE_LAUNCH_DESCRIPTOR_METHOD,
        ResolveLaunchDescriptorRequest.getDefaultInstance(),
        ResolveLaunchDescriptorResponse.getDefaultInstance(),
        resolveRequest(),
        GameDesignGrpcService::resolveLaunchDescriptor,
        response -> response.getError().getCode());
    assertDenied(
        interceptor,
        identity(ENTITY_MANAGEMENT_URI),
        new Metadata(),
        GET_LAUNCH_DESCRIPTOR_METHOD,
        GetLaunchDescriptorRequest.getDefaultInstance(),
        GetLaunchDescriptorResponse.getDefaultInstance(),
        readRequest(),
        GameDesignGrpcService::getLaunchDescriptor,
        response -> response.getError().getCode());
  }

  @Test
  void playerJwtCannotSubstituteForMissingGameSessionPeerIdentity() {
    JwtUtil jwtUtil = new JwtUtil("valid-test-player-jwt-secret-123456", 60_000L);
    Metadata playerJwt = new Metadata();
    playerJwt.put(
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
        "Bearer " + jwtUtil.generateToken("player-42", Map.of("role", "player")));

    assertAllLaunchHandlersDeny(interceptor(jwtUtil), null, playerJwt);
  }

  @Test
  void unlistedGameDesignMethodStillRequiresBearerToken() {
    AuthTokenInterceptor interceptor = interceptor();
    DispatchResult<GetPublishedReleaseBundleResponse> result =
        dispatch(
            interceptor,
            null,
            new Metadata(),
            UNLISTED_METHOD,
            GetPublishedReleaseBundleRequest.getDefaultInstance(),
            GetPublishedReleaseBundleResponse.getDefaultInstance(),
            GetPublishedReleaseBundleRequest.getDefaultInstance(),
            (request, observer) -> {});

    assertThat(result.dispatched()).isFalse();
    assertThat(result.closedStatus()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(result.response()).isNull();
  }

  private static void assertAllLaunchHandlersDeny(
      AuthTokenInterceptor interceptor, GrpcPeerIdentity peer, Metadata headers) {
    assertDenied(
        interceptor,
        peer,
        headers,
        RESOLVE_LAUNCH_DESCRIPTOR_METHOD,
        ResolveLaunchDescriptorRequest.getDefaultInstance(),
        ResolveLaunchDescriptorResponse.getDefaultInstance(),
        resolveRequest(),
        GameDesignGrpcService::resolveLaunchDescriptor,
        response -> response.getError().getCode());
    assertDenied(
        interceptor,
        peer,
        headers,
        GET_LAUNCH_DESCRIPTOR_METHOD,
        GetLaunchDescriptorRequest.getDefaultInstance(),
        GetLaunchDescriptorResponse.getDefaultInstance(),
        readRequest(),
        GameDesignGrpcService::getLaunchDescriptor,
        response -> response.getError().getCode());
    assertDenied(
        interceptor,
        peer,
        headers,
        GET_COMPLETE_LAUNCH_BINDING_METHOD,
        GetLaunchDescriptorRequest.getDefaultInstance(),
        GetCompleteLaunchBindingResponse.getDefaultInstance(),
        readRequest(),
        GameDesignGrpcService::getCompleteLaunchBinding,
        response -> response.getError().getCode());
  }

  private static <RequestT extends Message, ResponseT extends Message> void assertDenied(
      AuthTokenInterceptor interceptor,
      GrpcPeerIdentity peer,
      Metadata headers,
      String methodName,
      RequestT defaultRequest,
      ResponseT defaultResponse,
      RequestT request,
      HandlerCall<RequestT, ResponseT> handlerCall,
      java.util.function.Function<ResponseT, String> errorCode) {
    Harness harness = harness();
    DispatchResult<ResponseT> result =
        dispatchHandler(
            interceptor,
            harness,
            peer,
            headers,
            methodName,
            defaultRequest,
            defaultResponse,
            request,
            handlerCall);

    assertThat(result.dispatched()).isTrue();
    assertThat(result.closedStatus()).isNull();
    assertThat(result.response()).isNotNull();
    assertThat(errorCode.apply(result.response())).isEqualTo("PERMISSION_DENIED");
    verifyNoInteractions(
        harness.launchDescriptorService(), harness.completeLaunchBindingService());
  }

  private static void assertAllowedHandlerReached(DispatchResult<?> result) {
    assertThat(result.dispatched()).isTrue();
    assertThat(result.closedStatus()).isNull();
    assertThat(result.response()).isNotNull();
  }

  private static <RequestT extends Message, ResponseT extends Message>
      DispatchResult<ResponseT> dispatchHandler(
          AuthTokenInterceptor interceptor,
          Harness harness,
          String peerUri,
          Metadata headers,
          String methodName,
          RequestT defaultRequest,
          ResponseT defaultResponse,
          RequestT request,
          HandlerCall<RequestT, ResponseT> handlerCall) {
    return dispatchHandler(
        interceptor,
        harness,
        peerUri == null ? null : identity(peerUri),
        headers,
        methodName,
        defaultRequest,
        defaultResponse,
        request,
        handlerCall);
  }

  private static <RequestT extends Message, ResponseT extends Message>
      DispatchResult<ResponseT> dispatchHandler(
          AuthTokenInterceptor interceptor,
          Harness harness,
          GrpcPeerIdentity peer,
          Metadata headers,
          String methodName,
          RequestT defaultRequest,
          ResponseT defaultResponse,
          RequestT request,
          HandlerCall<RequestT, ResponseT> handlerCall) {
    return dispatch(
        interceptor,
        peer,
        headers,
        methodName,
        defaultRequest,
        defaultResponse,
        request,
        (message, observer) -> handlerCall.invoke(harness.service(), message, observer));
  }

  private static <RequestT extends Message, ResponseT extends Message>
      DispatchResult<ResponseT> dispatch(
          AuthTokenInterceptor interceptor,
          GrpcPeerIdentity peer,
          Metadata headers,
          String methodName,
          RequestT defaultRequest,
          ResponseT defaultResponse,
          RequestT request,
          java.util.function.BiConsumer<RequestT, StreamObserver<ResponseT>> handler) {
    ServerCall<RequestT, ResponseT> call = mock();
    when(call.getMethodDescriptor())
        .thenReturn(method(methodName, defaultRequest, defaultResponse));
    AtomicReference<Status.Code> closedStatus = new AtomicReference<>();
    AtomicBoolean dispatched = new AtomicBoolean();
    AtomicReference<ResponseT> response = new AtomicReference<>();
    doAnswer(
            invocation -> {
              Status status = invocation.getArgument(0);
              closedStatus.set(status.getCode());
              return null;
            })
        .when(call)
        .close(any(), any());

    ServerCallHandler<RequestT, ResponseT> next =
        (serverCall, ignoredHeaders) -> {
          dispatched.set(true);
          return new ServerCall.Listener<>() {
            @Override
            public void onMessage(RequestT message) {
              handler.accept(
                  message,
                  new StreamObserver<>() {
                    @Override
                    public void onNext(ResponseT value) {
                      response.set(value);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                      closedStatus.set(Status.fromThrowable(throwable).getCode());
                    }

                    @Override
                    public void onCompleted() {}
                  });
            }
          };
        };

    Runnable invoke =
        () -> {
          ServerCall.Listener<RequestT> listener = interceptor.interceptCall(call, headers, next);
          if (listener != null) {
            listener.onMessage(request);
          }
        };
    if (peer == null) {
      invoke.run();
    } else {
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(invoke);
    }
    return new DispatchResult<>(dispatched.get(), closedStatus.get(), response.get());
  }

  private static <RequestT extends Message, ResponseT extends Message>
      MethodDescriptor<RequestT, ResponseT> method(
          String methodName, RequestT defaultRequest, ResponseT defaultResponse) {
    return MethodDescriptor.<RequestT, ResponseT>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName(methodName)
        .setRequestMarshaller(ProtoUtils.marshaller(defaultRequest))
        .setResponseMarshaller(ProtoUtils.marshaller(defaultResponse))
        .build();
  }

  private static AuthTokenInterceptor interceptor() {
    return new AuthTokenInterceptor(mock(JwtUtil.class), Set.copyOf(ALLOWLISTED_METHODS));
  }

  private static AuthTokenInterceptor interceptor(JwtUtil jwtUtil) {
    return new AuthTokenInterceptor(jwtUtil, Set.copyOf(ALLOWLISTED_METHODS));
  }

  private static Harness harness() {
    LaunchDescriptorService launchDescriptorService = mock(LaunchDescriptorService.class);
    CompleteLaunchBindingService completeLaunchBindingService =
        mock(CompleteLaunchBindingService.class);
    GameDesignGrpcService service =
        new GameDesignGrpcService(
            mock(PingService.class),
            mock(RevisionService.class),
            mock(VersionService.class),
            launchDescriptorService,
            completeLaunchBindingService,
            mock(TemplateRemapSetService.class),
            mock(VersionAssetArtifactService.class),
            mock(SettingsAuthorityService.class),
            mock(GameAuthoredHelpTopicService.class),
            new TemporalVersionPublishWorkflowMetadataResolver(Optional.empty(), Optional.empty()),
            new SimpleMeterRegistry());
    ReflectionTestUtils.setField(service, "workloadNamespace", "test");
    return new Harness(service, launchDescriptorService, completeLaunchBindingService);
  }

  private static ResolveLaunchDescriptorRequest resolveRequest() {
    return ResolveLaunchDescriptorRequest.newBuilder()
        .setCanonicalTenantId(TENANT_ID)
        .setGameTemplateId(77L)
        .setControlPlaneRequestId("launch-auth-control-plane")
        .setWorldSlug("silver-march")
        .setAuthoredWorldSourceOperationId(SOURCE_OPERATION_ID)
        .setExpectedAuthoredWorldSourceEvidenceDigest(DIGEST)
        .build();
  }

  private static GetLaunchDescriptorRequest readRequest() {
    return GetLaunchDescriptorRequest.newBuilder()
        .setRequestId(REQUEST_ID)
        .setCanonicalTenantId(TENANT_ID)
        .setWorldSlug("silver-march")
        .setControlPlaneRequestId("launch-auth-control-plane")
        .setExpectedRequestDigest(DIGEST)
        .setExpectedResultDigest(DIGEST)
        .build();
  }

  private static GrpcPeerIdentity identity(String uri) {
    return GrpcPeerIdentity.parseUri(uri).orElseThrow();
  }

  private record Harness(
      GameDesignGrpcService service,
      LaunchDescriptorService launchDescriptorService,
      CompleteLaunchBindingService completeLaunchBindingService) {}

  private record DispatchResult<ResponseT>(
      boolean dispatched, Status.Code closedStatus, ResponseT response) {}

  @FunctionalInterface
  private interface HandlerCall<RequestT, ResponseT> {
    void invoke(
        GameDesignGrpcService service,
        RequestT request,
        StreamObserver<ResponseT> responseObserver);
  }
}
