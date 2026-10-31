package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.StatusProto;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AddCurrencyRequest;
import net.firedevops.firemud.account.v1.ExportAccountRequest;
import net.firedevops.firemud.account.v1.ExportTenantDataRequest;
import net.firedevops.firemud.account.v1.GetBalanceRequest;
import net.firedevops.firemud.account.v1.LinkExternalAccountRequest;
import net.firedevops.firemud.account.v1.RequestEmailVerificationRequest;
import net.firedevops.firemud.account.v1.SpendCurrencyRequest;
import net.firedevops.firemud.account.v1.VirtualCurrencyServiceGrpc;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.PingService;
import net.firedevops.firemud.common.grpc.MetricsInterceptor;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;

/** Transport-only loopback proof; workload TLS and authorization belong to the peer fixtures. */
class DeferredAccountGrpcTransportTest {
  private static final String ACCOUNT_ID = "11111111-1111-4111-8111-111111111111";
  private static final String CALLER_UNAVAILABLE = "No authorized internal caller is configured";

  @Test
  void allSevenDeferredAdaptersReturnNonOkDetailsWithoutOwnerAccessOnRepeatedCalls()
      throws Exception {
    AccountService owner = mock(AccountService.class);
    PingService ping = mock(PingService.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try {
      registry.config().commonTags("service", "account-service");
      try (TransportServer server = startServer(owner, ping, registry);
          ClientTransport client =
              new ClientTransport(
                  NettyChannelBuilder.forAddress("127.0.0.1", server.port())
                      .usePlaintext()
                      .build())) {
        var account = AccountServiceGrpc.newBlockingStub(client.channel());
        var currency = VirtualCurrencyServiceGrpc.newBlockingStub(client.channel());
        List<Refusal> calls =
            List.of(
                new Refusal(
                    () ->
                        currency
                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                            .getBalance(
                                GetBalanceRequest.newBuilder()
                                    .setTenantId("1")
                                    .setAccountId(ACCOUNT_ID)
                                    .setCurrencyCode("GOLD")
                                    .build()),
                    "GetBalance is unavailable"),
                new Refusal(
                    () ->
                        currency
                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                            .addCurrency(
                                AddCurrencyRequest.newBuilder()
                                    .setTenantId("1")
                                    .setAccountId(ACCOUNT_ID)
                                    .setCurrencyCode("GOLD")
                                    .setAmount(10)
                                    .build()),
                    "AddCurrency is unavailable"),
                new Refusal(
                    () ->
                        currency
                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                            .spendCurrency(
                                SpendCurrencyRequest.newBuilder()
                                    .setTenantId("1")
                                    .setAccountId(ACCOUNT_ID)
                                    .setCurrencyCode("GOLD")
                                    .setAmount(5)
                                    .build()),
                    "SpendCurrency is unavailable"),
                new Refusal(
                    () ->
                        account
                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                            .exportAccount(
                                ExportAccountRequest.newBuilder().setAccountId(ACCOUNT_ID).build()),
                    CALLER_UNAVAILABLE),
                new Refusal(
                    () ->
                        account
                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                            .exportTenantData(
                                ExportTenantDataRequest.newBuilder()
                                    .setTenantId("1")
                                    .setAccountId(ACCOUNT_ID)
                                    .build()),
                    CALLER_UNAVAILABLE),
                new Refusal(
                    () ->
                        account
                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                            .linkExternalAccount(
                                LinkExternalAccountRequest.newBuilder()
                                    .setTenantId("1")
                                    .setAccountId(ACCOUNT_ID)
                                    .setProvider("example")
                                    .setExternalId("untrusted-subject")
                                    .build()),
                    CALLER_UNAVAILABLE),
                new Refusal(
                    () ->
                        account
                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                            .requestEmailVerification(
                                RequestEmailVerificationRequest.newBuilder()
                                    .setAccountId(ACCOUNT_ID)
                                    .build()),
                    CALLER_UNAVAILABLE));

        int refusedCalls = 0;
        for (int attempt = 0; attempt < 2; attempt++) {
          for (Refusal call : calls) {
            assertRefused(call);
            refusedCalls++;
            verifyNoInteractions(owner, ping);
            assertEquals(
                (double) refusedCalls,
                registry
                    .get("grpc.app_error")
                    .tags("service", "account-service", "code", "FAILED_PRECONDITION")
                    .counter()
                    .count());
          }
        }
        List<Meter> errorMeters =
            registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals("grpc.app_error"))
                .toList();
        assertEquals(1, errorMeters.size());
        assertEquals(
            List.of("code", "service"),
            errorMeters.get(0).getId().getTags().stream().map(tag -> tag.getKey()).toList());
      }
    } finally {
      registry.close();
    }
  }

  private static void assertRefused(Refusal call) throws Exception {
    StatusRuntimeException error = assertThrows(StatusRuntimeException.class, call.invoke()::run);
    assertEquals(Status.Code.FAILED_PRECONDITION, error.getStatus().getCode());
    assertEquals(call.message(), error.getStatus().getDescription());
    com.google.rpc.Status status = StatusProto.fromThrowable(error);
    assertNotNull(status);
    assertEquals(Status.Code.FAILED_PRECONDITION.value(), status.getCode());
    assertEquals(call.message(), status.getMessage());
    assertEquals(1, status.getDetailsCount());
    assertEquals(
        ErrorDetail.newBuilder().setCode("FAILED_PRECONDITION").setMessage(call.message()).build(),
        status.getDetails(0).unpack(ErrorDetail.class));
  }

  private static TransportServer startServer(
      AccountService owner, PingService ping, SimpleMeterRegistry registry) throws Exception {
    return new TransportServer(
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .intercept(new MetricsInterceptor(registry))
            .addService(new AccountGrpcService(ping, owner, registry))
            .addService(new VirtualCurrencyGrpcService(registry))
            .build()
            .start());
  }

  private record Refusal(Runnable invoke, String message) {}

  private record TransportServer(Server server) implements AutoCloseable {
    private int port() {
      return server.getPort();
    }

    @Override
    public void close() throws InterruptedException {
      if (!server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Loopback deferred Account server did not terminate");
      }
    }
  }

  private record ClientTransport(ManagedChannel channel) implements AutoCloseable {
    @Override
    public void close() throws InterruptedException {
      if (!channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Loopback deferred Account client did not terminate");
      }
    }
  }
}
