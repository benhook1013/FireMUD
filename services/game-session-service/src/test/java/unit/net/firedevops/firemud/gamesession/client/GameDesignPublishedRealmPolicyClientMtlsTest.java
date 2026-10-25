package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyReadGrpcCodec;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesRequest;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedRealmEntryPolicyServiceGrpc;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import support.net.firedevops.firemud.gamesession.PublishedRealmPolicyEvidenceFixture;

/** Physical client transport proof over controlled PKI and stipulated complete owner evidence. */
@Timeout(60)
class GameDesignPublishedRealmPolicyClientMtlsTest {
  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;

  @BeforeAll
  static void certificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Published policy client test CA",
            new LoopbackMtlsTestPki.IdentitySpec(
                "client", "spiffe://firemud/ns/test/sa/game-session-service", false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "server", "spiffe://firemud/ns/test/sa/game-design-service", true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-service", "spiffe://firemud/ns/test/sa/world-management-service", true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-namespace", "spiffe://firemud/ns/other/sa/game-design-service", true));
  }

  @Test
  void initializedClientReadsCompleteSetOnlyFromExactGameDesignPeer() throws Exception {
    exercise("server", true);
  }

  @Test
  void sameCaWrongServiceAndNamespaceReceiveNoRpc() throws Exception {
    for (String identity : List.of("wrong-service", "wrong-namespace")) {
      exercise(identity, false);
    }
  }

  private static void exercise(String identity, boolean accepted) throws Exception {
    var evidence =
        PublishedRealmPolicyEvidenceFixture.fixture(
                PublishedRealmPolicyEvidenceFixture.policy("main", true, true, "SHARED"))
            .set();
    var rpcStarts = new AtomicInteger();
    var observedRequest = new AtomicReference<ListPublishedRealmEntryPoliciesRequest>();
    var observedPeer = new AtomicReference<String>();
    var service =
        new PublishedRealmEntryPolicyServiceGrpc.PublishedRealmEntryPolicyServiceImplBase() {
          @Override
          public void listPublishedRealmEntryPolicies(
              ListPublishedRealmEntryPoliciesRequest request,
              StreamObserver<ListPublishedRealmEntryPoliciesResponse> observer) {
            observedRequest.set(request);
            observedPeer.set(GrpcPeerIdentity.current().uri());
            var exact = PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(request);
            observer.onNext(PublishedRealmEntryPolicyReadGrpcCodec.toResponse(exact, evidence));
            observer.onCompleted();
          }
        };
    var count =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            rpcStarts.incrementAndGet();
            return next.startCall(call, headers);
          }
        };
    var serverIdentity = pki.identity(identity);
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            serverIdentity.privateKey(), serverIdentity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor(), count))
            .build()
            .start();
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("localhost:" + server.getPort());
    try (var client =
        new GameDesignPublishedRealmPolicyClient(
            endpoints,
            pki.clientProperties(directory, pki.identity("client")),
            new GrpcChannelFactory(),
            "test")) {
      client.init();
      if (accepted) {
        var result =
            client.listPublishedRealmEntryPolicies(
                evidence.target().canonicalTenantId(), evidence.target().canonicalVersionId());
        assertThat(result.canonicalBytes()).containsExactly(evidence.canonicalBytes());
        assertThat(rpcStarts).hasValue(1);
        assertThat(observedPeer.get())
            .isEqualTo("spiffe://firemud/ns/test/sa/game-session-service");
        assertThat(observedRequest.get().getTargetNamespace()).isEqualTo("test");
      } else {
        assertThatThrownBy(
                () ->
                    client.listPublishedRealmEntryPolicies(
                        evidence.target().canonicalTenantId(),
                        evidence.target().canonicalVersionId()))
            .isInstanceOfSatisfying(
                StatusRuntimeException.class,
                failure ->
                    assertThat(failure.getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
        assertThat(rpcStarts).hasValue(0);
        assertThat(observedRequest.get()).isNull();
        assertThat(observedPeer.get()).isNull();
      }
    } finally {
      server.shutdownNow();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}
