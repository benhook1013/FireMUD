package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.grpc.ManagedChannel;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthoredWorldLaunchDescriptorClientTest {
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
