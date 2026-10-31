package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeRequest;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeResponse;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipRequest;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipResponse;
import net.firedevops.firemud.account.v1.RequestEmailLoginOtpRequest;
import net.firedevops.firemud.account.v1.RequestEmailLoginOtpResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class AccountClientTest {
  private static final String ACCOUNT_UUID = "00000000-0000-4000-8000-000000000042";
  private static final Instant JOIN_TEST_NOW = Instant.parse("2026-10-02T00:00:00Z");

  @Test
  void directTextScopeCapturesExactCompleteRequest() throws Exception {
    RetryFixture fixture = newRetryFixture();
    var expected =
        IssueDirectTextConnectScopeResponse.newBuilder()
            .setConnectScopeId("account-issued-scope")
            .setConnectScopeExpiresAt("2030-01-01T00:00:00Z")
            .build();
    when(fixture.initialStub().issueDirectTextConnectScope(any())).thenReturn(expected);
    // Session/request are bounded opaque text, not numeric IDs. Preserve their exact bytes.
    var context =
        directTextContext().toBuilder()
            .setSessionId(" transport-session ")
            .setRequestId(" scope-request ")
            .build();
    var target = directTextTarget();

    assertThat(fixture.client().issueDirectTextConnectScope(context, target)).isEqualTo(expected);

    var captor = ArgumentCaptor.forClass(IssueDirectTextConnectScopeRequest.class);
    verify(fixture.initialStub()).issueDirectTextConnectScope(captor.capture());
    assertThat(captor.getValue())
        .isEqualTo(
            IssueDirectTextConnectScopeRequest.newBuilder()
                .setPlayerContext(context)
                .setTenantId("22")
                .setWorldSlug("demo-world")
                .setRealmSlug("production")
                .setRealmId(target.realmId())
                .setPlayableStateNamespaceId(target.playableStateNamespaceId())
                .setPlayableStateScope("SHARED")
                .setGameInstanceId("9")
                .setCatalogRevision(7L)
                .setPointerVersion(4L)
                .build());
    verify(fixture.channelFactory(), never())
        .buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "42",
        "not-a-uuid",
        "00000000-0000-0000-0000-000000000000",
        "00000000-0000-4000-8000-00000000000A",
        " 00000000-0000-4000-8000-000000000042",
        "00000000-0000-4000-8000-000000000042 "
      })
  void directTextMethodsRejectNonCanonicalAccountBeforeDispatch(String accountId) throws Exception {
    var stub = mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    var client = newClient(stub);
    var context = directTextContext().toBuilder().setAccountId(accountId).build();

    assertThat(client.issueDirectTextConnectScope(context, directTextTarget()).getError().getCode())
        .isEqualTo("INVALID_ARGUMENT");
    assertThat(join(client, context).getError().getCode()).isEqualTo("INVALID_ARGUMENT");
    org.mockito.Mockito.verifyNoInteractions(stub);
  }

  @Test
  void directTextMethodsRejectMissingAndMalformedCallerFieldsBeforeDispatch() throws Exception {
    var stub = mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    var client = newClient(stub);
    var context = directTextContext();
    for (var malformed :
        List.of(
            context.toBuilder().clearAccountId().build(),
            context.toBuilder().clearSessionId().build(),
            context.toBuilder().setSessionId(" ").build(),
            context.toBuilder().setSessionId("s".repeat(129)).build(),
            context.toBuilder().clearRequestId().build(),
            context.toBuilder().setRequestId(" ").build(),
            context.toBuilder().setRequestId("r".repeat(129)).build(),
            context.toBuilder().setTenantId("0").build(),
            context.toBuilder().setTenantId("tenant-name").build(),
            context.toBuilder().setTenantId(" 22").build(),
            context.toBuilder().setGameInstanceId("-1").build(),
            context.toBuilder().setGameInstanceId("runtime-name").build(),
            context.toBuilder().setGameInstanceId("9223372036854775808").build(),
            context.toBuilder().clearRealmId().build(),
            context.toBuilder().setRealmId("realm-name").build(),
            context.toBuilder().setPlayableStateNamespaceId("namespace-name").build(),
            context.toBuilder().clearPlayableStateNamespaceId().build(),
            context.toBuilder().clearPlayableStateScope().build(),
            context.toBuilder().setPlayableStateScope("shared").build(),
            context.toBuilder().setPlayableStateScope("SHARED ").build())) {
      assertThat(
              client
                  .issueDirectTextConnectScope(malformed, directTextTarget())
                  .getError()
                  .getCode())
          .isEqualTo("INVALID_ARGUMENT");
      assertThat(
              client
                  .joinPublicProductionMembership(
                      malformed,
                      "account-issued-scope",
                      malformed.getRequestId(),
                      JOIN_TEST_NOW.plusSeconds(60))
                  .getError()
                  .getCode())
          .isEqualTo("INVALID_ARGUMENT");
    }
    assertThat(client.issueDirectTextConnectScope(null, directTextTarget()).getError().getCode())
        .isEqualTo("INVALID_ARGUMENT");
    assertThat(client.issueDirectTextConnectScope(context, null).getError().getCode())
        .isEqualTo("INVALID_ARGUMENT");
    assertThat(join(client, null).getError().getCode()).isEqualTo("INVALID_ARGUMENT");
    org.mockito.Mockito.verifyNoInteractions(stub);
  }

  @Test
  void scopeIssuanceRejectsEachIndependentContextTargetMismatch() throws Exception {
    var stub = mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    var client = newClient(stub);
    var context = directTextContext();
    for (var mismatched :
        List.of(
            context.toBuilder().setTenantId("23").build(),
            context.toBuilder().setRealmId("4c4b57d8-e3a2-48fe-9977-e7df0fdce902").build(),
            context.toBuilder()
                .setPlayableStateNamespaceId("42d234a2-7487-4dda-a7e5-a3831214328f")
                .build(),
            context.toBuilder().setPlayableStateScope("ISOLATED").build(),
            context.toBuilder().setGameInstanceId("10").build())) {
      assertThat(
              client
                  .issueDirectTextConnectScope(mismatched, directTextTarget())
                  .getError()
                  .getCode())
          .isEqualTo("INVALID_ARGUMENT");
    }
    org.mockito.Mockito.verifyNoInteractions(stub);
  }

  @Test
  void directTextTargetRejectsMalformedRoutingEvidenceWithoutNormalizing() {
    var target = directTextTarget();
    String[][] malformed = {
      {"0", "world", "realm", target.realmId(), target.playableStateNamespaceId(), "SHARED", "9"},
      {
        "tenant",
        "world",
        "realm",
        target.realmId(),
        target.playableStateNamespaceId(),
        "SHARED",
        "9"
      },
      {"22", " ", "realm", target.realmId(), target.playableStateNamespaceId(), "SHARED", "9"},
      {
        "22",
        "w".repeat(129),
        "realm",
        target.realmId(),
        target.playableStateNamespaceId(),
        "SHARED",
        "9"
      },
      {
        "22",
        "world",
        "r".repeat(129),
        target.realmId(),
        target.playableStateNamespaceId(),
        "SHARED",
        "9"
      },
      {"22", "world", "realm", "realm", target.playableStateNamespaceId(), "SHARED", "9"},
      {"22", "world", "realm", target.realmId(), "namespace", "SHARED", "9"},
      {"22", "world", "realm", target.realmId(), target.playableStateNamespaceId(), "shared", "9"},
      {"22", "world", "realm", target.realmId(), target.playableStateNamespaceId(), "SHARED", "0"}
    };
    for (var fields : malformed) {
      assertThatThrownBy(
              () ->
                  new DirectTextConnectScopeTarget(
                      fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6],
                      7L, 4L))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                new DirectTextConnectScopeTarget(
                    "22",
                    "world",
                    "realm",
                    target.realmId(),
                    target.playableStateNamespaceId(),
                    "SHARED",
                    "9",
                    0L,
                    4L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DirectTextConnectScopeTarget(
                    "22",
                    "world",
                    "realm",
                    target.realmId(),
                    target.playableStateNamespaceId(),
                    "SHARED",
                    "9",
                    7L,
                    -1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void joinRejectsMissingOversizedOrMismatchedRetainedIdentity() throws Exception {
    var stub = mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    var client = newClient(stub);
    for (String scope : new String[] {null, "", " ", "s".repeat(2049)}) {
      assertThat(
              client
                  .joinPublicProductionMembership(
                      directTextContext(), scope, "join-request-1", JOIN_TEST_NOW.plusSeconds(60))
                  .getError()
                  .getCode())
          .isEqualTo("INVALID_ARGUMENT");
    }
    for (String request : new String[] {null, "", " ", "r".repeat(129), "different-request"}) {
      assertThat(
              client
                  .joinPublicProductionMembership(
                      directTextContext(), "scope", request, JOIN_TEST_NOW.plusSeconds(60))
                  .getError()
                  .getCode())
          .isEqualTo("INVALID_ARGUMENT");
    }
    org.mockito.Mockito.verifyNoInteractions(stub);
  }

  @Test
  void directTextMethodsReturnUnavailableForMissingStub() throws Exception {
    var client = newRetryFixture(new TestClock(JOIN_TEST_NOW)).client();
    setStub(client, null);
    assertThat(
            client
                .issueDirectTextConnectScope(directTextContext(), directTextTarget())
                .getError()
                .getCode())
        .isEqualTo("AUTH_UNAVAILABLE");
    assertThat(join(client, directTextContext()).getError().getCode())
        .isEqualTo("AUTH_UNAVAILABLE");
  }

  @ParameterizedTest
  @EnumSource(value = Status.Code.class, mode = EnumSource.Mode.EXCLUDE, names = "OK")
  void scopeIssuanceNeverRetriesTransportFailureAndSanitizesErrors(Status.Code status)
      throws Exception {
    var fixture = newRetryFixture();
    when(fixture.initialStub().issueDirectTextConnectScope(any()))
        .thenThrow(
            Status.fromCode(status)
                .withDescription("private upstream details")
                .asRuntimeException());

    var response =
        fixture.client().issueDirectTextConnectScope(directTextContext(), directTextTarget());

    assertThat(response.getError().getCode()).isEqualTo("AUTH_UNAVAILABLE");
    assertThat(response.getError().getMessage()).isEqualTo("Account authority unavailable");
    verify(fixture.initialStub()).issueDirectTextConnectScope(any());
    verify(fixture.retryStub(), never()).issueDirectTextConnectScope(any());
    verify(fixture.channelFactory(), never())
        .buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void scopeIssuancePreservesCompletedApplicationFailure() throws Exception {
    var fixture = newRetryFixture();
    var expected =
        IssueDirectTextConnectScopeResponse.newBuilder()
            .setError(
                ErrorDetail.newBuilder()
                    .setCode("CONNECT_SCOPE_MISMATCH")
                    .setMessage("Refresh REALMS"))
            .build();
    when(fixture.initialStub().issueDirectTextConnectScope(any())).thenReturn(expected);
    assertThat(
            fixture.client().issueDirectTextConnectScope(directTextContext(), directTextTarget()))
        .isSameAs(expected);
    verify(fixture.initialStub()).issueDirectTextConnectScope(any());
    verify(fixture.channelFactory(), never())
        .buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @ParameterizedTest
  @ValueSource(strings = {"JOINED", "ALREADY_ACTIVE"})
  void joinPreservesProducerSuccessAndReplayEvidence(String outcome) throws Exception {
    var fixture = newRetryFixture(new TestClock(JOIN_TEST_NOW));
    var expected = joinSuccess(outcome, "ALREADY_ACTIVE".equals(outcome));
    when(fixture.initialStub().joinPublicProductionMembership(any())).thenReturn(expected);

    assertThat(join(fixture.client(), directTextContext())).isSameAs(expected);

    var captor = ArgumentCaptor.forClass(JoinPublicProductionMembershipRequest.class);
    verify(fixture.initialStub()).joinPublicProductionMembership(captor.capture());
    assertThat(captor.getValue())
        .isEqualTo(
            JoinPublicProductionMembershipRequest.newBuilder()
                .setPlayerContext(directTextContext())
                .setConnectScopeId("account-issued-scope")
                .setRequestId("join-request-1")
                .build());
    verify(fixture.channelFactory(), never())
        .buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void joinPreservesTypedNonSuccessWithOrWithoutErrorWithoutRetry() throws Exception {
    var fixture = newRetryFixture(new TestClock(JOIN_TEST_NOW));
    var normal =
        JoinPublicProductionMembershipResponse.newBuilder()
            .setSuccess(false)
            .setOutcomeCode("PUBLIC_PRODUCTION_ADMISSION_DENIED")
            .setAccountId(ACCOUNT_UUID)
            .setTenantId("22")
            .setMembershipId("0")
            .build();
    for (var expected :
        List.of(
            normal,
            normal.toBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("PUBLIC_PRODUCTION_ADMISSION_DENIED")
                        .setMessage("Joining is disabled"))
                .build())) {
      when(fixture.initialStub().joinPublicProductionMembership(any())).thenReturn(expected);
      assertThat(join(fixture.client(), directTextContext())).isSameAs(expected);
    }
    verify(fixture.initialStub(), times(2)).joinPublicProductionMembership(any());
    verify(fixture.retryStub(), never()).joinPublicProductionMembership(any());
    verify(fixture.channelFactory(), never())
        .buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void joinAcceptsBoundedOpaqueIdentityWithoutTrimmingOrRegeneration() throws Exception {
    var fixture = newRetryFixture(new TestClock(JOIN_TEST_NOW));
    var requestId = " " + "r".repeat(126) + " ";
    var scopeId = " " + "s".repeat(2046) + " ";
    var context =
        directTextContext().toBuilder()
            .setSessionId("s".repeat(128))
            .setRequestId(requestId)
            .build();
    var expected = joinSuccess("JOINED", false);
    when(fixture.initialStub().joinPublicProductionMembership(any())).thenReturn(expected);

    assertThat(
            fixture
                .client()
                .joinPublicProductionMembership(
                    context, scopeId, requestId, JOIN_TEST_NOW.plusSeconds(60)))
        .isSameAs(expected);

    var captor = ArgumentCaptor.forClass(JoinPublicProductionMembershipRequest.class);
    verify(fixture.initialStub()).joinPublicProductionMembership(captor.capture());
    assertThat(captor.getValue())
        .isEqualTo(
            JoinPublicProductionMembershipRequest.newBuilder()
                .setPlayerContext(context)
                .setConnectScopeId(scopeId)
                .setRequestId(requestId)
                .build());
  }

  @Test
  void joinRejectsForeignOrMalformedSuccessfulIdentityWithoutRetry() throws Exception {
    var fixture = newRetryFixture(new TestClock(JOIN_TEST_NOW));
    var success = joinSuccess("JOINED", false);
    for (String account :
        List.of(
            "",
            "42",
            "not-a-uuid",
            "00000000-0000-0000-0000-000000000000",
            "00000000-0000-4000-8000-00000000000A",
            " " + ACCOUNT_UUID,
            "00000000-0000-4000-8000-000000000043")) {
      when(fixture.initialStub().joinPublicProductionMembership(any()))
          .thenReturn(success.toBuilder().setAccountId(account).build());
      assertThat(join(fixture.client(), directTextContext()).getError().getCode())
          .isEqualTo("AUTH_UNAVAILABLE");
    }
    for (String tenant : List.of("", "tenant", "0", "23", " 22", "022")) {
      when(fixture.initialStub().joinPublicProductionMembership(any()))
          .thenReturn(success.toBuilder().setTenantId(tenant).build());
      assertThat(join(fixture.client(), directTextContext()).getError().getCode())
          .isEqualTo("AUTH_UNAVAILABLE");
    }
    verify(fixture.retryStub(), never()).joinPublicProductionMembership(any());
    verify(fixture.channelFactory(), never())
        .buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNAVAILABLE", "DEADLINE_EXCEEDED"})
  void joinRetriesOnceWithSameImmutableRequestAfterChannelRebuild(String status) throws Exception {
    var fixture = newRetryFixture(new TestClock(JOIN_TEST_NOW));
    when(fixture.initialStub().joinPublicProductionMembership(any()))
        .thenThrow(Status.fromCode(Status.Code.valueOf(status)).asRuntimeException());
    var expected = joinSuccess("JOINED", true);
    when(fixture.retryStub().joinPublicProductionMembership(any())).thenReturn(expected);

    assertThat(join(fixture.client(), directTextContext())).isSameAs(expected);

    var first = ArgumentCaptor.forClass(JoinPublicProductionMembershipRequest.class);
    var retry = ArgumentCaptor.forClass(JoinPublicProductionMembershipRequest.class);
    verify(fixture.initialStub()).joinPublicProductionMembership(first.capture());
    verify(fixture.retryStub()).joinPublicProductionMembership(retry.capture());
    assertThat(retry.getValue()).isSameAs(first.getValue());
    assertThat(retry.getValue())
        .isEqualTo(
            JoinPublicProductionMembershipRequest.newBuilder()
                .setPlayerContext(directTextContext())
                .setConnectScopeId("account-issued-scope")
                .setRequestId("join-request-1")
                .build());
    verify(fixture.channelFactory()).buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNAVAILABLE", "DEADLINE_EXCEEDED"})
  void joinExhaustedRetryRemainsUnavailableAndNeverCallsThirdTime(String status) throws Exception {
    var fixture = newRetryFixture(new TestClock(JOIN_TEST_NOW));
    var failure =
        Status.fromCode(Status.Code.valueOf(status))
            .withDescription("private details")
            .asRuntimeException();
    when(fixture.initialStub().joinPublicProductionMembership(any())).thenThrow(failure);
    when(fixture.retryStub().joinPublicProductionMembership(any())).thenThrow(failure);

    var response = join(fixture.client(), directTextContext());

    assertThat(response.getOutcomeCode()).isEqualTo("AUTH_UNAVAILABLE");
    assertThat(response.getError().getMessage()).isEqualTo("Account authority unavailable");
    verify(fixture.initialStub()).joinPublicProductionMembership(any());
    verify(fixture.retryStub()).joinPublicProductionMembership(any());
    verify(fixture.channelFactory()).buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void joinChannelReloadFailureRemainsUnavailableWithoutRedispatch() throws Exception {
    var fixture = newRetryFixture(new TestClock(JOIN_TEST_NOW));
    when(fixture.initialStub().joinPublicProductionMembership(any()))
        .thenThrow(Status.UNAVAILABLE.asRuntimeException());
    when(fixture.channelFactory().buildChannel(anyString(), anyInt(), any(), anyBoolean()))
        .thenThrow(new IllegalStateException("private reload details"));

    assertThat(join(fixture.client(), directTextContext()).getError().getCode())
        .isEqualTo("AUTH_UNAVAILABLE");
    verify(fixture.initialStub()).joinPublicProductionMembership(any());
    verify(fixture.retryStub(), never()).joinPublicProductionMembership(any());
    verify(fixture.channelFactory()).buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void joinAlsoCorrelatesSuccessfulRetryBeforeReturningIt() throws Exception {
    var fixture = newRetryFixture(new TestClock(JOIN_TEST_NOW));
    when(fixture.initialStub().joinPublicProductionMembership(any()))
        .thenThrow(Status.UNAVAILABLE.asRuntimeException());
    when(fixture.retryStub().joinPublicProductionMembership(any()))
        .thenReturn(joinSuccess("JOINED", true).toBuilder().setTenantId("23").build());

    assertThat(join(fixture.client(), directTextContext()).getError().getCode())
        .isEqualTo("AUTH_UNAVAILABLE");
    verify(fixture.initialStub()).joinPublicProductionMembership(any());
    verify(fixture.retryStub()).joinPublicProductionMembership(any());
    verify(fixture.channelFactory()).buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void directTextGenericFailuresAreUnavailableWithoutRetryOrUpstreamMessages() throws Exception {
    var fixture = newRetryFixture(new TestClock(JOIN_TEST_NOW));
    when(fixture.initialStub().issueDirectTextConnectScope(any()))
        .thenThrow(new IllegalStateException("private scope detail"));
    when(fixture.initialStub().joinPublicProductionMembership(any()))
        .thenThrow(new IllegalStateException("private join detail"));

    assertThat(
            fixture
                .client()
                .issueDirectTextConnectScope(directTextContext(), directTextTarget())
                .getError()
                .getMessage())
        .isEqualTo("Account authority unavailable");
    assertThat(join(fixture.client(), directTextContext()).getError().getMessage())
        .isEqualTo("Account authority unavailable");
    verify(fixture.initialStub()).issueDirectTextConnectScope(any());
    verify(fixture.initialStub()).joinPublicProductionMembership(any());
    verify(fixture.retryStub(), never()).issueDirectTextConnectScope(any());
    verify(fixture.retryStub(), never()).joinPublicProductionMembership(any());
    verify(fixture.channelFactory(), never())
        .buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @ParameterizedTest
  @EnumSource(
      value = Status.Code.class,
      mode = EnumSource.Mode.EXCLUDE,
      names = {"OK", "UNAVAILABLE", "DEADLINE_EXCEEDED"})
  void joinOtherTransportFailuresAreSanitizedCommandFailuresWithoutRetry(Status.Code status)
      throws Exception {
    var fixture = newRetryFixture(new TestClock(JOIN_TEST_NOW));
    when(fixture.initialStub().joinPublicProductionMembership(any()))
        .thenThrow(
            Status.fromCode(status)
                .withDescription("private account details")
                .asRuntimeException());

    var response = join(fixture.client(), directTextContext());

    assertThat(response.getOutcomeCode()).isEqualTo("JOIN_FAILED");
    assertThat(response.getError().getCode()).isEqualTo("JOIN_FAILED");
    assertThat(response.getError().getMessage()).isEqualTo("Account JOIN request failed");
    verify(fixture.initialStub()).joinPublicProductionMembership(any());
    verify(fixture.retryStub(), never()).joinPublicProductionMembership(any());
    verify(fixture.channelFactory(), never())
        .buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void joinRejectsNullPastOrEqualExpiryBeforeFirstDispatch() throws Exception {
    var fixture = newRetryFixture(new TestClock(JOIN_TEST_NOW));
    for (Instant expiry : new Instant[] {null, JOIN_TEST_NOW.minusNanos(1), JOIN_TEST_NOW}) {
      assertThat(
              fixture
                  .client()
                  .joinPublicProductionMembership(
                      directTextContext(), "account-issued-scope", "join-request-1", expiry)
                  .getError()
                  .getCode())
          .isEqualTo("CONNECT_SCOPE_INVALID");
    }
    verify(fixture.initialStub(), never()).joinPublicProductionMembership(any());
    verify(fixture.channelFactory(), never())
        .buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void joinRechecksExpiryAfterInitialStubPreparationBeforeFirstDispatch() throws Exception {
    var clock = new TestClock(JOIN_TEST_NOW);
    var fixture = newRetryFixture(clock);
    when(fixture.initialStub().withDeadlineAfter(5L, TimeUnit.SECONDS))
        .thenAnswer(
            invocation -> {
              clock.set(JOIN_TEST_NOW.plusSeconds(60));
              return fixture.initialStub();
            });

    assertThat(join(fixture.client(), directTextContext()).getError().getCode())
        .isEqualTo("CONNECT_SCOPE_INVALID");
    verify(fixture.initialStub(), never()).joinPublicProductionMembership(any());
    verify(fixture.channelFactory(), never())
        .buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNAVAILABLE", "DEADLINE_EXCEEDED"})
  void joinExpiryAfterAmbiguityIsUnavailableNotNoCommitEvidence(String status) throws Exception {
    var clock = new TestClock(JOIN_TEST_NOW);
    var fixture = newRetryFixture(clock);
    when(fixture.initialStub().joinPublicProductionMembership(any()))
        .thenAnswer(
            invocation -> {
              clock.set(JOIN_TEST_NOW.plusSeconds(60));
              throw Status.fromCode(Status.Code.valueOf(status)).asRuntimeException();
            });

    assertThat(join(fixture.client(), directTextContext()).getError().getCode())
        .isEqualTo("AUTH_UNAVAILABLE");
    verify(fixture.initialStub()).joinPublicProductionMembership(any());
    verify(fixture.retryStub(), never()).joinPublicProductionMembership(any());
    verify(fixture.channelFactory(), never())
        .buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void joinRechecksExpiryDuringChannelReloadBeforeRetry() throws Exception {
    var clock = new TestClock(JOIN_TEST_NOW);
    var fixture = newRetryFixture(clock);
    when(fixture.initialStub().joinPublicProductionMembership(any()))
        .thenThrow(Status.UNAVAILABLE.asRuntimeException());
    when(fixture.channelFactory().buildChannel(anyString(), anyInt(), any(), anyBoolean()))
        .thenAnswer(
            invocation -> {
              clock.set(JOIN_TEST_NOW.plusSeconds(60));
              return mock(ManagedChannel.class);
            });

    assertThat(join(fixture.client(), directTextContext()).getError().getCode())
        .isEqualTo("AUTH_UNAVAILABLE");
    verify(fixture.initialStub()).joinPublicProductionMembership(any());
    verify(fixture.retryStub(), never()).joinPublicProductionMembership(any());
    verify(fixture.channelFactory()).buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  private static DirectTextConnectScopeTarget directTextTarget() {
    return new DirectTextConnectScopeTarget(
        "22",
        "demo-world",
        "production",
        "4c4b57d8-e3a2-48fe-9977-e7df0fdce901",
        "42d234a2-7487-4dda-a7e5-a3831214328e",
        "SHARED",
        "9",
        7L,
        4L);
  }

  private static PlayerExecutionContext directTextContext() {
    var target = directTextTarget();
    return PlayerExecutionContext.newBuilder()
        .setAccountId(ACCOUNT_UUID)
        .setSessionId("transport-session")
        .setTenantId(target.tenantId())
        .setRealmId(target.realmId())
        .setPlayableStateNamespaceId(target.playableStateNamespaceId())
        .setPlayableStateScope(target.playableStateScope())
        .setGameInstanceId(target.gameInstanceId())
        .setRequestId("join-request-1")
        .build();
  }

  private static JoinPublicProductionMembershipResponse joinSuccess(
      String outcome, boolean replayed) {
    return JoinPublicProductionMembershipResponse.newBuilder()
        .setSuccess(true)
        .setOutcomeCode(outcome)
        .setAccountId(ACCOUNT_UUID)
        .setTenantId("22")
        .setMembershipId("81")
        .setMembershipVersion(12L)
        .setMembershipAuthorityGeneration(3L)
        .setReplayed(replayed)
        .build();
  }

  private static JoinPublicProductionMembershipResponse join(
      AccountClient client, PlayerExecutionContext context) {
    return client.joinPublicProductionMembership(
        context, "account-issued-scope", "join-request-1", JOIN_TEST_NOW.plusSeconds(60));
  }

  @Test
  void authenticateReturnsUnavailableWhenStubIsNotInitialized() throws Exception {
    AuthenticateResponse response = newClient(null).authenticate("demo@example.com", "swordfish");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Authentication service unavailable");
  }

  @Test
  void authenticateForReadinessReturnsUnavailableWhenStubIsNotInitialized() throws Exception {
    AuthenticateResponse response =
        newClient(null).authenticateForReadiness("demo@example.com", "swordfish");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Authentication service unavailable");
  }

  @Test
  void requestEmailLoginOtpReturnsUnavailableWhenStubIsNotInitialized() throws Exception {
    RequestEmailLoginOtpResponse response =
        newClient(null).requestEmailLoginOtp("demo@example.com");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Authentication service unavailable");
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNAVAILABLE", "DEADLINE_EXCEEDED"})
  void authenticateNormalizesRetryableTransportFailuresToUnavailable(String statusName)
      throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    String description = "Account authentication rejected: " + statusName;
    when(stub.authenticate(any(AuthenticateRequest.class)))
        .thenThrow(
            new StatusRuntimeException(
                Status.fromCode(Status.Code.valueOf(statusName)).withDescription(description)));
    GrpcChannelFactory channelFactory = newChannelFactory();
    AccountClient client = newClient(stub, channelFactory);

    AuthenticateResponse response = client.authenticate("demo@example.com", "swordfish");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Authentication service unavailable");
    verify(stub, times(1)).authenticate(any(AuthenticateRequest.class));
    verify(channelFactory, times("UNAVAILABLE".equals(statusName) ? 1 : 0))
        .buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void authenticateNormalizesGenericTransportFailuresToUnavailable() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.authenticate(any(AuthenticateRequest.class)))
        .thenThrow(new IllegalStateException("channel failed before a response completed"));
    AccountClient client = newClient(stub);

    AuthenticateResponse response = client.authenticate("demo@example.com", "swordfish");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Authentication service unavailable");
    verify(stub, times(1)).authenticate(any(AuthenticateRequest.class));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "INTERNAL",
        "RESOURCE_EXHAUSTED",
        "UNKNOWN",
        "INVALID_ARGUMENT",
        "UNAUTHENTICATED",
        "PERMISSION_DENIED"
      })
  void authenticatePreservesTerminalGrpcStatusAndUsesGenericMessage(String statusName)
      throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.authenticate(any(AuthenticateRequest.class)))
        .thenThrow(
            new StatusRuntimeException(
                Status.fromCode(Status.Code.valueOf(statusName))
                    .withDescription("upstream credential details")));
    AccountClient client = newClient(stub);

    AuthenticateResponse response = client.authenticate("demo@example.com", "swordfish");

    assertThat(response.getError().getCode()).isEqualTo(statusName);
    assertThat(response.getError().getMessage()).isEqualTo("Authentication request failed");
    verify(stub).authenticate(any(AuthenticateRequest.class));
  }

  @Test
  void authenticateUsesGenericMessageWhenTerminalGrpcDescriptionIsBlank() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.authenticate(any(AuthenticateRequest.class)))
        .thenThrow(
            new StatusRuntimeException(
                Status.fromCode(Status.Code.INVALID_ARGUMENT).withDescription("   ")));
    AccountClient client = newClient(stub);

    AuthenticateResponse response = client.authenticate("demo@example.com", "swordfish");

    assertThat(response.getError().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT.name());
    assertThat(response.getError().getMessage()).isEqualTo("Authentication request failed");
    verify(stub).authenticate(any(AuthenticateRequest.class));
  }

  @Test
  void authenticatePreservesCompletedApplicationErrorDetail() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    AuthenticateResponse expected =
        AuthenticateResponse.newBuilder()
            .setError(
                ErrorDetail.newBuilder()
                    .setCode("AUTH_RETRY_LATER")
                    .setMessage("Try again later")
                    .build())
            .build();
    when(stub.authenticate(any(AuthenticateRequest.class))).thenReturn(expected);
    AccountClient client = newClient(stub);

    AuthenticateResponse response = client.authenticate("demo@example.com", "swordfish");

    assertThat(response).isEqualTo(expected);
    ArgumentCaptor<AuthenticateRequest> requestCaptor =
        ArgumentCaptor.forClass(AuthenticateRequest.class);
    verify(stub).authenticate(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getEmail()).isEqualTo("demo@example.com");
    assertThat(requestCaptor.getValue().getDescriptorForType().findFieldByName("tenant_id"))
        .isNull();
  }

  @Test
  void emailLoginChallengeRequestHasNoTenantScopeField() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.requestEmailLoginOtp(any(RequestEmailLoginOtpRequest.class)))
        .thenReturn(RequestEmailLoginOtpResponse.newBuilder().setAccepted(true).build());
    AccountClient client = newClient(stub);

    client.requestEmailLoginOtp("demo@example.com");

    ArgumentCaptor<RequestEmailLoginOtpRequest> requestCaptor =
        ArgumentCaptor.forClass(RequestEmailLoginOtpRequest.class);
    verify(stub).requestEmailLoginOtp(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getEmail()).isEqualTo("demo@example.com");
    assertThat(requestCaptor.getValue().getDescriptorForType().findFieldByName("tenant_id"))
        .isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "42",
        "not-a-uuid",
        "00000000-0000-0000-0000-000000000000",
        "00000000-0000-4000-8000-00000000000A",
        " 00000000-0000-4000-8000-000000000042"
      })
  void runtimeReadersRejectNonCanonicalAccountsBeforeCallingAccount(String invalidAccount)
      throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    AccountClient client = newClient(stub);
    assertThat(
            client
                .getTenantMembershipForRuntime(invalidAccount, "7", "request-1")
                .getError()
                .getCode())
        .isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(
            client
                .getRealmAccessGrantForRuntime(invalidAccount, "7", "world", "realm", "request-1")
                .getError()
                .getCode())
        .isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    org.mockito.Mockito.verifyNoInteractions(stub);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "42",
        "00000000-0000-0000-0000-000000000000",
        "00000000-0000-4000-8000-000000000043"
      })
  void runtimeMembershipRejectsUncorrelatedAccountResult(String returnedAccount) throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class)))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId(returnedAccount)
                .setTenantId("7")
                .setMembershipExists(true)
                .setGameplayAdmissionAllowed(true)
                .build());
    AccountClient client = newClient(stub);
    assertThat(
            client
                .getTenantMembershipForRuntime(ACCOUNT_UUID, "7", "request-1")
                .getError()
                .getCode())
        .isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    verify(stub, times(1))
        .getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class));
  }

  @Test
  void realmGrantRequiresExactAccountTenantWorldAndRealmResult() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    var exact =
        GetRealmAccessGrantForRuntimeResponse.newBuilder()
            .setAccountId(ACCOUNT_UUID)
            .setTenantId("7")
            .setWorldSlug("world")
            .setRealmSlug("realm")
            .setGranted(true)
            .setGrantVersion(4L)
            .build();
    AccountClient client = newClient(stub);
    for (var result :
        java.util.List.of(
            exact.toBuilder().setAccountId("42").build(),
            exact.toBuilder().setAccountId("00000000-0000-4000-8000-000000000043").build(),
            exact.toBuilder().setTenantId("8").build(),
            exact.toBuilder().setWorldSlug("other").build(),
            exact.toBuilder().setRealmSlug("other").build())) {
      when(stub.getRealmAccessGrantForRuntime(any(GetRealmAccessGrantForRuntimeRequest.class)))
          .thenReturn(result);
      assertThat(
              client
                  .getRealmAccessGrantForRuntime(ACCOUNT_UUID, "7", "world", "realm", "request-1")
                  .getError()
                  .getCode())
          .isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    }
    when(stub.getRealmAccessGrantForRuntime(any(GetRealmAccessGrantForRuntimeRequest.class)))
        .thenReturn(exact);
    assertThat(
            client.getRealmAccessGrantForRuntime(ACCOUNT_UUID, "7", "world", "realm", "request-1"))
        .isEqualTo(exact);
  }

  @Test
  void runtimeMembershipCallerUsesCanonicalAccountTenantRequest() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    GetTenantMembershipForRuntimeResponse expected =
        GetTenantMembershipForRuntimeResponse.newBuilder()
            .setAccountId(ACCOUNT_UUID)
            .setTenantId("7")
            .setMembershipExists(true)
            .setGameplayAdmissionAllowed(true)
            .setMembershipVersion(12L)
            .setEvaluatedAt("2026-07-31T00:00:00Z")
            .build();
    when(stub.getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class)))
        .thenReturn(expected);
    AccountClient client = newClient(stub);

    GetTenantMembershipForRuntimeResponse actual =
        client.getTenantMembershipForRuntime(ACCOUNT_UUID, "7", null);

    ArgumentCaptor<GetTenantMembershipForRuntimeRequest> captor =
        ArgumentCaptor.forClass(GetTenantMembershipForRuntimeRequest.class);
    verify(stub).getTenantMembershipForRuntime(captor.capture());
    assertThat(captor.getValue().getAccountId()).isEqualTo(ACCOUNT_UUID);
    assertThat(captor.getValue().getTenantId()).isEqualTo("7");
    assertThat(captor.getValue().getRequestId()).isEmpty();
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  void runtimeMembershipReturnsCanonicalUnavailableWhenStubIsMissing() throws Exception {
    GetTenantMembershipForRuntimeResponse response =
        newClient(null).getTenantMembershipForRuntime(ACCOUNT_UUID, "7", "request-1");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Membership authority unavailable");
  }

  @Test
  void realmAccessGrantReturnsCanonicalUnavailableWhenStubIsMissing() throws Exception {
    GetRealmAccessGrantForRuntimeResponse response =
        newClient(null)
            .getRealmAccessGrantForRuntime(ACCOUNT_UUID, "7", "world", "realm", "request-1");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Realm grant authority unavailable");
  }

  @Test
  void runtimeMembershipRetriesOnceAfterUnavailableAndPreservesSuccess() throws Exception {
    RetryFixture fixture = newRetryFixture();
    when(fixture
            .initialStub()
            .getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    GetTenantMembershipForRuntimeResponse expected =
        GetTenantMembershipForRuntimeResponse.newBuilder()
            .setAccountId(ACCOUNT_UUID)
            .setTenantId("7")
            .setMembershipExists(true)
            .setGameplayAdmissionAllowed(true)
            .setMembershipVersion(12L)
            .build();
    when(fixture
            .retryStub()
            .getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class)))
        .thenReturn(expected);

    GetTenantMembershipForRuntimeResponse actual =
        fixture.client().getTenantMembershipForRuntime(ACCOUNT_UUID, "7", "request-1");

    assertThat(actual).isEqualTo(expected);
    verify(fixture.initialStub())
        .getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class));
    verify(fixture.retryStub())
        .getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class));
    verify(fixture.channelFactory()).buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void runtimeMembershipNormalizesExhaustedUnavailableToCanonicalUnavailable() throws Exception {
    RetryFixture fixture = newRetryFixture();
    when(fixture
            .initialStub()
            .getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    when(fixture
            .retryStub()
            .getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

    GetTenantMembershipForRuntimeResponse response =
        fixture.client().getTenantMembershipForRuntime(ACCOUNT_UUID, "7", "request-1");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Membership authority unavailable");
    verify(fixture.initialStub())
        .getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class));
    verify(fixture.retryStub())
        .getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class));
    verify(fixture.channelFactory()).buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void runtimeEntitlementsReturnsCanonicalUnavailableWhenStubIsMissing() throws Exception {
    GetTenantEntitlementsForRuntimeResponse response =
        newClient(null).getTenantEntitlementsForRuntime("7", "request-1");

    assertThat(response.getError().getCode()).isEqualTo("ENTITLEMENT_UNAVAILABLE");
    assertThat(response.getError().getMessage()).isEqualTo("Entitlement authority unavailable");
  }

  @Test
  void runtimeEntitlementsNormalizesExhaustedUnavailableToCanonicalUnavailable() throws Exception {
    RetryFixture fixture = newRetryFixture();
    when(fixture
            .initialStub()
            .getTenantEntitlementsForRuntime(any(GetTenantEntitlementsForRuntimeRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    when(fixture
            .retryStub()
            .getTenantEntitlementsForRuntime(any(GetTenantEntitlementsForRuntimeRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

    GetTenantEntitlementsForRuntimeResponse response =
        fixture.client().getTenantEntitlementsForRuntime("7", "request-1");

    assertThat(response.getError().getCode()).isEqualTo("ENTITLEMENT_UNAVAILABLE");
    assertThat(response.getError().getMessage()).isEqualTo("Entitlement authority unavailable");
    verify(fixture.initialStub())
        .getTenantEntitlementsForRuntime(any(GetTenantEntitlementsForRuntimeRequest.class));
    verify(fixture.retryStub())
        .getTenantEntitlementsForRuntime(any(GetTenantEntitlementsForRuntimeRequest.class));
    verify(fixture.channelFactory()).buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void realmAccessGrantNormalizesExhaustedUnavailableToCanonicalUnavailable() throws Exception {
    RetryFixture fixture = newRetryFixture();
    when(fixture
            .initialStub()
            .getRealmAccessGrantForRuntime(any(GetRealmAccessGrantForRuntimeRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    when(fixture
            .retryStub()
            .getRealmAccessGrantForRuntime(any(GetRealmAccessGrantForRuntimeRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

    GetRealmAccessGrantForRuntimeResponse response =
        fixture
            .client()
            .getRealmAccessGrantForRuntime(ACCOUNT_UUID, "7", "world", "realm", "request-1");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Realm grant authority unavailable");
    verify(fixture.initialStub())
        .getRealmAccessGrantForRuntime(any(GetRealmAccessGrantForRuntimeRequest.class));
    verify(fixture.retryStub())
        .getRealmAccessGrantForRuntime(any(GetRealmAccessGrantForRuntimeRequest.class));
    verify(fixture.channelFactory()).buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void runtimeMembershipNormalizesInitialInternalWithoutRetryOrChannelRebuild() throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub stub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class)))
        .thenThrow(new StatusRuntimeException(Status.INTERNAL));
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    AccountClient client = newClient(stub, channelFactory);

    GetTenantMembershipForRuntimeResponse response =
        client.getTenantMembershipForRuntime(ACCOUNT_UUID, "7", "request-1");

    assertThat(response.getError().getCode()).isEqualTo(AuthenticationErrorCodes.UNAVAILABLE);
    assertThat(response.getError().getMessage()).isEqualTo("Membership authority unavailable");
    verify(stub).getTenantMembershipForRuntime(any(GetTenantMembershipForRuntimeRequest.class));
    verify(channelFactory, times(0)).buildChannel(anyString(), anyInt(), any(), anyBoolean());
  }

  @Test
  void buildStubAppliesInjectedStubCustomizer() {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    AtomicInteger customizeCalls = new AtomicInteger();
    BlockingGrpcStubCustomizer stubCustomizer =
        new BlockingGrpcStubCustomizer() {
          @Override
          public <T extends io.grpc.stub.AbstractStub<T>> T customize(T candidate) {
            customizeCalls.incrementAndGet();
            return candidate;
          }
        };
    AccountClient client =
        new AccountClient(endpoints, grpc, mock(GrpcChannelFactory.class), stubCustomizer);

    invokeBuildStub(client, mock(ManagedChannel.class));

    assertThat(customizeCalls.get()).isEqualTo(1);
  }

  private static void invokeBuildStub(AccountClient client, ManagedChannel channel) {
    try {
      Method method = AccountClient.class.getDeclaredMethod("buildStub", ManagedChannel.class);
      method.setAccessible(true);
      method.invoke(client, channel);
    } catch (ReflectiveOperationException ex) {
      throw new AssertionError(ex);
    }
  }

  private static AccountClient newClient(AccountServiceGrpc.AccountServiceBlockingStub stub)
      throws Exception {
    return newClient(stub, mock(GrpcChannelFactory.class));
  }

  private static GrpcChannelFactory newChannelFactory() throws Exception {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    when(channelFactory.buildChannel(anyString(), anyInt(), any(), anyBoolean()))
        .thenReturn(mock(ManagedChannel.class));
    return channelFactory;
  }

  private static AccountClient newClient(
      AccountServiceGrpc.AccountServiceBlockingStub stub, GrpcChannelFactory channelFactory)
      throws Exception {
    AccountClient client =
        new AccountClient(
            new ServiceEndpointsProperties(),
            new CommonGrpcClientProperties(),
            channelFactory,
            BlockingGrpcStubCustomizer.noop());
    setStub(client, stub);
    return client;
  }

  private static AccountClient newClientWithRetryStub(
      AccountServiceGrpc.AccountServiceBlockingStub initialStub,
      AccountServiceGrpc.AccountServiceBlockingStub retryStub,
      GrpcChannelFactory channelFactory,
      Clock clock)
      throws Exception {
    BlockingGrpcStubCustomizer customizer = mock(BlockingGrpcStubCustomizer.class);
    when(customizer.customize(any(AccountServiceGrpc.AccountServiceBlockingStub.class)))
        .thenReturn(retryStub);
    AccountClient client =
        new AccountClient(
            new ServiceEndpointsProperties(),
            new CommonGrpcClientProperties(),
            channelFactory,
            customizer,
            clock);
    setStub(client, initialStub);
    return client;
  }

  private static RetryFixture newRetryFixture() throws Exception {
    return newRetryFixture(Clock.systemUTC());
  }

  private static RetryFixture newRetryFixture(Clock clock) throws Exception {
    AccountServiceGrpc.AccountServiceBlockingStub initialStub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    AccountServiceGrpc.AccountServiceBlockingStub retryStub =
        mock(AccountServiceGrpc.AccountServiceBlockingStub.class);
    when(initialStub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(initialStub);
    when(retryStub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(retryStub);
    GrpcChannelFactory channelFactory = newChannelFactory();
    return new RetryFixture(
        initialStub,
        retryStub,
        channelFactory,
        newClientWithRetryStub(initialStub, retryStub, channelFactory, clock));
  }

  private static final class TestClock extends Clock {
    private Instant current;

    private TestClock(Instant current) {
      this.current = current;
    }

    private void set(Instant current) {
      this.current = current;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(current, zone);
    }

    @Override
    public Instant instant() {
      return current;
    }
  }

  private record RetryFixture(
      AccountServiceGrpc.AccountServiceBlockingStub initialStub,
      AccountServiceGrpc.AccountServiceBlockingStub retryStub,
      GrpcChannelFactory channelFactory,
      AccountClient client) {}

  private static void setStub(
      AccountClient client, AccountServiceGrpc.AccountServiceBlockingStub stub) throws Exception {
    Field field =
        net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient.class.getDeclaredField(
            "stub");
    field.setAccessible(true);
    field.set(client, stub);
  }
}
