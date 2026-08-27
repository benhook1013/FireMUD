package net.firedevops.firemud.loggingadmin.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.v1.CreateReportRequest;
import net.firedevops.firemud.loggingadmin.v1.CreateReportResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReportGrpcServiceTest {
  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void createReportRejectsWhileMutationGateIsUnavailable() {
    SessionContext.setContext(
        "", List.of(), Map.of(), true, "social-groups-service", "test-instance");
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ReportGrpcService service = new ReportGrpcService(meterRegistry);

    AtomicReference<CreateReportResponse> ref = new AtomicReference<>();
    service.createReport(
        CreateReportRequest.newBuilder()
            .setTenantId("1")
            .setReporterAccountId("2")
            .setTargetAccountId("3")
            .setType("BUG")
            .setDescription("bad")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CreateReportResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("UNAVAILABLE", ref.get().getError().getCode());
    assertEquals(
        "Report creation is unavailable until the shared mutation gate is implemented",
        ref.get().getError().getMessage());
  }

  @Test
  void createReportDoesNotDelegateToPersistenceWhileMutationGateIsUnavailable() {
    SessionContext.setContext(
        "", List.of(), Map.of(), true, "social-groups-service", "test-instance");
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ReportGrpcService service = new ReportGrpcService(meterRegistry);

    AtomicReference<CreateReportResponse> ref = new AtomicReference<>();
    service.createReport(
        CreateReportRequest.newBuilder()
            .setTenantId("1")
            .setReporterAccountId("2")
            .setTargetAccountId("3")
            .setType("BUG")
            .setDescription("bad")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CreateReportResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("UNAVAILABLE", ref.get().getError().getCode());
  }

  @Test
  void createReportRejectsZeroReporterAccountIdBeforeDispatch() {
    SessionContext.setContext(
        "", List.of(), Map.of(), true, "social-groups-service", "test-instance");
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ReportGrpcService service = new ReportGrpcService(meterRegistry);

    AtomicReference<CreateReportResponse> ref = new AtomicReference<>();
    service.createReport(
        CreateReportRequest.newBuilder()
            .setTenantId("1")
            .setReporterAccountId("0")
            .setType("BUG")
            .setDescription("bad")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CreateReportResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("reporterAccountId must be positive", ref.get().getError().getMessage());
  }

  @Test
  void createReportRejectsZeroTargetAccountIdBeforeDispatch() {
    SessionContext.setContext(
        "", List.of(), Map.of(), true, "social-groups-service", "test-instance");
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ReportGrpcService service = new ReportGrpcService(meterRegistry);

    AtomicReference<CreateReportResponse> ref = new AtomicReference<>();
    service.createReport(
        CreateReportRequest.newBuilder()
            .setTenantId("1")
            .setReporterAccountId("2")
            .setTargetAccountId("0")
            .setType("BUG")
            .setDescription("bad")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CreateReportResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("targetAccountId must be positive", ref.get().getError().getMessage());
  }

  @Test
  void createReportRejectsMissingCallerBeforePersistence() {
    SessionContext.clear();
    ReportGrpcService service = new ReportGrpcService(new SimpleMeterRegistry());

    AtomicReference<CreateReportResponse> ref = new AtomicReference<>();
    service.createReport(
        CreateReportRequest.newBuilder()
            .setTenantId("1")
            .setReporterAccountId("2")
            .setType("BUG")
            .setDescription("bad")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CreateReportResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("PERMISSION_DENIED", ref.get().getError().getCode());
  }

  @Test
  void createReportRejectsAuthenticatedEndUserBeforePersistence() {
    SessionContext.setContext("42", List.of("player"), Map.of());
    ReportGrpcService service = new ReportGrpcService(new SimpleMeterRegistry());

    AtomicReference<CreateReportResponse> ref = new AtomicReference<>();
    service.createReport(
        CreateReportRequest.newBuilder()
            .setTenantId("1")
            .setReporterAccountId("2")
            .setType("BUG")
            .setDescription("bad")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CreateReportResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("PERMISSION_DENIED", ref.get().getError().getCode());
  }

  @Test
  void createReportRejectsWrongInternalServiceBeforePersistence() {
    SessionContext.setContext(
        "", List.of(), Map.of(), true, "game-session-service", "test-instance");
    ReportGrpcService service = new ReportGrpcService(new SimpleMeterRegistry());

    AtomicReference<CreateReportResponse> ref = new AtomicReference<>();
    service.createReport(
        CreateReportRequest.newBuilder()
            .setTenantId("1")
            .setReporterAccountId("2")
            .setType("BUG")
            .setDescription("bad")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CreateReportResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("PERMISSION_DENIED", ref.get().getError().getCode());
  }
}
