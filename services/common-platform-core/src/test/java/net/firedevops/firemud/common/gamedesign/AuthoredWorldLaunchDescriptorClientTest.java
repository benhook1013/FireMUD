package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthoredWorldLaunchDescriptorClientTest {
  private static final String NAMESPACE = "test";
  private static final UUID TENANT_ID = UUID.fromString("12345678-1234-4234-8234-123456789abc");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("22345678-1234-4234-8234-123456789abc");
  private static final UUID READ_REQUEST_ID =
      UUID.fromString("32345678-1234-4234-8234-123456789abc");

  @Test
  void rejectsPlaintextMissingClasspathAndUnreadableTlsMaterialBeforeChannelCreation(
      @TempDir Path directory) throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);

    CommonGrpcClientProperties plaintext = fileBackedMtls(directory);
    plaintext.setPlaintext(true);
    assertThatThrownBy(() -> newClient(plaintext, channelFactory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");

    assertThatThrownBy(() -> newClient(new CommonGrpcClientProperties(), channelFactory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");

    CommonGrpcClientProperties classpath = fileBackedMtls(directory);
    classpath.setCaCert("classpath:certs/ca.crt");
    assertThatThrownBy(() -> newClient(classpath, channelFactory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file-backed");

    CommonGrpcClientProperties missingFiles = new CommonGrpcClientProperties();
    missingFiles.setCertChain(directory.resolve("missing-client.crt").toString());
    missingFiles.setPrivateKey(directory.resolve("missing-client.key").toString());
    missingFiles.setCaCert(directory.resolve("missing-ca.crt").toString());
    assertThatThrownBy(() -> newClient(missingFiles, channelFactory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("existing readable file");

    verifyNoInteractions(channelFactory);
  }

  @Test
  void deniesResolveAndReadUntilExplicitInitializationAndAfterClose(@TempDir Path directory)
      throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    AuthoredWorldLaunchDescriptorClient client =
        newClient(fileBackedMtls(directory), channelFactory);
    var request = request(NAMESPACE);
    var getRequest =
        new AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest(
            READ_REQUEST_ID, request, "sha256:" + "b".repeat(64));
    var completeRequest =
        GetLaunchDescriptorRequest.newBuilder()
            .setRequestId(READ_REQUEST_ID.toString())
            .setCanonicalTenantId(TENANT_ID.toString())
            .setWorldSlug("copper-coast")
            .setControlPlaneRequestId("launch-operation-7")
            .setExpectedRequestDigest("sha256:" + "a".repeat(64))
            .setExpectedResultDigest("sha256:" + "b".repeat(64))
            .build();

    assertThatThrownBy(() -> client.resolve(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized and available");
    assertThatThrownBy(() -> client.get(getRequest))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized and available");
    assertThatThrownBy(() -> client.getComplete(completeRequest))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized and available");
    verifyNoInteractions(channelFactory);

    client.close();
    assertThatThrownBy(() -> client.resolve(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not initialized and available");
    verifyNoInteractions(channelFactory);
  }

  @Test
  void initializesConfiguredTargetWhenFileBackedTlsFilesExist(@TempDir Path directory)
      throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("game-design.internal:6565");
    CommonGrpcClientProperties tls = fileBackedMtls(directory);
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    ManagedChannel channel = mock(ManagedChannel.class);
    when(channelFactory.buildChannel(
            eq("game-design.internal:6565"),
            eq(6565),
            any(CommonGrpcClientProperties.class),
            eq(true)))
        .thenReturn(channel);
    AuthoredWorldLaunchDescriptorClient client =
        new AuthoredWorldLaunchDescriptorClient(endpoints, tls, channelFactory, NAMESPACE);

    try {
      // The mocked channel factory covers explicit target wiring only; socket peer proof is
      // separate.
      client.init();
      verify(channelFactory)
          .buildChannel(
              eq("game-design.internal:6565"),
              eq(6565),
              any(CommonGrpcClientProperties.class),
              eq(true));
    } finally {
      client.close();
    }
  }

  @Test
  void rejectsARequestOutsideTheConfiguredNamespace(@TempDir Path directory) throws Exception {
    AuthoredWorldLaunchDescriptorClient client =
        newClient(fileBackedMtls(directory), mock(GrpcChannelFactory.class));
    try {
      assertThatThrownBy(() -> client.resolve(request("other")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("configured workload namespace");
    } finally {
      client.close();
    }
  }

  private static AuthoredWorldLaunchDescriptorClient newClient(
      CommonGrpcClientProperties tls, GrpcChannelFactory channelFactory) {
    return new AuthoredWorldLaunchDescriptorClient(
        new ServiceEndpointsProperties(), tls, channelFactory, NAMESPACE);
  }

  private static CommonGrpcClientProperties fileBackedMtls(Path directory) throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.writeString(directory.resolve("client.crt"), "certificate").toString());
    tls.setPrivateKey(Files.writeString(directory.resolve("client.key"), "private key").toString());
    tls.setCaCert(
        Files.writeString(directory.resolve("server-ca.crt"), "CA certificate").toString());
    return tls;
  }

  private static AuthoredWorldLaunchDescriptorEvidence.Request request(String namespace) {
    return new AuthoredWorldLaunchDescriptorEvidence.Request(
        namespace,
        "launch-operation-7",
        TENANT_ID,
        "copper-coast",
        SOURCE_OPERATION_ID,
        "sha256:" + "a".repeat(64),
        19L,
        false,
        null,
        false,
        null,
        false,
        null,
        false,
        null);
  }
  @Test
  void closeReleasesClientMonitorBeforeClosingCertificateWatcher(@TempDir Path directory)
      throws Exception {
    String previousReloadPolicy = System.getProperty("firemud.grpc.tls-reload.enabled");
    System.setProperty("firemud.grpc.tls-reload.enabled", "false");
    try {
      Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate");
      Path privateKey = Files.writeString(directory.resolve("tls.key"), "private-key");
      Path caCertificate = Files.writeString(directory.resolve("ca.crt"), "ca-certificate");
      CommonGrpcClientProperties tlsProperties = new CommonGrpcClientProperties();
      tlsProperties.setCertChain(certificate.toString());
      tlsProperties.setPrivateKey(privateKey.toString());
      tlsProperties.setCaCert(caCertificate.toString());
      CountingChannelFactory channelFactory = new CountingChannelFactory();
      AuthoredWorldLaunchDescriptorClient client =
          new AuthoredWorldLaunchDescriptorClient(
              new ServiceEndpointsProperties(), tlsProperties, channelFactory, "test-namespace");
      client.init();

      TlsCertificateWatcher watcher = mock(TlsCertificateWatcher.class);
      CountDownLatch callbackFinished = new CountDownLatch(1);
      AtomicBoolean callbackAcquiredMonitor = new AtomicBoolean();
      AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
      doAnswer(
              invocation -> {
                assertThat(Thread.holdsLock(client)).isFalse();
                Thread callback =
                    new Thread(
                        () -> {
                          try {
                            synchronized (client) {
                              callbackAcquiredMonitor.set(true);
                            }
                          } catch (Throwable failure) {
                            callbackFailure.set(failure);
                          } finally {
                            callbackFinished.countDown();
                          }
                        },
                        "authored-world-launch-descriptor-test-callback");
                callback.start();
                if (!callbackFinished.await(1, TimeUnit.SECONDS)) {
                  callback.interrupt();
                  callback.join(TimeUnit.SECONDS.toMillis(1));
                  throw new AssertionError("Watcher callback could not acquire the client monitor");
                }
                callback.join(TimeUnit.SECONDS.toMillis(1));
                assertThat(callback.isAlive()).isFalse();
                assertThat(callbackAcquiredMonitor.get()).isTrue();
                assertThat(callbackFailure.get()).isNull();
                return null;
              })
          .when(watcher)
          .close();
      setWatcher(client, watcher);

      client.close();

      verify(watcher).close();
      assertThat(channelFactory.buildAttempts.get()).isEqualTo(1);
      assertThatThrownBy(client::init)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Authored-world launch descriptor client is closed");
      assertThat(channelFactory.buildAttempts.get()).isEqualTo(1);
    } finally {
      if (previousReloadPolicy == null) {
        System.clearProperty("firemud.grpc.tls-reload.enabled");
      } else {
        System.setProperty("firemud.grpc.tls-reload.enabled", previousReloadPolicy);
      }
    }
  }

  private static void setWatcher(
      AuthoredWorldLaunchDescriptorClient client, TlsCertificateWatcher watcher)
      throws ReflectiveOperationException {
    Field watcherField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("watcher");
    watcherField.setAccessible(true);
    watcherField.set(client, watcher);
  }

  private static final class CountingChannelFactory extends GrpcChannelFactory {
    private final AtomicInteger buildAttempts = new AtomicInteger();

    @Override
    public ManagedChannel buildChannel(
        String target, int defaultPort, CommonGrpcClientProperties properties, boolean keepAlive)
        throws SSLException {
      buildAttempts.incrementAndGet();
      return mock(ManagedChannel.class);
    }
  }
}
