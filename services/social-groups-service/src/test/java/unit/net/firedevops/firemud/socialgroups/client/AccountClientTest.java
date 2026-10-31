package net.firedevops.firemud.socialgroups.client;

import static net.firedevops.firemud.socialgroups.test.AccountUuidFixtures.accountUuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.grpc.ManagedChannel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import net.firedevops.firemud.socialgroups.test.AccountUuidFixtures;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

class AccountClientTest {

  @Test
  void malformedPresencePolicySubjectWarningDoesNotExposeSuppliedSubjectText() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.listPresenceVisibilityPolicies(any()))
        .thenReturn(
            ListPresenceVisibilityPoliciesResponse.newBuilder()
                .addPolicies(policyEntry("private-subject-secret", "FRIENDS_ONLY"))
                .build());
    AccountClient client = newClient(stub);
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AccountClient.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);

    try {
      assertThat(
              client.getPresenceVisibilityPolicies(11L, List.of(accountUuid(1L), accountUuid(2L))))
          .isEmpty();

      assertThat(appender.list).hasSize(1);
      ILoggingEvent warning = appender.list.get(0);
      assertThat(warning.getFormattedMessage())
          .contains("tenantId=11", "batchSize=2")
          .doesNotContain("private-subject-secret");
      assertThat(warning.getThrowableProxy()).isNull();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

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
        LongStream.rangeClosed(1L, 101L).mapToObj(AccountUuidFixtures::accountUuid).toList();

    Map<String, FriendPresenceVisibilityPolicyValue> policies =
        client.getPresenceVisibilityPolicies(11L, accountIds);

    assertThat(policies)
        .hasSize(101)
        .containsEntry(accountUuid(1L), FriendPresenceVisibilityPolicyValue.FRIENDS_ONLY)
        .containsEntry(accountUuid(101L), FriendPresenceVisibilityPolicyValue.FRIENDS_ONLY);
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
  void normalizesLegacyHiddenPolicyAndOmitsUnknownOrMissingValuesAtBulkIngress() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.listPresenceVisibilityPolicies(any()))
        .thenReturn(
            ListPresenceVisibilityPoliciesResponse.newBuilder()
                .addPolicies(policyEntry(accountUuid(1L), "HIDDEN_STAFF"))
                .addPolicies(policyEntry(accountUuid(2L), "FUTURE_POLICY"))
                .addPolicies(policyEntry(accountUuid(3L), ""))
                .build());
    AccountClient client = newClient(stub);

    Map<String, FriendPresenceVisibilityPolicyValue> policies =
        client.getPresenceVisibilityPolicies(
            11L, List.of(accountUuid(1L), accountUuid(2L), accountUuid(3L), accountUuid(4L)));

    assertThat(policies)
        .containsExactly(Map.entry(accountUuid(1L), FriendPresenceVisibilityPolicyValue.PRIVATE))
        .doesNotContainKeys(accountUuid(2L), accountUuid(3L), accountUuid(4L));
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

    assertThat(client.getPresenceVisibilityPolicies(11L, List.of(accountUuid(1L)))).isEmpty();
    assertThat(meterRegistry.counter("grpc.app_error", "code", "UNAVAILABLE").count())
        .isEqualTo(1.0d);
  }

  @Test
  void malformedDuplicateAndUnrequestedPolicySubjectsRedactTheWholeRead() throws Exception {
    for (String subject : List.of("2", accountUuid(99L), accountUuid(1L))) {
      var stub = mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
      when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
      when(stub.listPresenceVisibilityPolicies(any()))
          .thenReturn(
              ListPresenceVisibilityPoliciesResponse.newBuilder()
                  .addPolicies(
                      PresenceVisibilityPolicyEntry.newBuilder()
                          .setAccountId(accountUuid(1L))
                          .setPolicy("PUBLIC"))
                  .addPolicies(
                      PresenceVisibilityPolicyEntry.newBuilder()
                          .setAccountId(subject)
                          .setPolicy("PUBLIC"))
                  .build());
      assertThat(newClient(stub).getPresenceVisibilityPolicies(11L, List.of(accountUuid(1L))))
          .isEmpty();
    }
  }

  @Test
  void laterPolicyChunkFailureRedactsEarlierSuccessfulChunks() throws Exception {
    var stub = mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.listPresenceVisibilityPolicies(any()))
        .thenReturn(
            ListPresenceVisibilityPoliciesResponse.newBuilder()
                .addPolicies(
                    PresenceVisibilityPolicyEntry.newBuilder()
                        .setAccountId(accountUuid(1L))
                        .setPolicy("PUBLIC"))
                .build(),
            ListPresenceVisibilityPoliciesResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode("UNAVAILABLE").setMessage("unavailable"))
                .build());
    assertThat(
            newClient(stub)
                .getPresenceVisibilityPolicies(
                    11L,
                    LongStream.rangeClosed(1L, 101L)
                        .mapToObj(AccountUuidFixtures::accountUuid)
                        .toList()))
        .isEmpty();
  }

  @Test
  void profileReadRequiresExactCanonicalRequestedAccountAndTenant() throws Exception {
    for (String json :
        List.of(
            "{\"tenantId\":11,\"accountId\":2,\"presenceVisibilityPolicy\":\"PUBLIC\"}",
            "{\"tenantId\":11,\"accountId\":\""
                + accountUuid(99L)
                + "\",\"presenceVisibilityPolicy\":\"PUBLIC\"}",
            "{\"tenantId\":12,\"accountId\":\""
                + accountUuid(1L)
                + "\",\"presenceVisibilityPolicy\":\"PUBLIC\"}",
            "{\"tenantId\":11,\"presenceVisibilityPolicy\":\"PUBLIC\"}")) {
      var stub = mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
      when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
      when(stub.getProfile(any()))
          .thenReturn(
              net.firedevops.firemud.account.v1.GetProfileResponse.newBuilder()
                  .setProfileJson(json)
                  .build());
      var client = newClient(stub);
      assertThat(client.getPresenceVisibilityPolicy(11L, accountUuid(1L))).isEmpty();
      assertThat(
              client.updatePresenceVisibilityPolicy(
                  11L, accountUuid(1L), FriendPresenceVisibilityPolicyValue.PUBLIC))
          .isFalse();
      org.mockito.Mockito.verify(stub, org.mockito.Mockito.never()).updateProfile(any());
    }
  }

  @Test
  void missingPersistedProfilePolicyStaysPrivateAndCanonicalSelfUpdatePreservesProfile()
      throws Exception {
    var stub = mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.getProfile(any()))
        .thenReturn(
            net.firedevops.firemud.account.v1.GetProfileResponse.newBuilder()
                .setProfileJson(
                    "{\"tenantId\":11,\"accountId\":\""
                        + accountUuid(1L)
                        + "\",\"displayName\":\"Name\",\"bio\":\"Bio\"}")
                .build());
    when(stub.updateProfile(any()))
        .thenReturn(
            net.firedevops.firemud.account.v1.UpdateProfileResponse.newBuilder()
                .setSuccess(true)
                .build());
    var client = newClient(stub);
    assertThat(client.getPresenceVisibilityPolicy(11L, accountUuid(1L)))
        .contains(FriendPresenceVisibilityPolicyValue.PRIVATE);
    assertThat(
            client.updatePresenceVisibilityPolicy(
                11L, accountUuid(1L), FriendPresenceVisibilityPolicyValue.FRIENDS_ONLY))
        .isTrue();
    var request =
        ArgumentCaptor.forClass(net.firedevops.firemud.account.v1.UpdateProfileRequest.class);
    verify(stub).updateProfile(request.capture());
    assertThat(request.getValue().getAccountId()).isEqualTo(accountUuid(1L));
    assertThat(request.getValue().getProfileJson()).contains("Name", "Bio", "FRIENDS_ONLY");
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

  @Test
  void refusesDuplicateAndUnrequestedPresencePolicySubjects() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.listPresenceVisibilityPolicies(any()))
        .thenReturn(
            ListPresenceVisibilityPoliciesResponse.newBuilder()
                .addPolicies(policyEntry(accountUuid(1L), "FRIENDS_ONLY"))
                .addPolicies(policyEntry(accountUuid(1L), "PRIVATE"))
                .build(),
            ListPresenceVisibilityPoliciesResponse.newBuilder()
                .addPolicies(policyEntry(accountUuid(1L), "FRIENDS_ONLY"))
                .addPolicies(policyEntry(accountUuid(999L), "PUBLIC"))
                .build());
    AccountClient client = newClient(stub);

    assertThat(client.getPresenceVisibilityPolicies(11L, List.of(accountUuid(1L), accountUuid(2L))))
        .isEmpty();
    assertThat(client.getPresenceVisibilityPolicies(11L, List.of(accountUuid(1L), accountUuid(2L))))
        .isEmpty();
  }

  @Test
  void laterPolicyBatchFailureRedactsEarlierSuccessfulBatch() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    AtomicInteger calls = new AtomicInteger();
    when(stub.listPresenceVisibilityPolicies(any()))
        .thenAnswer(
            invocation -> {
              if (calls.getAndIncrement() == 0) {
                ListPresenceVisibilityPoliciesRequest request = invocation.getArgument(0);
                return ListPresenceVisibilityPoliciesResponse.newBuilder()
                    .addAllPolicies(
                        request.getAccountIdsList().stream()
                            .map(accountId -> policyEntry(accountId, "FRIENDS_ONLY"))
                            .toList())
                    .build();
              }
              return ListPresenceVisibilityPoliciesResponse.newBuilder()
                  .setError(
                      ErrorDetail.newBuilder().setCode("UNAVAILABLE").setMessage("Retry later"))
                  .build();
            });
    AccountClient client = newClient(stub);

    assertThat(
            client.getPresenceVisibilityPolicies(
                11L,
                LongStream.rangeClosed(1L, 101L)
                    .mapToObj(AccountUuidFixtures::accountUuid)
                    .toList()))
        .isEmpty();
    assertThat(calls.get()).isEqualTo(2);
  }

  private static PresenceVisibilityPolicyEntry policyEntry(String accountId, String policy) {
    return PresenceVisibilityPolicyEntry.newBuilder()
        .setAccountId(accountId)
        .setPolicy(policy)
        .build();
  }

  @Test
  void uuidProfileResponseRemainsReadableWithCanonicalRequestSelectors() throws Exception {
    var stub = mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.getProfile(any()))
        .thenReturn(
            net.firedevops.firemud.account.v1.GetProfileResponse.newBuilder()
                .setProfileJson(
                    "{\"tenantId\":11,\"accountId\":\""
                        + accountUuid(2L)
                        + "\",\"displayName\":\"demo\",\"bio\":\"retained bio\",\"presenceVisibilityPolicy\":\"FRIENDS_ONLY\"}")
                .build());
    when(stub.updateProfile(any()))
        .thenReturn(
            net.firedevops.firemud.account.v1.UpdateProfileResponse.newBuilder()
                .setSuccess(true)
                .build());
    AccountClient client = newClient(stub);
    assertThat(client.getPresenceVisibilityPolicy(11L, accountUuid(2L)))
        .contains(FriendPresenceVisibilityPolicyValue.FRIENDS_ONLY);
    assertThat(
            client.updatePresenceVisibilityPolicy(
                11L, accountUuid(2L), FriendPresenceVisibilityPolicyValue.PRIVATE))
        .isTrue();
    ArgumentCaptor<net.firedevops.firemud.account.v1.GetProfileRequest> reads =
        ArgumentCaptor.forClass(net.firedevops.firemud.account.v1.GetProfileRequest.class);
    verify(stub, times(2)).getProfile(reads.capture());
    assertThat(reads.getAllValues())
        .allSatisfy(
            request -> {
              assertThat(request.getTenantId()).isEqualTo("11");
              assertThat(request.getAccountId()).isEqualTo(accountUuid(2L));
            });
    ArgumentCaptor<net.firedevops.firemud.account.v1.UpdateProfileRequest> update =
        ArgumentCaptor.forClass(net.firedevops.firemud.account.v1.UpdateProfileRequest.class);
    verify(stub).updateProfile(update.capture());
    assertThat(update.getValue().getAccountId()).isEqualTo(accountUuid(2L));
    var json =
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .readTree(update.getValue().getProfileJson());
    assertThat(json.path("displayName").asText()).isEqualTo("demo");
    assertThat(json.path("bio").asText()).isEqualTo("retained bio");
    assertThat(json.path("presenceVisibilityPolicy").asText()).isEqualTo("PRIVATE");
  }
}
