package net.firedevops.firemud.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.PingRequest;
import net.firedevops.firemud.gamedesign.v1.PingResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.health.contributor.Health;

class AbstractReloadingBlockingGrpcClientTest {
  @Test
  void closePreventsLateReloadFromReplacingTheShutdownChannel() throws Exception {
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    CountingChannelFactory factory = new CountingChannelFactory();
    TestClient client = new TestClient(new ServiceEndpointsProperties(), grpc, factory);

    client.init();
    GameDesignServiceGrpc.GameDesignServiceBlockingStub initializedStub = client.currentStub();
    assertThat(initializedStub).isNotNull();
    client.close();
    client.close();
    client.reloadChannel();

    assertThat(factory.buildAttempts.get()).isEqualTo(1);
    assertThat(client.currentStub()).isSameAs(initializedStub);
    verify(factory.channel, times(1)).shutdown();
  }

  @Test
  void explicitLifecycleInitializesOnceAndRejectsUseAfterClose() throws Exception {
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    CountingChannelFactory factory = new CountingChannelFactory();
    TestClient client = new TestClient(new ServiceEndpointsProperties(), grpc, factory);

    assertThatThrownBy(client::requireExplicitStub)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("test client is not initialized and available");
    assertThatThrownBy(client::requirePublishedExplicitStub)
        .isInstanceOf(IllegalStateException.class);

    client.initExplicit();
    GameDesignServiceGrpc.GameDesignServiceBlockingStub initializedStub =
        client.requireExplicitStub();
    client.initExplicit();

    assertThat(factory.buildAttempts.get()).isEqualTo(1);
    assertThat(client.requireExplicitStub()).isSameAs(initializedStub);
    assertThat(client.requirePublishedExplicitStub()).isSameAs(initializedStub);

    client.close();

    assertThatThrownBy(client::requireExplicitStub)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("test client is not initialized and available");
    assertThatThrownBy(client::requirePublishedExplicitStub)
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(client::initExplicit)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("test client is closed");
  }

  @Test
  void publishedStubIsUnavailableToLeasedCallsUntilExplicitInitialization() throws Exception {
    var grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    var beforeInit = mock(ManagedChannel.class);
    var afterInit = mock(ManagedChannel.class);
    var factory = mock(GrpcChannelFactory.class);
    when(factory.buildChannel(anyString(), anyInt(), any(), anyBoolean()))
        .thenReturn(beforeInit, afterInit);
    try (var client = new TestClient(new ServiceEndpointsProperties(), grpc, factory)) {
      client.reloadChannel();
      assertThat(client.currentStub()).isNotNull();
      assertThat(client.currentStub().getChannel()).isSameAs(beforeInit);
      assertThatThrownBy(client::requirePublishedExplicitStub)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("test client is not initialized and available");

      client.initExplicit();
      assertThat(client.requirePublishedExplicitStub().getChannel()).isSameAs(afterInit);
    }
  }

