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
import net.firedevops.firemud.account.v1.RequestEmailLoginOtpResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    verify(channelFactory, times(1)).buildChannel(anyString(), anyInt(), any(), anyBoolean());
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
    Field field =
        net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient.class.getDeclaredField(
            "stub");
    field.setAccessible(true);
    field.set(client, stub);
    return client;
  }
}
