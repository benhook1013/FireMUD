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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.GrpcAuthProperties;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameSessionTenantAssociationRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantIdentity;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import net.firedevops.firemud.gamedesign.service.impl.TenantIdentityGrpcService;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesRequest;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesResponse;
import net.firedevops.firemud.gamedesign.v1.PingRequest;
import net.firedevops.firemud.gamedesign.v1.PingResponse;
import net.firedevops.firemud.gamedesign.v1.ResolvePublishedRealmEntryPolicyRequest;
import net.firedevops.firemud.gamedesign.v1.ResolvePublishedRealmEntryPolicyResponse;
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
import tools.jackson.databind.ObjectMapper;

class TenantIdentityGrpcAuthWiringTest {
  private static final String TENANT_METHOD =
      "gamedesign.v1.TenantIdentityService/ResolveRuntimeTenantIdentity";
  private static final String ASSOCIATION_METHOD =
      "gamedesign.v1.TenantIdentityService/ResolveLegacyGameSessionTenantAssociation";
  private static final String REALM_POLICY_METHOD =
      "gamedesign.v1.TenantIdentityService/ResolvePublishedRealmEntryPolicy";
  private static final String REALM_POLICY_SET_METHOD =
      "gamedesign.v1.TenantIdentityService/ListPublishedRealmEntryPolicies";
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
  private static final MethodDescriptor<
          ResolvePublishedRealmEntryPolicyRequest, ResolvePublishedRealmEntryPolicyResponse>
      REALM_POLICY_METHOD_DESCRIPTOR =
          unaryMethod(
              REALM_POLICY_METHOD,
              ResolvePublishedRealmEntryPolicyRequest.getDefaultInstance(),
              ResolvePublishedRealmEntryPolicyResponse.getDefaultInstance());
  private static final MethodDescriptor<
          ListPublishedRealmEntryPoliciesRequest, ListPublishedRealmEntryPoliciesResponse>
      REALM_POLICY_SET_METHOD_DESCRIPTOR =
          unaryMethod(
              REALM_POLICY_SET_METHOD,
              ListPublishedRealmEntryPoliciesRequest.getDefaultInstance(),
              ListPublishedRealmEntryPoliciesResponse.getDefaultInstance());

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
                  repository,
                  mock(GameSessionTenantAssociationRepository.class),
                  mock(PublishedReleaseBundleService.class),
                  "test",
                  new ObjectMapper());

          DispatchResult result = dispatchTenantMethod(interceptor, service, GAME_SESSION_URI);

          assertThat(result.observer().failure).isFalse();
          assertThat(result.observer().completed).isTrue();
          assertThat(result.observer().response.getCanonicalTenantId()).isEqualTo(TENANT_UUID);
          assertThat(result.handlerDispatched()).isTrue();
          verify(repository).findRuntimeTenantIdentityByCanonicalTenantId(tenantId);
        });
  }

  @Test
  void noTokenWithValidSameNamespacePeerReachesPublishedRealmPolicyRead() {
    withConfiguredInterceptor(
        interceptor -> {
          PublishedReleaseBundleService publishedReleaseBundleService =
              mock(PublishedReleaseBundleService.class);
          UUID tenantId = UUID.fromString(TENANT_UUID);
          RealmEntryPolicy policy =
              RealmEntryPolicy.parse(
                  "{\"schemaVersion\":1,\"worldSlug\":\"earth\",\"worldDisplayName\":\"Earth\","
                      + "\"realmSlug\":\"main\",\"realmDisplayName\":\"Main Realm\","
                      + "\"visible\":true,\"publicProduction\":false,\"stateScope\":\"SHARED\","
                      + "\"entryPolicy\":\"PRESEEDED_ONLY\"}",
                  new ObjectMapper());
          var evidence =
              PublishedRealmEntryPolicyEvidence.create(
                  UUID.fromString("22222222-2222-4222-8222-222222222222"),
                  tenantId,
                  "NEW_GAME_ROW",
                  42L,
                  "source-game-key",
                  7L,
                  3,
                  18L,
                  "sha256:" + "1".repeat(64),
                  "publish-workflow-3",
                  "manifest-3",
                  policy,
                  new ObjectMapper());
          when(publishedReleaseBundleService.resolvePublishedRealmEntryPolicy(
                  tenantId, 7L, "earth", "main"))
              .thenReturn(evidence);
          TenantIdentityGrpcService service =
              new TenantIdentityGrpcService(
                  mock(GameRepository.class),
                  mock(GameSessionTenantAssociationRepository.class),
                  publishedReleaseBundleService,
                  "test",
                  new ObjectMapper());

          PolicyDispatchResult result =
              dispatchRealmPolicyMethod(interceptor, service, GAME_SESSION_URI);

          assertThat(result.observer().failure).isFalse();
          assertThat(result.observer().completed).isTrue();
          assertThat(result.observer().response.getPolicyId())
              .isEqualTo(evidence.policyId().toString());
          assertThat(result.handlerDispatched()).isTrue();
          verify(publishedReleaseBundleService)
              .resolvePublishedRealmEntryPolicy(tenantId, 7L, "earth", "main");
        });
  }

  @Test
  void noTokenWithValidSameNamespacePeerReachesCompleteRealmPolicySetRead() {
    withConfiguredInterceptor(
        interceptor -> {
          PublishedReleaseBundleService publishedReleaseBundleService =
              mock(PublishedReleaseBundleService.class);
          UUID tenantId = UUID.fromString(TENANT_UUID);
          ObjectMapper mapper = new ObjectMapper();
          RealmEntryPolicy policy =
              RealmEntryPolicy.parse(
                  "{\"schemaVersion\":1,\"worldSlug\":\"earth\",\"worldDisplayName\":\"Earth\","
                      + "\"realmSlug\":\"main\",\"realmDisplayName\":\"Main Realm\","
                      + "\"visible\":true,\"publicProduction\":true,\"stateScope\":\"SHARED\","
                      + "\"entryPolicy\":\"PRESEEDED_ONLY\"}",
                  mapper);
          var policyEvidence =
              PublishedRealmEntryPolicyEvidence.create(
                  UUID.fromString("22222222-2222-4222-8222-222222222222"),
                  tenantId,
                  "NEW_GAME_ROW",
                  42L,
                  "source-game-key",
                  7L,
                  3,
                  18L,
                  "sha256:" + "1".repeat(64),
                  "publish-workflow-3",
                  "manifest-3",
                  policy,
                  mapper);
          var setEvidence =
              PublishedRealmEntryPolicySetEvidence.create(
                  tenantId,
                  7L,
                  3,
                  policyEvidence.releaseBundleIdentity(),
                  "publish-workflow-3",
                  "manifest-3",
                  List.of(policyEvidence),
                  mapper);
          when(publishedReleaseBundleService.listPublishedRealmEntryPolicies(tenantId, 7L))
              .thenReturn(setEvidence);
          TenantIdentityGrpcService service =
              new TenantIdentityGrpcService(
                  mock(GameRepository.class),
                  mock(GameSessionTenantAssociationRepository.class),
                  publishedReleaseBundleService,
                  "test",
                  new ObjectMapper());

          PolicySetDispatchResult result =
              dispatchRealmPolicySetMethod(interceptor, service, GAME_SESSION_URI);

          assertThat(result.observer().failure).isFalse();
          assertThat(result.observer().completed).isTrue();
          assertThat(result.observer().response.getPolicyCount()).isEqualTo(1);
          assertThat(result.observer().response.getPolicySetDigest())
              .isEqualTo(setEvidence.policySetDigest());
          assertThat(result.observer().response.getPolicies(0).getPublicProduction()).isTrue();
          assertThat(result.handlerDispatched()).isTrue();
          verify(publishedReleaseBundleService).listPublishedRealmEntryPolicies(tenantId, 7L);
        });
  }

  @Test
  void publishedRealmPolicyMethodIsDeniedBeforeOwnerReadForWrongPeer() {
    withConfiguredInterceptor(
        interceptor -> {
          PublishedReleaseBundleService publishedReleaseBundleService =
              mock(PublishedReleaseBundleService.class);
          TenantIdentityGrpcService service =
              new TenantIdentityGrpcService(
                  mock(GameRepository.class),
                  mock(GameSessionTenantAssociationRepository.class),
                  publishedReleaseBundleService,
                  "test",
                  new ObjectMapper());

          PolicyDispatchResult wrong =
              dispatchRealmPolicyMethod(
                  interceptor, service, "spiffe://firemud/ns/test/sa/account-service");

          assertThat(wrong.observer().failure).isTrue();
          assertThat(wrong.observer().statusCode).isEqualTo(Status.Code.PERMISSION_DENIED);
          assertThat(wrong.handlerDispatched()).isTrue();
          org.mockito.Mockito.verifyNoInteractions(publishedReleaseBundleService);
        });
  }

  @Test
  void completeRealmPolicySetMethodIsDeniedBeforeOwnerReadForWrongPeer() {
    withConfiguredInterceptor(
        interceptor -> {
          PublishedReleaseBundleService publishedReleaseBundleService =
              mock(PublishedReleaseBundleService.class);
          TenantIdentityGrpcService service =
              new TenantIdentityGrpcService(
                  mock(GameRepository.class),
                  mock(GameSessionTenantAssociationRepository.class),
                  publishedReleaseBundleService,
                  "test",
                  new ObjectMapper());

          PolicySetDispatchResult wrong =
              dispatchRealmPolicySetMethod(
                  interceptor, service, "spiffe://firemud/ns/test/sa/account-service");

          assertThat(wrong.observer().failure).isTrue();
          assertThat(wrong.observer().statusCode).isEqualTo(Status.Code.PERMISSION_DENIED);
          assertThat(wrong.handlerDispatched()).isTrue();
          org.mockito.Mockito.verifyNoInteractions(publishedReleaseBundleService);
        });
  }

  @Test
  void noTokenWithAbsentOrWrongPeerIsDeniedBeforeOwnerRead() {
    withConfiguredInterceptor(
        interceptor -> {
          GameRepository repository = mock(GameRepository.class);
          TenantIdentityGrpcService service =
              new TenantIdentityGrpcService(
                  repository,
                  mock(GameSessionTenantAssociationRepository.class),
                  mock(PublishedReleaseBundleService.class),
                  "test",
                  new ObjectMapper());

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

  private static PolicyDispatchResult dispatchRealmPolicyMethod(
      AuthTokenInterceptor interceptor, TenantIdentityGrpcService service, String peerUri) {
    @SuppressWarnings({"rawtypes", "unchecked"})
    ServerCall<ResolvePublishedRealmEntryPolicyRequest, ResolvePublishedRealmEntryPolicyResponse>
        call = mock(ServerCall.class);
    when(call.getMethodDescriptor()).thenReturn(REALM_POLICY_METHOD_DESCRIPTOR);
    AtomicBoolean handlerDispatched = new AtomicBoolean();
    PolicyTestObserver observer = new PolicyTestObserver();
    ServerCallHandler<
            ResolvePublishedRealmEntryPolicyRequest, ResolvePublishedRealmEntryPolicyResponse>
        next =
            new ServerCallHandler<>() {
              @Override
              public ServerCall.Listener<ResolvePublishedRealmEntryPolicyRequest> startCall(
                  ServerCall<
                          ResolvePublishedRealmEntryPolicyRequest,
                          ResolvePublishedRealmEntryPolicyResponse>
                      serverCall,
                  Metadata headers) {
                handlerDispatched.set(true);
                return new ServerCall.Listener<>() {
                  @Override
                  public void onMessage(ResolvePublishedRealmEntryPolicyRequest request) {
                    service.resolvePublishedRealmEntryPolicy(request, observer);
                  }
                };
              }
            };
    ResolvePublishedRealmEntryPolicyRequest request =
        ResolvePublishedRealmEntryPolicyRequest.newBuilder()
            .setCanonicalTenantId(TENANT_UUID)
            .setVersionId(7L)
            .setWorldSlug("earth")
            .setRealmSlug("main")
            .build();
    Runnable dispatch =
        () -> {
          ServerCall.Listener<ResolvePublishedRealmEntryPolicyRequest> listener =
              interceptor.interceptCall(call, new Metadata(), next);
          listener.onMessage(request);
        };

    if (peerUri == null) {
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, null).run(dispatch);
    } else {
      GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(peerUri).orElseThrow();
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(dispatch);
    }
    return new PolicyDispatchResult(observer, handlerDispatched.get());
  }

  private static PolicySetDispatchResult dispatchRealmPolicySetMethod(
      AuthTokenInterceptor interceptor, TenantIdentityGrpcService service, String peerUri) {
    @SuppressWarnings({"rawtypes", "unchecked"})
    ServerCall<ListPublishedRealmEntryPoliciesRequest, ListPublishedRealmEntryPoliciesResponse>
        call = mock(ServerCall.class);
    when(call.getMethodDescriptor()).thenReturn(REALM_POLICY_SET_METHOD_DESCRIPTOR);
    AtomicBoolean handlerDispatched = new AtomicBoolean();
    PolicySetTestObserver observer = new PolicySetTestObserver();
    ServerCallHandler<
            ListPublishedRealmEntryPoliciesRequest, ListPublishedRealmEntryPoliciesResponse>
        next =
            new ServerCallHandler<>() {
              @Override
              public ServerCall.Listener<ListPublishedRealmEntryPoliciesRequest> startCall(
                  ServerCall<
                          ListPublishedRealmEntryPoliciesRequest,
                          ListPublishedRealmEntryPoliciesResponse>
                      serverCall,
                  Metadata headers) {
                handlerDispatched.set(true);
                return new ServerCall.Listener<>() {
                  @Override
                  public void onMessage(ListPublishedRealmEntryPoliciesRequest request) {
                    service.listPublishedRealmEntryPolicies(request, observer);
                  }
                };
              }
            };
    ListPublishedRealmEntryPoliciesRequest request =
        ListPublishedRealmEntryPoliciesRequest.newBuilder()
            .setCanonicalTenantId(TENANT_UUID)
            .setVersionId(7L)
            .build();
    Runnable dispatch =
        () -> {
          ServerCall.Listener<ListPublishedRealmEntryPoliciesRequest> listener =
              interceptor.interceptCall(call, new Metadata(), next);
          listener.onMessage(request);
        };

    if (peerUri == null) {
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, null).run(dispatch);
    } else {
      GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(peerUri).orElseThrow();
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(dispatch);
    }
    return new PolicySetDispatchResult(observer, handlerDispatched.get());
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
                  .containsExactly(
                      TENANT_METHOD,
                      ASSOCIATION_METHOD,
                      REALM_POLICY_METHOD,
                      REALM_POLICY_SET_METHOD);
              action.accept(context.getBean(AuthTokenInterceptor.class));
            });
  }

  @Test
  void productionConfigurationExemptsExactlyTheOwnerReadMethods() throws IOException {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class,
                CommonSecurityAutoConfiguration.class))
        .withInitializer(
            context -> {
              try {
                PropertySource<?> production =
                    new YamlPropertySourceLoader()
                        .load(
                            "game-design-production",
                            new FileSystemResource("src/main/resources/application-prod.yml"))
                        .get(0);
                context.getEnvironment().getPropertySources().addLast(production);
              } catch (IOException exception) {
                throw new IllegalStateException(
                    "Could not load Game Design production config", exception);
              }
            })
        .withPropertyValues("firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234")
        .run(
            context ->
                assertThat(context.getBean(GrpcAuthProperties.class).getPublicMethods())
                    .containsExactly(
                        TENANT_METHOD,
                        ASSOCIATION_METHOD,
                        REALM_POLICY_METHOD,
                        REALM_POLICY_SET_METHOD));
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

  private record PolicyDispatchResult(PolicyTestObserver observer, boolean handlerDispatched) {}

  private record PolicySetDispatchResult(
      PolicySetTestObserver observer, boolean handlerDispatched) {}

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

  private static final class PolicyTestObserver
      implements StreamObserver<ResolvePublishedRealmEntryPolicyResponse> {
    private ResolvePublishedRealmEntryPolicyResponse response;
    private Status.Code statusCode;
    private boolean failure;
    private boolean completed;

    @Override
    public void onNext(ResolvePublishedRealmEntryPolicyResponse value) {
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

  private static final class PolicySetTestObserver
      implements StreamObserver<ListPublishedRealmEntryPoliciesResponse> {
    private ListPublishedRealmEntryPoliciesResponse response;
    private Status.Code statusCode;
    private boolean failure;
    private boolean completed;

    @Override
    public void onNext(ListPublishedRealmEntryPoliciesResponse value) {
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
