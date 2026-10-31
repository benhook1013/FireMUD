package unit.net.firedevops.firemud.entitymanagement.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.entitymanagement.client.DemoAccountAuthenticationClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class DemoAccountAuthenticationClientTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(DemoAccountAuthenticationClientConfiguration.class)
          .withPropertyValues(
              "firemud.smoke.seed-demo-runtime.enabled=true", "firemud.grpc.workload-namespace=");

  @Test
  void usesCredentialAuthenticationAndExactReturnedUuidWithoutNumericIdentity() throws Exception {
    var stub = mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5, TimeUnit.SECONDS)).thenReturn(stub);
    UUID actual = UUID.randomUUID();
    when(stub.authenticate(any()))
        .thenReturn(
            AuthenticateResponse.newBuilder()
                .setAccountId(actual.toString())
                .setAuthToken("ordinary-fixture-token")
                .build());
    var client = client(stub);
    assertThat(client.authenticateDemoOwner()).isEqualTo(actual);
    verify(stub)
        .authenticate(
            argThat(
                request ->
                    request.getEmail().equals("demo@example.com")
                        && request.getPassword().equals("swordfish")));
  }

  @Test
  void retriesOnlyTransientTransportFailuresWithTheSameCredentialRequest() throws Exception {
    var stub = mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5, TimeUnit.SECONDS)).thenReturn(stub);
    UUID owner = UUID.randomUUID();
    when(stub.authenticate(any()))
        .thenThrow(Status.UNAVAILABLE.asRuntimeException())
        .thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException())
        .thenReturn(
            AuthenticateResponse.newBuilder()
                .setAccountId(owner.toString())
                .setAuthToken("ordinary-fixture-token")
                .build());

    assertThat(client(stub).authenticateDemoOwner()).isEqualTo(owner);

    var requests = ArgumentCaptor.forClass(AuthenticateRequest.class);
    verify(stub, times(3)).authenticate(requests.capture());
    verify(stub, times(3)).withDeadlineAfter(5, TimeUnit.SECONDS);
    assertThat(requests.getAllValues())
        .allSatisfy(
            request -> {
              assertThat(request).isSameAs(requests.getValue());
              assertThat(request.getEmail()).isEqualTo("demo@example.com");
              assertThat(request.getPassword()).isEqualTo("swordfish");
            });
  }

  @Test
  void propagatesFinalTransientFailureAfterThreeCalls() throws Exception {
    var stub = mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5, TimeUnit.SECONDS)).thenReturn(stub);
    var failure = Status.DEADLINE_EXCEEDED.asRuntimeException();
    when(stub.authenticate(any())).thenThrow(failure);
    var client = client(stub);

    assertThatThrownBy(client::authenticateDemoOwner).isSameAs(failure);

    verify(stub, times(3)).authenticate(any());
  }

  @Test
  void refusesPermanentAuthorizationStatusesWithoutRetry() throws Exception {
    for (Status status : new Status[] {Status.PERMISSION_DENIED, Status.UNAUTHENTICATED}) {
      var stub = mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
      when(stub.withDeadlineAfter(5, TimeUnit.SECONDS)).thenReturn(stub);
      var failure = status.asRuntimeException();
      when(stub.authenticate(any())).thenThrow(failure);
      var client = client(stub);

      assertThatThrownBy(client::authenticateDemoOwner)
          .isInstanceOf(StatusRuntimeException.class)
          .isSameAs(failure);

      verify(stub).authenticate(any());
    }
  }

  @Test
  void refusesCredentialErrorsAndMissingTokensWithoutRetry() throws Exception {
    for (AuthenticateResponse response :
        new AuthenticateResponse[] {
          AuthenticateResponse.newBuilder()
              .setError(
                  net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                      .setCode("AUTH_INVALID_CREDENTIALS"))
              .build(),
          AuthenticateResponse.newBuilder().setAccountId(UUID.randomUUID().toString()).build()
        }) {
      var stub = mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
      when(stub.withDeadlineAfter(5, TimeUnit.SECONDS)).thenReturn(stub);
      when(stub.authenticate(any())).thenReturn(response);
      var client = client(stub);

      assertThatThrownBy(client::authenticateDemoOwner).isInstanceOf(IllegalStateException.class);

      verify(stub).authenticate(any());
    }
  }

  @Test
  void interruptionStopsRetryAndPreservesInterruptWithoutReturningAnOwner() throws Exception {
    var stub = mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.authenticate(any()))
        .thenAnswer(
            invocation -> {
              Thread.currentThread().interrupt();
              throw Status.UNAVAILABLE.asRuntimeException();
            });
    var client = client(stub);
    try {
      assertThatThrownBy(client::authenticateDemoOwner)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Account demo authentication interrupted");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      verify(stub).authenticate(any());
      verify(stub).withDeadlineAfter(5, TimeUnit.SECONDS);
      assertThatThrownBy(client::authenticateDemoOwner)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Account demo authentication interrupted");
      verifyNoMoreInteractions(stub);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void interruptedFixtureAuthenticationCannotReachOwnerMutation() throws Exception {
    var stub = mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    var characters =
        mock(net.firedevops.firemud.entitymanagement.repository.CharacterRepository.class);
    var items = mock(net.firedevops.firemud.entitymanagement.repository.ItemRepository.class);
    var itemInstances =
        mock(net.firedevops.firemud.entitymanagement.repository.ItemInstanceRepository.class);
    var containers =
        mock(net.firedevops.firemud.entitymanagement.repository.ContainerInstanceRepository.class);
    var equipmentSlots =
        mock(
            net.firedevops.firemud.entitymanagement.repository.EquipmentSlotDefinitionRepository
                .class);
    var bodySlots =
        mock(
            net.firedevops.firemud.entitymanagement.repository.BodyLayoutSlotDefinitionRepository
                .class);
    var seeder =
        new net.firedevops.firemud.entitymanagement.data.TestDataSeeder(
            client(stub), characters, items, itemInstances, containers, equipmentSlots, bodySlots);
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(
              () -> seeder.run(new org.springframework.boot.DefaultApplicationArguments()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Account demo authentication interrupted");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      verifyNoInteractions(
          stub, characters, items, itemInstances, containers, equipmentSlots, bodySlots);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void refusesUnavailableMalformedNilAndNoncanonicalOwners() throws Exception {
    var stub = mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5, TimeUnit.SECONDS)).thenReturn(stub);
    var client = client(stub);
    for (String value :
        new String[] {
          "",
          "1",
          "not-a-uuid",
          "00000000-0000-0000-0000-000000000000",
          "11111111-1111-4111-8111-11111111111A"
        }) {
      when(stub.authenticate(any()))
          .thenReturn(
              AuthenticateResponse.newBuilder()
                  .setAccountId(value)
                  .setAuthToken("ordinary-fixture-token")
                  .build());
      assertThatThrownBy(client::authenticateDemoOwner)
          .isInstanceOf(IllegalArgumentException.class);
    }
    when(stub.authenticate(any()))
        .thenReturn(
            AuthenticateResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("AUTH_UNAVAILABLE"))
                .build());
    assertThatThrownBy(client::authenticateDemoOwner).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void enabledUnusedClientDoesNotValidatePlaintextConfigurationUntilRequested() {
    contextRunner
        .withPropertyValues("firemud.grpc.plaintext=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThatThrownBy(() -> context.getBean(DemoAccountAuthenticationClient.class))
                  .hasRootCauseInstanceOf(IllegalArgumentException.class)
                  .hasRootCauseMessage(
                      "Demo Account authentication require Entity Management workload mTLS");
              verifyNoInteractions(context.getBean(GrpcChannelFactory.class));
            });
  }

  @Test
  void enabledUnusedClientDoesNotValidateMissingNamespaceUntilRequested() {
    contextRunner
        .withPropertyValues(
            "firemud.grpc.plaintext=false",
            "firemud.grpc.cert-chain=/fixture/client.crt",
            "firemud.grpc.private-key=/fixture/client.key",
            "firemud.grpc.ca-cert=/fixture/ca.crt")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThatThrownBy(() -> context.getBean(DemoAccountAuthenticationClient.class))
                  .hasRootCauseInstanceOf(IllegalArgumentException.class)
                  .hasRootCauseMessage(
                      "Demo authentication requires the configured workload namespace");
              verifyNoInteractions(context.getBean(GrpcChannelFactory.class));
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({
    ServiceEndpointsProperties.class,
    CommonGrpcClientProperties.class
  })
  @Import(DemoAccountAuthenticationClient.class)
  static class DemoAccountAuthenticationClientConfiguration {
    @Bean
    GrpcChannelFactory grpcChannelFactory() {
      return mock(GrpcChannelFactory.class);
    }
  }

  private static DemoAccountAuthenticationClient client(
      AccountServiceGrpc.AccountServiceBlockingStub stub) throws Exception {
    var properties = new CommonGrpcClientProperties();
    properties.setPlaintext(false);
    properties.setCertChain("/fixture/client.crt");
    properties.setPrivateKey("/fixture/client.key");
    properties.setCaCert("/fixture/ca.crt");
    var client =
        new DemoAccountAuthenticationClient(
            new ServiceEndpointsProperties(),
            properties,
            mock(GrpcChannelFactory.class),
            "dev",
            "demo@example.com",
            "swordfish");
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    field.set(client, stub);
    Field initialized =
        AbstractReloadingBlockingGrpcClient.class.getDeclaredField("explicitlyInitialized");
    initialized.setAccessible(true);
    initialized.setBoolean(client, true);
    return client;
  }
}
