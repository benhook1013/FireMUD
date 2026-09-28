package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeRequest;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeResponse;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipRequest;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipResponse;
import net.firedevops.firemud.account.v1.RequestEmailLoginOtpRequest;
import net.firedevops.firemud.account.v1.RequestEmailLoginOtpResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class AccountClientTest {

  @Test
  void directTextScopeRequestCarriesTypedCallerAndCompleteServerResolvedTarget() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    IssueDirectTextConnectScopeResponse expected =
        IssueDirectTextConnectScopeResponse.newBuilder()
            .setConnectScopeId("account-issued-scope")
            .setConnectScopeExpiresAt("2030-01-01T00:00:00Z")
            .build();
    when(stub.issueDirectTextConnectScope(any(IssueDirectTextConnectScopeRequest.class)))
        .thenReturn(expected);
    AccountClient client = newClient(stub);
    PlayerExecutionContext context = directTextContext("request-unused");
    DirectTextConnectScopeTarget target =
        new DirectTextConnectScopeTarget(
            "22",
            "demo-world",
            "production",
            "4c4b57d8-e3a2-48fe-9977-e7df0fdce901",
            "42d234a2-7487-4dda-a7e5-a3831214328e",
            "SHARED",
            "9",
            7L,
            4L);

    IssueDirectTextConnectScopeResponse actual =
        client.issueDirectTextConnectScope(context, target);

    assertThat(actual).isEqualTo(expected);
    ArgumentCaptor<IssueDirectTextConnectScopeRequest> captor =
        ArgumentCaptor.forClass(IssueDirectTextConnectScopeRequest.class);
    verify(stub).issueDirectTextConnectScope(captor.capture());
    assertThat(captor.getValue().getPlayerContext().getAccountId()).isEqualTo("41");
    assertThat(captor.getValue().getPlayerContext().getSessionId()).isEqualTo("7");
    assertThat(captor.getValue().getTenantId()).isEqualTo("22");
    assertThat(captor.getValue().getWorldSlug()).isEqualTo("demo-world");
    assertThat(captor.getValue().getRealmId()).isEqualTo(target.realmId());
    assertThat(captor.getValue().getPlayableStateNamespaceId())
        .isEqualTo(target.playableStateNamespaceId());
    assertThat(captor.getValue().getPlayableStateScope()).isEqualTo("SHARED");
    assertThat(captor.getValue().getGameInstanceId()).isEqualTo("9");
    assertThat(captor.getValue().getCatalogRevision()).isEqualTo(7L);
    assertThat(captor.getValue().getPointerVersion()).isEqualTo(4L);
  }

  @Test
  void directTextJoinRetryReusesExactContextScopeAndRequestId() throws Exception {
    RetryFixture fixture = newRetryFixture();
    when(fixture
            .initialStub()
            .joinPublicProductionMembership(any(JoinPublicProductionMembershipRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    JoinPublicProductionMembershipResponse expected =
        JoinPublicProductionMembershipResponse.newBuilder()
            .setSuccess(true)
            .setOutcomeCode("CREATED")
            .build();
    when(fixture
            .retryStub()
            .joinPublicProductionMembership(any(JoinPublicProductionMembershipRequest.class)))
        .thenReturn(expected);
    AccountClient client = fixture.client();
    PlayerExecutionContext context = directTextContext("join-request-1");

    JoinPublicProductionMembershipResponse actual =
        client.joinPublicProductionMembership(context, "account-issued-scope", "join-request-1");

    assertThat(actual).isEqualTo(expected);
    ArgumentCaptor<JoinPublicProductionMembershipRequest> firstCaptor =
        ArgumentCaptor.forClass(JoinPublicProductionMembershipRequest.class);
    ArgumentCaptor<JoinPublicProductionMembershipRequest> retryCaptor =
        ArgumentCaptor.forClass(JoinPublicProductionMembershipRequest.class);
    verify(fixture.initialStub()).joinPublicProductionMembership(firstCaptor.capture());
    verify(fixture.retryStub()).joinPublicProductionMembership(retryCaptor.capture());
    assertThat(firstCaptor.getValue()).isEqualTo(retryCaptor.getValue());
    assertThat(retryCaptor.getValue().getConnectScopeId()).isEqualTo("account-issued-scope");
    assertThat(retryCaptor.getValue().getRequestId()).isEqualTo("join-request-1");
    assertThat(retryCaptor.getValue().getPlayerContext().getRequestId())
        .isEqualTo("join-request-1");
    verify(fixture.channelFactory()).buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  private static PlayerExecutionContext directTextContext(String requestId) {
    return PlayerExecutionContext.newBuilder()
        .setAccountId("41")
        .setSessionId("7")
        .setTenantId("22")
        .setRealmId("4c4b57d8-e3a2-48fe-9977-e7df0fdce901")
        .setPlayableStateNamespaceId("42d234a2-7487-4dda-a7e5-a3831214328e")
        .setPlayableStateScope("SHARED")
        .setGameInstanceId("9")
        .setRequestId(requestId)
        .build();
  }

  @Test
  void authenticateReturnsUnavailableWhenStubIsNotInitialized() throws Exception {
    AuthenticateResponse response = newClient(null).authenticate("demo@example.com", "swordfish");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Authentication service unavailable");
  }

  @Test
  void authenticateForReadinessReturnsUnavailableWhenStubIsNotInitialized() throws Exception {
    AuthenticateResponse response =
        newClient(null).authenticateForReadiness("demo@example.com", "swordfish");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Authentication service unavailable");
  }

  @Test
  void requestEmailLoginOtpReturnsUnavailableWhenStubIsNotInitialized() throws Exception {
    RequestEmailLoginOtpResponse response =
        newClient(null).requestEmailLoginOtp("demo@example.com");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Authentication service unavailable");
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNAVAILABLE", "DEADLINE_EXCEEDED"})
  void authenticateNormalizesRetryableTransportFailuresToUnavailable(String statusName)
      throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    String description = "Account authentication rejected: " + statusName;
    when(stub.authenticate(any(AuthenticateRequest.class)))
        .thenThrow(
            new StatusRuntimeException(
                Status.fromCode(Status.Code.valueOf(statusName)).withDescription(description)));
    GrpcChannelFactory channelFactory = newChannelFactory();
    AccountClient client = newClient(stub, channelFactory);

    AuthenticateResponse response = client.authenticate("demo@example.com", "swordfish");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Authentication service unavailable");
    verify(stub, times(1)).authenticate(any(AuthenticateRequest.class));
    verify(channelFactory, times("UNAVAILABLE".equals(statusName) ? 1 : 0))
        .buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void authenticateNormalizesGenericTransportFailuresToUnavailable() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.authenticate(any(AuthenticateRequest.class)))
        .thenThrow(new IllegalStateException("channel failed before a response completed"));
    AccountClient client = newClient(stub);

    AuthenticateResponse response = client.authenticate("demo@example.com", "swordfish");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Authentication service unavailable");
    verify(stub, times(1)).authenticate(any(AuthenticateRequest.class));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "INTERNAL",
        "RESOURCE_EXHAUSTED",
        "UNKNOWN",
        "INVALID_ARGUMENT",
        "UNAUTHENTICATED",
        "PERMISSION_DENIED"
      })
  void authenticatePreservesTerminalGrpcStatusAndUsesGenericMessage(String statusName)
      throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.authenticate(any(AuthenticateRequest.class)))
        .thenThrow(
            new StatusRuntimeException(
                Status.fromCode(Status.Code.valueOf(statusName))
                    .withDescription("upstream credential details")));
    AccountClient client = newClient(stub);

    AuthenticateResponse response = client.authenticate("demo@example.com", "swordfish");

    assertThat(response.getError().getCode()).isEqualTo(statusName);
    assertThat(response.getError().getMessage()).isEqualTo("Authentication request failed");
    verify(stub).authenticate(any(AuthenticateRequest.class));
  }

  @Test
  void authenticateUsesGenericMessageWhenTerminalGrpcDescriptionIsBlank() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.authenticate(any(AuthenticateRequest.class)))
        .thenThrow(
            new StatusRuntimeException(
                Status.fromCode(Status.Code.INVALID_ARGUMENT).withDescription("   ")));
    AccountClient client = newClient(stub);

    AuthenticateResponse response = client.authenticate("demo@example.com", "swordfish");

    assertThat(response.getError().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT.name());
    assertThat(response.getError().getMessage()).isEqualTo("Authentication request failed");
    verify(stub).authenticate(any(AuthenticateRequest.class));
  }

  @Test
  void authenticatePreservesCompletedApplicationErrorDetail() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    AuthenticateResponse expected =
        AuthenticateResponse.newBuilder()
            .setError(
                ErrorDetail.newBuilder()
                    .setCode("AUTH_RETRY_LATER")
                    .setMessage("Try again later")
                    .build())
            .build();
    when(stub.authenticate(any(AuthenticateRequest.class))).thenReturn(expected);
    AccountClient client = newClient(stub);

    AuthenticateResponse response = client.authenticate("demo@example.com", "swordfish");

    assertThat(response).isEqualTo(expected);
    ArgumentCaptor<AuthenticateRequest> requestCaptor =
        ArgumentCaptor.forClass(AuthenticateRequest.class);
    verify(stub).authenticate(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getEmail()).isEqualTo("demo@example.com");
    assertThat(requestCaptor.getValue().getDescriptorForType().findFieldByName("tenant_id"))
        .isNull();
  }

  @Test
  void emailLoginChallengeRequestHasNoTenantScopeField() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.requestEmailLoginOtp(any(RequestEmailLoginOtpRequest.class)))
        .thenReturn(RequestEmailLoginOtpResponse.newBuilder().setAccepted(true).build());
    AccountClient client = newClient(stub);

    client.requestEmailLoginOtp("demo@example.com");

    ArgumentCaptor<RequestEmailLoginOtpRequest> requestCaptor =
        ArgumentCaptor.forClass(RequestEmailLoginOtpRequest.class);
    verify(stub).requestEmailLoginOtp(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getEmail()).isEqualTo("demo@example.com");
    assertThat(requestCaptor.getValue().getDescriptorForType().findFieldByName("tenant_id"))
        .isNull();
  }

  @Test
  void runtimeMembershipCallerUsesCanonicalAccountTenantRequest() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    GetTenantMembershipForRuntimeResponse expected =
        GetTenantMembershipForRuntimeResponse.newBuilder()
            .setAccountId("42")
            .setTenantId("7")
            .setMembershipExists(true)
            .setGameplayAdmissionAllowed(true)
            .setMembershipVersion(12L)
            .setEvaluatedAt("2026-07-31T00:00:00Z")
            .build();
    when(stub.getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class)))
        .thenReturn(expected);
    AccountClient client = newClient(stub);

    GetTenantMembershipForRuntimeResponse actual =
        client.getTenantMembershipForRuntime("42", "7", null);

    ArgumentCaptor<GetTenantMembershipForRuntimeRequest> captor =
        ArgumentCaptor.forClass(GetTenantMembershipForRuntimeRequest.class);
    verify(stub).getTenantMembershipForRuntime(captor.capture());
    assertThat(captor.getValue().getAccountId()).isEqualTo("42");
    assertThat(captor.getValue().getTenantId()).isEqualTo("7");
    assertThat(captor.getValue().getRequestId()).isEmpty();
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  void runtimeMembershipReturnsCanonicalUnavailableWhenStubIsMissing() throws Exception {
    GetTenantMembershipForRuntimeResponse response =
        newClient(null).getTenantMembershipForRuntime("42", "7", "request-1");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Membership authority unavailable");
  }

  @Test
  void realmAccessGrantReturnsCanonicalUnavailableWhenStubIsMissing() throws Exception {
    GetRealmAccessGrantForRuntimeResponse response =
        newClient(null).getRealmAccessGrantForRuntime("42", "7", "world", "realm", "request-1");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Realm grant authority unavailable");
  }

  @Test
  void runtimeMembershipRetriesOnceAfterUnavailableAndPreservesSuccess() throws Exception {
    RetryFixture fixture = newRetryFixture();
    when(fixture
            .initialStub()
            .getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    GetTenantMembershipForRuntimeResponse expected =
        GetTenantMembershipForRuntimeResponse.newBuilder()
            .setAccountId("42")
            .setTenantId("7")
            .setMembershipExists(true)
            .setGameplayAdmissionAllowed(true)
            .setMembershipVersion(12L)
            .build();
    when(fixture
            .retryStub()
            .getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class)))
        .thenReturn(expected);

    GetTenantMembershipForRuntimeResponse actual =
        fixture.client().getTenantMembershipForRuntime("42", "7", "request-1");

    assertThat(actual).isEqualTo(expected);
    verify(fixture.initialStub())
        .getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class));
    verify(fixture.retryStub())
        .getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class));
    verify(fixture.channelFactory()).buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void runtimeMembershipNormalizesExhaustedUnavailableToCanonicalUnavailable() throws Exception {
    RetryFixture fixture = newRetryFixture();
    when(fixture
            .initialStub()
            .getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    when(fixture
            .retryStub()
            .getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

    GetTenantMembershipForRuntimeResponse response =
        fixture.client().getTenantMembershipForRuntime("42", "7", "request-1");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Membership authority unavailable");
    verify(fixture.initialStub())
        .getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class));
    verify(fixture.retryStub())
        .getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class));
    verify(fixture.channelFactory()).buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void runtimeEntitlementsReturnsCanonicalUnavailableWhenStubIsMissing() throws Exception {
    GetTenantEntitlementsForRuntimeResponse response =
        newClient(null).getTenantEntitlementsForRuntime("7", "request-1");

    assertThat(response.getError().getCode()).isEqualTo("ENTITLEMENT_UNAVAILABLE");
    assertThat(response.getError().getMessage()).isEqualTo("Entitlement authority unavailable");
  }

  @Test
  void runtimeEntitlementsNormalizesExhaustedUnavailableToCanonicalUnavailable() throws Exception {
    RetryFixture fixture = newRetryFixture();
    when(fixture
            .initialStub()
            .getTenantEntitlementsForRuntime(any(GetTenantEntitlementsForRuntimeRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    when(fixture
            .retryStub()
            .getTenantEntitlementsForRuntime(any(GetTenantEntitlementsForRuntimeRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

    GetTenantEntitlementsForRuntimeResponse response =
        fixture.client().getTenantEntitlementsForRuntime("7", "request-1");

    assertThat(response.getError().getCode()).isEqualTo("ENTITLEMENT_UNAVAILABLE");
    assertThat(response.getError().getMessage()).isEqualTo("Entitlement authority unavailable");
    verify(fixture.initialStub())
        .getTenantEntitlementsForRuntime(any(GetTenantEntitlementsForRuntimeRequest.class));
    verify(fixture.retryStub())
        .getTenantEntitlementsForRuntime(any(GetTenantEntitlementsForRuntimeRequest.class));
    verify(fixture.channelFactory()).buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void realmAccessGrantNormalizesExhaustedUnavailableToCanonicalUnavailable() throws Exception {
    RetryFixture fixture = newRetryFixture();
    when(fixture
            .initialStub()
            .getRealmAccessGrantForRuntime(any(GetRealmAccessGrantForRuntimeRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    when(fixture
            .retryStub()
            .getRealmAccessGrantForRuntime(any(GetRealmAccessGrantForRuntimeRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

    GetRealmAccessGrantForRuntimeResponse response =
        fixture.client().getRealmAccessGrantForRuntime("42", "7", "world", "realm", "request-1");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Realm grant authority unavailable");
    verify(fixture.initialStub())
        .getRealmAccessGrantForRuntime(any(GetRealmAccessGrantForRuntimeRequest.class));
    verify(fixture.retryStub())
        .getRealmAccessGrantForRuntime(any(GetRealmAccessGrantForRuntimeRequest.class));
    verify(fixture.channelFactory()).buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void runtimeMembershipNormalizesInitialInternalWithoutRetryOrChannelRebuild() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.INTERNAL));
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    AccountClient client = newClient(stub, channelFactory);

    GetTenantMembershipForRuntimeResponse response =
        client.getTenantMembershipForRuntime("42", "7", "request-1");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Membership authority unavailable");
    verify(stub).getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class));
    verify(channelFactory, times(0)).buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void buildStubAppliesInjectedStubCustomizer() {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    AtomicInteger customizeCalls = new AtomicInteger();
    BlockingGrpcStubCustomizer stubCustomizer =
        new BlockingGrpcStubCustomizer() {
          @Override
          public <T extends io.grpc.stub.AbstractStub<T>> T customize(T candidate) {
            customizeCalls.incrementAndGet();
            return candidate;
          }
        };
    AccountClient client =
        new AccountClient(endpoints, grpc, mock(GrpcChannelFactory.class), stubCustomizer);

    invokeBuildStub(client, mock(ManagedChannel.class));

    assertThat(customizeCalls.get()).isEqualTo(1);
  }

  private static void invokeBuildStub(AccountClient client, ManagedChannel channel) {
    try {
      Method method = AccountClient.class.getDeclaredMethod("buildStub", ManagedChannel.class);
      method.setAccessible(true);
      method.invoke(client, channel);
    } catch (ReflectiveOperationException ex) {
      throw new AssertionError(ex);
    }
  }

  private static AccountClient newClient(AccountServiceGrpc.AccountServiceBlockingStub stub)
      throws Exception {
    return newClient(stub, mock(GrpcChannelFactory.class));
  }

  private static GrpcChannelFactory newChannelFactory() throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    when(channelFactory.buildChannel(anyString(), anyInt(), any(), anyBoolean()))
        .thenReturn(mock(ManagedChannel.class));
    return channelFactory;
  }

  private static AccountClient newClient(
      AccountServiceGrpc.AccountServiceBlockingStub stub, GrpcChannelFactory channelFactory)
      throws Exception {
    AccountClient client =
        new AccountClient(
            new ServiceEndpointsProperties(),
            new CommonGrpcClientProperties(),
            channelFactory,
            BlockingGrpcStubCustomizer.noop());
    setStub(client, stub);
    return client;
  }

  private static AccountClient newClientWithRetryStub(
      AccountServiceGrpc.AccountServiceBlockingStub initialStub,
      AccountServiceGrpc.AccountServiceBlockingStub retryStub,
      GrpcChannelFactory channelFactory)
      throws Exception {
    BlockingGrpcStubCustomizer customizer = mock(BlockingGrpcStubCustomizer.class);
    when(customizer.customize(any(AccountServiceGrpc.AccountServiceBlockingStub.class)))
        .thenReturn(retryStub);
    AccountClient client =
        new AccountClient(
            new ServiceEndpointsProperties(),
            new CommonGrpcClientProperties(),
            channelFactory,
            customizer);
    setStub(client, initialStub);
    return client;
  }

  private static RetryFixture newRetryFixture() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub initialStub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    AccountServiceGrpc.AccountServiceBlockingStub retryStub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(initialStub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(initialStub);
    when(retryStub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(retryStub);
    GrpcChannelFactory channelFactory = newChannelFactory();
    return new RetryFixture(
        initialStub,
        retryStub,
        channelFactory,
        newClientWithRetryStub(initialStub, retryStub, channelFactory));
  }

  private record RetryFixture(
      AccountServiceGrpc.AccountServiceBlockingStub initialStub,
      AccountServiceGrpc.AccountServiceBlockingStub retryStub,
      GrpcChannelFactory channelFactory,
      AccountClient client) {}

  private static void setStub(
      AccountClient client, AccountServiceGrpc.AccountServiceBlockingStub stub) throws Exception {
    Field field =
        net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient.class.getDeclaredField(
            "stub");
    field.setAccessible(true);
    field.set(client, stub);
  }
}
