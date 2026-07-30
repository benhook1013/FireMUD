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
import net.firedevops.firemud.account.v1.EnsurePublicProductionPlayerMembershipRequest;
import net.firedevops.firemud.account.v1.EnsurePublicProductionPlayerMembershipResponse;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
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

  @Test
  void authenticateReturnsUnavailableWhenStubIsNotInitialized() throws Exception {
    AuthenticateResponse response =
        newClient(null).authenticate("22", "demo@example.com", "swordfish");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Authentication service unavailable");
  }

  @Test
  void authenticateForReadinessReturnsUnavailableWhenStubIsNotInitialized() throws Exception {
    AuthenticateResponse response =
        newClient(null).authenticateForReadiness("22", "demo@example.com", "swordfish");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Authentication service unavailable");
  }

  @Test
  void requestEmailLoginOtpReturnsUnavailableWhenStubIsNotInitialized() throws Exception {
    RequestEmailLoginOtpResponse response =
        newClient(null).requestEmailLoginOtp("22", "demo@example.com");

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
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    when(channelFactory.buildChannel(anyString(), anyInt(), any(), anyBoolean()))
        .thenReturn(mock(ManagedChannel.class));
    AccountClient client = newClient(stub, channelFactory);

    AuthenticateResponse response = client.authenticate("22", "demo@example.com", "swordfish");

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

    AuthenticateResponse response = client.authenticate("22", "demo@example.com", "swordfish");

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

    AuthenticateResponse response = client.authenticate("22", "demo@example.com", "swordfish");

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

    AuthenticateResponse response = client.authenticate("22", "demo@example.com", "swordfish");

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

    AuthenticateResponse response = client.authenticate("22", "demo@example.com", "swordfish");

    assertThat(response).isEqualTo(expected);
    verify(stub).authenticate(any(AuthenticateRequest.class));
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
  void publicMembershipEnsureReturnsCanonicalUnavailableWhenStubIsMissing() throws Exception {
    EnsurePublicProductionPlayerMembershipResponse response =
        newClient(null)
            .ensurePublicProductionPlayerMembership("42", "7", "world", "realm", "request-1");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Membership authority unavailable");
  }

  @Test
  void runtimeMembershipRetriesOnceAfterUnavailableAndPreservesSuccess() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub initialStub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    AccountServiceGrpc.AccountServiceBlockingStub retryStub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(initialStub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(initialStub);
    when(retryStub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(retryStub);
    when(initialStub.getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    GetTenantMembershipForRuntimeResponse expected =
        GetTenantMembershipForRuntimeResponse.newBuilder()
            .setAccountId("42")
            .setTenantId("7")
            .setGameplayAdmissionAllowed(true)
            .setMembershipVersion(12L)
            .build();
    when(retryStub.getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class)))
        .thenReturn(expected);
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    when(channelFactory.buildChannel(anyString(), anyInt(), any(), anyBoolean()))
        .thenReturn(mock(ManagedChannel.class));
    AccountClient client = newClientWithRetryStub(initialStub, retryStub, channelFactory);

    GetTenantMembershipForRuntimeResponse actual =
        client.getTenantMembershipForRuntime("42", "7", "request-1");

    assertThat(actual).isEqualTo(expected);
    verify(initialStub)
        .getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class));
    verify(retryStub)
        .getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class));
    verify(channelFactory).buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void runtimeMembershipNormalizesExhaustedUnavailableToCanonicalUnavailable() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub initialStub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    AccountServiceGrpc.AccountServiceBlockingStub retryStub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(initialStub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(initialStub);
    when(retryStub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(retryStub);
    when(initialStub.getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    when(retryStub.getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    when(channelFactory.buildChannel(anyString(), anyInt(), any(), anyBoolean()))
        .thenReturn(mock(ManagedChannel.class));
    AccountClient client = newClientWithRetryStub(initialStub, retryStub, channelFactory);

    GetTenantMembershipForRuntimeResponse response =
        client.getTenantMembershipForRuntime("42", "7", "request-1");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Membership authority unavailable");
    verify(initialStub)
        .getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class));
    verify(retryStub)
        .getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class));
    verify(channelFactory).buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void realmAccessGrantNormalizesExhaustedUnavailableToCanonicalUnavailable() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub initialStub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    AccountServiceGrpc.AccountServiceBlockingStub retryStub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(initialStub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(initialStub);
    when(retryStub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(retryStub);
    when(initialStub.getRealmAccessGrantForRuntime(any(GetRealmAccessGrantForRuntimeRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    when(retryStub.getRealmAccessGrantForRuntime(any(GetRealmAccessGrantForRuntimeRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    when(channelFactory.buildChannel(anyString(), anyInt(), any(), anyBoolean()))
        .thenReturn(mock(ManagedChannel.class));
    AccountClient client = newClientWithRetryStub(initialStub, retryStub, channelFactory);

    GetRealmAccessGrantForRuntimeResponse response =
        client.getRealmAccessGrantForRuntime("42", "7", "world", "realm", "request-1");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Realm grant authority unavailable");
    verify(initialStub)
        .getRealmAccessGrantForRuntime(any(GetRealmAccessGrantForRuntimeRequest.class));
    verify(retryStub)
        .getRealmAccessGrantForRuntime(any(GetRealmAccessGrantForRuntimeRequest.class));
    verify(channelFactory).buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void publicMembershipEnsureNormalizesExhaustedUnavailableToCanonicalUnavailable()
      throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub initialStub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    AccountServiceGrpc.AccountServiceBlockingStub retryStub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(initialStub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(initialStub);
    when(retryStub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(retryStub);
    when(initialStub.ensurePublicProductionPlayerMembership(
            any(EnsurePublicProductionPlayerMembershipRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    when(retryStub.ensurePublicProductionPlayerMembership(
            any(EnsurePublicProductionPlayerMembershipRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    when(channelFactory.buildChannel(anyString(), anyInt(), any(), anyBoolean()))
        .thenReturn(mock(ManagedChannel.class));
    AccountClient client = newClientWithRetryStub(initialStub, retryStub, channelFactory);

    EnsurePublicProductionPlayerMembershipResponse response =
        client.ensurePublicProductionPlayerMembership("42", "7", "world", "realm", "request-1");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Membership authority unavailable");
    verify(initialStub)
        .ensurePublicProductionPlayerMembership(
            any(EnsurePublicProductionPlayerMembershipRequest.class));
    verify(retryStub)
        .ensurePublicProductionPlayerMembership(
            any(EnsurePublicProductionPlayerMembershipRequest.class));
    verify(channelFactory).buildChannel(anyString(), anyInt(), any(), anyBoolean());
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

  private static void setStub(
      AccountClient client, AccountServiceGrpc.AccountServiceBlockingStub stub) throws Exception {
    Field field =
        net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient.class.getDeclaredField(
            "stub");
    field.setAccessible(true);
    field.set(client, stub);
  }
}
