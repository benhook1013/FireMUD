package unit.net.firedevops.firemud.gamedesign.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.account.v1.ResolveCurrentTenantCreationAuthorizationRequest;
import net.firedevops.firemud.account.v1.ResolveCurrentTenantCreationAuthorizationResponse;
import net.firedevops.firemud.account.v1.TenantCreationCurrentAuthorizationReadServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.tenant.CurrentTenantCreationAuthorizationGrpcCodec;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Binding;
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

  @Test
  void shorterIncomingDeadlineStillCapsAnActualAuthorizationRead() throws Exception {
    var binding =
        new Binding(
            "test",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "sha256:" + "a".repeat(64),
            "sha256:" + "b".repeat(64));
    var reads = new AtomicInteger();
    var cappedReadBudgetNanos = new AtomicLong();
    var cappedReadCancelled = new CountDownLatch(1);
    var endpoint =
        new TenantCreationCurrentAuthorizationReadServiceGrpc
            .TenantCreationCurrentAuthorizationReadServiceImplBase() {
          @Override
          public void resolveCurrentTenantCreationAuthorization(
              ResolveCurrentTenantCreationAuthorizationRequest request,
              StreamObserver<ResolveCurrentTenantCreationAuthorizationResponse> observer) {
            if (reads.incrementAndGet() == 1) {
              var decoded = CurrentTenantCreationAuthorizationGrpcCodec.decodeRequest(request);
              observer.onNext(
                  CurrentTenantCreationAuthorizationGrpcCodec.response(
                      decoded.readRequestId(),
                      decoded.binding(),
                      System.currentTimeMillis() + 60_000L));
              observer.onCompleted();
              return;
            }
            cappedReadBudgetNanos.set(
                Objects.requireNonNull(Context.current().getDeadline())
                    .timeRemaining(TimeUnit.NANOSECONDS));
            ((ServerCallStreamObserver<ResolveCurrentTenantCreationAuthorizationResponse>) observer)
                .setOnCancelHandler(cappedReadCancelled::countDown);
          }
        };
    var client =
        new AccountTenantCreationCurrentAuthorizationClient(
            new ServiceEndpointsProperties(), tlsFiles(), mock(GrpcChannelFactory.class), "test");
    // This controlled loopback transport exercises deadlines; it supplies no mTLS identity proof.
    var server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .directExecutor()
            .addService(endpoint)
            .build()
            .start();
    var channel =
        NettyChannelBuilder.forAddress("127.0.0.1", server.getPort())
            .usePlaintext()
            .directExecutor()
            .build();
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    try (client) {
      var stub = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
      stub.setAccessible(true);
      stub.set(client, TenantCreationCurrentAuthorizationReadServiceGrpc.newBlockingStub(channel));
      var initialized =
          AbstractReloadingBlockingGrpcClient.class.getDeclaredField("explicitlyInitialized");
      initialized.setAccessible(true);
      initialized.setBoolean(client, true);
      // Establish the actual connection before testing the shorter incoming deadline.
      assertThat(client.resolveCurrent(binding).binding()).isEqualTo(binding);
      try (var incoming = Context.current().withDeadlineAfter(1, TimeUnit.SECONDS, scheduler)) {
        var incomingCancelled = new CountDownLatch(1);
        incoming.addListener(ignored -> incomingCancelled.countDown(), Runnable::run);
        incoming.run(
            () ->
                assertThatThrownBy(() -> client.resolveCurrent(binding))
                    .isInstanceOf(StatusRuntimeException.class)
                    .extracting(failure -> ((StatusRuntimeException) failure).getStatus().getCode())
                    .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
        assertThat(incomingCancelled.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(incoming.isCancelled()).isTrue();
      }
      assertThat(reads.get()).isEqualTo(2);
      assertThat(cappedReadBudgetNanos.get()).isBetween(1L, TimeUnit.SECONDS.toNanos(1));
      assertThat(cappedReadCancelled.await(2, TimeUnit.SECONDS)).isTrue();
    } finally {
      scheduler.shutdownNow();
      channel.shutdownNow();
      server.shutdownNow();
      channel.awaitTermination(5, TimeUnit.SECONDS);
      server.awaitTermination(5, TimeUnit.SECONDS);
    }
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
