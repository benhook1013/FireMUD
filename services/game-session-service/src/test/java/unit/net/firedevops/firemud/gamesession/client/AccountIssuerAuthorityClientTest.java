package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Attributes;
import io.grpc.ClientCall;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.account.v1.CaptureIssuerProjectionForRuntimeRequest;
import net.firedevops.firemud.account.v1.CaptureIssuerProjectionForRuntimeResponse;
import net.firedevops.firemud.account.v1.IssuerAuthorityServiceGrpc;
import net.firedevops.firemud.account.v1.IssuerAuthoritySourceSnapshot;
import net.firedevops.firemud.account.v1.ReadIssuerAuthorityForRuntimeRequest;
import net.firedevops.firemud.account.v1.ReadIssuerAuthorityForRuntimeResponse;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import net.firedevops.firemud.common.account.authority.IssuerProjectionReconciliationRequestDigestV1;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

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