  @Test
  void replacementStubIsPublishedWhenPreviousChannelStartsShutdown() throws Exception {
    var grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    var previous = mock(ManagedChannel.class);
    var replacement = mock(ManagedChannel.class);
    var factory = mock(GrpcChannelFactory.class);
    when(factory.buildChannel(anyString(), anyInt(), any(), anyBoolean()))
        .thenReturn(previous, replacement);
    var stubAtShutdown = new AtomicReference<GameDesignServiceGrpc.GameDesignServiceBlockingStub>();
    try (var client = new TestClient(new ServiceEndpointsProperties(), grpc, factory)) {
      client.initExplicit();
      var original = client.requirePublishedExplicitStub();
      when(previous.shutdown())
          .thenAnswer(invocation -> {
            stubAtShutdown.set(client.requirePublishedExplicitStub());
            return previous;
          });

      client.reloadChannel();

      var published = client.requirePublishedExplicitStub();
      assertThat(published).isNotSameAs(original);
      assertThat(stubAtShutdown.get()).isSameAs(published);
      verify(previous).shutdown();
    }
    verify(replacement).shutdown();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void selectedChannelSurvivesReplacementOrCloseUntilBothBlockingCallsReturn(boolean close)
      throws Exception {
    var calls = new AtomicInteger();
    var expectedResponse = PingResponse.getDefaultInstance();
    var server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .addService(
                new GameDesignServiceGrpc.GameDesignServiceImplBase() {
                  @Override
                  public void ping(PingRequest request, StreamObserver<PingResponse> observer) {
                    calls.incrementAndGet();
                    observer.onNext(expectedResponse);
                    observer.onCompleted();
                  }
                })
            .build()
            .start();
    try {
      var previous =
          NettyChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
      try {
        var replacement =
            NettyChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        var grpc = new CommonGrpcClientProperties();
        grpc.setPlaintext(true);
        var factory = mock(GrpcChannelFactory.class);
        when(factory.buildChannel(anyString(), anyInt(), any(), anyBoolean()))
            .thenReturn(previous, replacement);
        var selected = new CountDownLatch(2);
        var startFirst = new CountDownLatch(1);
        var startSecond = new CountDownLatch(1);
        try (var client = new TestClient(new ServiceEndpointsProperties(), grpc, factory)) {
          client.initExplicit();
          var executor = Executors.newFixedThreadPool(2);
          Function<CountDownLatch, Future<PingResponse>> submitCall =
              ready ->
                  executor.submit(() ->
                      client.withPublishedInitializedStub(
                          "test client has no stub",
                          stub -> {
                            assertThat(stub.getChannel()).isSameAs(previous);
                            selected.countDown();
                            try {
                              // The second call also waits for stub selection and the first RPC.
                              assertThat(ready.await(30, TimeUnit.SECONDS))
                                  .as("the selected call must be released before its bounded wait")
                                  .isTrue();
                            } catch (InterruptedException interrupted) {
                              Thread.currentThread().interrupt();
                              throw new AssertionError(interrupted);
                            }
                            return stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                                .ping(PingRequest.getDefaultInstance());
                          }));
          try {
            var first = submitCall.apply(startFirst);
            var second = submitCall.apply(startSecond);
            assertThat(selected.await(15, TimeUnit.SECONDS)).isTrue();
            if (close) {
              client.close();
              assertThatThrownBy(client::requirePublishedExplicitStub)
                  .isInstanceOf(IllegalStateException.class);
            } else {
              client.reloadChannel();
              assertThat(client.requirePublishedExplicitStub().getChannel()).isSameAs(replacement);
            }
            assertThat(previous.isShutdown()).isFalse();
            assertThat(calls).hasValue(0);
            startFirst.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(expectedResponse);
            assertThat(calls).hasValue(1);
            assertThat(previous.isShutdown()).isFalse();
            startSecond.countDown();
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(expectedResponse);
            assertThat(calls).hasValue(2);
            assertThat(previous.isShutdown()).isTrue();
          } finally {
            startFirst.countDown();
            startSecond.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
          }
        } finally {
          replacement.shutdownNow();
        }
      } finally {
        previous.shutdownNow();
      }
    } finally {
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void idleRetirementFailureDoesNotInvalidatePublishedReplacement() throws Exception {
    var grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    var previous = mock(ManagedChannel.class);
    var replacement = mock(ManagedChannel.class);
    var factory = mock(GrpcChannelFactory.class);
    when(factory.buildChannel(anyString(), anyInt(), any(), anyBoolean()))
        .thenReturn(previous, replacement);
    when(previous.shutdown()).thenThrow(new IllegalStateException("shutdown failure"));
    try (var client = new TestClient(new ServiceEndpointsProperties(), grpc, factory)) {
      client.initExplicit();
      client.reloadChannel();
      assertThat(client.requirePublishedExplicitStub().getChannel()).isSameAs(replacement);
      verify(previous).shutdown();
    }
  }

  @Test
  void retiredChannelShutdownFailureDoesNotReplaceCallOutcome() throws Exception {
    var grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    for (boolean failCall : new boolean[] {false, true}) {
      var previous = mock(ManagedChannel.class);
      var replacement = mock(ManagedChannel.class);
      var factory = mock(GrpcChannelFactory.class);
      when(factory.buildChannel(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(previous, replacement);
      when(previous.shutdown()).thenThrow(new IllegalStateException("shutdown failure"));
      var originalFailure = new IllegalArgumentException("original call failure");
      try (var client = new TestClient(new ServiceEndpointsProperties(), grpc, factory)) {
        client.initExplicit();
        java.util.function.Supplier<String> invoke =
            () ->
                client.withPublishedInitializedStub(
                    "missing",
                    stub -> {
                      try {
                        client.reloadChannel();
                      } catch (SSLException failure) {
                        throw new AssertionError(failure);
                      }
                      if (failCall) throw originalFailure;
                      return "response";
                    });
        if (failCall) {
          assertThatThrownBy(invoke::get).isSameAs(originalFailure);
        } else {
          assertThat(invoke.get()).isEqualTo("response");
        }
        verify(previous).shutdown();
      }
    }
  }

  @Test
  void callSelectionDoesNotWaitForReplacementChannelConstruction() throws Exception {
    var grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    var previous = mock(ManagedChannel.class);
    var replacement = mock(ManagedChannel.class);
    var factory = mock(GrpcChannelFactory.class);
    var building = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(factory.buildChannel(anyString(), anyInt(), any(), anyBoolean()))
        .thenReturn(previous)
        .thenAnswer(invocation -> {
          building.countDown();
          assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
          return replacement;
        });
    var client = new TestClient(new ServiceEndpointsProperties(), grpc, factory);
    var executor = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
    try {
      client.initExplicit();
      var reload =
          executor.submit(() -> {
            client.reloadChannel();
            return null;
          });
      try {
        assertThat(building.await(5, TimeUnit.SECONDS)).isTrue();
        var call =
            executor.submit(
                () -> client.withPublishedInitializedStub("missing", stub -> stub.getChannel()));
        assertThat(call.get(1, TimeUnit.SECONDS)).isSameAs(previous);
        assertThat(release.getCount()).isEqualTo(1);
      } finally {
        release.countDown();
      }
      reload.get(5, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      executor.shutdownNow();
      boolean terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
      // A regression may deadlock on non-interruptible monitors. Do not block cleanup on them.
      if (terminated) client.close();
      assertThat(terminated).isTrue();
    }
  }

  @Test
  void initialisationFailurePreventsLateReloadFromCreatingAnotherChannel(@TempDir Path directory)
      throws Exception {
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");
    Path privateKey = Files.writeString(directory.resolve("tls.key"), "private-key-1");
    Path caCertificate = Files.writeString(directory.resolve("ca.crt"), "ca-certificate-1");
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setCertChain(certificate.toString());
    grpc.setPrivateKey(privateKey.toString());
    grpc.setCaCert(caCertificate.toString());
    FailingInitialChannelFactory factory = new FailingInitialChannelFactory();
    TestClient client = new TestClient(new ServiceEndpointsProperties(), grpc, factory);

    assertThatThrownBy(client::init).isInstanceOf(SSLException.class);

    client.reloadChannel();

    assertThat(factory.buildAttempts.get()).isEqualTo(1);
  }

  @Test
  void watcherStartFailureRetainsChannelShutdownFailure(@TempDir Path directory) throws Exception {
    var grpc = new CommonGrpcClientProperties();
    grpc.setCertChain(directory.resolve("tls.crt").toString());
    grpc.setPrivateKey(directory.resolve("tls.key").toString());
    grpc.setCaCert(directory.resolve("ca.crt").toString());
    var factory = mock(GrpcChannelFactory.class);
    var channel = mock(ManagedChannel.class);
    when(factory.buildChannel(anyString(), anyInt(), any(), anyBoolean())).thenReturn(channel);
    var startup = new IllegalStateException("watcher start failed");
    var shutdown = new IllegalStateException("channel shutdown failed");
    when(channel.shutdown()).thenThrow(shutdown);

    try (var policy = mockStatic(GrpcTlsReloadPolicy.class);
        var watchers =
            mockConstruction(
                TlsCertificateWatcher.class,
                (watcher, context) -> doThrow(startup).when(watcher).start());
        var client = new TestClient(new ServiceEndpointsProperties(), grpc, factory)) {
      policy.when(GrpcTlsReloadPolicy::isEnabled).thenReturn(true);
      assertThatThrownBy(client::initExplicit)
          .isSameAs(startup)
          .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(shutdown));
      assertThat(client.currentStub()).isNull();
      assertThatThrownBy(client::requirePublishedExplicitStub)
          .isInstanceOf(IllegalStateException.class);
      verify(watchers.constructed().getFirst()).close();
      verify(channel).shutdown();
    }
  }

  @Test
  void certificateChangeDuringInitialChannelBuildTriggersReload(@TempDir Path directory)
      throws Exception {
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");
    Path privateKey = Files.writeString(directory.resolve("tls.key"), "private-key-1");
    Path caCertificate = Files.writeString(directory.resolve("ca.crt"), "ca-certificate-1");
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setCertChain(certificate.toString());
    grpc.setPrivateKey(privateKey.toString());
    grpc.setCaCert(caCertificate.toString());
    CertificateChangingChannelFactory factory = new CertificateChangingChannelFactory(certificate);
    TestClient client = new TestClient(new ServiceEndpointsProperties(), grpc, factory);
    try {
      client.init();

      awaitCondition(() -> factory.observedCertificates.size() >= 2);

      assertThat(factory.observedCertificates).hasSizeGreaterThanOrEqualTo(2);
      assertThat(factory.observedCertificates.get(0)).isEqualTo("certificate-1");
      assertThat(factory.observedCertificates.get(1)).isEqualTo("certificate-2");
    } finally {
      client.close();
    }
  }

  @Test
  void failedCertificateReloadMakesWatcherReadinessUnavailable(@TempDir Path directory)
      throws Exception {
    Path certificate = Files.writeString(directory.resolve("tls.crt"), "certificate-1");
    Path privateKey = Files.writeString(directory.resolve("tls.key"), "private-key-1");
    Path caCertificate = Files.writeString(directory.resolve("ca.crt"), "ca-certificate-1");
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setCertChain(certificate.toString());
    grpc.setPrivateKey(privateKey.toString());
    grpc.setCaCert(caCertificate.toString());
    int unhealthyWatchersBefore =
        ((Number) TlsCertificateWatcher.health().getDetails().get("unhealthyWatchers")).intValue();

    TestClient client =
        new TestClient(new ServiceEndpointsProperties(), grpc, new FailingReloadChannelFactory());
    try {
      client.init();
      Files.writeString(certificate, "certificate-2");

      awaitCondition(() -> {
        Health health = TlsCertificateWatcher.health();
        return "OUT_OF_SERVICE".equals(health.getStatus().getCode())
            && ((Number) health.getDetails().get("unhealthyWatchers")).intValue()
                >= unhealthyWatchersBefore + 1;
      });

      Health health = TlsCertificateWatcher.health();
      assertThat(health.getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");
      assertThat(((Number) health.getDetails().get("unhealthyWatchers")).intValue())
          .isGreaterThanOrEqualTo(unhealthyWatchersBefore + 1);
    } finally {
      client.close();
    }
  }

  private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  private static final class FailingReloadChannelFactory extends GrpcChannelFactory {
    private final AtomicInteger buildAttempts = new AtomicInteger();

    @Override
    public ManagedChannel buildChannel(
        String target, int defaultPort, CommonGrpcClientProperties properties, boolean keepAlive)
        throws SSLException {
      if (buildAttempts.incrementAndGet() > 1) {
        throw new SSLException("simulated certificate reload failure");
      }
      return mock(ManagedChannel.class);
    }
  }

  private static final class FailingInitialChannelFactory extends GrpcChannelFactory {
    private final AtomicInteger buildAttempts = new AtomicInteger();

    @Override
    public ManagedChannel buildChannel(
        String target, int defaultPort, CommonGrpcClientProperties properties, boolean keepAlive)
        throws SSLException {
      buildAttempts.incrementAndGet();
      throw new SSLException("simulated initial channel build failure");
    }
  }

  private static final class CountingChannelFactory extends GrpcChannelFactory {
    private final AtomicInteger buildAttempts = new AtomicInteger();
    private final ManagedChannel channel = mock(ManagedChannel.class);

    @Override
    public ManagedChannel buildChannel(
        String target, int defaultPort, CommonGrpcClientProperties properties, boolean keepAlive) {
      buildAttempts.incrementAndGet();
      return channel;
    }
  }

  private static final class CertificateChangingChannelFactory extends GrpcChannelFactory {
    private final Path certificate;
    private final AtomicInteger buildAttempts = new AtomicInteger();
    private final List<String> observedCertificates = new CopyOnWriteArrayList<>();

    private CertificateChangingChannelFactory(Path certificate) {
      this.certificate = certificate;
    }

    @Override
    public ManagedChannel buildChannel(
        String target, int defaultPort, CommonGrpcClientProperties properties, boolean keepAlive)
        throws SSLException {
      int attempt = buildAttempts.incrementAndGet();
      try {
        observedCertificates.add(Files.readString(certificate));
        if (attempt == 1) {
          Files.writeString(certificate, "certificate-2");
        }
      } catch (IOException e) {
        throw new SSLException("simulated initial certificate change", e);
      }
      return mock(ManagedChannel.class);
    }
  }

  private static final class TestClient
      extends AbstractReloadingBlockingGrpcClient<
          GameDesignServiceGrpc.GameDesignServiceBlockingStub> {
    private TestClient(
        ServiceEndpointsProperties endpoints,
        CommonGrpcClientProperties grpc,
        GrpcChannelFactory channelFactory) {
      super(endpoints, grpc, channelFactory, TestClient.class);
    }

    private void init() throws Exception {
      initReloadingClient();
    }

    private void initExplicit() throws IOException, SSLException {
      initOnce("test client is closed", "test client has no stub");
    }

    private GameDesignServiceGrpc.GameDesignServiceBlockingStub requireExplicitStub() {
      return requireInitializedStub("test client is not initialized and available");
    }

    private GameDesignServiceGrpc.GameDesignServiceBlockingStub requirePublishedExplicitStub() {
      return withPublishedInitializedStub(
          "test client is not initialized and available", selected -> selected);
    }

    private GameDesignServiceGrpc.GameDesignServiceBlockingStub currentStub() {
      return stub();
    }

    @Override
    protected String configuredTarget(ServiceEndpointsProperties endpoints) {
      return null;
    }

    @Override
    protected String defaultTarget() {
      return "localhost:6565";
    }

    @Override
    protected GameDesignServiceGrpc.GameDesignServiceBlockingStub buildStub(
        ManagedChannel channel) {
      return GameDesignServiceGrpc.newBlockingStub(channel);
    }
  }
}
