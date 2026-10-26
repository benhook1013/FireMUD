package net.firedevops.firemud.gamesession.service.impl;

import static net.firedevops.firemud.gamesession.repository.AccountInitialAdmissionProtectionBindingRepositoryTest.NAMESPACE;
import static net.firedevops.firemud.gamesession.repository.AccountInitialAdmissionProtectionBindingRepositoryTest.TERMINAL;
import static net.firedevops.firemud.gamesession.repository.AccountInitialAdmissionProtectionBindingRepositoryTest.protection;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.Server;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.account.startsession.AccountInitialAdmissionSettlementReadEvidence;
import net.firedevops.firemud.common.account.startsession.AccountInitialAdmissionSettlementReadGrpcCodec;
import net.firedevops.firemud.common.account.startsession.AccountInitialAdmissionSettlementReadInput;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.service.AccountInitialAdmissionSettlementReadService;
import net.firedevops.firemud.gamesession.v1.AccountInitialAdmissionSettlementReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetAccountInitialAdmissionSettlementRequest;
import net.firedevops.firemud.gamesession.v1.GetAccountInitialAdmissionSettlementResponse;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual loopback mTLS handler with a controlled terminal owner double, not owner persistence. */
class AccountInitialAdmissionSettlementReadGrpcServiceTest {
  @TempDir Path directory;
  private final AccountInitialAdmissionSettlementReadService owner =
      mock(AccountInitialAdmissionSettlementReadService.class);

  @AfterEach
  void clearPlayerContext() {
    SessionContext.clear();
  }

