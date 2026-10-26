package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.AccountStartSessionAdmissionProtectionServiceGrpc;
import net.firedevops.firemud.account.v1.AcquireStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual TLS server construction and purpose bound for the standalone Account receiver. */
class AccountStartSessionAdmissionProtectionManualFactoryTest {
  private static final String NAMESPACE = "world-runtime";

  @TempDir private java.nio.file.Path directory;

  private final AccountStartSessionAdmissionProtectionAcquisitionService owner =
      mock(AccountStartSessionAdmissionProtectionAcquisitionService.class);
  private final List<ManagedChannel> channels = new ArrayList<>();
  private LoopbackMtlsTestPki.Pki pki;
  private Server server;

  @AfterEach
  void stopTransport() throws Exception {
    for (ManagedChannel channel : channels) {
      assertThat(channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
    channels.clear();
    if (server != null) {
      server.shutdownNow();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      server = null;
    }
    SessionContext.clear();
  }

  @Test
  void boundedTlsReceiverRejectsOversizeBeforeOwner() throws Exception {
    startServer();
    var channel = channel("game-session");
    var oversized =
        AcquireStartSessionAdmissionProtectionRequest.newBuilder()
            .addSchemaVersion(1)
            .addRequestCanonicalBytes(
                ByteString.copyFrom(
                    new byte
                        [AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec
                            .MAX_REQUEST_WIRE_BYTES]))
            .build();

    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .acquireStartSessionAdmissionProtection(oversized))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.RESOURCE_EXHAUSTED));
    verifyNoInteractions(owner);
  }

  @Test
  void exactSameNamespaceCertificatePassesGuardAndWrongPeerIsRejected() throws Exception {
    startServer();
    when(owner.acquire(any()))
        .thenThrow(
            Status.FAILED_PRECONDITION
                .withDescription("controlled owner outcome")
                .asRuntimeException());
    var request =
        AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toRequest(
            AccountStartSessionAdmissionProtectionGrpcTestFixture.request(NAMESPACE));

    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionServiceGrpc.newBlockingStub(
                        channel("game-session"))
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .acquireStartSessionAdmissionProtection(request))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION))
        .hasMessageNotContaining("controlled owner outcome");
    verify(owner).acquire(any());

    assertThatThrownBy(
            () ->
                AccountStartSessionAdmissionProtectionServiceGrpc.newBlockingStub(
                        channel("wrong-workload"))
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .acquireStartSessionAdmissionProtection(request))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.PERMISSION_DENIED));
    verify(owner).acquire(any());
  }

  private void startServer() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Account admission protection manual factory test CA",
            new IdentitySpec("account", uri(NAMESPACE, "account-service"), true),
            new IdentitySpec("game-session", uri(NAMESPACE, "game-session-service"), false),
            new IdentitySpec("wrong-workload", uri(NAMESPACE, "world-management-service"), false),
            new IdentitySpec("foreign-namespace", uri("another", "game-session-service"), false));
    server =
        AccountStartSessionAdmissionProtectionManualFactory.createUnregisteredServer(
                "127.0.0.1:0",
                pki.keyManagerFactory(pki.identity("account")),
                pki.trustManagerFactory(),
                owner,
                NAMESPACE)
            .start();
  }

  private ManagedChannel channel(String identityAlias) throws Exception {
    var identity = pki.identity(identityAlias);
    var channel =
        NettyChannelBuilder.forAddress(new InetSocketAddress("127.0.0.1", server.getPort()))
            .sslContext(
                GrpcSslContexts.forClient()
                    .trustManager(pki.caCertificate())
                    .keyManager(identity.privateKey(), identity.certificate())
                    .build())
            .build();
    channels.add(channel);
    return channel;
  }

  private static String uri(String namespace, String service) {
    return "spiffe://firemud/ns/" + namespace + "/sa/" + service;
  }
}
