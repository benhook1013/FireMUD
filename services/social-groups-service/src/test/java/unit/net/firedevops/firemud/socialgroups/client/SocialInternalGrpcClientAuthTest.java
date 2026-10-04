package net.firedevops.firemud.socialgroups.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.QueryAccountPresenceRequest;
import net.firedevops.firemud.loggingadmin.v1.CreateReportRequest;
import net.firedevops.firemud.loggingadmin.v1.CreateReportResponse;
import net.firedevops.firemud.loggingadmin.v1.EvaluateModerationPolicyRequest;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class SocialInternalGrpcClientAuthTest {
  private static final String ACCOUNT_ID = "cc51ef2c-9a14-4c56-98bd-af7f8e4c60c1";
  private static final String FRIEND_ID = "816018fe-bf4b-4946-9bb7-3a7073edc1a8";
  private static final Metadata.Key<String> AUTH_HEADER =
      Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

  private final JwtUtil jwtUtil = new JwtUtil("testsecretkeytestsecretkeytest1234", 60_000L);
  private final RuntimeIdentity runtimeIdentity =
      new RuntimeIdentity(
          "social-groups-service", "social-1", "localhost", Instant.now(), "1.0.0", "abc", "local");

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void loggingAdminClientUsesInternalIdentityEvenWithEndUserContext() {
    SessionContext.setContext("42", List.of("player"), Map.of("7", List.of("tenantAdmin")));
    CapturingChannel channel = new CapturingChannel();
    LoggingAdminClient client =
        new LoggingAdminClient(
            new ServiceEndpointsProperties(),
            plaintextGrpcProperties(),
            mock(GrpcChannelFactory.class),
            jwtUtil,
            runtimeIdentityProvider());

    client.buildStub(channel).createReport(CreateReportRequest.getDefaultInstance());

    assertInternalSocialServiceToken(channel);
  }

  @Test
  void moderationPolicyClientUsesInternalIdentityEvenWithEndUserContext() {
    SessionContext.setContext("42", List.of("player"), Map.of("7", List.of("tenantAdmin")));
    CapturingChannel channel = new CapturingChannel();
    ModerationPolicyClient client =
        new ModerationPolicyClient(
            new ServiceEndpointsProperties(),
            plaintextGrpcProperties(),
            mock(GrpcChannelFactory.class),
            jwtUtil,
            runtimeIdentityProvider());

    client
        .buildStub(channel)
        .evaluateModerationPolicy(EvaluateModerationPolicyRequest.getDefaultInstance());

    assertInternalSocialServiceToken(channel);
  }

  @Test
  void loggingAdminClientUsesUnknownServiceFallbackWhenRuntimeIdentityUnavailable() {
    CapturingChannel channel = new CapturingChannel();
    LoggingAdminClient client =
        new LoggingAdminClient(
            new ServiceEndpointsProperties(),
            plaintextGrpcProperties(),
            mock(GrpcChannelFactory.class),
            jwtUtil,
            nullRuntimeIdentityProvider());

    client.buildStub(channel).createReport(CreateReportRequest.getDefaultInstance());

    assertInternalServiceToken(channel, "unknown-service");
  }

  @Test
  void loggingAdminClientPropagatesApplicationErrors() throws Exception {
    CapturingChannel channel =
        new CapturingChannel(
            CreateReportResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("UNAVAILABLE")
                        .setMessage("Report creation unavailable"))
                .build()
                .toByteArray());
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    when(channelFactory.buildChannel(anyString(), anyInt(), any(), anyBoolean()))
        .thenReturn(channel);
    LoggingAdminClient client =
        new LoggingAdminClient(
            new ServiceEndpointsProperties(),
            plaintextGrpcProperties(),
            channelFactory,
            jwtUtil,
            runtimeIdentityProvider());
    client.init();

    assertThatThrownBy(() -> client.reportChatViolation(7L, ACCOUNT_ID, "Filtered profanity"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Chat violation report failed: UNAVAILABLE: Report creation unavailable")
        .hasMessageNotContaining("Filtered profanity");
  }

  @Test
  void socialRequestsCarryUuidIdentitiesWithoutNumericAliases() throws Exception {
    CapturingChannel channel = new CapturingChannel();
    GrpcChannelFactory factory = mock(GrpcChannelFactory.class);
    when(factory.buildChannel(anyString(), anyInt(), any(), anyBoolean())).thenReturn(channel);
    LoggingAdminClient reports =
        new LoggingAdminClient(
            new ServiceEndpointsProperties(),
            plaintextGrpcProperties(),
            factory,
            jwtUtil,
            runtimeIdentityProvider());
    reports.init();
    reports.reportChatViolation(7L, ACCOUNT_ID, "Filtered profanity");
    CreateReportRequest report = (CreateReportRequest) channel.lastRequest;
    assertThat(report.getReporterAccountId()).isEqualTo(ACCOUNT_ID);
    assertThat(report.getTargetAccountId()).isEqualTo(ACCOUNT_ID);
    assertThat(report.getTenantId()).isEqualTo("7");
    assertInternalSocialServiceToken(channel);

    ModerationPolicyClient policies =
        new ModerationPolicyClient(
            new ServiceEndpointsProperties(),
            plaintextGrpcProperties(),
            factory,
            jwtUtil,
            runtimeIdentityProvider());
    policies.init();
    policies.evaluateChatSend(7L, ACCOUNT_ID);
    EvaluateModerationPolicyRequest policy = (EvaluateModerationPolicyRequest) channel.lastRequest;
    assertThat(policy.getAccountId()).isEqualTo(ACCOUNT_ID);
    assertThat(policy.getTenantId()).isEqualTo("7");
    assertInternalSocialServiceToken(channel);

    GameSessionClient presence =
        new GameSessionClient(
            new ServiceEndpointsProperties(),
            plaintextGrpcProperties(),
            factory,
            BlockingGrpcStubCustomizer.noop());
    presence.init();
    presence.queryAccountPresence(7L, ACCOUNT_ID, List.of(FRIEND_ID));
    QueryAccountPresenceRequest request = (QueryAccountPresenceRequest) channel.lastRequest;
    assertThat(request.getViewerAccountId()).isEqualTo(ACCOUNT_ID);
    assertThat(request.getAccountIdsList()).containsExactly(FRIEND_ID);
    assertThat(request.getTenantId()).isEqualTo("7");
  }

  @Test
  void malformedSocialAccountSelectorsFailBeforeAnyRpc() {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    GrpcChannelFactory factory = mock(GrpcChannelFactory.class);
    LoggingAdminClient reports =
        new LoggingAdminClient(
            endpoints, plaintextGrpcProperties(), factory, jwtUtil, runtimeIdentityProvider());
    ModerationPolicyClient policies =
        new ModerationPolicyClient(
            endpoints, plaintextGrpcProperties(), factory, jwtUtil, runtimeIdentityProvider());
    GameSessionClient presence =
        new GameSessionClient(
            endpoints, plaintextGrpcProperties(), factory, BlockingGrpcStubCustomizer.noop());
    for (String accountId :
        List.of(
            "42",
            "",
            "00000000-0000-0000-0000-000000000000",
            ACCOUNT_ID.toUpperCase(java.util.Locale.ROOT),
            " " + ACCOUNT_ID)) {
      assertThatThrownBy(() -> reports.reportChatViolation(7L, accountId, "Filtered profanity"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> policies.evaluateChatSend(7L, accountId))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> presence.queryAccountPresence(7L, accountId, List.of(FRIEND_ID)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> presence.queryAccountPresence(7L, ACCOUNT_ID, List.of(accountId)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    org.mockito.Mockito.verifyNoInteractions(factory);
  }

  @Test
  void moderationPolicyClientUsesUnknownServiceFallbackWhenRuntimeIdentityUnavailable() {
    CapturingChannel channel = new CapturingChannel();
    ModerationPolicyClient client =
        new ModerationPolicyClient(
            new ServiceEndpointsProperties(),
            plaintextGrpcProperties(),
            mock(GrpcChannelFactory.class),
            jwtUtil,
            nullRuntimeIdentityProvider());

    client
        .buildStub(channel)
        .evaluateModerationPolicy(EvaluateModerationPolicyRequest.getDefaultInstance());

    assertInternalServiceToken(channel, "unknown-service");
  }

  private ObjectProvider<RuntimeIdentity> runtimeIdentityProvider() {
    return new ObjectProvider<>() {
      @Override
      public RuntimeIdentity getIfAvailable() {
        return runtimeIdentity;
      }
    };
  }

  private static ObjectProvider<RuntimeIdentity> nullRuntimeIdentityProvider() {
    return new ObjectProvider<>() {
      @Override
      public RuntimeIdentity getIfAvailable() {
        return null;
      }
    };
  }

  private static CommonGrpcClientProperties plaintextGrpcProperties() {
    CommonGrpcClientProperties properties = new CommonGrpcClientProperties();
    properties.setPlaintext(true);
    return properties;
  }

  private void assertInternalSocialServiceToken(CapturingChannel channel) {
    assertInternalServiceToken(channel, "social-groups-service");
  }

  private void assertInternalServiceToken(CapturingChannel channel, String expectedServiceName) {
    assertThat(channel.lastAuthorization).startsWith("Bearer ");
    String token = channel.lastAuthorization.substring("Bearer ".length());
    Jws<Claims> claims = jwtUtil.parseToken(token);

    assertThat(claims.getPayload().getSubject()).isEqualTo("service:" + expectedServiceName);
    assertThat(claims.getPayload().get("internalService", Boolean.class)).isTrue();
    assertThat(claims.getPayload().get("serviceName", String.class)).isEqualTo(expectedServiceName);
    assertThat(claims.getPayload()).doesNotContainKey("accountId");
    assertThat(claims.getPayload().get("globalRoles", Object.class)).isEqualTo(List.of());
    assertThat(claims.getPayload().get("scopedRoles", Object.class)).isEqualTo(Map.of());
  }

  private static final class CapturingChannel extends ManagedChannel {
    private String lastAuthorization;
    private Object lastRequest;
    private final byte[] responseBytes;

    private CapturingChannel() {
      this(new byte[0]);
    }

    private CapturingChannel(byte[] responseBytes) {
      this.responseBytes = responseBytes;
    }

    @Override
    public String authority() {
      return "test-authority";
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
        MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions callOptions) {
      return new ClientCall<>() {
        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          lastAuthorization = headers.get(AUTH_HEADER);
          responseListener.onMessage(
              methodDescriptor.parseResponse(new ByteArrayInputStream(responseBytes)));
          responseListener.onClose(Status.OK, new Metadata());
        }

        @Override
        public void request(int numMessages) {}

        @Override
        public void cancel(String message, Throwable cause) {}

        @Override
        public void halfClose() {}

        @Override
        public void sendMessage(ReqT message) {
          lastRequest = message;
        }
      };
    }

    @Override
    public ManagedChannel shutdown() {
      return this;
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public ManagedChannel shutdownNow() {
      return this;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }
  }
}
