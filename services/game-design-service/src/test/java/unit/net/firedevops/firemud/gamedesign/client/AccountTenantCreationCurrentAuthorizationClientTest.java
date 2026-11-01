package unit.net.firedevops.firemud.gamedesign.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.gamedesign.client.AccountTenantCreationCurrentAuthorizationClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountTenantCreationCurrentAuthorizationClientTest {
  @TempDir Path temporaryDirectory;

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void requiresFileBackedWorkloadMtlsAndCanonicalNamespace() {
    var endpoints = new ServiceEndpointsProperties();
    var plaintext = new CommonGrpcClientProperties();
    plaintext.setPlaintext(true);

    assertThatThrownBy(
            () ->
                new AccountTenantCreationCurrentAuthorizationClient(
                    endpoints, plaintext, new GrpcChannelFactory(), "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload mTLS");
    assertThatThrownBy(
            () ->
                new AccountTenantCreationCurrentAuthorizationClient(
                    endpoints, new CommonGrpcClientProperties(), new GrpcChannelFactory(), "test"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountTenantCreationCurrentAuthorizationClient(
                    endpoints, tlsFiles(), new GrpcChannelFactory(), "not/a/namespace"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesCurrentAuthorityRpcInsideAmbientSqlBeforeUsingChannel() throws IOException {
    var client =
        new AccountTenantCreationCurrentAuthorizationClient(
            new ServiceEndpointsProperties(), tlsFiles(), new GrpcChannelFactory(), "test");

    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> client.resolveCurrent(null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("outside SQL");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThatThrownBy(() -> client.resolveCurrent(null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("outside SQL");
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    client.close();
  }

  @Test
  void outboundStubPinsExactAccountServerWorkloadIdentity() throws Exception {
    var client =
        new AccountTenantCreationCurrentAuthorizationClient(
            new ServiceEndpointsProperties(), tlsFiles(), new GrpcChannelFactory(), "test");
    Method builder =
        AccountTenantCreationCurrentAuthorizationClient.class.getDeclaredMethod(
            "buildStub", ManagedChannel.class);
    builder.setAccessible(true);
    var stub =
        (net.firedevops.firemud.account.v1.TenantCreationCurrentAuthorizationReadServiceGrpc
                .TenantCreationCurrentAuthorizationReadServiceBlockingStub)
            builder.invoke(client, mock(ManagedChannel.class));

    assertThat(stub.getCallOptions().getCredentials())
        .isInstanceOf(GrpcServerPeerIdentityCallCredentials.class);
    Field interceptorField =
        AccountTenantCreationCurrentAuthorizationClient.class.getDeclaredField(
            "peerIdentityInterceptor");
    interceptorField.setAccessible(true);
    Object interceptor = interceptorField.get(client);
    assertThat(interceptor).isInstanceOf(GrpcServerPeerIdentityClientInterceptor.class);
    Field expectedPeer = interceptor.getClass().getDeclaredField("expectedPeerUri");
    expectedPeer.setAccessible(true);
    assertThat(expectedPeer.get(interceptor))
        .isEqualTo("spiffe://firemud/ns/test/sa/account-service");
    client.close();
  }

  private CommonGrpcClientProperties tlsFiles() {
    CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
    properties.setCertChain(file("client.crt").toString());
    properties.setPrivateKey(file("client.key").toString());
    properties.setCaCert(file("ca.crt").toString());
    return properties;
  }

  private Path file(String name) {
    try {
      return Files.createFile(temporaryDirectory.resolve(name));
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
