package unit.net.firedevops.firemud.gamedesign.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.GrpcAuthProperties;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameSessionTenantAssociationRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantIdentity;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import net.firedevops.firemud.gamedesign.service.impl.TenantIdentityGrpcService;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesRequest;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesResponse;
import net.firedevops.firemud.gamedesign.v1.PingRequest;
import net.firedevops.firemud.gamedesign.v1.PingResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationResponse;
import net.firedevops.firemud.gamedesign.v1.ResolvePublishedRealmEntryPolicyRequest;
import net.firedevops.firemud.gamedesign.v1.ResolvePublishedRealmEntryPolicyResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveRuntimeTenantIdentityRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveRuntimeTenantIdentityResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import tools.jackson.databind.ObjectMapper;

class TenantIdentityGrpcAuthWiringTest {
  private static final String FRESH_CREATION_METHOD =
      "gamedesign.v1.TenantIdentityService/ResolveFreshTenantCreation";
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
                  mock(GameTenantCreationRepository.class),
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
                  mock(GameTenantCreationRepository.class),
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
                  mock(GameTenantCreationRepository.class),
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
                  mock(GameTenantCreationRepository.class),
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
                  mock(GameTenantCreationRepository.class),
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
                  mock(GameTenantCreationRepository.class),
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
                      REALM_POLICY_SET_METHOD,
                      FRESH_CREATION_METHOD);
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
                        REALM_POLICY_SET_METHOD,
                        FRESH_CREATION_METHOD));
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

  @Nested
  class FreshCreation {
    private static final String FRESH_CREATION_METHOD =
        "gamedesign.v1.TenantIdentityService/ResolveFreshTenantCreation";
    private static final String OTHER_METHOD = "gamedesign.v1.TenantIdentityService/UnlistedMethod";
    private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
    private static final String GAME_SESSION_URI =
        "spiffe://firemud/ns/test/sa/game-session-service";
    private static final String SOURCE_KEY = "fresh-owner-key-91";
    private static final UUID REQUEST_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID OPERATION_ID =
        UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID TENANT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String REQUEST_DIGEST =
        GameTenantCreationDigest.requestDigest("test", REQUEST_ID, SOURCE_KEY, "Fresh Realm", null);

    @Test
    void defaultAndProductionProfilesAllowOwnerReadsAndRequireClientTls() throws IOException {
      for (String file : List.of("application.yml", "application-prod.yml")) {
        GrpcConfiguration config = load(file);
        assertThat(config.publicMethods())
            .containsExactly(
                TENANT_METHOD,
                ASSOCIATION_METHOD,
                REALM_POLICY_METHOD,
                REALM_POLICY_SET_METHOD,
                FRESH_CREATION_METHOD);
        assertThat(config.clientAuth()).isEqualTo("REQUIRE");
      }
    }

    @Test
    void allowlistedNoTokenReadStillRequiresExactOwnerPeerBeforeOwnerRead() {
      withConfiguredInterceptor(
          interceptor -> {
            GameTenantCreationRepository repository = mock(GameTenantCreationRepository.class);
            TenantIdentityGrpcService service =
                new TenantIdentityGrpcService(
                    repository,
                    mock(GameRepository.class),
                    mock(GameSessionTenantAssociationRepository.class),
                    mock(PublishedReleaseBundleService.class),
                    "test",
                    new ObjectMapper());
            FreshTenantCreationEvidence evidence = evidence("test", REQUEST_DIGEST);
            when(repository.read(REQUEST_ID, "test")).thenReturn(Optional.of(evidence));

            DispatchResult account = dispatch(interceptor, service, ACCOUNT_URI);
            assertThat(account.observer().failure).isNull();
            assertThat(account.observer().completed).isTrue();
            assertThat(account.observer().response.getEvidenceDigest())
                .isEqualTo(evidence.evidenceDigest());
            assertThat(account.handlerDispatched()).isTrue();
            verify(repository).read(REQUEST_ID, "test");

            GameTenantCreationRepository gameSessionRepository =
                mock(GameTenantCreationRepository.class);
            TenantIdentityGrpcService gameSessionService =
                new TenantIdentityGrpcService(
                    gameSessionRepository,
                    mock(GameRepository.class),
                    mock(GameSessionTenantAssociationRepository.class),
                    mock(PublishedReleaseBundleService.class),
                    "test",
                    new ObjectMapper());
            when(gameSessionRepository.read(REQUEST_ID, "test")).thenReturn(Optional.of(evidence));

            DispatchResult gameSession =
                dispatch(interceptor, gameSessionService, GAME_SESSION_URI);
            assertThat(gameSession.observer().failure).isNull();
            assertThat(gameSession.observer().completed).isTrue();
            assertThat(gameSession.observer().response.getEvidenceDigest())
                .isEqualTo(evidence.evidenceDigest());
            assertThat(gameSession.handlerDispatched()).isTrue();
            verify(gameSessionRepository).read(REQUEST_ID, "test");

            GameTenantCreationRepository unauthorizedRepository =
                mock(GameTenantCreationRepository.class);
            TenantIdentityGrpcService unauthorizedService =
                new TenantIdentityGrpcService(
                    unauthorizedRepository,
                    mock(GameRepository.class),
                    mock(GameSessionTenantAssociationRepository.class),
                    mock(PublishedReleaseBundleService.class),
                    "test",
                    new ObjectMapper());
            DispatchResult unauthorized =
                dispatch(
                    interceptor,
                    unauthorizedService,
                    "spiffe://firemud/ns/test/sa/entity-management-service");
            assertThat(unauthorized.observer().failure).isEqualTo(Status.Code.PERMISSION_DENIED);
            assertThat(unauthorized.observer().response).isNull();
            assertThat(unauthorized.observer().completed).isFalse();
            assertThat(unauthorized.handlerDispatched()).isTrue();
            verifyNoInteractions(unauthorizedRepository);
          });
    }

    @Test
    void jwtBypassIncludesFreshReadAndRejectsUnlistedMethod() throws IOException {
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
                    .containsExactly(
                        TENANT_METHOD,
                        ASSOCIATION_METHOD,
                        REALM_POLICY_METHOD,
                        REALM_POLICY_SET_METHOD,
                        FRESH_CREATION_METHOD);
                action.accept(context.getBean(AuthTokenInterceptor.class));
              });
    }

    private static DispatchResult dispatch(
        AuthTokenInterceptor interceptor, TenantIdentityGrpcService service, String peerUri) {
      ServerCall<ResolveFreshTenantCreationRequest, ResolveFreshTenantCreationResponse> call =
          mock();
      when(call.getMethodDescriptor()).thenReturn(method(FRESH_CREATION_METHOD));
      AtomicBoolean dispatched = new AtomicBoolean();
      Observer observer = new Observer();
      ServerCallHandler<ResolveFreshTenantCreationRequest, ResolveFreshTenantCreationResponse>
          next =
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
      ServerCall<ResolveFreshTenantCreationRequest, ResolveFreshTenantCreationResponse> call =
          mock();
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
      ServerCallHandler<ResolveFreshTenantCreationRequest, ResolveFreshTenantCreationResponse>
          next =
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
}
