package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Attributes;
import io.grpc.ClientCall;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.account.v1.AcknowledgeIssuerProjectionForRuntimeRequest;
import net.firedevops.firemud.account.v1.AcknowledgeIssuerProjectionForRuntimeResponse;
import net.firedevops.firemud.account.v1.CaptureIssuerProjectionForRuntimeRequest;
import net.firedevops.firemud.account.v1.CaptureIssuerProjectionForRuntimeResponse;
import net.firedevops.firemud.account.v1.IssuerAuthorityServiceGrpc;
import net.firedevops.firemud.account.v1.IssuerAuthoritySourceSnapshot;
import net.firedevops.firemud.account.v1.ReadIssuerAuthorityForRuntimeRequest;
import net.firedevops.firemud.account.v1.ReadIssuerAuthorityForRuntimeResponse;
import net.firedevops.firemud.common.account.authority.IssuerAuthorityProjectionV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import net.firedevops.firemud.common.account.authority.IssuerProjectionInstallationAcknowledgmentDigestV1;
import net.firedevops.firemud.common.account.authority.IssuerProjectionReconciliationRequestDigestV1;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.service.IssuerAuthorityProjectionRedisContract;
import net.firedevops.firemud.gamesession.service.IssuerProjectionReconciliationInstaller;
import net.firedevops.firemud.gamesession.service.IssuerProjectionReconciliationInstaller.InstallationReceipt;
import net.firedevops.firemud.gamesession.service.RedisIssuerAuthorityProjectionStore;
import net.firedevops.firemud.gamesession.service.RedisIssuerAuthorityProjectionStore.CacheRateLimitEndpoint;
import net.firedevops.firemud.gamesession.service.RedisIssuerAuthorityProjectionStore.CoordinationEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

class AccountIssuerAuthorityClientTest {
  private static final String NAMESPACE = "test";
  private static final String ISSUER_ID = "https://accounts.example.test/issuer";
  private static final String SOURCE_SCOPE = "issuer/" + ISSUER_ID;
  private static final String STREAM_KEY =
      IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + SOURCE_SCOPE;
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID EVENT_REQUEST_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID OPERATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String LARGE_COUNTER = "922337203685477580812345678901234567890";
  private static final String CAPTURE_CALLER =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service";
  private static final String CAPTURE_PROJECTION_KEY =
      "session:game:auth:issuer-generation:v1:" + ISSUER_ID;
  private static final UUID ACKNOWLEDGMENT_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void currentReadVerifiesAndReturnsTheSequenceZeroPositiveBaseline() throws Exception {
    IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub = mockStub();
    when(stub.readIssuerAuthorityForRuntime(any())).thenReturn(baselineResponse());
    AccountIssuerAuthorityClient client = newClient(stub);

    AccountIssuerAuthorityClient.SourceReadback readback =
        client.readCurrent(REQUEST_ID.toString());

    assertThat(readback.requestId()).isEqualTo(REQUEST_ID.toString());
    assertThat(readback.targetNamespace()).isEqualTo(NAMESPACE);
    assertThat(readback.sourceSnapshot().issuerId()).isEqualTo(ISSUER_ID);
    assertThat(readback.sourceSnapshot().sourceScope()).isEqualTo(SOURCE_SCOPE);
    assertThat(readback.sourceSnapshot().outboxStreamKey()).isEqualTo(STREAM_KEY);
    assertThat(readback.sourceSnapshot().issuerAuthGeneration()).isEqualTo("1");
    assertThat(readback.sourceSnapshot().sourceVersion()).isEqualTo("1");
    assertThat(readback.sourceSnapshot().outboxSequence()).isEqualTo("0");
    assertThat(readback.sourceSnapshot().latestEvent()).isEmpty();
    assertThat(readback.requestedEvent()).isEmpty();

    ArgumentCaptor<ReadIssuerAuthorityForRuntimeRequest> requestCaptor =
        ArgumentCaptor.forClass(ReadIssuerAuthorityForRuntimeRequest.class);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verify(stub).readIssuerAuthorityForRuntime(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getIssuerId()).isEqualTo(ISSUER_ID);
    assertThat(requestCaptor.getValue().getRequestId()).isEqualTo(REQUEST_ID.toString());
    assertThat(requestCaptor.getValue().hasRequestedOutboxSequence()).isFalse();
  }

  @Test
  void currentReadVerifiesPositiveLatestEventAndArbitraryPrecisionCounters() throws Exception {
    String sequence = LARGE_COUNTER;
    IssuerGenerationAuthorityEvent event =
        event(sequence, LARGE_COUNTER, LARGE_COUNTER, EVENT_REQUEST_ID);
    IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub = mockStub();
    when(stub.readIssuerAuthorityForRuntime(any()))
        .thenReturn(
            snapshotResponse(LARGE_COUNTER, LARGE_COUNTER, sequence, event.canonicalJson()));
    AccountIssuerAuthorityClient client = newClient(stub);

    AccountIssuerAuthorityClient.SourceReadback readback =
        client.readCurrent(REQUEST_ID.toString());

    assertThat(readback.sourceSnapshot().issuerAuthGeneration()).isEqualTo(LARGE_COUNTER);
    assertThat(readback.sourceSnapshot().sourceVersion()).isEqualTo(LARGE_COUNTER);
    assertThat(readback.sourceSnapshot().outboxSequence()).isEqualTo(sequence);
    assertLatestEvent(readback.sourceSnapshot().latestEvent().orElseThrow(), event);
  }

  @Test
  void historicalReadPreservesLargeSelectorAndReturnsSeparateCurrentAndSelectedEvents()
      throws Exception {
    String currentSequence = "100000000000000000000000000000000000000";
    String selectedSequence = "99999999999999999999999999999999999999";
    IssuerGenerationAuthorityEvent latest = event(currentSequence, "101", "201", EVENT_REQUEST_ID);
    IssuerGenerationAuthorityEvent selected =
        event(
            selectedSequence,
            "100",
            "200",
            UUID.fromString("33333333-3333-4333-8333-333333333333"));
    IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub = mockStub();
    when(stub.readIssuerAuthorityForRuntime(any()))
        .thenReturn(
            snapshotResponse(
                "101", "201", currentSequence, latest.canonicalJson(), selected.canonicalJson()));
    AccountIssuerAuthorityClient client = newClient(stub);

    AccountIssuerAuthorityClient.SourceReadback readback =
        client.readCommittedEvent(REQUEST_ID.toString(), selectedSequence);

    assertLatestEvent(readback.sourceSnapshot().latestEvent().orElseThrow(), latest);
    assertLatestEvent(readback.requestedEvent().orElseThrow(), selected);
    ArgumentCaptor<ReadIssuerAuthorityForRuntimeRequest> requestCaptor =
        ArgumentCaptor.forClass(ReadIssuerAuthorityForRuntimeRequest.class);
    verify(stub).readIssuerAuthorityForRuntime(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getRequestedOutboxSequence()).isEqualTo(selectedSequence);
  }

