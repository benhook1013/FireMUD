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
  private static final String REPORTER_UUID = "550e8400-e29b-41d4-a716-446655440000";
  private static final String TARGET_UUID = "6ba7b810-9dad-41d1-80b4-00c04fd430c8";

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
            .setReporterAccountId(REPORTER_UUID)
            .setTargetAccountId(TARGET_UUID)
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
            .setReporterAccountId(REPORTER_UUID)
            .setTargetAccountId(TARGET_UUID)
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
  void createReportRejectsNumericReporterAccountIdBeforeDispatch() {
    SessionContext.setContext(
        "", List.of(), Map.of(), true, "social-groups-service", "test-instance");
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ReportGrpcService service = new ReportGrpcService(meterRegistry);

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
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("Malformed claim: reporterAccountId", ref.get().getError().getMessage());
  }

  @Test
  void createReportRejectsNilTargetAccountIdBeforeDispatch() {
    SessionContext.setContext(
        "", List.of(), Map.of(), true, "social-groups-service", "test-instance");
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ReportGrpcService service = new ReportGrpcService(meterRegistry);

    AtomicReference<CreateReportResponse> ref = new AtomicReference<>();
    service.createReport(
        CreateReportRequest.newBuilder()
            .setTenantId("1")
            .setReporterAccountId(REPORTER_UUID)
            .setTargetAccountId("00000000-0000-0000-0000-000000000000")
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
    assertEquals("Invalid claim: targetAccountId", ref.get().getError().getMessage());
  }

  @Test
  void createReportRejectsMissingCallerBeforePersistence() {
    SessionContext.clear();
    ReportGrpcService service = new ReportGrpcService(new SimpleMeterRegistry());

    AtomicReference<CreateReportResponse> ref = new AtomicReference<>();
    service.createReport(
        CreateReportRequest.newBuilder()
            .setTenantId("1")
            .setReporterAccountId(REPORTER_UUID)
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
            .setReporterAccountId(REPORTER_UUID)
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
            .setReporterAccountId(REPORTER_UUID)
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
