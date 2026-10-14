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
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.gamedesign.v1.TenantIdentityServiceGrpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class GameDesignFreshTenantCreationClientTest {
  private static final String NAMESPACE = "dev";
  private static final String WRONG_PEER_URI = "spiffe://firemud/ns/other/sa/game-design-service";

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
