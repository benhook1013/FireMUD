package net.firedevops.firemud.socialgroups.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.GetProfileRequest;
import net.firedevops.firemud.account.v1.GetProfileResponse;
import net.firedevops.firemud.account.v1.ListPresenceVisibilityPoliciesRequest;
import net.firedevops.firemud.account.v1.ListPresenceVisibilityPoliciesResponse;
import net.firedevops.firemud.account.v1.PresenceVisibilityPolicyEntry;
import net.firedevops.firemud.account.v1.UpdateProfileRequest;
import net.firedevops.firemud.account.v1.UpdateProfileResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceVisibilityPolicyValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class AccountClientTest {
  private static final String ACCOUNT_ID = "cc51ef2c-9a14-4c56-98bd-af7f8e4c60c1";
  private static final String OTHER_ACCOUNT_ID = "816018fe-bf4b-4946-9bb7-3a7073edc1a8";

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
    List<String> accountIds =
        Stream.generate(() -> UUID.randomUUID().toString()).limit(101).toList();

    Map<String, FriendPresenceVisibilityPolicyValue> policies =
        client.getPresenceVisibilityPolicies(11L, accountIds);

    assertThat(policies)
        .hasSize(101)
        .containsEntry(accountIds.getFirst(), FriendPresenceVisibilityPolicyValue.FRIENDS_ONLY)
        .containsEntry(accountIds.getLast(), FriendPresenceVisibilityPolicyValue.FRIENDS_ONLY);
    ArgumentCaptor<ListPresenceVisibilityPoliciesRequest> requests =
        ArgumentCaptor.forClass(ListPresenceVisibilityPoliciesRequest.class);
    verify(stub, times(2)).listPresenceVisibilityPolicies(requests.capture());
    assertThat(requests.getAllValues())
        .extracting(ListPresenceVisibilityPoliciesRequest::getAccountIdsCount)
        .containsExactly(100, 1);
    assertThat(requests.getAllValues())
        .extracting(request -> List.copyOf(request.getAccountIdsList()))
        .containsExactly(accountIds.subList(0, 100), accountIds.subList(100, 101));
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

    assertThat(client.getPresenceVisibilityPolicies(11L, List.of(ACCOUNT_ID))).isEmpty();
    assertThat(meterRegistry.counter("grpc.app_error", "code", "UNAVAILABLE").count())
        .isEqualTo(1.0d);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "42",
        "",
        " cc51ef2c-9a14-4c56-98bd-af7f8e4c60c1",
        "CC51EF2C-9A14-4C56-98BD-AF7F8E4C60C1",
        "00000000-0000-0000-0000-000000000000"
      })
  void rejectsNoncanonicalAccountBeforeProfileOrBatchCall(String accountId) throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    AccountClient client = newClient(stub);

    assertThatThrownBy(() -> client.getPresenceVisibilityPolicy(11L, accountId))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> client.getPresenceVisibilityPolicies(11L, List.of(accountId)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                client.updatePresenceVisibilityPolicy(
                    11L, accountId, FriendPresenceVisibilityPolicyValue.PRIVATE))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(stub);
  }

  @Test
  void verifiesProfileAccountAndTenantBeforeReturningPolicy() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub = profileStub(profileJson(ACCOUNT_ID, "11"));
    AccountClient client = newClient(stub);

    assertThat(client.getPresenceVisibilityPolicy(11L, ACCOUNT_ID))
        .contains(FriendPresenceVisibilityPolicyValue.FRIENDS_ONLY);
    ArgumentCaptor<GetProfileRequest> request = ArgumentCaptor.forClass(GetProfileRequest.class);
    verify(stub).getProfile(request.capture());
    assertThat(request.getValue().getAccountId()).isEqualTo(ACCOUNT_ID);
    assertThat(request.getValue().getTenantId()).isEqualTo("11");
  }

  @ParameterizedTest
  @MethodSource("untrustedProfiles")
  void refusesMissingMalformedOrMismatchedProfileIdentity(String profileJson) throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub = profileStub(profileJson);
    AccountClient client = newClient(stub);

    assertThat(client.getPresenceVisibilityPolicy(11L, ACCOUNT_ID)).isEmpty();
    assertThat(
            client.updatePresenceVisibilityPolicy(
                11L, ACCOUNT_ID, FriendPresenceVisibilityPolicyValue.PRIVATE))
        .isFalse();
    verify(stub, never()).updateProfile(any());
  }

  private static Stream<String> untrustedProfiles() {
    return Stream.of(
        "{\"presenceVisibilityPolicy\":\"PUBLIC\"}",
        profileJson(OTHER_ACCOUNT_ID, "11"),
        profileJson("42", "11"),
        profileJson(ACCOUNT_ID.toUpperCase(java.util.Locale.ROOT), "11"),
        profileJson(ACCOUNT_ID, "12"),
        profileJson(ACCOUNT_ID, "\"11\""),
        profileJson(ACCOUNT_ID, "18446744073709551627"));
  }

  @Test
  void updateRequiresExactAccountAndTenantEcho() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub = profileStub(profileJson(ACCOUNT_ID, "11"));
    AccountClient client = newClient(stub);
    when(stub.updateProfile(any()))
        .thenReturn(
            UpdateProfileResponse.newBuilder()
                .setSuccess(true)
                .setAccountId(ACCOUNT_ID)
                .setTenantId("11")
                .build(),
            UpdateProfileResponse.newBuilder()
                .setSuccess(true)
                .setAccountId(OTHER_ACCOUNT_ID)
                .setTenantId("11")
                .build(),
            UpdateProfileResponse.newBuilder()
                .setSuccess(true)
                .setAccountId(ACCOUNT_ID)
                .setTenantId("12")
                .build(),
            UpdateProfileResponse.newBuilder().setSuccess(true).build());

    assertThat(
            client.updatePresenceVisibilityPolicy(
                11L, ACCOUNT_ID, FriendPresenceVisibilityPolicyValue.PRIVATE))
        .isTrue();
    assertThat(
            client.updatePresenceVisibilityPolicy(
                11L, ACCOUNT_ID, FriendPresenceVisibilityPolicyValue.PRIVATE))
        .isFalse();
    assertThat(
            client.updatePresenceVisibilityPolicy(
                11L, ACCOUNT_ID, FriendPresenceVisibilityPolicyValue.PRIVATE))
        .isFalse();
    assertThat(
            client.updatePresenceVisibilityPolicy(
                11L, ACCOUNT_ID, FriendPresenceVisibilityPolicyValue.PRIVATE))
        .isFalse();
    ArgumentCaptor<UpdateProfileRequest> requests =
        ArgumentCaptor.forClass(UpdateProfileRequest.class);
    verify(stub, times(4)).updateProfile(requests.capture());
    assertThat(requests.getAllValues())
        .extracting(UpdateProfileRequest::getAccountId)
        .containsOnly(ACCOUNT_ID);
    assertThat(requests.getAllValues())
        .extracting(UpdateProfileRequest::getTenantId)
        .containsOnly("11");
  }

  @Test
  void ignoresNumericUnexpectedAndUnknownBatchPolicyEntries() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.listPresenceVisibilityPolicies(any()))
        .thenReturn(
            ListPresenceVisibilityPoliciesResponse.newBuilder()
                .addPolicies(
                    PresenceVisibilityPolicyEntry.newBuilder()
                        .setAccountId("42")
                        .setPolicy("PUBLIC"))
                .addPolicies(
                    PresenceVisibilityPolicyEntry.newBuilder()
                        .setAccountId(OTHER_ACCOUNT_ID)
                        .setPolicy("PUBLIC"))
                .addPolicies(
                    PresenceVisibilityPolicyEntry.newBuilder()
                        .setAccountId(ACCOUNT_ID)
                        .setPolicy("UNKNOWN"))
                .build());

    assertThat(newClient(stub).getPresenceVisibilityPolicies(11L, List.of(ACCOUNT_ID))).isEmpty();
  }

  @Test
  void laterBatchFailureDiscardsEarlierPartialVisibility() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    List<String> accounts = Stream.generate(() -> UUID.randomUUID().toString()).limit(101).toList();
    when(stub.listPresenceVisibilityPolicies(any()))
        .thenReturn(
            ListPresenceVisibilityPoliciesResponse.newBuilder()
                .addPolicies(
                    PresenceVisibilityPolicyEntry.newBuilder()
                        .setAccountId(accounts.getFirst())
                        .setPolicy("PUBLIC"))
                .build(),
            ListPresenceVisibilityPoliciesResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode("UNAVAILABLE"))
                .build());

    assertThat(newClient(stub).getPresenceVisibilityPolicies(11L, accounts)).isEmpty();
    verify(stub, times(2)).listPresenceVisibilityPolicies(any());
  }

  private static String profileJson(String accountId, String tenantJson) {
    return "{\"accountId\":\""
        + accountId
        + "\",\"tenantId\":"
        + tenantJson
        + ",\"displayName\":\"Player\",\"bio\":\"Bio\",\"presenceVisibilityPolicy\":\"FRIENDS_ONLY\"}";
  }

  private static AccountServiceGrpc.AccountServiceBlockingStub profileStub(String profileJson) {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.getProfile(any()))
        .thenReturn(GetProfileResponse.newBuilder().setProfileJson(profileJson).build());
    return stub;
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
