package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.DisconnectDeduplicationService;
import net.firedevops.firemud.gamesession.service.PingService;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectResponse;
import net.firedevops.firemud.tcpproxy.v1.PingRequest;
import net.firedevops.firemud.tcpproxy.v1.PingResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class TcpProxyServiceImplTest {
  @Test
  void validDisconnectHintIsAcknowledgedAfterReadOnlyInstanceValidation() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    GameInstance entity = buildEntity(12L, 7L);
    Mockito.when(repository.findById(12L)).thenReturn(Optional.of(entity));
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    TcpProxyServiceImpl service = service(repository, meterRegistry, acceptingDeduplication());

    NotifyDisconnectResponse response =
        notifyDisconnect(
            service,
            NotifyDisconnectRequest.newBuilder()
                .setSessionId("41")
                .setGameInstanceId("12")
                .setTenantId("7")
                .build());

    assertEquals("OK", response.getError().getCode());
    assertEquals("Disconnect recorded", response.getError().getMessage());
    assertEntityUnchanged(entity);
    Mockito.verify(repository).findById(12L);
    Mockito.verifyNoMoreInteractions(repository);
  }

  @Test
  void notifyDisconnectRetainsGameplayLoggingContext() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    Mockito.when(repository.findById(12L))
        .thenAnswer(
            ignored -> {
              assertEquals("7", MDC.get("tenantId"));
              assertEquals("12", MDC.get("gameInstanceId"));
              assertEquals(null, MDC.get("characterId"));
              return Optional.of(buildEntity(12L, 7L));
            });
    TcpProxyServiceImpl service =
        service(repository, new SimpleMeterRegistry(), acceptingDeduplication());

    NotifyDisconnectResponse response =
        notifyDisconnect(
            service,
            NotifyDisconnectRequest.newBuilder().setGameInstanceId("12").setTenantId("7").build());

    assertEquals("OK", response.getError().getCode());
    assertEquals(null, MDC.get("tenantId"));
    assertEquals(null, MDC.get("gameInstanceId"));
    assertEquals(null, MDC.get("characterId"));
    Mockito.verify(repository).findById(12L);
    Mockito.verifyNoMoreInteractions(repository);
  }

  @Test
  void notifyDisconnectRejectsInvalidTenantWithoutInstanceLookup(CapturedOutput output) {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    TcpProxyServiceImpl service = service(repository, meterRegistry, acceptingDeduplication());

    NotifyDisconnectResponse response =
        notifyDisconnect(
            service,
            NotifyDisconnectRequest.newBuilder()
                .setSessionId("12")
                .setGameInstanceId("12")
                .setTenantId("bad")
                .build());

    assertEquals("INVALID_ARGUMENT", response.getError().getCode());
    assertEquals("tenantId must be a number", response.getError().getMessage());
    assertEquals(
        1.0, meterRegistry.get("grpc.app_error").tag("code", "INVALID_ARGUMENT").counter().count());
    org.assertj.core.api.Assertions.assertThat(output)
        .contains(
            "NotifyDisconnect returned app error INVALID_ARGUMENT: tenantId must be a number");
    Mockito.verifyNoInteractions(repository);
  }

  @Test
  void notifyDisconnectRejectsNonPositiveInstanceId() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    TcpProxyServiceImpl service = service(repository, meterRegistry, acceptingDeduplication());

    NotifyDisconnectResponse response =
        notifyDisconnect(
            service,
            NotifyDisconnectRequest.newBuilder()
                .setSessionId("12")
                .setGameInstanceId("0")
                .setTenantId("7")
                .build());

    assertEquals("INVALID_ARGUMENT", response.getError().getCode());
    assertEquals("gameInstanceId must be positive", response.getError().getMessage());
    assertEquals(
        1.0, meterRegistry.get("grpc.app_error").tag("code", "INVALID_ARGUMENT").counter().count());
    Mockito.verifyNoInteractions(repository);
  }

  @Test
  void notifyDisconnectRejectsNonPositiveTenantId() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    TcpProxyServiceImpl service = service(repository, meterRegistry, acceptingDeduplication());

    NotifyDisconnectResponse response =
        notifyDisconnect(
            service,
            NotifyDisconnectRequest.newBuilder()
                .setSessionId("12")
                .setGameInstanceId("12")
                .setTenantId("0")
                .build());

    assertEquals("INVALID_ARGUMENT", response.getError().getCode());
    assertEquals("tenantId must be positive", response.getError().getMessage());
    assertEquals(
        1.0, meterRegistry.get("grpc.app_error").tag("code", "INVALID_ARGUMENT").counter().count());
    Mockito.verifyNoInteractions(repository);
  }

  @Test
  void notifyDisconnectRejectsWrongTenantWithoutChangingInstance() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    GameInstance entity = buildEntity(12L, 7L);
    Mockito.when(repository.findById(12L)).thenReturn(Optional.of(entity));
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    TcpProxyServiceImpl service = service(repository, meterRegistry, acceptingDeduplication());

    NotifyDisconnectResponse response =
        notifyDisconnect(
            service,
            NotifyDisconnectRequest.newBuilder()
                .setSessionId("41")
                .setGameInstanceId("12")
                .setTenantId("9")
                .build());

    assertEquals("INVALID_ARGUMENT", response.getError().getCode());
    assertEquals("Tenant does not own session", response.getError().getMessage());
    assertEntityUnchanged(entity);
    Mockito.verify(repository).findById(12L);
    Mockito.verifyNoMoreInteractions(repository);
  }

  @Test
  void notifyDisconnectReturnsNotFoundForMissingInstance() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    Mockito.when(repository.findById(12L)).thenReturn(Optional.empty());
    TcpProxyServiceImpl service =
        service(repository, new SimpleMeterRegistry(), acceptingDeduplication());

    NotifyDisconnectResponse response =
        notifyDisconnect(
            service,
            NotifyDisconnectRequest.newBuilder().setGameInstanceId("12").setTenantId("7").build());

    assertEquals("NOT_FOUND", response.getError().getCode());
    assertEquals("Game instance not found", response.getError().getMessage());
    Mockito.verify(repository).findById(12L);
    Mockito.verifyNoMoreInteractions(repository);
  }

  @Test
  void duplicateDisconnectHintIsAcknowledgedAndMeteredWithoutRepeatLookup() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    GameInstance entity = buildEntity(12L, 7L);
    Mockito.when(repository.findById(12L)).thenReturn(Optional.of(entity));
    DisconnectDeduplicationService deduplication =
        Mockito.mock(DisconnectDeduplicationService.class);
    Mockito.when(deduplication.shouldProcess(Mockito.anyString(), Mockito.anyLong()))
        .thenReturn(true, false);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    TcpProxyServiceImpl service = service(repository, meterRegistry, deduplication);
    NotifyDisconnectRequest request = sequencedHint(2L);

    NotifyDisconnectResponse first = notifyDisconnect(service, request);
    NotifyDisconnectResponse duplicate = notifyDisconnect(service, request);

    assertEquals("OK", first.getError().getCode());
    assertEquals("OK", duplicate.getError().getCode());
    assertEquals("Duplicate disconnect ignored", duplicate.getError().getMessage());
    assertEquals(
        1.0, meterRegistry.get("gamesession.notifydisconnect.duplicate").counter().count());
    assertEntityUnchanged(entity);
    Mockito.verify(deduplication, Mockito.times(2)).shouldProcess("proxy-1", 2L);
    Mockito.verify(repository).findById(12L);
    Mockito.verifyNoMoreInteractions(repository);
  }

  @Test
  void olderDisconnectSequenceIsIgnoredAndMetered() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    GameInstance entity = buildEntity(12L, 7L);
    Mockito.when(repository.findById(12L)).thenReturn(Optional.of(entity));
    DisconnectDeduplicationService deduplication =
        Mockito.mock(DisconnectDeduplicationService.class);
    Mockito.when(deduplication.shouldProcess(Mockito.anyString(), Mockito.anyLong()))
        .thenReturn(true, false);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    TcpProxyServiceImpl service = service(repository, meterRegistry, deduplication);

    NotifyDisconnectResponse newest = notifyDisconnect(service, sequencedHint(2L));
    NotifyDisconnectResponse older = notifyDisconnect(service, sequencedHint(1L));

    assertEquals("OK", newest.getError().getCode());
    assertEquals("OK", older.getError().getCode());
    assertEquals("Duplicate disconnect ignored", older.getError().getMessage());
    assertEquals(
        1.0, meterRegistry.get("gamesession.notifydisconnect.duplicate").counter().count());
    assertEntityUnchanged(entity);
    Mockito.verify(deduplication).shouldProcess("proxy-1", 2L);
    Mockito.verify(deduplication).shouldProcess("proxy-1", 1L);
    Mockito.verify(repository).findById(12L);
    Mockito.verifyNoMoreInteractions(repository);
  }

  @Test
  void acceptedHintAfterDedupeResetRemainsEffectFree() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    GameInstance entity = buildEntity(12L, 7L);
    Mockito.when(repository.findById(12L)).thenReturn(Optional.of(entity));
    DisconnectDeduplicationService deduplication = acceptingDeduplication();
    TcpProxyServiceImpl service = service(repository, new SimpleMeterRegistry(), deduplication);

    // A missing/reset dedupe record may admit the hint; it still is not authority to mutate state.
    NotifyDisconnectResponse response = notifyDisconnect(service, sequencedHint(3L));

    assertEquals("OK", response.getError().getCode());
    assertEntityUnchanged(entity);
    Mockito.verify(deduplication).shouldProcess("proxy-1", 3L);
    Mockito.verify(repository).findById(12L);
    Mockito.verifyNoMoreInteractions(repository);
  }

  @Test
  void missingProxyMetadataWithOptionalSessionIdIsMeteredWithoutEffects() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    TcpProxyServiceImpl service = service(repository, meterRegistry, acceptingDeduplication());

    NotifyDisconnectResponse response =
        notifyDisconnect(
            service,
            NotifyDisconnectRequest.newBuilder().setSessionId("12").setTenantId("7").build());

    assertEquals("OK", response.getError().getCode());
    assertEquals(
        "Disconnect recorded (no proxy bootstrap metadata)", response.getError().getMessage());
    assertEquals(
        1.0, meterRegistry.get("gamesession.notifydisconnect.missing_context").counter().count());
    Mockito.verifyNoInteractions(repository);
  }

  @Test
  void missingProxyMetadataDoesNotInterpretSessionIdAsInstanceId() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    TcpProxyServiceImpl service = service(repository, meterRegistry, acceptingDeduplication());

    NotifyDisconnectResponse response =
        notifyDisconnect(
            service,
            NotifyDisconnectRequest.newBuilder()
                .setSessionId("12")
                .setTenantId("7")
                .setProxyConnectionId("proxy-1")
                .setDisconnectSequence(1L)
                .build());

    assertEquals("OK", response.getError().getCode());
    assertEquals(
        1.0, meterRegistry.get("gamesession.notifydisconnect.missing_context").counter().count());
    Mockito.verifyNoInteractions(repository);
  }

  @Test
  void invalidOptionalSessionIdDoesNotAffectMissingContextAcknowledgement() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    TcpProxyServiceImpl service = service(repository, meterRegistry, acceptingDeduplication());

    NotifyDisconnectResponse response =
        notifyDisconnect(
            service,
            NotifyDisconnectRequest.newBuilder().setSessionId("-1").setTenantId("7").build());

    assertEquals("OK", response.getError().getCode());
    assertEquals(
        1.0, meterRegistry.get("gamesession.notifydisconnect.missing_context").counter().count());
    Mockito.verifyNoInteractions(repository);
  }

  @Test
  void pingStillDelegatesToPingService() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenReturn("pong");
    TcpProxyServiceImpl service =
        new TcpProxyServiceImpl(repository, meterRegistry, pingService, acceptingDeduplication());
    AtomicReference<PingResponse> responseRef = new AtomicReference<>();

    service.ping(
        PingRequest.getDefaultInstance(),
        new StreamObserver<>() {
          @Override
          public void onNext(PingResponse value) {
            responseRef.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("pong", responseRef.get().getMessage());
    Mockito.verify(pingService).ping();
    Mockito.verifyNoInteractions(repository);
  }

  private static TcpProxyServiceImpl service(
      GameInstanceRepository repository,
      MeterRegistry meterRegistry,
      DisconnectDeduplicationService deduplication) {
    return new TcpProxyServiceImpl(
        repository, meterRegistry, Mockito.mock(PingService.class), deduplication);
  }

  private static DisconnectDeduplicationService acceptingDeduplication() {
    DisconnectDeduplicationService deduplication =
        Mockito.mock(DisconnectDeduplicationService.class);
    Mockito.when(deduplication.shouldProcess(Mockito.anyString(), Mockito.anyLong()))
        .thenReturn(true);
    return deduplication;
  }

  private static NotifyDisconnectRequest sequencedHint(long sequence) {
    return NotifyDisconnectRequest.newBuilder()
        .setSessionId("41")
        .setGameInstanceId("12")
        .setTenantId("7")
        .setProxyConnectionId("proxy-1")
        .setDisconnectSequence(sequence)
        .build();
  }

  private static NotifyDisconnectResponse notifyDisconnect(
      TcpProxyServiceImpl service, NotifyDisconnectRequest request) {
    AtomicReference<NotifyDisconnectResponse> responseRef = new AtomicReference<>();
    service.notifyDisconnect(request, observerFor(responseRef));
    return responseRef.get();
  }

  private static StreamObserver<NotifyDisconnectResponse> observerFor(
      AtomicReference<NotifyDisconnectResponse> ref) {
    return new StreamObserver<>() {
      @Override
      public void onNext(NotifyDisconnectResponse value) {
        ref.set(value);
      }

      @Override
      public void onError(Throwable t) {
        fail(t);
      }

      @Override
      public void onCompleted() {}
    };
  }

  private static void assertEntityUnchanged(GameInstance entity) {
    assertEquals(12L, entity.getId());
    assertEquals(7L, entity.getTenantId());
    assertEquals("rt", entity.getRuntimeVersion());
    assertEquals("patch", entity.getScriptPatchVersion());
    assertEquals(3L, entity.getScriptPinEpoch());
    assertEquals("pin-request-3", entity.getScriptPatchPinnedControlPlaneRequestId());
    assertEquals(99L, entity.getOwnerAccountId());
    assertEquals("RUNNING", entity.getStatus());
  }

  private static GameInstance buildEntity(long gameInstanceId, long tenantId) {
    GameInstance entity = new GameInstance();
    entity.setId(gameInstanceId);
    entity.setTenantId(tenantId);
    entity.setRuntimeVersion("rt");
    entity.setScriptPatchVersion("patch");
    entity.setScriptPinEpoch(3L);
    entity.setScriptPatchPinnedControlPlaneRequestId("pin-request-3");
    entity.setOwnerAccountId(99L);
    entity.setStatus("RUNNING");
    return entity;
  }
}
