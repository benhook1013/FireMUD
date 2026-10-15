package net.firedevops.firemud.common.account.sourceintake;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountSelectedOwnerIntakeAuthorizationGrpcClientTest {
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
                new AccountSelectedOwnerIntakeAuthorizationGrpcClient(
                    endpoints, plaintext, new GrpcChannelFactory(), "test"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                new AccountSelectedOwnerIntakeAuthorizationGrpcClient(
                    endpoints, new CommonGrpcClientProperties(), new GrpcChannelFactory(), "test"))
        .isInstanceOf(IllegalArgumentException.class);

    var properties = tlsFiles();
    assertThatThrownBy(
            () ->
                new AccountSelectedOwnerIntakeAuthorizationGrpcClient(
                    endpoints, properties, new GrpcChannelFactory(), "not/a/namespace"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsRemoteReadWithAmbientSqlOrSynchronizationBeforeChannelUse() throws IOException {
    var client =
        new AccountSelectedOwnerIntakeAuthorizationGrpcClient(
            new ServiceEndpointsProperties(), tlsFiles(), new GrpcChannelFactory(), "test");

    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> client.read(null)).isInstanceOf(IllegalStateException.class);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThatThrownBy(() -> client.read(null)).isInstanceOf(IllegalStateException.class);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
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
    Path path = temporaryDirectory.resolve(name);
    try {
      return Files.createFile(path);
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
