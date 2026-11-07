package net.firedevops.firemud.worldmanagement.client;

import static net.firedevops.firemud.common.account.authority.TenantOperationEntitlementTransportFixture.evidence;
import static net.firedevops.firemud.common.account.authority.TenantOperationEntitlementTransportFixture.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.stub.StreamObserver;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.AccountTenantOperationEntitlementReadServiceGrpc;
import net.firedevops.firemud.account.v1.ReadTenantOperationEntitlementRequest;
import net.firedevops.firemud.account.v1.ReadTenantOperationEntitlementResponse;
import net.firedevops.firemud.accountservice.service.AccountTenantOperationEntitlementOwner;
import net.firedevops.firemud.accountservice.service.impl.AccountTenantOperationEntitlementGrpcService;
import net.firedevops.firemud.common.account.authority.RuntimeTenantOperationEntitlementGrpcCodec;
import net.firedevops.firemud.common.account.authority.RuntimeTenantOperationEntitlementGrpcCodec.SourceKind;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Real loopback mTLS with a controlled source owner; no physical source/currentness qualification.
 */
class AccountTenantOperationEntitlementClientMtlsTest {
  @Test
  void protectedReceiverDispatchesExplicitSourcesWithAuthenticatedWorkloadAndNoFallback(
      @TempDir Path temporary) throws Exception {
    var pki = pki(temporary);
    var owner = mock(AccountTenantOperationEntitlementOwner.class);
    var paid = request(SourceKind.PAID_HOSTING);
    var demo = request(SourceKind.NON_PAID_DEMO);
    when(owner.evaluatePaid(any()))
        .thenAnswer(
            invocation -> {
              var actual =
                  (AccountTenantOperationEntitlementOwner.Request) invocation.getArgument(0);
              assertThat(actual)
                  .isEqualTo(
                      new AccountTenantOperationEntitlementOwner.Request(
                          paid.tenantId(), paid.requestId(), paid.expectedWorldWorkload()));
              return evidence(paid);
            });
    when(owner.evaluateDemo(any())).thenReturn(evidence(demo));
    var receiver = new AccountTenantOperationEntitlementGrpcService(owner, "firemud");
    Server server =
        receiver
            .createUnregisteredServer(
                "localhost:0",
                pki.keyManagerFactory(pki.identity("account")),
                pki.trustManagerFactory())
            .start();
    try {
      try (var client = client(pki, temporary, "world", server.getPort())) {
        assertThatThrownBy(() -> client.read(paid)).isInstanceOf(IllegalStateException.class);
        client.init();
        var read = client.read(paid);
        assertThat(read.request()).isEqualTo(paid);
        assertThat(read.evidence()).isEqualTo(evidence(paid));
        assertThat(client.read(demo).evidence()).isEqualTo(evidence(demo));
        doThrow(
                new AccountTenantOperationEntitlementOwner.SourceUnavailableException(
                    "private source detail"))
            .when(owner)
            .evaluatePaid(any());
        assertStatus(() -> client.read(paid), Status.Code.UNAVAILABLE);
        for (RuntimeException failure :
            new RuntimeException[] {
              new IllegalArgumentException("private namespace mismatch"),
              new NullPointerException("private codec failure")
            }) {
          doThrow(failure).when(owner).evaluatePaid(any());
          assertThatThrownBy(() -> client.read(paid))
              .satisfies(
                  thrown -> {
                    assertThat(Status.fromThrowable(thrown).getCode())
                        .isEqualTo(Status.Code.INTERNAL);
                    var details = io.grpc.protobuf.StatusProto.fromThrowable(thrown);
                    assertThat(details).isNotNull();
                    assertThat(details.getDetailsCount()).isEqualTo(1);
                    var detail =
                        details
                            .getDetails(0)
                            .unpack(net.firedevops.firemud.shared.v1.ErrorDetail.class);
                    assertThat(detail.getCode()).isEqualTo("INTERNAL");
                    assertThat(detail.getMessage()).doesNotContain("private");
                  });
        }
        verify(owner, org.mockito.Mockito.times(1)).evaluateDemo(any());
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
          assertThatThrownBy(() -> client.read(demo)).isInstanceOf(IllegalStateException.class);
        } finally {
          TransactionSynchronizationManager.setActualTransactionActive(false);
        }
      }
      for (String wrong : new String[] {"wrong-client", "other-world"}) {
        try (var client = client(pki, temporary, wrong, server.getPort())) {
          client.init();
          assertStatus(() -> client.read(paid), Status.Code.PERMISSION_DENIED);
        }
      }
    } finally {
      stop(server);
    }
    // Calling a handler directly, even with a syntactically valid request, supplies no TLS
    // assertion.
    var observer = new OutcomeObserver();
    receiver.readTenantOperationEntitlement(
        RuntimeTenantOperationEntitlementGrpcCodec.toRequest(paid), observer);
    assertThat(observer.failureCode).isEqualTo(Status.Code.UNAUTHENTICATED);
  }

  @Test
  void clientRefusesWrongOwnerBeforeSendingAndRejectsChangedOrMalformedEvidence(
      @TempDir Path temporary) throws Exception {
    var pki = pki(temporary);
    var request = request(SourceKind.PAID_HOSTING);
    var response =
        new AtomicReference<>(
            RuntimeTenantOperationEntitlementGrpcCodec.toResponse(request, evidence(request)));
    var calls = new AtomicInteger();
    for (String alias : new String[] {"wrong-server", "other-account", "account"}) {
      var identity = pki.identity(alias);
      Server server =
          NettyServerBuilder.forPort(0)
              .sslContext(
                  GrpcSslContexts.configure(
                          SslContextBuilder.forServer(
                              identity.privateKey(), identity.certificate()))
                      .trustManager(pki.caCertificate())
                      .clientAuth(ClientAuth.REQUIRE)
                      .build())
              .addService(
                  new AccountTenantOperationEntitlementReadServiceGrpc
                      .AccountTenantOperationEntitlementReadServiceImplBase() {
                    @Override
                    public void readTenantOperationEntitlement(
                        ReadTenantOperationEntitlementRequest ignored,
                        StreamObserver<ReadTenantOperationEntitlementResponse> observer) {
                      calls.incrementAndGet();
                      observer.onNext(response.get());
                      observer.onCompleted();
                    }
                  })
              .build()
              .start();
      try (var client = client(pki, temporary, "world", server.getPort())) {
        client.init();
        if (!"account".equals(alias)) {
          assertStatus(() -> client.read(request), Status.Code.UNAUTHENTICATED);
          assertThat(calls).hasValue(0);
        } else {
          assertThat(client.read(request).evidence()).isEqualTo(evidence(request));
          var changed =
              new RuntimeTenantOperationEntitlementGrpcCodec.Request(
                  "firemud", request.tenantId(), UUID.randomUUID(), request.sourceKind());
          response.set(
              response.get().toBuilder()
                  .setCanonicalEvidence(
                      0,
                      com.google.protobuf.ByteString.copyFrom(evidence(changed).canonicalBytes()))
                  .build());
          assertThatThrownBy(() -> client.read(request)).isInstanceOf(IllegalStateException.class);
          response.set(
              response.get().toBuilder()
                  .addCanonicalEvidence(response.get().getCanonicalEvidence(0))
                  .build());
          assertThatThrownBy(() -> client.read(request)).isInstanceOf(IllegalStateException.class);
        }
      } finally {
        stop(server);
      }
    }
  }

  @Test
  void clientRejectsPlaintextBeforeChannelCreation() {
    var properties = new net.firedevops.firemud.common.grpc.CommonGrpcClientProperties();
    properties.setPlaintext(true);
    var channels = mock(GrpcChannelFactory.class);
    assertThatThrownBy(
            () ->
                new AccountTenantOperationEntitlementClient(
                    new ServiceEndpointsProperties(), properties, channels, "firemud"))
        .isInstanceOf(IllegalArgumentException.class);
    org.mockito.Mockito.verifyNoInteractions(channels);
  }

  @Test
  void receiverRejectsMalformedUnknownDuplicateWrongNamespaceAndOversizedRequestsBeforeSource(
      @TempDir Path temporary) throws Exception {
    var pki = pki(temporary);
    var owner = mock(AccountTenantOperationEntitlementOwner.class);
    var receiver = new AccountTenantOperationEntitlementGrpcService(owner, "firemud");
    Server server =
        receiver
            .createUnregisteredServer(
                "localhost:0",
                pki.keyManagerFactory(pki.identity("account")),
                pki.trustManagerFactory())
            .start();
    var channel =
        new GrpcChannelFactory()
            .buildChannel(
                "localhost:" + server.getPort(),
                6565,
                pki.clientProperties(temporary, pki.identity("world")),
                false);
    try {
      var stub =
          AccountTenantOperationEntitlementReadServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(5, TimeUnit.SECONDS);
      var wire =
          RuntimeTenantOperationEntitlementGrpcCodec.toRequest(request(SourceKind.PAID_HOSTING));
      var unknown =
          com.google.protobuf.UnknownFieldSet.newBuilder()
              .addField(
                  99, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1).build())
              .build();
      for (var invalid :
          java.util.List.of(
              wire.toBuilder().clearSourceKind().build(),
              wire.toBuilder().addRequestId(wire.getRequestId(0)).build(),
              wire.toBuilder().setUnknownFields(unknown).build())) {
        assertStatus(
            () -> stub.readTenantOperationEntitlement(invalid), Status.Code.INVALID_ARGUMENT);
      }
      assertStatus(
          () ->
              stub.readTenantOperationEntitlement(
                  wire.toBuilder().setTargetNamespace(0, "other").build()),
          Status.Code.PERMISSION_DENIED);
      assertThatThrownBy(
              () ->
                  stub.readTenantOperationEntitlement(
                      wire.toBuilder().setSourceKind(0, "a".repeat(2048)).build()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isIn(Status.Code.RESOURCE_EXHAUSTED, Status.Code.INVALID_ARGUMENT));
      org.mockito.Mockito.verifyNoInteractions(owner);
    } finally {
      channel.shutdownNow();
      assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      stop(server);
    }
  }

  private static LoopbackMtlsTestPki.Pki pki(Path temporary) throws Exception {
    return LoopbackMtlsTestPki.create(
        temporary,
        "Tenant operation transport test CA",
        identity("account", "firemud", "account-service", true),
        identity("wrong-server", "firemud", "game-session-service", true),
        identity("other-account", "other", "account-service", true),
        identity("world", "firemud", "world-management-service", false),
        identity("wrong-client", "firemud", "game-session-service", false),
        identity("other-world", "other", "world-management-service", false));
  }

  private static IdentitySpec identity(
      String alias, String namespace, String service, boolean server) {
    return new IdentitySpec(alias, "spiffe://firemud/ns/" + namespace + "/sa/" + service, server);
  }

  private static AccountTenantOperationEntitlementClient client(
      LoopbackMtlsTestPki.Pki pki, Path temporary, String alias, int port) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("localhost:" + port);
    return new AccountTenantOperationEntitlementClient(
        endpoints,
        pki.clientProperties(temporary, pki.identity(alias)),
        new GrpcChannelFactory(),
        "firemud");
  }

  private static void assertStatus(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action, Status.Code code) {
    assertThatThrownBy(action)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(code));
  }

  private static void stop(Server server) throws Exception {
    server.shutdownNow();
    assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  private static final class OutcomeObserver
      implements StreamObserver<ReadTenantOperationEntitlementResponse> {
    private Status.Code failureCode;

    @Override
    public void onNext(ReadTenantOperationEntitlementResponse response) {
      throw new AssertionError("Unauthenticated direct handler must not return evidence");
    }

    @Override
    public void onError(Throwable failure) {
      failureCode = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      throw new AssertionError("Unexpected completion");
    }
  }
}