  @Test
  void historicalReadAtCurrentSequenceRequiresTheCompleteLatestEvent() throws Exception {
    IssuerGenerationAuthorityEvent event = event("2", "3", "3", EVENT_REQUEST_ID);
    IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub = mockStub();
    when(stub.readIssuerAuthorityForRuntime(any()))
        .thenReturn(snapshotResponse("3", "3", "2", event.canonicalJson(), event.canonicalJson()));
    AccountIssuerAuthorityClient client = newClient(stub);

    AccountIssuerAuthorityClient.SourceReadback readback =
        client.readCommittedEvent(REQUEST_ID.toString(), "2");

    assertLatestEvent(readback.sourceSnapshot().latestEvent().orElseThrow(), event);
    assertLatestEvent(readback.requestedEvent().orElseThrow(), event);
  }

  @Test
  void captureRequestUsesOnlyIssuerAndStableRequestAndReturnsTypedZeroBaseline() throws Exception {
    IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub = mockStub();
    when(stub.captureIssuerProjectionForRuntime(any()))
        .thenReturn(captureResponse(REQUEST_ID, OPERATION_ID, sourceSnapshot("1", "1", "0", null)));
    AccountIssuerAuthorityClient client = newClient(stub);

    AccountIssuerAuthorityClient.ProjectionCaptureReceipt receipt =
        client.captureProjection(REQUEST_ID.toString());

    assertThat(receipt.operationUUID()).isEqualTo(OPERATION_ID);
    assertThat(receipt.requestUUID()).isEqualTo(REQUEST_ID);
    assertThat(receipt.issuerId()).isEqualTo(ISSUER_ID);
    assertThat(receipt.callerWorkloadIdentity()).isEqualTo(CAPTURE_CALLER);
    assertThat(receipt.projectionKey()).isEqualTo(CAPTURE_PROJECTION_KEY);
    assertThat(receipt.requestDigestVersion()).isEqualTo(1);
    assertThat(receipt.requestDigest())
        .isEqualTo(
            IssuerProjectionReconciliationRequestDigestV1.digest(
                ISSUER_ID, CAPTURE_CALLER, CAPTURE_PROJECTION_KEY, REQUEST_ID));
    assertThat(receipt.capturedSource().issuerAuthGeneration()).isEqualTo("1");
    assertThat(receipt.capturedSource().sourceVersion()).isEqualTo("1");
    assertThat(receipt.capturedSource().outboxSequence()).isEqualTo("0");
    assertThat(receipt.capturedSource().latestEvent()).isEmpty();

    ArgumentCaptor<CaptureIssuerProjectionForRuntimeRequest> requestCaptor =
        ArgumentCaptor.forClass(CaptureIssuerProjectionForRuntimeRequest.class);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verify(stub).captureIssuerProjectionForRuntime(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getIssuerId()).isEqualTo(ISSUER_ID);
    assertThat(requestCaptor.getValue().getRequestId()).isEqualTo(REQUEST_ID.toString());
    assertThat(requestCaptor.getValue().getAllFields()).hasSize(2);
  }

  @Test
  void captureVerifiesSharedAsciiDigestVectorAndLargeIndependentSourceCounters() throws Exception {
    String namespace = "account-unit";
    String issuerId = "https://account.example.test/issuer";
    UUID requestId = UUID.fromString("a3a69671-8149-4d97-86f8-25a2582fdd68");
    UUID operationId = UUID.fromString("4ed42a0f-80e4-44db-8f4e-16c70e2c70a3");
    String caller = "spiffe://firemud/ns/account-unit/sa/game-session-service";
    String projectionKey = "session:game:auth:issuer-generation:v1:" + issuerId;
    String digest = "240027543295dc990703ec833578b423f1cdc51e91333d8b58c91d6c5f571d15";
    String generation = LARGE_COUNTER + "1";
    String sourceVersion = LARGE_COUNTER + "2";
    String sequence = LARGE_COUNTER + "3";
    IssuerGenerationAuthorityEvent event =
        event(sequence, generation, sourceVersion, EVENT_REQUEST_ID, issuerId);
    IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub = mockStub();
    when(stub.captureIssuerProjectionForRuntime(any()))
        .thenReturn(
            captureResponse(
                namespace,
                issuerId,
                requestId,
                operationId,
                caller,
                projectionKey,
                1,
                digest,
                sourceSnapshot(
                    issuerId, generation, sourceVersion, sequence, event.canonicalJson())));
    AccountIssuerAuthorityClient client = newClient(stub, namespace, issuerId);

    AccountIssuerAuthorityClient.ProjectionCaptureReceipt receipt =
        client.captureProjection(requestId.toString());

    assertThat(receipt.requestDigest()).isEqualTo(digest);
    assertThat(receipt.capturedSource().issuerAuthGeneration()).isEqualTo(generation);
    assertThat(receipt.capturedSource().sourceVersion()).isEqualTo(sourceVersion);
    assertThat(receipt.capturedSource().outboxSequence()).isEqualTo(sequence);
    assertLatestEvent(receipt.capturedSource().latestEvent().orElseThrow(), event);
  }

  @Test
  void exactCaptureRetryReturnsItsUnchangedHistoricalSnapshot() throws Exception {
    IssuerGenerationAuthorityEvent historical = event("3", "7", "9", EVENT_REQUEST_ID);
    IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub = mockStub();
    when(stub.captureIssuerProjectionForRuntime(any()))
        .thenReturn(
            captureResponse(
                REQUEST_ID,
                OPERATION_ID,
                sourceSnapshot("7", "9", "3", historical.canonicalJson())));
    AccountIssuerAuthorityClient client = newClient(stub);

    AccountIssuerAuthorityClient.ProjectionCaptureReceipt receipt =
        client.captureProjection(REQUEST_ID.toString());

    assertThat(receipt.operationUUID()).isEqualTo(OPERATION_ID);
    assertThat(receipt.capturedSource().outboxSequence()).isEqualTo("3");
    assertLatestEvent(receipt.capturedSource().latestEvent().orElseThrow(), historical);
    verify(stub).captureIssuerProjectionForRuntime(any());
  }

