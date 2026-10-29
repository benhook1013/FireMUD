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
import net.firedevops.firemud.account.v1.RequestEmailLoginOtpRequest;
import net.firedevops.firemud.account.v1.RequestEmailLoginOtpResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class AccountClientTest {
  private static final String ACCOUNT_UUID = "00000000-0000-4000-8000-000000000042";

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

  @ParameterizedTest
  @ValueSource(
      strings = {
        "42",
        "not-a-uuid",
        "00000000-0000-0000-0000-000000000000",
        "00000000-0000-4000-8000-00000000000A",
        " 00000000-0000-4000-8000-000000000042"
      })
  void runtimeReadersRejectNonCanonicalAccountsBeforeCallingAccount(String invalidAccount)
      throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    AccountClient client = newClient(stub);
    assertThat(
            client
                .getTenantMembershipForRuntime(invalidAccount, "7", "request-1")
                .getError()
                .getCode())
        .isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(
            client
                .getRealmAccessGrantForRuntime(invalidAccount, "7", "world", "realm", "request-1")
                .getError()
                .getCode())
        .isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    org.mockito.Mockito.verifyNoInteractions(stub);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "42",
        "00000000-0000-0000-0000-000000000000",
        "00000000-0000-4000-8000-000000000043"
      })
  void runtimeMembershipRejectsUncorrelatedAccountResult(String returnedAccount) throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class)))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId(returnedAccount)
                .setTenantId("7")
                .setMembershipExists(true)
                .setGameplayAdmissionAllowed(true)
                .build());
    AccountClient client = newClient(stub);
    assertThat(
            client
                .getTenantMembershipForRuntime(ACCOUNT_UUID, "7", "request-1")
                .getError()
                .getCode())
        .isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    verify(stub, times(1))
        .getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class));
  }

  @Test
  void realmGrantRequiresExactAccountTenantWorldAndRealmResult() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    var exact =
        GetRealmAccessGrantForRuntimeResponse.newBuilder()
            .setAccountId(ACCOUNT_UUID)
            .setTenantId("7")
            .setWorldSlug("world")
            .setRealmSlug("realm")
            .setGranted(true)
            .setGrantVersion(4L)
            .build();
    AccountClient client = newClient(stub);
    for (var result :
        java.util.List.of(
            exact.toBuilder().setAccountId("42").build(),
            exact.toBuilder().setAccountId("00000000-0000-4000-8000-000000000043").build(),
            exact.toBuilder().setTenantId("8").build(),
            exact.toBuilder().setWorldSlug("other").build(),
            exact.toBuilder().setRealmSlug("other").build())) {
      when(stub.getRealmAccessGrantForRuntime(any(GetRealmAccessGrantForRuntimeRequest.class)))
          .thenReturn(result);
      assertThat(
              client
                  .getRealmAccessGrantForRuntime(ACCOUNT_UUID, "7", "world", "realm", "request-1")
                  .getError()
                  .getCode())
          .isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    }
    when(stub.getRealmAccessGrantForRuntime(any(GetRealmAccessGrantForRuntimeRequest.class)))
        .thenReturn(exact);
    assertThat(
            client.getRealmAccessGrantForRuntime(ACCOUNT_UUID, "7", "world", "realm", "request-1"))
        .isEqualTo(exact);
  }

  @Test
  void runtimeMembershipCallerUsesCanonicalAccountTenantRequest() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    GetTenantMembershipForRuntimeResponse expected =
        GetTenantMembershipForRuntimeResponse.newBuilder()
            .setAccountId(ACCOUNT_UUID)
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
        client.getTenantMembershipForRuntime(ACCOUNT_UUID, "7", null);

    ArgumentCaptor<GetTenantMembershipForRuntimeRequest> captor =
        ArgumentCaptor.forClass(GetTenantMembershipForRuntimeRequest.class);
    verify(stub).getTenantMembershipForRuntime(captor.capture());
    assertThat(captor.getValue().getAccountId()).isEqualTo(ACCOUNT_UUID);
    assertThat(captor.getValue().getTenantId()).isEqualTo("7");
    assertThat(captor.getValue().getRequestId()).isEmpty();
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  void runtimeMembershipReturnsCanonicalUnavailableWhenStubIsMissing() throws Exception {
    GetTenantMembershipForRuntimeResponse response =
        newClient(null).getTenantMembershipForRuntime(ACCOUNT_UUID, "7", "request-1");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Membership authority unavailable");
  }

  @Test
  void realmAccessGrantReturnsCanonicalUnavailableWhenStubIsMissing() throws Exception {
    GetRealmAccessGrantForRuntimeResponse response =
        newClient(null)
            .getRealmAccessGrantForRuntime(ACCOUNT_UUID, "7", "world", "realm", "request-1");

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
            .setAccountId(ACCOUNT_UUID)
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
        fixture.client().getTenantMembershipForRuntime(ACCOUNT_UUID, "7", "request-1");

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
        fixture.client().getTenantMembershipForRuntime(ACCOUNT_UUID, "7", "request-1");

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
        fixture
            .client()
            .getRealmAccessGrantForRuntime(ACCOUNT_UUID, "7", "world", "realm", "request-1");

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
        client.getTenantMembershipForRuntime(ACCOUNT_UUID, "7", "request-1");

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
