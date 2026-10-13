package net.firedevops.firemud.common.account.authority;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AccountIssuerAuthoritySourceSnapshot;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountIssuerAuthoritySourceClientTest {
  private static final String NAMESPACE = "firemud";
  private static final String CALLER = "spiffe://firemud/ns/firemud/sa/game-session-service";
  private static final String ISSUER = AccountIssuerSourceSnapshotEvidence.ISSUER_ID;
  private static final String STREAM = AccountIssuerSourceSnapshotEvidence.ISSUER_STREAM_KEY;

  @Test
  void rejectsInvalidNamespaceAndUninitializedOrClosedLifecycle(@TempDir Path directory)
      throws Exception {
    var material = AccountIssuerAuthoritySourceClientMtlsTest.writeClientTlsMaterial(directory);
    CommonGrpcClientProperties tls = tlsProperties(material);
    assertThatThrownBy(() -> newClient("FireMUD", tls))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical DNS label");

    AccountIssuerAuthoritySourceClient client = newClient(NAMESPACE, tls);
    assertThatThrownBy(() -> client.read(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized");

    client.close();
    assertThatThrownBy(() -> client.read(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
  }

  @Test
  void requiresReadableFileBackedMutualTlsAndExactClientLeafIdentity(@TempDir Path directory)
      throws Exception {
    CommonGrpcClientProperties plaintext = new CommonGrpcClientProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(() -> newClient(NAMESPACE, plaintext))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires TLS with file-backed client mTLS");

    var material = AccountIssuerAuthoritySourceClientMtlsTest.writeClientTlsMaterial(directory);
    CommonGrpcClientProperties missingKey = tlsProperties(material);
    missingKey.setPrivateKey(directory.resolve("missing-key.pem").toString());
    assertThatThrownBy(() -> newClient(NAMESPACE, missingKey))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("privateKey must be a readable regular file");

    var wrongMaterial =
        AccountIssuerAuthoritySourceClientMtlsTest.writeClientTlsMaterial(
            directory.resolve("wrong-identity"), true);
    AccountIssuerAuthoritySourceClient wrongIdentityClient =
        newClient(NAMESPACE, tlsProperties(wrongMaterial));
    assertThatThrownBy(wrongIdentityClient::init)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must match the configured Game Session workload");
  }

  @Test
  void rejectsAmbientTransactionsAndNamespaceMismatchedRequestsBeforeRpc(@TempDir Path directory)
      throws Exception {
    var material = AccountIssuerAuthoritySourceClientMtlsTest.writeClientTlsMaterial(directory);
    AccountIssuerAuthoritySourceClient client = newClient(NAMESPACE, tlsProperties(material));
    client.init();
    try {
      var request = request(UUID.randomUUID(), Optional.empty(), "other");
      assertThatThrownBy(() -> client.read(request))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("configured namespace");

      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> client.read(request(UUID.randomUUID(), Optional.empty(), NAMESPACE)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ambient transaction or synchronization");
      TransactionSynchronizationManager.setActualTransactionActive(false);

      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(() -> client.read(request(UUID.randomUUID(), Optional.empty(), NAMESPACE)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ambient transaction or synchronization");
      TransactionSynchronizationManager.clearSynchronization();
    } finally {
      if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.clearSynchronization();
      }
      TransactionSynchronizationManager.setActualTransactionActive(false);
      client.close();
    }
  }

  @Test
  void rejectsMalformedUnknownAndChangedSourceResponses() {
    UUID operation = UUID.randomUUID();
    var captured = positiveSource(operation, "1", "2");
    var request = request(operation, Optional.of(captured), NAMESPACE);

    assertThatThrownBy(
            () ->
                AccountIssuerAuthoritySourceClient.decodeResponse(
                    AccountIssuerAuthoritySourceSnapshot.newBuilder()
                        .setSchemaVersion(AccountIssuerSourceSnapshotEvidence.SCHEMA_VERSION)
                        .build(),
                    request,
                    CALLER))
        .isInstanceOf(IllegalArgumentException.class);

    var unknownResponse =
        AccountIssuerSourceSnapshotGrpcCodec.toWire(captured).toBuilder()
            .setUnknownFields(
                com.google.protobuf.UnknownFieldSet.newBuilder()
                    .addField(
                        99,
                        com.google.protobuf.UnknownFieldSet.Field.newBuilder()
                            .addVarint(1L)
                            .build())
                    .build())
            .build();
    assertThatThrownBy(
            () ->
                AccountIssuerAuthoritySourceClient.decodeResponse(unknownResponse, request, CALLER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown protobuf fields");

    var changedSource = positiveSource(operation, "2", "3");
    assertThatThrownBy(
            () ->
                AccountIssuerAuthoritySourceClient.decodeResponse(
                    AccountIssuerSourceSnapshotGrpcCodec.toWire(changedSource), request, CALLER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact expected snapshot");
  }

  private static AccountIssuerAuthoritySourceClient newClient(
      String namespace, CommonGrpcClientProperties tlsProperties) {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    ManagedChannel channel = mock(ManagedChannel.class);
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    try {
      when(channelFactory.buildChannel(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(channel);
    } catch (javax.net.ssl.SSLException impossible) {
      throw new AssertionError(impossible);
    }
    return new AccountIssuerAuthoritySourceClient(
        endpoints, tlsProperties, channelFactory, namespace);
  }

  private static CommonGrpcClientProperties tlsProperties(
      AccountIssuerAuthoritySourceClientMtlsTest.ClientTlsMaterial material) {
    CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
    properties.setCertChain(material.clientCertificate().toString());
    properties.setPrivateKey(material.clientPrivateKey().toString());
    properties.setCaCert(material.caCertificate().toString());
    return properties;
  }

  private static AccountIssuerSourceSnapshotGrpcCodec.ReadRequest request(
      UUID operation, Optional<AccountIssuerSourceSnapshotEvidence> expected, String namespace) {
    return new AccountIssuerSourceSnapshotGrpcCodec.ReadRequest(
        operation, namespace, ISSUER, expected);
  }

  private static AccountIssuerSourceSnapshotEvidence positiveSource(
      UUID operation, String sequence, String generation) {
    String event =
        AccountAuthoritySourceEventV1Codec.sealIssuer(
                new AccountAuthoritySourceEventV1Codec.IssuerPreimage(
                    operation.toString(),
                    operation.toString(),
                    STREAM,
                    sequence,
                    ISSUER,
                    generation,
                    generation,
                    "SIGNER_COMPROMISE"))
            .canonicalJson();
    return new AccountIssuerSourceSnapshotEvidence(
        operation,
        NAMESPACE,
        CALLER,
        ISSUER,
        generation,
        generation,
        STREAM,
        sequence,
        Optional.of(event));
  }
}