  @Test
  void installationAcknowledgmentUsesPrivateReceiptAndReplaysOriginalAcknowledgment()
      throws Exception {
    InstallationFixture fixture = installationFixture(sourceSnapshot("1", "1", "0", null));
    try {
      InstallationReceipt installation =
          fixture
              .installer()
              .install(REQUEST_ID.toString(), "original-applied-at")
              .receipt()
              .orElseThrow();
      AcknowledgeIssuerProjectionForRuntimeResponse originalResponse =
          installationAcknowledgmentResponse(installation, NAMESPACE, ISSUER_ID, ACKNOWLEDGMENT_ID);
      when(fixture.stub().acknowledgeIssuerProjectionForRuntime(any()))
          .thenReturn(originalResponse);

      AccountIssuerAuthorityClient.InstallationAcknowledgmentReceipt original =
          fixture.client().acknowledgeInstallation(installation);
      AccountIssuerAuthorityClient.InstallationAcknowledgmentReceipt retry =
          fixture.client().acknowledgeInstallation(installation);

      assertThat(original.acknowledgmentId()).isEqualTo(ACKNOWLEDGMENT_ID);
      assertThat(retry.acknowledgmentId()).isEqualTo(original.acknowledgmentId());
      assertThat(original.captureOperationId()).isEqualTo(installation.operationId());
      assertThat(original.captureRequestId()).isEqualTo(installation.requestId());
      assertThat(original.targetNamespace()).isEqualTo(NAMESPACE);
      assertThat(original.captureRequestDigest()).isEqualTo(installation.requestDigest());
      assertThat(original.captureRequestDigestVersion()).isEqualTo(1);
      assertThat(original.requestDigestVersion())
          .isEqualTo(IssuerProjectionInstallationAcknowledgmentDigestV1.VERSION);
      assertThat(original.installedProjectionJson())
          .isEqualTo(installation.projectionSnapshot().json())
          .contains("\"appliedAt\":\"original-applied-at\"");
      assertThat(original.installedProjectionSha256())
          .isEqualTo(originalResponse.getInstalledProjectionSha256());
      assertThat(
              AccountIssuerAuthorityClient.InstallationAcknowledgmentReceipt.class
                  .getConstructors())
          .isEmpty();

      ArgumentCaptor<AcknowledgeIssuerProjectionForRuntimeRequest> requestCaptor =
          ArgumentCaptor.forClass(AcknowledgeIssuerProjectionForRuntimeRequest.class);
      verify(fixture.stub(), times(5)).withDeadlineAfter(5L, TimeUnit.SECONDS);
      verify(fixture.stub(), times(2))
          .acknowledgeIssuerProjectionForRuntime(requestCaptor.capture());
      assertThat(requestCaptor.getAllValues()).hasSize(2);
      for (AcknowledgeIssuerProjectionForRuntimeRequest request : requestCaptor.getAllValues()) {
        assertThat(request.getIssuerId()).isEqualTo(ISSUER_ID);
        assertThat(request.getCaptureOperationId())
            .isEqualTo(installation.operationId().toString());
        assertThat(request.getCaptureRequestId()).isEqualTo(installation.requestId().toString());
        assertThat(request.getCaptureRequestDigestVersion()).isEqualTo(1);
        assertThat(request.getCaptureRequestDigest()).isEqualTo(installation.requestDigest());
        assertThat(request.getProjectionKey()).isEqualTo(CAPTURE_PROJECTION_KEY);
        assertThat(request.getInstalledProjectionJson())
            .isEqualTo(installation.projectionSnapshot().json());
        assertThat(request.getAllFields()).hasSize(7);
      }
      verify(fixture.stub(), times(1)).captureIssuerProjectionForRuntime(any());
      verify(fixture.stub(), times(2)).readIssuerAuthorityForRuntime(any());
    } finally {
      fixture.store().close();
    }
  }

  @Test
  void rejectsChangedInstallationAcknowledgmentBindingsDigestsBytesAndEvent() throws Exception {
    IssuerGenerationAuthorityEvent capturedEvent = event("2", "3", "3", EVENT_REQUEST_ID);
    InstallationFixture fixture =
        installationFixture(sourceSnapshot("3", "3", "2", capturedEvent.canonicalJson()));
    try {
      InstallationReceipt installation =
          fixture
              .installer()
              .install(REQUEST_ID.toString(), "original-applied-at")
              .receipt()
              .orElseThrow();
      AcknowledgeIssuerProjectionForRuntimeResponse valid =
          installationAcknowledgmentResponse(installation, NAMESPACE, ISSUER_ID, ACKNOWLEDGMENT_ID);
      AtomicReference<AcknowledgeIssuerProjectionForRuntimeResponse> response =
          new AtomicReference<>(valid);
      when(fixture.stub().acknowledgeIssuerProjectionForRuntime(any()))
          .thenAnswer(ignored -> response.get());

      IssuerGenerationAuthorityEvent changedEvent =
          event("2", "3", "3", UUID.fromString("55555555-5555-4555-8555-555555555555"));
      String changedEventProjection =
          replaceProjectionEvent(
              installation.projectionSnapshot().json(), capturedEvent, changedEvent);
      assertThat(
              IssuerAuthorityProjectionV1Codec.verify(changedEventProjection)
                  .latestEvent()
                  .orElseThrow()
                  .canonicalJson())
          .isEqualTo(changedEvent.canonicalJson());
      AcknowledgeIssuerProjectionForRuntimeResponse changedEventResponse =
          valid.toBuilder()
              .setInstalledProjectionJson(changedEventProjection)
              .setInstalledProjectionSha256(sha256Hex(changedEventProjection))
              .setRequestDigest(
                  installationAcknowledgmentDigest(installation, changedEventProjection))
              .build();

      List<AcknowledgeIssuerProjectionForRuntimeResponse> invalidResponses =
          List.of(
              AcknowledgeIssuerProjectionForRuntimeResponse.getDefaultInstance(),
              valid.toBuilder().setUnknownFields(unknownFields()).build(),
              valid.toBuilder().setSchemaVersion("other").build(),
              valid.toBuilder().setTargetNamespace("other").build(),
              valid.toBuilder().setAcknowledgmentId("not-a-uuid").build(),
              valid.toBuilder().setIssuerId("other").build(),
              valid.toBuilder().setCallerWorkloadIdentity("other").build(),
              valid.toBuilder().setProjectionKey("other").build(),
              valid.toBuilder().setCaptureOperationId(EVENT_REQUEST_ID.toString()).build(),
              valid.toBuilder().setCaptureRequestId(EVENT_REQUEST_ID.toString()).build(),
              valid.toBuilder().setCaptureRequestDigestVersion(2).build(),
              valid.toBuilder().setCaptureRequestDigest("0".repeat(64)).build(),
              valid.toBuilder().setRequestDigestVersion(2).build(),
              valid.toBuilder().setRequestDigest("0".repeat(64)).build(),
              valid.toBuilder()
                  .setInstalledProjectionJson(
                      installation
                          .projectionSnapshot()
                          .json()
                          .replace("original-applied-at", "changed-applied-at"))
                  .build(),
              valid.toBuilder().setInstalledProjectionSha256("0".repeat(64)).build(),
              changedEventResponse);

      for (AcknowledgeIssuerProjectionForRuntimeResponse invalid : invalidResponses) {
        response.set(invalid);
        assertThatThrownBy(() -> fixture.client().acknowledgeInstallation(installation))
            .isInstanceOf(IllegalStateException.class);
      }
      verify(fixture.stub(), times(invalidResponses.size()))
          .acknowledgeIssuerProjectionForRuntime(any());
    } finally {
      fixture.store().close();
    }
  }

