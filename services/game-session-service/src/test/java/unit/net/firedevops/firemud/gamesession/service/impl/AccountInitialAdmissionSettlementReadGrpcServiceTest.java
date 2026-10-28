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
import java.sql.SQLException;
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
import org.springframework.dao.DataAccessResourceFailureException;

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
  void constructionRejectsMissingOrInvalidWorkloadNamespaceBeforeOwnerAccess() {
    for (String namespace :
        java.util.Arrays.asList(
            null, "", " ", "Owner-Namespace", "invalid_namespace", "x".repeat(64))) {
      assertThatThrownBy(
              () -> new AccountInitialAdmissionSettlementReadGrpcService(owner, namespace))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Valid settlement workload namespace required");
    }
    verifyNoInteractions(owner);
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
    var unavailableTerminal =
        new AccountInitialAdmissionSettlementReadService.SettlementUnavailableException(
            "not terminal");
    doThrow(unavailableTerminal).when(owner).read(any());
    var knownRefusal = invokeStatus(account, request);
    assertThat(knownRefusal.getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(knownRefusal.getDescription()).isEqualTo("Exact protected terminal is unavailable");
    assertThat(knownRefusal.getCause()).isSameAs(unavailableTerminal);
    var encodingFailure = new IllegalArgumentException("private encoding detail");
    doThrow(encodingFailure).when(owner).read(any());
    var invalidEvidence = invokeStatus(account, request);
    assertThat(invalidEvidence.getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(invalidEvidence.getDescription())
        .isEqualTo("Exact protected terminal is unavailable")
        .doesNotContain("private");
    assertThat(invalidEvidence.getCause()).isSameAs(encodingFailure);

    doThrow(new org.jooq.exception.TooManyRowsException("private cardinality detail"))
        .when(owner)
        .read(any());
    var tooManyRows = invokeStatus(account, request);
    assertThat(tooManyRows.getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(tooManyRows.getDescription())
        .isEqualTo("Exact protected terminal is unavailable")
        .doesNotContain("private cardinality detail");

    doThrow(
            new org.jooq.exception.DataAccessException(
                "private storage detail", new SQLException("private SQL detail", "08006")))
        .when(owner)
        .read(any());
    var connectionFailure = invokeStatus(account, request);
    assertThat(connectionFailure.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(connectionFailure.getDescription())
        .isEqualTo("Settlement owner is unavailable")
        .doesNotContain("private");

    doThrow(new DataAccessResourceFailureException("private resource failure"))
        .when(owner)
        .read(any());
    var resourceFailure = invokeStatus(account, request);
    assertThat(resourceFailure.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(resourceFailure.getDescription())
        .isEqualTo("Settlement owner is unavailable")
        .doesNotContain("private");

    doThrow(
            new org.jooq.exception.DataAccessException(
                "private storage detail", new SQLException("private SQL detail", "42P01")))
        .when(owner)
        .read(any());
    var permanentSqlFailure = invokeStatus(account, request);
    assertThat(permanentSqlFailure.getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(permanentSqlFailure.getDescription())
        .isEqualTo("Settlement owner read failed")
        .doesNotContain("private");
    assertThat(permanentSqlFailure.getCause())
        .isInstanceOf(org.jooq.exception.DataAccessException.class);

    var untypedStorageFailure =
        new org.jooq.exception.DataAccessException("private untyped storage failure");
    doThrow(untypedStorageFailure).when(owner).read(any());
    var untypedFailure = invokeStatus(account, request);
    assertThat(untypedFailure.getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(untypedFailure.getDescription())
        .isEqualTo("Settlement owner read failed")
        .doesNotContain("private");
    assertThat(untypedFailure.getCause()).isSameAs(untypedStorageFailure);

    doThrow(new UnsupportedOperationException("unexpected")).when(owner).read(any());
    var unexpected = invokeStatus(account, request);
    assertThat(unexpected.getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(unexpected.getDescription()).isEqualTo("Settlement owner read failed");
    assertThat(unexpected.getCause()).isInstanceOf(UnsupportedOperationException.class);
  }

  private AccountInitialAdmissionSettlementReadGrpcService service() {
    return new AccountInitialAdmissionSettlementReadGrpcService(owner, NAMESPACE);
  }

  private Status.Code invoke(
      GrpcPeerIdentity peer, GetAccountInitialAdmissionSettlementRequest request) {
    return invokeStatus(peer, request).getCode();
  }

  private Status invokeStatus(
      GrpcPeerIdentity peer, GetAccountInitialAdmissionSettlementRequest request) {
    AtomicReference<Status> error = new AtomicReference<>();
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
                        error.set(Status.fromThrowable(failure));
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