  @Test
  void authenticatedAccountGetsExactBytesOverRealMtlsAndWrongPeersNeverReachOwner()
      throws Exception {
    var pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Account settlement read test CA",
            new LoopbackMtlsTestPki.IdentitySpec(
                "server", uri(NAMESPACE, "game-session-service"), true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "account", uri(NAMESPACE, "account-service"), false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "world", uri(NAMESPACE, "world-management-service"), false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "foreign", uri("foreign", "account-service"), false));
    var identity = pki.identity("server");
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .maxInboundMessageSize(
                AccountInitialAdmissionSettlementReadGrpcCodec.MAX_REQUEST_WIRE_BYTES)
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(identity.privateKey(), identity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(ServerInterceptors.intercept(service(), new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    try {
      for (String alias : List.of("world", "foreign")) {
        ManagedChannel channel = channel(pki, alias, server.getPort());
        try {
          assertThatThrownBy(
                  () ->
                      stub(channel)
                          .getAccountInitialAdmissionSettlement(
                              GetAccountInitialAdmissionSettlementRequest.getDefaultInstance()))
              .isInstanceOfSatisfying(
                  StatusRuntimeException.class,
                  error ->
                      assertThat(error.getStatus().getCode())
                          .isEqualTo(Status.Code.PERMISSION_DENIED));
        } finally {
          channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
      }
      verifyNoInteractions(owner);
      var input = new AccountInitialAdmissionSettlementReadInput(UUID.randomUUID(), protection());
      // Decode a separate value to prove response correlation is canonical-byte equality, not
      // Java reference equality of protection value objects.
      var retained =
          net.firedevops.firemud.common.account.startsession
              .AccountStartSessionAdmissionProtectionEvidence.decode(
              input.protection().canonicalBytes());
      var terminal =
          new GameSessionCanonicalInitialAdmissionOwnerProof(
              retained.request().worldAdmissionHoldIdentity(),
              GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED,
              null,
              null,
              "sha256:" + "c".repeat(64),
              true,
              TERMINAL);
      when(owner.read(any()))
          .thenReturn(
              new AccountInitialAdmissionSettlementReadEvidence(
                  input.readRequestId(), retained, terminal));
      ManagedChannel channel = channel(pki, "account", server.getPort());
      try {
        var response =
            stub(channel)
                .getAccountInitialAdmissionSettlement(
                    AccountInitialAdmissionSettlementReadGrpcCodec.toRequest(input, NAMESPACE));
        var readback = AccountInitialAdmissionSettlementReadGrpcCodec.fromResponse(input, response);
        assertThat(readback.protection().canonicalBytes())
            .containsExactly(retained.canonicalBytes());
        assertThat(readback.terminal()).isEqualTo(terminal);
      } finally {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    } finally {
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void peerAndEndUserDenialsPrecedeMalformedDecodeAndOwnerLookup() {
    var malformed = GetAccountInitialAdmissionSettlementRequest.getDefaultInstance();
    assertThat(invoke(null, malformed)).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(invoke(peer(NAMESPACE, "world-management-service"), malformed))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(invoke(peer("foreign", "account-service"), malformed))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    SessionContext.setContext(UUID.randomUUID().toString(), List.of(), Map.of());
    assertThat(invoke(peer(NAMESPACE, "account-service"), malformed))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(owner);
  }

  @Test
  void malformedAndForeignSelectorsRefuseBeforeOwnerAndKnownUnprovedFactsStayClosed() {
    var account = peer(NAMESPACE, "account-service");
    assertThat(invoke(account, GetAccountInitialAdmissionSettlementRequest.getDefaultInstance()))
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    var input = new AccountInitialAdmissionSettlementReadInput(UUID.randomUUID(), protection());
    var request = AccountInitialAdmissionSettlementReadGrpcCodec.toRequest(input, NAMESPACE);
    assertThat(invoke(account, request.toBuilder().setTargetNamespace(0, "foreign").build()))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(owner);
    doThrow(
            new AccountInitialAdmissionSettlementReadService.SettlementUnavailableException(
                "not terminal"))
        .when(owner)
        .read(any());
    assertThat(invoke(account, request)).isEqualTo(Status.Code.FAILED_PRECONDITION);
    doThrow(new org.jooq.exception.DataAccessException("storage unavailable"))
        .when(owner)
        .read(any());
    assertThat(invoke(account, request)).isEqualTo(Status.Code.UNAVAILABLE);
    doThrow(new UnsupportedOperationException("unexpected")).when(owner).read(any());
    assertThat(invoke(account, request)).isEqualTo(Status.Code.INTERNAL);
  }

  private AccountInitialAdmissionSettlementReadGrpcService service() {
    return new AccountInitialAdmissionSettlementReadGrpcService(owner, NAMESPACE);
  }

  private Status.Code invoke(
      GrpcPeerIdentity peer, GetAccountInitialAdmissionSettlementRequest request) {
    AtomicReference<Status.Code> error = new AtomicReference<>();
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    context.run(
        () ->
            service()
                .getAccountInitialAdmissionSettlement(
                    request,
                    new StreamObserver<>() {
                      @Override
                      public void onNext(GetAccountInitialAdmissionSettlementResponse value) {}

                      @Override
                      public void onError(Throwable failure) {
                        error.set(Status.fromThrowable(failure).getCode());
                      }

                      @Override
                      public void onCompleted() {}
                    }));
    return error.get();
  }

  private ManagedChannel channel(LoopbackMtlsTestPki.Pki pki, String alias, int port)
      throws Exception {
    return new GrpcChannelFactory()
        .buildChannel(
            "127.0.0.1:" + port, port, pki.clientProperties(directory, pki.identity(alias)), true);
  }

  private static AccountInitialAdmissionSettlementReadServiceGrpc
          .AccountInitialAdmissionSettlementReadServiceBlockingStub
      stub(ManagedChannel channel) {
    return AccountInitialAdmissionSettlementReadServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(20, TimeUnit.SECONDS);
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return new GrpcPeerIdentity(uri(namespace, service), namespace, service);
  }

  private static String uri(String namespace, String service) {
    return "spiffe://firemud/ns/" + namespace + "/sa/" + service;
  }
}
