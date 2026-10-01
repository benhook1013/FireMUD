package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyAccountTenantAssociationResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameDesignTenantIdentityClientTest {
  @Test
  void rejectsInvalidNamespaceAndNonFileBackedMtlsBeforeChannelCreation() {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    CommonGrpcClientProperties plaintext = mtlsProperties();
    plaintext.setPlaintext(true);

    assertThatThrownBy(() -> newClient(plaintext, channelFactory, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");
    assertThatThrownBy(() -> newClient(null, channelFactory, "test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TLS configuration is required");

    List<CommonGrpcClientProperties> incompleteConfigurations =
        List.of(
            mtlsPropertiesWith(null, "account.key", "gamedesign-ca.crt"),
            mtlsPropertiesWith("account.crt", null, "gamedesign-ca.crt"),
            mtlsPropertiesWith("account.crt", "account.key", null));
    for (CommonGrpcClientProperties incomplete : incompleteConfigurations) {
      assertThatThrownBy(() -> newClient(incomplete, channelFactory, "test"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("certificate, key, and CA");
    }

    List<CommonGrpcClientProperties> classpathConfigurations =
        List.of(
            mtlsPropertiesWith("classpath:account.crt", "account.key", "gamedesign-ca.crt"),
            mtlsPropertiesWith("account.crt", "classpath:account.key", "gamedesign-ca.crt"),
            mtlsPropertiesWith("account.crt", "account.key", "classpath:gamedesign-ca.crt"));
    for (CommonGrpcClientProperties classpathConfiguration : classpathConfigurations) {
      assertThatThrownBy(() -> newClient(classpathConfiguration, channelFactory, "test"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("file-backed");
    }

    for (String invalidNamespace : new String[] {null, "", "Upper", "not/a-label"}) {
      assertThatThrownBy(() -> newClient(mtlsProperties(), channelFactory, invalidNamespace))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("namespace");
    }
    verifyNoInteractions(channelFactory);
  }

  @Test
  void initInstallsExactGameDesignServerIdentityOnTheConfiguredTlsChannel(@TempDir Path directory)
      throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("game-design-service.internal:6565");
    GrpcChannelFactory channelFactory = spy(new GrpcChannelFactory());
    GameDesignTenantIdentityClient client =
        new GameDesignTenantIdentityClient(
            endpoints, mtlsProperties(directory), channelFactory, "account-test");
    try {
      // This constructs the configured TLS channel and stub, but performs no live TLS handshake.
      client.init();

      verify(channelFactory)
          .buildChannel(
              eq("game-design-service.internal:6565"),
              eq(6565),
              any(CommonGrpcClientProperties.class),
              eq(true));
      assertThat(installedServerPeerUri(client))
          .isEqualTo("spiffe://firemud/ns/account-test/sa/game-design-service");
    } finally {
      client.close();
    }
  }

  @Test
  void initializedStubRejectsResponseWithoutAuthenticatedServerSession(@TempDir Path directory)
      throws Exception {
    UnauthenticatedResponseChannel channel =
        new UnauthenticatedResponseChannel(
            ResolveLegacyAccountTenantAssociationResponse.getDefaultInstance());
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    when(channelFactory.buildChannel(
            anyString(), anyInt(), any(CommonGrpcClientProperties.class), anyBoolean()))
        .thenReturn(channel);
    GameDesignTenantIdentityClient client =
        newClient(mtlsProperties(directory), channelFactory, "account-test");

    try {
      client.init();

      assertThatThrownBy(() -> client.resolveApprovedAssociation(1L))
          .isInstanceOf(StatusRuntimeException.class)
          .extracting(error -> ((StatusRuntimeException) error).getStatus().getCode())
          .isEqualTo(Status.Code.UNAUTHENTICATED);
      assertThat(channel.lastCallCancelled).isTrue();
    } finally {
      client.close();
    }
  }

  private static GameDesignTenantIdentityClient newClient(
      CommonGrpcClientProperties tls, GrpcChannelFactory channelFactory, String namespace) {
    return new GameDesignTenantIdentityClient(
        new ServiceEndpointsProperties(), tls, channelFactory, namespace);
  }

  private static String installedServerPeerUri(GameDesignTenantIdentityClient client)
      throws Exception {
    Field interceptorField =
        GameDesignTenantIdentityClient.class.getDeclaredField("serverPeerIdentityInterceptor");
    interceptorField.setAccessible(true);
    GrpcServerPeerIdentityClientInterceptor interceptor =
        (GrpcServerPeerIdentityClientInterceptor) interceptorField.get(client);
    Field expectedPeerUriField =
        GrpcServerPeerIdentityClientInterceptor.class.getDeclaredField("expectedPeerUri");
    expectedPeerUriField.setAccessible(true);
    return (String) expectedPeerUriField.get(interceptor);
  }

  private static CommonGrpcClientProperties mtlsProperties() {
    return mtlsPropertiesWith(
        "certs/account-client.crt", "certs/account-client.key", "certs/gamedesign-server-ca.crt");
  }

  private static CommonGrpcClientProperties mtlsPropertiesWith(
      String certificate, String privateKey, String caCertificate) {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(certificate);
    tls.setPrivateKey(privateKey);
    tls.setCaCert(caCertificate);
    return tls;
  }

  private static CommonGrpcClientProperties mtlsProperties(Path directory) throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(copyCertificateResource("dev-cert.pem", directory).toString());
    tls.setPrivateKey(copyCertificateResource("dev-key.pem", directory).toString());
    tls.setCaCert(copyCertificateResource("dev-ca.pem", directory).toString());
    return tls;
  }

  private static Path copyCertificateResource(String fileName, Path directory) throws Exception {
    Path destination = directory.resolve(fileName);
    try (InputStream source =
        GameDesignTenantIdentityClientTest.class.getResourceAsStream("/certs/" + fileName)) {
      if (source == null) {
        throw new IllegalStateException("Missing shared TLS test resource: " + fileName);
      }
      Files.copy(source, destination);
    }
    return destination;
  }

  private static final class UnauthenticatedResponseChannel extends ManagedChannel {
    private final byte[] responseBytes;
    private boolean lastCallCancelled;

    private UnauthenticatedResponseChannel(ResolveLegacyAccountTenantAssociationResponse response) {
      this.responseBytes = response.toByteArray();
    }

    @Override
    public String authority() {
      return "test-authority";
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions) {
      return new ClientCall<>() {
        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          responseListener.onHeaders(new Metadata());
          responseListener.onMessage(method.parseResponse(new ByteArrayInputStream(responseBytes)));
          responseListener.onClose(Status.OK, new Metadata());
        }

        @Override
        public void request(int numMessages) {}

        @Override
        public void cancel(String message, Throwable cause) {
          lastCallCancelled = true;
        }

        @Override
        public void halfClose() {}

        @Override
        public void sendMessage(ReqT message) {}

        @Override
        public Attributes getAttributes() {
          // No SSL session means the server workload identity cannot be authenticated.
          return Attributes.EMPTY;
        }
      };
    }

    @Override
    public ManagedChannel shutdown() {
      return this;
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public ManagedChannel shutdownNow() {
      return this;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }
  }
}