  @Test
  void rejectsInstallationReceiptForDifferentConfiguredIssuerOrNamespaceBeforeRpc()
      throws Exception {
    InstallationFixture fixture = installationFixture(sourceSnapshot("1", "1", "0", null));
    try {
      InstallationReceipt installation =
          fixture
              .installer()
              .install(REQUEST_ID.toString(), "original-applied-at")
              .receipt()
              .orElseThrow();
      AccountIssuerAuthorityClient wrongIssuer =
          newClient(fixture.stub(), NAMESPACE, "https://other.example.test/issuer");
      AccountIssuerAuthorityClient wrongNamespace = newClient(fixture.stub(), "other", ISSUER_ID);

      assertThatThrownBy(() -> fixture.client().acknowledgeInstallation(null))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> wrongIssuer.acknowledgeInstallation(installation))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> wrongNamespace.acknowledgeInstallation(installation))
          .isInstanceOf(IllegalArgumentException.class);
      verify(fixture.stub(), never()).acknowledgeIssuerProjectionForRuntime(any());
    } finally {
      fixture.store().close();
    }
  }

  @Test
  void rejectsCaptureResponseBindingSnapshotEventAndUnknownFieldDeviations() throws Exception {
    IssuerAuthoritySourceSnapshot baseline = sourceSnapshot("1", "1", "0", null);
    CaptureIssuerProjectionForRuntimeResponse valid =
        captureResponse(REQUEST_ID, OPERATION_ID, baseline);
    assertCaptureRejected(null);
    assertCaptureRejected(valid.toBuilder().setSchemaVersion("other").build());
    assertCaptureRejected(valid.toBuilder().setTargetNamespace("other").build());
    assertCaptureRejected(valid.toBuilder().setRequestId(EVENT_REQUEST_ID.toString()).build());
    assertCaptureRejected(valid.toBuilder().setOperationId("not-a-uuid").build());
    assertCaptureRejected(
        valid.toBuilder().setOperationId("00000000-0000-0000-0000-000000000000").build());
    assertCaptureRejected(valid.toBuilder().setIssuerId("other").build());
    assertCaptureRejected(valid.toBuilder().setCallerWorkloadIdentity("other").build());
    assertCaptureRejected(valid.toBuilder().setProjectionKey("other").build());
    assertCaptureRejected(valid.toBuilder().setRequestDigestVersion(2).build());
    assertCaptureRejected(valid.toBuilder().setRequestDigest("0".repeat(64)).build());
    assertCaptureRejected(valid.toBuilder().clearCapturedSourceSnapshot().build());
    assertCaptureRejected(
        valid.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                    .build())
            .build());
    assertCaptureRejected(
        valid.toBuilder()
            .setCapturedSourceSnapshot(baseline.toBuilder().setIssuerId("other").build())
            .build());
    assertCaptureRejected(
        valid.toBuilder()
            .setCapturedSourceSnapshot(baseline.toBuilder().setSourceScope("issuer/other").build())
            .build());
    assertCaptureRejected(
        valid.toBuilder()
            .setCapturedSourceSnapshot(baseline.toBuilder().setOutboxStreamKey("other").build())
            .build());
    assertCaptureRejected(
        valid.toBuilder()
            .setCapturedSourceSnapshot(baseline.toBuilder().setIssuerAuthGeneration("01").build())
            .build());
    assertCaptureRejected(
        valid.toBuilder()
            .setCapturedSourceSnapshot(baseline.toBuilder().setSourceVersion("0").build())
            .build());
    assertCaptureRejected(
        valid.toBuilder()
            .setCapturedSourceSnapshot(baseline.toBuilder().setOutboxSequence("00").build())
            .build());
    assertCaptureRejected(
        valid.toBuilder()
            .setCapturedSourceSnapshot(baseline.toBuilder().setLatestEventCanonicalJson("").build())
            .build());
    assertCaptureRejected(
        valid.toBuilder()
            .setCapturedSourceSnapshot(
                baseline.toBuilder()
                    .setUnknownFields(
                        UnknownFieldSet.newBuilder()
                            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                            .build())
                    .build())
            .build());

    IssuerGenerationAuthorityEvent latest = event("1", "2", "2", EVENT_REQUEST_ID);
    assertCaptureRejected(
        valid.toBuilder().setCapturedSourceSnapshot(sourceSnapshot("2", "2", "1", null)).build());
    assertCaptureRejected(
        valid.toBuilder()
            .setCapturedSourceSnapshot(sourceSnapshot("3", "2", "1", latest.canonicalJson()))
            .build());
    assertCaptureRejected(
        valid.toBuilder()
            .setCapturedSourceSnapshot(sourceSnapshot("2", "3", "1", latest.canonicalJson()))
            .build());
    assertCaptureRejected(
        valid.toBuilder()
            .setCapturedSourceSnapshot(
                sourceSnapshot(
                    "2",
                    "2",
                    "1",
                    latest
                        .canonicalJson()
                        .replace(
                            "\"eventDigest\":\"sha256:",
                            "\"unexpected\":true,\"eventDigest\":\"sha256:")))
            .build());
  }

  @Test
  void unavailableCaptureStatusPropagatesOnceWithoutReplacementIdentityOrRetry() throws Exception {
    IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub = mockStub();
    StatusRuntimeException unavailable =
        new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Account unavailable"));
    when(stub.captureIssuerProjectionForRuntime(any())).thenThrow(unavailable);
    AccountIssuerAuthorityClient client = newClient(stub);

    assertThatThrownBy(() -> client.captureProjection(REQUEST_ID.toString())).isSameAs(unavailable);

    ArgumentCaptor<CaptureIssuerProjectionForRuntimeRequest> requestCaptor =
        ArgumentCaptor.forClass(CaptureIssuerProjectionForRuntimeRequest.class);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verify(stub).captureIssuerProjectionForRuntime(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getRequestId()).isEqualTo(REQUEST_ID.toString());
    verify(stub, never()).withDeadlineAfter(10L, TimeUnit.SECONDS);
  }

  @Test
  void rejectsMalformedRequestIdentityOrNoncanonicalHistoricalSelectorBeforeStubUse() {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    AccountIssuerAuthorityClient client = newClientWithoutStub(channelFactory);

    for (String invalid :
        List.of(
            "",
            "11111111-1111-4111-8111-11111111111",
            "11111111-1111-4111-8111-11111111111x",
            "00000000-0000-0000-0000-000000000000")) {
      assertThatThrownBy(() -> client.readCurrent(invalid))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> client.readCommittedEvent(invalid, "1"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> client.captureProjection(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (String invalid : List.of("", "0", "00", "01", "+1", "-1", "1.0", " 1")) {
      assertThatThrownBy(() -> client.readCommittedEvent(REQUEST_ID.toString(), invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(channelFactory);
  }

  @Test
  void rejectsEveryResponseEchoAndTopLevelPresenceDeviation() throws Exception {
    ReadIssuerAuthorityForRuntimeResponse valid = baselineResponse();
    assertCurrentRejected(null);
    assertCurrentRejected(valid.toBuilder().setSchemaVersion("other").build());
    assertCurrentRejected(valid.toBuilder().setTargetNamespace("other").build());
    assertCurrentRejected(
        valid.toBuilder().setRequestId("33333333-3333-4333-8333-333333333333").build());
    assertCurrentRejected(valid.toBuilder().clearSourceSnapshot().build());
    assertCurrentRejected(valid.toBuilder().setRequestedEventCanonicalJson("").build());
    assertCurrentRejected(
        valid.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                    .build())
            .build());
  }

  @Test
  void rejectsEverySourceSnapshotEchoCounterPresenceAndNestedUnknownDeviation() throws Exception {
    IssuerAuthoritySourceSnapshot valid = baselineResponse().getSourceSnapshot();
    assertCurrentRejected(snapshotOnly(valid.toBuilder().setIssuerId("other").build()));
    assertCurrentRejected(snapshotOnly(valid.toBuilder().setSourceScope("issuer/other").build()));
    assertCurrentRejected(snapshotOnly(valid.toBuilder().setOutboxStreamKey("other").build()));
    assertCurrentRejected(snapshotOnly(valid.toBuilder().setIssuerAuthGeneration("2").build()));
    assertCurrentRejected(snapshotOnly(valid.toBuilder().setSourceVersion("2").build()));
    assertCurrentRejected(snapshotOnly(valid.toBuilder().setOutboxSequence("1").build()));
    assertCurrentRejected(snapshotOnly(valid.toBuilder().setIssuerAuthGeneration("01").build()));
    assertCurrentRejected(snapshotOnly(valid.toBuilder().setIssuerAuthGeneration("+1").build()));
    assertCurrentRejected(snapshotOnly(valid.toBuilder().setSourceVersion("").build()));
    assertCurrentRejected(snapshotOnly(valid.toBuilder().setOutboxSequence("00").build()));
    assertCurrentRejected(snapshotOnly(valid.toBuilder().setOutboxSequence("-1").build()));
    assertCurrentRejected(snapshotOnly(valid.toBuilder().setLatestEventCanonicalJson("").build()));
    assertCurrentRejected(
        snapshotOnly(
            valid.toBuilder()
                .setUnknownFields(
                    UnknownFieldSet.newBuilder()
                        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                        .build())
                .build()));
  }

  @Test
  void zeroCheckpointRequiresOnlyTheExactPositiveBaselineAndNoLatestEvent() throws Exception {
    assertCurrentRejected(snapshotResponse("0", "1", "0", null));
    assertCurrentRejected(snapshotResponse("1", "0", "0", null));
    assertCurrentRejected(snapshotResponse("2", "1", "0", null));
  }

  @Test
  void positiveCheckpointRequiresLatestEventMatchingEveryCurrentField() throws Exception {
    assertCurrentRejected(snapshotResponse("2", "2", "1", null));
    IssuerGenerationAuthorityEvent good = event("1", "2", "2", EVENT_REQUEST_ID);
    assertCurrentRejected(snapshotResponse("3", "2", "1", good.canonicalJson()));
    assertCurrentRejected(snapshotResponse("2", "3", "1", good.canonicalJson()));
    assertCurrentRejected(snapshotResponse("2", "2", "2", good.canonicalJson()));

    IssuerGenerationAuthorityEvent mismatchedIssuer =
        event("1", "2", "2", EVENT_REQUEST_ID, "another-issuer");
    assertCurrentRejected(snapshotResponse("2", "2", "1", mismatchedIssuer.canonicalJson()));
    IssuerGenerationAuthorityEvent malformedIdentity =
        event("1", "2", "2", EVENT_REQUEST_ID, ISSUER_ID, "unexpected-event-id");
    assertCurrentRejected(snapshotResponse("2", "2", "1", malformedIdentity.canonicalJson()));
  }

  @Test
  void rejectsMalformedNoncanonicalOrDigestModifiedEventJson() throws Exception {
    IssuerGenerationAuthorityEvent valid = event("1", "2", "2", EVENT_REQUEST_ID);
    assertCurrentRejected(snapshotResponse("2", "2", "1", "not-json"));
    assertCurrentRejected(snapshotResponse("2", "2", "1", valid.canonicalJson() + " "));
    assertCurrentRejected(
        snapshotResponse(
            "2",
            "2",
            "1",
            valid
                .canonicalJson()
                .replace("\"issuerAuthGeneration\":\"2\"", "\"issuerAuthGeneration\":\"3\"")));
    assertCurrentRejected(
        snapshotResponse(
            "2",
            "2",
            "1",
            valid
                .canonicalJson()
                .replace(
                    "\"eventDigest\":\"sha256:", "\"unexpected\":true,\"eventDigest\":\"sha256:")));
  }

  @Test
  void rejectsHistoricalAbsenceMismatchAheadEvidenceAndNonexactCurrentEvent() throws Exception {
    IssuerGenerationAuthorityEvent latest = event("2", "3", "3", EVENT_REQUEST_ID);
    ReadIssuerAuthorityForRuntimeResponse current =
        snapshotResponse("3", "3", "2", latest.canonicalJson());
    assertHistoricalRejected(current, "1");
    assertHistoricalRejected(snapshotResponse("3", "3", "2", latest.canonicalJson(), ""), "1");
    assertHistoricalRejected(
        snapshotResponse("3", "3", "2", latest.canonicalJson(), latest.canonicalJson()), "1");
    assertHistoricalRejected(
        snapshotResponse("3", "3", "2", latest.canonicalJson(), latest.canonicalJson()), "3");
    assertHistoricalRejected(
        snapshotResponse("3", "3", "2", latest.canonicalJson(), latest.canonicalJson()),
        LARGE_COUNTER);

    IssuerGenerationAuthorityEvent aheadSequence = event("3", "3", "3", EVENT_REQUEST_ID);
    assertHistoricalRejected(
        snapshotResponse("3", "3", "2", latest.canonicalJson(), aheadSequence.canonicalJson()),
        "3");
    IssuerGenerationAuthorityEvent aheadGeneration = event("1", "4", "3", EVENT_REQUEST_ID);
    assertHistoricalRejected(
        snapshotResponse("3", "3", "2", latest.canonicalJson(), aheadGeneration.canonicalJson()),
        "1");
    IssuerGenerationAuthorityEvent aheadVersion = event("1", "3", "4", EVENT_REQUEST_ID);
    assertHistoricalRejected(
        snapshotResponse("3", "3", "2", latest.canonicalJson(), aheadVersion.canonicalJson()), "1");
    IssuerGenerationAuthorityEvent sameSequenceDifferentEvent =
        event("2", "3", "3", UUID.fromString("44444444-4444-4444-8444-444444444444"));
    assertHistoricalRejected(
        snapshotResponse(
            "3", "3", "2", latest.canonicalJson(), sameSequenceDifferentEvent.canonicalJson()),
        "2");
    IssuerGenerationAuthorityEvent wrongIssuer =
        event("1", "2", "2", EVENT_REQUEST_ID, "another-issuer");
    assertHistoricalRejected(
        snapshotResponse("3", "3", "2", latest.canonicalJson(), wrongIssuer.canonicalJson()), "1");
  }

  @Test
  void unavailableStatusPropagatesOnceWithoutRetryOrFallback() throws Exception {
    IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub = mockStub();
    StatusRuntimeException unavailable =
        new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Account unavailable"));
    when(stub.readIssuerAuthorityForRuntime(any())).thenThrow(unavailable);
    AccountIssuerAuthorityClient client = newClient(stub);

    assertThatThrownBy(() -> client.readCurrent(REQUEST_ID.toString())).isSameAs(unavailable);

    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
    verify(stub).readIssuerAuthorityForRuntime(any());
    verify(stub, never()).withDeadlineAfter(10L, TimeUnit.SECONDS);
  }

  @Test
  void constructorRequiresFileBackedMtlsNamespaceIssuerAndMiddleware() {
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    CommonGrpcClientProperties plaintext = mtlsProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new AccountIssuerAuthorityClient(
                    new ServiceEndpointsProperties(),
                    plaintext,
                    channelFactory,
                    BlockingGrpcStubCustomizer.noop(),
                    NAMESPACE,
                    ISSUER_ID))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mTLS");
    assertThatThrownBy(
            () ->
                new AccountIssuerAuthorityClient(
                    new ServiceEndpointsProperties(),
                    mtlsProperties(),
                    channelFactory,
                    BlockingGrpcStubCustomizer.noop(),
                    "Invalid_Namespace",
                    ISSUER_ID))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountIssuerAuthorityClient(
                    new ServiceEndpointsProperties(),
                    mtlsProperties(),
                    channelFactory,
                    BlockingGrpcStubCustomizer.noop(),
                    NAMESPACE,
                    "  "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountIssuerAuthorityClient(
                    new ServiceEndpointsProperties(),
                    mtlsProperties(),
                    channelFactory,
                    null,
                    NAMESPACE,
                    ISSUER_ID))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(channelFactory);
  }

  @Test
  void explicitInitUsesConfiguredAccountTargetAndFileBackedTls(@TempDir Path directory)
      throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("account.internal:6565");
    CommonGrpcClientProperties tls = mtlsProperties(directory);
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    ManagedChannel channel = mock(ManagedChannel.class);
    when(channelFactory.buildChannel(
            eq("account.internal:6565"), eq(6565), any(CommonGrpcClientProperties.class), eq(true)))
        .thenReturn(channel);
    AccountIssuerAuthorityClient client =
        new AccountIssuerAuthorityClient(
            endpoints,
            tls,
            channelFactory,
            BlockingGrpcStubCustomizer.noop(),
            NAMESPACE,
            ISSUER_ID);
    try {
      verifyNoInteractions(channelFactory);
      client.init();
      verify(channelFactory)
          .buildChannel(
              eq("account.internal:6565"),
              eq(6565),
              any(CommonGrpcClientProperties.class),
              eq(true));
    } finally {
      client.close();
    }
  }

  @Test
  void installedPeerInterceptorDeniesMissingAndWrongServerCertificateIdentity() throws Exception {
    // These mocked transport attributes exercise the installed guard, not physical mTLS proof.
    AccountIssuerAuthorityClient client = newClientWithoutStub(mock(GrpcChannelFactory.class));
    for (SSLSession session : new SSLSession[] {null, peerSession("game-design-service")}) {
      ManagedChannel channel = mock(ManagedChannel.class);
      Attributes attributes =
          session == null
              ? Attributes.EMPTY
              : Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session).build();
      when(channel.newCall(any(), any())).thenAnswer(invocation -> immediateCloseCall(attributes));
      var stub = client.buildStub(channel);

      assertThatThrownBy(
              () ->
                  stub.readIssuerAuthorityForRuntime(
                      ReadIssuerAuthorityForRuntimeRequest.newBuilder()
                          .setIssuerId(ISSUER_ID)
                          .setRequestId(REQUEST_ID.toString())
                          .build()))
          .isInstanceOf(StatusRuntimeException.class)
          .extracting(exception -> ((StatusRuntimeException) exception).getStatus().getCode())
          .isEqualTo(Status.Code.UNAUTHENTICATED);
      assertThatThrownBy(
              () ->
                  stub.captureIssuerProjectionForRuntime(
                      CaptureIssuerProjectionForRuntimeRequest.newBuilder()
                          .setIssuerId(ISSUER_ID)
                          .setRequestId(REQUEST_ID.toString())
                          .build()))
          .isInstanceOf(StatusRuntimeException.class)
          .extracting(exception -> ((StatusRuntimeException) exception).getStatus().getCode())
          .isEqualTo(Status.Code.UNAUTHENTICATED);
      assertThatThrownBy(
              () ->
                  stub.acknowledgeIssuerProjectionForRuntime(
                      AcknowledgeIssuerProjectionForRuntimeRequest.getDefaultInstance()))
          .isInstanceOf(StatusRuntimeException.class)
          .extracting(exception -> ((StatusRuntimeException) exception).getStatus().getCode())
          .isEqualTo(Status.Code.UNAUTHENTICATED);
    }
  }

  private static void assertCaptureRejected(CaptureIssuerProjectionForRuntimeResponse response)
      throws Exception {
    IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub = mockStub();
    when(stub.captureIssuerProjectionForRuntime(any())).thenReturn(response);
    AccountIssuerAuthorityClient client = newClient(stub);
    assertThatThrownBy(() -> client.captureProjection(REQUEST_ID.toString()))
        .isInstanceOf(IllegalStateException.class);
  }

  private static InstallationFixture installationFixture(IssuerAuthoritySourceSnapshot snapshot)
      throws Exception {
    IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub = mockStub();
    when(stub.captureIssuerProjectionForRuntime(any()))
        .thenReturn(captureResponse(REQUEST_ID, OPERATION_ID, snapshot));
    when(stub.readIssuerAuthorityForRuntime(any())).thenReturn(snapshotOnly(snapshot));
    AccountIssuerAuthorityClient client = newClient(stub);
    RedisFixture redis = redisFixture();
    RedisIssuerAuthorityProjectionStore store = storeWithConnection();
    Field templateField =
        RedisIssuerAuthorityProjectionStore.class.getDeclaredField("redisTemplate");
    templateField.setAccessible(true);
    templateField.set(store, redis.template());
    return new InstallationFixture(
        stub, client, store, new IssuerProjectionReconciliationInstaller(client, store));
  }

  private static RedisFixture redisFixture() throws Exception {
    byte[] script;
    try (var input =
        new ClassPathResource(IssuerAuthorityProjectionRedisContract.RESOURCE_PATH)
            .getInputStream()) {
      script = input.readAllBytes();
    }
    String sha1 =
        java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(script));
    RedisConnection connection = mock(RedisConnection.class);
    RedisStringCommands stringCommands = mock(RedisStringCommands.class);
    RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
    RedisScriptingCommands scriptingCommands = mock(RedisScriptingCommands.class);
    when(connection.stringCommands()).thenReturn(stringCommands);
    when(connection.keyCommands()).thenReturn(keyCommands);
    when(connection.scriptingCommands()).thenReturn(scriptingCommands);
    AtomicReference<byte[]> storedValue = new AtomicReference<>();
    when(stringCommands.get(any(byte[].class)))
        .thenAnswer(
            ignored -> {
              byte[] value = storedValue.get();
              return value == null ? null : value.clone();
            });
    when(keyCommands.pTtl(any(byte[].class))).thenReturn(-1L);
    when(scriptingCommands.scriptLoad(any(byte[].class))).thenReturn(sha1);
    when(scriptingCommands.evalSha(eq(sha1), eq(ReturnType.VALUE), eq(1), any(byte[][].class)))
        .thenAnswer(
            invocation -> {
              byte[][] arguments = (byte[][]) invocation.getRawArguments()[3];
              String mode = new String(arguments[1], StandardCharsets.US_ASCII);
              byte[] expected = arguments[2];
              byte[] candidate = arguments[3];
              byte[] current = storedValue.get();
              String result;
              if (mode.equals("VERIFY")) {
                result = Arrays.equals(current, expected) ? "REPLAY" : "STALE";
              } else if (mode.equals("ABSENT") && current == null) {
                storedValue.set(candidate.clone());
                result = "APPLIED";
              } else if (mode.equals("PRESENT") && Arrays.equals(current, expected)) {
                storedValue.set(candidate.clone());
                result = "APPLIED";
              } else if (Arrays.equals(current, candidate)) {
                result = "REPLAY";
              } else {
                result = "STALE";
              }
              return result.getBytes(StandardCharsets.US_ASCII);
            });
    StringRedisTemplate template = mock(StringRedisTemplate.class);
    doAnswer(
            invocation -> {
              RedisCallback<?> callback = invocation.getArgument(0);
              return callback.doInRedis(connection);
            })
        .when(template)
        .execute(any(RedisCallback.class));
    return new RedisFixture(template);
  }

  private static RedisIssuerAuthorityProjectionStore storeWithConnection() {
    RedisIssuerAuthorityProjectionStore store =
        new RedisIssuerAuthorityProjectionStore(
            NAMESPACE,
            new CoordinationEndpoint("127.0.0.1", 1, "gamesession_coord_app", "test-secret"),
            new CacheRateLimitEndpoint("127.0.0.1", 2));
    store.init();
    return store;
  }

  private static AcknowledgeIssuerProjectionForRuntimeResponse installationAcknowledgmentResponse(
      InstallationReceipt installation, String namespace, String issuerId, UUID acknowledgmentId)
      throws Exception {
    String caller = "spiffe://firemud/ns/" + namespace + "/sa/game-session-service";
    String projectionKey = IssuerAuthorityProjectionRedisContract.keyForIssuer(issuerId);
    String projectionJson = installation.projectionSnapshot().json();
    String requestDigest =
        IssuerProjectionInstallationAcknowledgmentDigestV1.digest(
            issuerId,
            caller,
            projectionKey,
            installation.operationId(),
            installation.requestId(),
            1,
            installation.requestDigest(),
            projectionJson);
    return AcknowledgeIssuerProjectionForRuntimeResponse.newBuilder()
        .setSchemaVersion("account-auth-issuer-projection-installation-ack/v1")
        .setTargetNamespace(namespace)
        .setAcknowledgmentId(acknowledgmentId.toString())
        .setIssuerId(issuerId)
        .setCallerWorkloadIdentity(caller)
        .setProjectionKey(projectionKey)
        .setCaptureOperationId(installation.operationId().toString())
        .setCaptureRequestId(installation.requestId().toString())
        .setCaptureRequestDigestVersion(1)
        .setCaptureRequestDigest(installation.requestDigest())
        .setRequestDigestVersion(IssuerProjectionInstallationAcknowledgmentDigestV1.VERSION)
        .setRequestDigest(requestDigest)
        .setInstalledProjectionJson(projectionJson)
        .setInstalledProjectionSha256(sha256Hex(projectionJson))
        .build();
  }

  private static String installationAcknowledgmentDigest(
      InstallationReceipt installation, String projectionJson) {
    return IssuerProjectionInstallationAcknowledgmentDigestV1.digest(
        ISSUER_ID,
        CAPTURE_CALLER,
        CAPTURE_PROJECTION_KEY,
        installation.operationId(),
        installation.requestId(),
        1,
        installation.requestDigest(),
        projectionJson);
  }

  private static String sha256Hex(String text) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
  }

  private static String replaceProjectionEvent(
      String projectionJson,
      IssuerGenerationAuthorityEvent original,
      IssuerGenerationAuthorityEvent replacement)
      throws Exception {
    return projectionJson
        .replace(
            JSON.writeValueAsString(original.canonicalJson()),
            JSON.writeValueAsString(replacement.canonicalJson()))
        .replace(
            "\"lastAppliedSourceEventId\":\"" + original.eventId() + "\"",
            "\"lastAppliedSourceEventId\":\"" + replacement.eventId() + "\"")
        .replace(
            "\"lastAppliedSourceEventDigest\":\"" + original.eventDigest() + "\"",
            "\"lastAppliedSourceEventDigest\":\"" + replacement.eventDigest() + "\"");
  }

  private static UnknownFieldSet unknownFields() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
        .build();
  }

  private record InstallationFixture(
      IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub,
      AccountIssuerAuthorityClient client,
      RedisIssuerAuthorityProjectionStore store,
      IssuerProjectionReconciliationInstaller installer) {}

  private record RedisFixture(StringRedisTemplate template) {}

  private static void assertCurrentRejected(ReadIssuerAuthorityForRuntimeResponse response)
      throws Exception {
    IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub = mockStub();
    when(stub.readIssuerAuthorityForRuntime(any())).thenReturn(response);
    AccountIssuerAuthorityClient client = newClient(stub);
    assertThatThrownBy(() -> client.readCurrent(REQUEST_ID.toString()))
        .isInstanceOf(IllegalStateException.class);
  }

  private static void assertHistoricalRejected(
      ReadIssuerAuthorityForRuntimeResponse response, String selectedSequence) throws Exception {
    IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub = mockStub();
    when(stub.readIssuerAuthorityForRuntime(any())).thenReturn(response);
    AccountIssuerAuthorityClient client = newClient(stub);
    assertThatThrownBy(() -> client.readCommittedEvent(REQUEST_ID.toString(), selectedSequence))
        .isInstanceOf(IllegalStateException.class);
  }

  private static AccountIssuerAuthorityClient newClient(
      IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub) throws Exception {
    return newClient(stub, NAMESPACE, ISSUER_ID);
  }

  private static AccountIssuerAuthorityClient newClient(
      IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub,
      String namespace,
      String issuerId)
      throws Exception {
    AccountIssuerAuthorityClient client =
        new AccountIssuerAuthorityClient(
            new ServiceEndpointsProperties(),
            mtlsProperties(),
            mock(GrpcChannelFactory.class),
            BlockingGrpcStubCustomizer.noop(),
            namespace,
            issuerId);
    Field stubField = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    stubField.setAccessible(true);
    stubField.set(client, stub);
    return client;
  }

  private static AccountIssuerAuthorityClient newClientWithoutStub(
      GrpcChannelFactory channelFactory) {
    return new AccountIssuerAuthorityClient(
        new ServiceEndpointsProperties(),
        mtlsProperties(),
        channelFactory,
        BlockingGrpcStubCustomizer.noop(),
        NAMESPACE,
        ISSUER_ID);
  }

  private static IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub mockStub() {
    IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub stub =
        mock(IssuerAuthorityServiceGrpc.IssuerAuthorityServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    return stub;
  }

  private static ReadIssuerAuthorityForRuntimeResponse baselineResponse() {
    return snapshotResponse("1", "1", "0", null);
  }

  private static ReadIssuerAuthorityForRuntimeResponse snapshotOnly(
      IssuerAuthoritySourceSnapshot snapshot) {
    return envelope().setSourceSnapshot(snapshot).build();
  }

  private static ReadIssuerAuthorityForRuntimeResponse snapshotResponse(
      String generation, String sourceVersion, String sequence, String latestEvent) {
    return snapshotResponse(generation, sourceVersion, sequence, latestEvent, null);
  }

  private static ReadIssuerAuthorityForRuntimeResponse snapshotResponse(
      String generation,
      String sourceVersion,
      String sequence,
      String latestEvent,
      String requestedEvent) {
    ReadIssuerAuthorityForRuntimeResponse.Builder response =
        envelope()
            .setSourceSnapshot(sourceSnapshot(generation, sourceVersion, sequence, latestEvent));
    if (requestedEvent != null) {
      response.setRequestedEventCanonicalJson(requestedEvent);
    }
    return response.build();
  }

  private static IssuerAuthoritySourceSnapshot sourceSnapshot(
      String generation, String sourceVersion, String sequence, String latestEvent) {
    IssuerAuthoritySourceSnapshot.Builder snapshot =
        IssuerAuthoritySourceSnapshot.newBuilder()
            .setIssuerId(ISSUER_ID)
            .setSourceScope(SOURCE_SCOPE)
            .setOutboxStreamKey(STREAM_KEY)
            .setIssuerAuthGeneration(generation)
            .setSourceVersion(sourceVersion)
            .setOutboxSequence(sequence);
    if (latestEvent != null) {
      snapshot.setLatestEventCanonicalJson(latestEvent);
    }
    return snapshot.build();
  }

  private static IssuerAuthoritySourceSnapshot sourceSnapshot(
      String issuerId,
      String generation,
      String sourceVersion,
      String sequence,
      String latestEvent) {
    String scope = "issuer/" + issuerId;
    IssuerAuthoritySourceSnapshot.Builder snapshot =
        IssuerAuthoritySourceSnapshot.newBuilder()
            .setIssuerId(issuerId)
            .setSourceScope(scope)
            .setOutboxStreamKey(IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + scope)
            .setIssuerAuthGeneration(generation)
            .setSourceVersion(sourceVersion)
            .setOutboxSequence(sequence);
    if (latestEvent != null) {
      snapshot.setLatestEventCanonicalJson(latestEvent);
    }
    return snapshot.build();
  }

  private static CaptureIssuerProjectionForRuntimeResponse captureResponse(
      UUID requestId, UUID operationId, IssuerAuthoritySourceSnapshot sourceSnapshot) {
    return captureResponse(
        NAMESPACE,
        ISSUER_ID,
        requestId,
        operationId,
        CAPTURE_CALLER,
        CAPTURE_PROJECTION_KEY,
        1,
        IssuerProjectionReconciliationRequestDigestV1.digest(
            ISSUER_ID, CAPTURE_CALLER, CAPTURE_PROJECTION_KEY, requestId),
        sourceSnapshot);
  }

  private static CaptureIssuerProjectionForRuntimeResponse captureResponse(
      String namespace,
      String issuerId,
      UUID requestId,
      UUID operationId,
      String caller,
      String projectionKey,
      int digestVersion,
      String digest,
      IssuerAuthoritySourceSnapshot sourceSnapshot) {
    return CaptureIssuerProjectionForRuntimeResponse.newBuilder()
        .setSchemaVersion("account-auth-issuer-projection-capture/v1")
        .setTargetNamespace(namespace)
        .setOperationId(operationId.toString())
        .setRequestId(requestId.toString())
        .setIssuerId(issuerId)
        .setCallerWorkloadIdentity(caller)
        .setProjectionKey(projectionKey)
        .setRequestDigestVersion(digestVersion)
        .setRequestDigest(digest)
        .setCapturedSourceSnapshot(sourceSnapshot)
        .build();
  }

  private static ReadIssuerAuthorityForRuntimeResponse.Builder envelope() {
    return ReadIssuerAuthorityForRuntimeResponse.newBuilder()
        .setSchemaVersion("account-auth-issuer-source-readback/v1")
        .setTargetNamespace(NAMESPACE)
        .setRequestId(REQUEST_ID.toString());
  }

  private static IssuerGenerationAuthorityEvent event(
      String sequence, String generation, String sourceVersion, UUID eventRequestId) {
    return event(sequence, generation, sourceVersion, eventRequestId, ISSUER_ID);
  }

  private static IssuerGenerationAuthorityEvent event(
      String sequence,
      String generation,
      String sourceVersion,
      UUID eventRequestId,
      String issuerId) {
    return event(
        sequence,
        generation,
        sourceVersion,
        eventRequestId,
        issuerId,
        "account-issuer-authority-event-v1:" + eventRequestId);
  }

  private static IssuerGenerationAuthorityEvent event(
      String sequence,
      String generation,
      String sourceVersion,
      UUID eventRequestId,
      String issuerId,
      String eventId) {
    String scope = "issuer/" + issuerId;
    return IssuerGenerationAuthorityEventV1Codec.seal(
        Map.of(
            "schemaVersion", IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
            "eventType", IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE,
            "eventId", eventId,
            "requestId", eventRequestId.toString(),
            "issuerId", issuerId,
            "sourceScope", scope,
            "outboxStreamKey", IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + scope,
            "outboxSequence", sequence,
            "issuerAuthGeneration", generation,
            "sourceVersion", sourceVersion));
  }

  private static ClientCall<Object, Object> immediateCloseCall(Attributes attributes) {
    return new ClientCall<>() {
      private Listener<Object> listener;

      @Override
      public void start(Listener<Object> callListener, Metadata headers) {
        listener = callListener;
      }

      @Override
      public void request(int numMessages) {}

      @Override
      public void cancel(String message, Throwable cause) {}

      @Override
      public void halfClose() {
        listener.onClose(Status.OK, new Metadata());
      }

      @Override
      public void sendMessage(Object message) {}

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setMessageCompression(boolean enabled) {}

      @Override
      public Attributes getAttributes() {
        return attributes;
      }
    };
  }

  private static void assertLatestEvent(
      IssuerGenerationAuthorityEvent actual, IssuerGenerationAuthorityEvent expected) {
    assertThat(actual.canonicalJson()).isEqualTo(expected.canonicalJson());
  }

  private static SSLSession peerSession(String service) throws Exception {
    X509Certificate certificate = mock(X509Certificate.class);
    try {
      when(certificate.getSubjectAlternativeNames())
          .thenReturn(List.of(List.of(6, "spiffe://firemud/ns/" + NAMESPACE + "/sa/" + service)));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
    SSLSession session = mock(SSLSession.class);
    when(session.getPeerCertificates()).thenReturn(new Certificate[] {certificate});
    return session;
  }

  private static CommonGrpcClientProperties mtlsProperties() {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain("certs/game-session-client.crt");
    tls.setPrivateKey("certs/game-session-client.key");
    tls.setCaCert("certs/account-ca.crt");
    return tls;
  }

  private static CommonGrpcClientProperties mtlsProperties(Path directory) throws Exception {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(Files.createFile(directory.resolve("game-session-client.crt")).toString());
    tls.setPrivateKey(Files.createFile(directory.resolve("game-session-client.key")).toString());
    tls.setCaCert(Files.createFile(directory.resolve("account-ca.crt")).toString());
    return tls;
  }
}
