package net.firedevops.firemud.loggingadmin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.dto.ModerationPolicyDecisionDto;
import net.firedevops.firemud.loggingadmin.service.LogEventService;
import net.firedevops.firemud.loggingadmin.service.LogQueryService;
import net.firedevops.firemud.loggingadmin.service.ModerationService;
import net.firedevops.firemud.loggingadmin.v1.CreateReportRequest;
import net.firedevops.firemud.loggingadmin.v1.CreateReportResponse;
import net.firedevops.firemud.loggingadmin.v1.EvaluateModerationPolicyRequest;
import net.firedevops.firemud.loggingadmin.v1.EvaluateModerationPolicyResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

class AccountUuidReceiverTest {
  private static final String ACCOUNT = "b8d093f7-cb70-40ed-9fac-3c82d4bf28f1";
  private static final String ACTOR = "e830a76a-a786-43de-9416-5840fa6e0578";

  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2",
        "not-a-uuid",
        "00000000-0000-0000-0000-000000000000",
        "B8D093F7-CB70-40ED-9FAC-3C82D4BF28F1",
        " b8d093f7-cb70-40ed-9fac-3c82d4bf28f1 "
      })
  void rejectsInvalidAccountShapesBeforeModerationOrActorUse(String account) {
    SessionContext.setContext(ACTOR, List.of(), Map.of(), true, "game-session-service", "peer");
    ModerationService moderation = Mockito.mock(ModerationService.class);
    LoggingAdminGrpcService service =
        new LoggingAdminGrpcService(
            Mockito.mock(LogQueryService.class),
            Mockito.mock(LogEventService.class),
            moderation,
            new SimpleMeterRegistry());
    AtomicReference<EvaluateModerationPolicyResponse> response = new AtomicReference<>();
    service.evaluateModerationPolicy(
        EvaluateModerationPolicyRequest.newBuilder()
            .setTenantId("1")
            .setAccountId(account)
            .setScope("CHAT_SEND")
            .build(),
        observer(response));
    assertThat(response.get().getError().getCode()).isEqualTo("INVALID_ARGUMENT");
    verifyNoInteractions(moderation);
    SessionContext.setContext(account, List.of("platformAdmin"), Map.of());
    assertThatThrownBy(SessionActorReaders::actorPrincipalOrInternalService)
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void moderationLooksUpExactTargetAndPreservesBanWithDistinctCallerAccount() {
    SessionContext.setContext(ACTOR, List.of(), Map.of(), true, "game-session-service", "peer");
    ModerationService moderation = Mockito.mock(ModerationService.class);
    Instant expires = Instant.ofEpochSecond(1900000000L);
    when(moderation.evaluatePolicy(1L, UUID.fromString(ACCOUNT), "CHAT_SEND"))
        .thenReturn(new ModerationPolicyDecisionDto(false, "chat_mute", "abuse", expires));
    LoggingAdminGrpcService service =
        new LoggingAdminGrpcService(
            Mockito.mock(LogQueryService.class),
            Mockito.mock(LogEventService.class),
            moderation,
            new SimpleMeterRegistry());
    AtomicReference<EvaluateModerationPolicyResponse> response = new AtomicReference<>();
    service.evaluateModerationPolicy(
        EvaluateModerationPolicyRequest.newBuilder()
            .setTenantId("1")
            .setAccountId(ACCOUNT)
            .setScope("CHAT_SEND")
            .build(),
        observer(response));
    assertThat(response.get().hasError()).isFalse();
    assertThat(response.get().getAllowed()).isFalse();
    assertThat(response.get().getAction()).isEqualTo("chat_mute");
    assertThat(response.get().getReason()).isEqualTo("abuse");
    assertThat(response.get().getExpiresAtEpochSeconds()).isEqualTo(expires.getEpochSecond());
    verify(moderation).evaluatePolicy(1L, UUID.fromString(ACCOUNT), "CHAT_SEND");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2",
        "not-a-uuid",
        "00000000-0000-0000-0000-000000000000",
        "B8D093F7-CB70-40ED-9FAC-3C82D4BF28F1",
        " b8d093f7-cb70-40ed-9fac-3c82d4bf28f1 "
      })
  void reportsValidateReporterAndTargetSeparatelyWhilePreservingHold(String invalid) {
    SessionContext.setContext(ACTOR, List.of(), Map.of(), true, "social-groups-service", "peer");
    ReportGrpcService service = new ReportGrpcService(new SimpleMeterRegistry());
    for (boolean invalidReporter : List.of(true, false)) {
      AtomicReference<CreateReportResponse> response = new AtomicReference<>();
      service.createReport(
          CreateReportRequest.newBuilder()
              .setTenantId("1")
              .setReporterAccountId(invalidReporter ? invalid : ACTOR)
              .setTargetAccountId(invalidReporter ? ACCOUNT : invalid)
              .build(),
          observer(response));
      assertThat(response.get().getError().getCode()).isEqualTo("INVALID_ARGUMENT");
    }
    AtomicReference<CreateReportResponse> held = new AtomicReference<>();
    service.createReport(
        CreateReportRequest.newBuilder()
            .setTenantId("1")
            .setReporterAccountId(ACTOR)
            .setTargetAccountId(ACCOUNT)
            .build(),
        observer(held));
    assertThat(held.get().getError().getCode()).isEqualTo("UNAVAILABLE");
  }

  private static <T> StreamObserver<T> observer(AtomicReference<T> result) {
    return new StreamObserver<>() {
      public void onNext(T value) {
        result.set(value);
      }

      public void onError(Throwable error) {
        throw new AssertionError(error);
      }

      public void onCompleted() {}
    };
  }
}
