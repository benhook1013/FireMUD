package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Attributes;
import io.grpc.CallCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationResponse;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameDesignFreshTenantCreationClientTest {
  private static final String NAMESPACE = "dev";
  private static final String WRONG_PEER_URI = "spiffe://firemud/ns/other/sa/game-design-service";

  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID OPERATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID TENANT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String REQUEST_DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void requiresFileBackedMtlsBeforeCreatingAChannel(@TempDir Path directory) throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    CommonGrpcClientProperties plaintext = tls(directory);
    plaintext.setPlaintext(true);

    assertThatThrownBy(() -> newClient(plaintext, channelFactory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workload mTLS");

    verifyNoInteractions(channelFactory);
  }

  @Test
  void resolveRequiresExplicitInitializationBeforeAnyChannelUse(@TempDir Path directory)
      throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    GameDesignFreshTenantCreationClient client = newClient(tls(directory), channelFactory);
    try {
      assertThatThrownBy(() -> client.resolveCreation(null, null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not initialized and available");
      verifyNoInteractions(channelFactory);
    } finally {
      client.close();
    }
  }

  @Test
  void rejectsInvalidWorkloadNamespaceBeforeChannelCreation(@TempDir Path directory)
      throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);

    assertThatThrownBy(
            () ->
                new GameDesignFreshTenantCreationClient(
                    new ServiceEndpointsProperties(), tls(directory), channelFactory, " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one DNS label");

    verifyNoInteractions(channelFactory);
  }

  @Test
  void exactPeerCredentialsRejectWrongNamespaceBeforeApplyingRequestMetadata(
      @TempDir Path directory) throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    ManagedChannel channel = mock(ManagedChannel.class);
    when(channelFactory.buildChannel(
            any(), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(channel);
    GameDesignFreshTenantCreationClient client = newClient(tls(directory), channelFactory);
    try {
      client.init();
      TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub = initializedStub(client);
      assertThat(stub.getCallOptions().getCredentials())
          .isInstanceOf(GrpcServerPeerIdentityCallCredentials.class);

      SSLSession sslSession = mock(SSLSession.class);
      var peerCertificate = mock(java.security.cert.X509Certificate.class);
      when(peerCertificate.getSubjectAlternativeNames())
          .thenReturn(List.of(List.of(6, WRONG_PEER_URI)));
      when(sslSession.getPeerCertificates())
          .thenReturn(new java.security.cert.Certificate[] {peerCertificate});
      CallCredentials.RequestInfo requestInfo = mock(CallCredentials.RequestInfo.class);
      when(requestInfo.getTransportAttrs())
          .thenReturn(
              Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession).build());
      CallCredentials.MetadataApplier applier = mock(CallCredentials.MetadataApplier.class);

      stub.getCallOptions()
          .getCredentials()
          .applyRequestMetadata(requestInfo, Runnable::run, applier);

      ArgumentCaptor<Status> failure = ArgumentCaptor.forClass(Status.class);
      verify(applier).fail(failure.capture());
      assertThat(failure.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
      verify(applier, never()).apply(any(Metadata.class));
    } finally {
      client.close();
    }
  }

  @Test
  void bindsCompleteCreationReceiptToExactRequestAndBoundedDeadline(@TempDir Path directory)
      throws Exception {
    var stub = mock(TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    var response = creationResponse(NAMESPACE, REQUEST_ID, REQUEST_DIGEST);
    when(stub.resolveFreshTenantCreation(any())).thenReturn(response);
    var client = initializedClient(directory, stub);
    try {
      FreshTenantCreationEvidence actual = client.resolveCreation(REQUEST_ID, REQUEST_DIGEST);

      assertThat(actual)
          .isEqualTo(
              new FreshTenantCreationEvidence(
                  1,
                  NAMESPACE,
                  REQUEST_ID,
                  OPERATION_ID,
                  REQUEST_DIGEST,
                  TENANT_ID,
                  91L,
                  "fresh-owner-key-91",
                  "NEW_GAME_ROW",
                  response.getEvidenceDigest()));
      var request = ArgumentCaptor.forClass(ResolveFreshTenantCreationRequest.class);
      verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
      verify(stub).resolveFreshTenantCreation(request.capture());
      assertThat(request.getValue())
          .isEqualTo(
              ResolveFreshTenantCreationRequest.newBuilder()
                  .setCreationRequestId(REQUEST_ID.toString())
                  .setExpectedRequestDigest(REQUEST_DIGEST)
                  .build());
    } finally {
      client.close();
    }
  }

  @Test
  void refusesUnknownMalformedAndIndependentlyValidForeignReceipts(@TempDir Path directory)
      throws Exception {
    var stub = mock(TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    var client = initializedClient(directory, stub);
    var valid = creationResponse(NAMESPACE, REQUEST_ID, REQUEST_DIGEST);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    try {
      for (var response :
          List.of(
              valid.toBuilder().setUnknownFields(unknown).build(),
              valid.toBuilder().setProvenanceKind("RETAINED_GAME_V30").build(),
              valid.toBuilder().setEvidenceDigest("sha256:" + "b".repeat(64)).build(),
              creationResponse("other", REQUEST_ID, REQUEST_DIGEST),
              creationResponse(
                  NAMESPACE,
                  UUID.fromString("44444444-4444-4444-8444-444444444444"),
                  REQUEST_DIGEST),
              creationResponse(NAMESPACE, REQUEST_ID, "sha256:" + "c".repeat(64)))) {
        when(stub.resolveFreshTenantCreation(any())).thenReturn(response);
        assertThatThrownBy(() -> client.resolveCreation(REQUEST_ID, REQUEST_DIGEST))
            .isInstanceOf(IllegalStateException.class);
      }
    } finally {
      client.close();
    }
  }

  @Test
  void rejectsMalformedSelectorsAndActiveSqlBeforeCallingOwner(@TempDir Path directory)
      throws Exception {
    var stub = mock(TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub.class);
    var client = initializedClient(directory, stub);
    try {
      assertThatThrownBy(() -> client.resolveCreation(null, REQUEST_DIGEST))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> client.resolveCreation(new UUID(0L, 0L), REQUEST_DIGEST))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> client.resolveCreation(REQUEST_ID, "bad"))
          .isInstanceOf(IllegalArgumentException.class);
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> client.resolveCreation(REQUEST_ID, REQUEST_DIGEST))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("outside a Game Session transaction");
      verifyNoInteractions(stub);
    } finally {
      TransactionSynchronizationManager.clear();
      client.close();
    }
  }

  // Placeholder files and a replaced blocking stub make these codec/caller tests, not TLS proof.
  private static GameDesignFreshTenantCreationClient initializedClient(
      Path directory, TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub stub)
      throws Exception {
    var factory = mock(GrpcChannelFactory.class);
    var channel = mock(ManagedChannel.class);
    when(factory.buildChannel(any(), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(channel);
    var client = newClient(tls(directory), factory);
    client.init();
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    field.set(client, stub);
    return client;
  }

  private static ResolveFreshTenantCreationResponse creationResponse(
      String namespace, UUID requestId, String requestDigest) {
    return ResolveFreshTenantCreationResponse.newBuilder()
        .setSchemaVersion(1)
        .setTargetNamespace(namespace)
        .setCreationRequestId(requestId.toString())
        .setOperationId(OPERATION_ID.toString())
        .setRequestDigest(requestDigest)
        .setCanonicalTenantId(TENANT_ID.toString())
        .setSourceGameRowId(91L)
        .setSourceGameTenantKey("fresh-owner-key-91")
        .setProvenanceKind("NEW_GAME_ROW")
        .setEvidenceDigest(
            GameTenantCreationDigest.evidenceDigest(
                namespace,
                requestId,
                OPERATION_ID,
                requestDigest,
                TENANT_ID,
                91L,
                "fresh-owner-key-91",
                "NEW_GAME_ROW"))
        .build();
  }

  private static GameDesignFreshTenantCreationClient newClient(
      CommonGrpcClientProperties tlsProperties, GrpcChannelFactory channelFactory) {
    return new GameDesignFreshTenantCreationClient(
        new ServiceEndpointsProperties(), tlsProperties, channelFactory, NAMESPACE);
  }

  private static CommonGrpcClientProperties tls(Path directory) throws Exception {
    Path certificate = Files.writeString(directory.resolve("client.crt"), "certificate");
    Path privateKey = Files.writeString(directory.resolve("client.key"), "private key");
    Path caCertificate = Files.writeString(directory.resolve("ca.crt"), "CA certificate");
    CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
    properties.setCertChain(certificate.toString());
    properties.setPrivateKey(privateKey.toString());
    properties.setCaCert(caCertificate.toString());
    return properties;
  }

  @SuppressWarnings("unchecked")
  private static TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub initializedStub(
      GameDesignFreshTenantCreationClient client) throws Exception {
    Field stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    stubField.setAccessible(true);
    return (TenantIdentityServiceGrpc.TenantIdentityServiceBlockingStub) stubField.get(client);
  }
}
