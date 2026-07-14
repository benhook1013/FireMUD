package net.firedevops.firemud.socialgroups.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.ListPresenceVisibilityPoliciesRequest;
import net.firedevops.firemud.account.v1.ListPresenceVisibilityPoliciesResponse;
import net.firedevops.firemud.account.v1.PresenceVisibilityPolicyEntry;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceVisibilityPolicyValue;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AccountClientTest {

  @Test
  void batchesPresenceVisibilityPolicyReadsAtTheAccountServiceLimit() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.listPresenceVisibilityPolicies(any()))
        .thenAnswer(
            invocation -> {
              ListPresenceVisibilityPoliciesRequest request = invocation.getArgument(0);
              return ListPresenceVisibilityPoliciesResponse.newBuilder()
                  .addAllPolicies(
                      request.getAccountIdsList().stream()
                          .map(
                              accountId ->
                                  PresenceVisibilityPolicyEntry.newBuilder()
                                      .setAccountId(accountId)
                                      .setPolicy("FRIENDS_ONLY")
                                      .build())
                          .toList())
                  .build();
            });
    AccountClient client = newClient(stub);
    List<Long> accountIds = LongStream.rangeClosed(1L, 101L).boxed().toList();

    Map<Long, FriendPresenceVisibilityPolicyValue> policies =
        client.getPresenceVisibilityPolicies(11L, accountIds);

    assertThat(policies)
        .hasSize(101)
        .containsEntry(1L, FriendPresenceVisibilityPolicyValue.FRIENDS_ONLY)
        .containsEntry(101L, FriendPresenceVisibilityPolicyValue.FRIENDS_ONLY);
    ArgumentCaptor<ListPresenceVisibilityPoliciesRequest> requests =
        ArgumentCaptor.forClass(ListPresenceVisibilityPoliciesRequest.class);
    verify(stub, times(2)).listPresenceVisibilityPolicies(requests.capture());
    assertThat(requests.getAllValues())
        .extracting(ListPresenceVisibilityPoliciesRequest::getAccountIdsCount)
        .containsExactly(100, 1);
    assertThat(requests.getAllValues())
        .extracting(ListPresenceVisibilityPoliciesRequest::getTenantId)
        .containsOnly("11");
  }

  @Test
  void buildStubAppliesInjectedStubCustomizer() {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    java.util.concurrent.atomic.AtomicInteger customizeCalls =
        new java.util.concurrent.atomic.AtomicInteger();
    BlockingGrpcStubCustomizer stubCustomizer =
        new BlockingGrpcStubCustomizer() {
          @Override
          public <T extends io.grpc.stub.AbstractStub<T>> T customize(T candidate) {
            customizeCalls.incrementAndGet();
            return candidate;
          }
        };
    AccountClient client =
        new AccountClient(
            endpoints,
            grpc,
            mock(GrpcChannelFactory.class),
            stubCustomizer,
            new SimpleMeterRegistry());

    invokeBuildStub(client, mock(ManagedChannel.class));

    assertThat(customizeCalls.get()).isEqualTo(1);
  }

  @Test
  void recordsApplicationErrorsFromPresenceVisibilityPolicyReads() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.listPresenceVisibilityPolicies(any()))
        .thenReturn(
            ListPresenceVisibilityPoliciesResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode("UNAVAILABLE").setMessage("Retry later"))
                .build());
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    AccountClient client = newClient(stub, meterRegistry);

    assertThat(client.getPresenceVisibilityPolicies(11L, List.of(1L))).isEmpty();
    assertThat(meterRegistry.counter("grpc.app_error", "code", "UNAVAILABLE").count())
        .isEqualTo(1.0d);
  }

  private static AccountClient newClient(AccountServiceGrpc.AccountServiceBlockingStub stub)
      throws Exception {
    return newClient(stub, new SimpleMeterRegistry());
  }

  private static AccountClient newClient(
      AccountServiceGrpc.AccountServiceBlockingStub stub, MeterRegistry meterRegistry)
      throws Exception {
    AccountClient client =
        new AccountClient(
            new ServiceEndpointsProperties(),
            new CommonGrpcClientProperties(),
            mock(GrpcChannelFactory.class),
            BlockingGrpcStubCustomizer.noop(),
            meterRegistry);
    Field field =
        net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient.class.getDeclaredField(
            "stub");
    field.setAccessible(true);
    field.set(client, stub);
    return client;
  }

  private static void invokeBuildStub(AccountClient client, ManagedChannel channel) {
    try {
      java.lang.reflect.Method method =
          AccountClient.class.getDeclaredMethod("buildStub", ManagedChannel.class);
      method.setAccessible(true);
      method.invoke(client, channel);
    } catch (ReflectiveOperationException ex) {
      throw new AssertionError(ex);
    }
  }
}
