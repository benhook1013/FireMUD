package unit.net.firedevops.firemud.entitymanagement.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.entitymanagement.client.DemoAccountAuthenticationClient;
import org.junit.jupiter.api.Test;

class DemoAccountAuthenticationClientTest {
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
    return client;
  }
}
