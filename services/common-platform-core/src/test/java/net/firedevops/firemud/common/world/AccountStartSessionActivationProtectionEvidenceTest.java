package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionGrpcCodec;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionInput;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionRequest;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Structural integrity tests; fixtures establish no authenticated owner or live source lock. */
class AccountStartSessionActivationProtectionEvidenceTest {
  private static final UUID GAME_INSTANCE_ID = uuid("12121212-1212-4212-8212-121212121212");
  private static final UUID PLAYABLE_STATE_NAMESPACE_ID =
      uuid("23232323-2323-4232-8232-232323232323");
  private static final UUID GAME_SESSION_MUTATION = uuid("45454545-4545-4454-8454-454545454545");
  private static final UUID GAME_SESSION_ATTEMPT = uuid("56565656-5656-4565-8565-565656565656");
  private static final UUID ACCOUNT_PARTICIPATION = uuid("67676767-6767-4676-8676-676767676767");
  private static final UUID ACCOUNT_PROTECTION = uuid("78787878-7878-4878-8878-787878787878");
  private static final UUID ACTIVATION_ID = uuid("89898989-8989-4898-8898-898989898989");
  private static final Instant ORIGINAL_LEASE_EXPIRY = Instant.parse("2026-10-09T10:20:30.456789Z");
  private static final Instant ACQUIRED_AT = Instant.parse("2026-10-09T10:11:12.123Z");
  private static final UUID GS_READ_ID = uuid("90909090-9090-4090-8090-909090909090");
  private static final UUID CURRENT_ATTEMPT_READ_ID = uuid("f6f6f6f6-f6f6-46f6-86f6-f6f6f6f6f6f6");
  private static final UUID WORLD_READ_ID = uuid("a1a1a1a1-a1a1-41a1-81a1-a1a1a1a1a1a1");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void acquisitionCodecRetainsTheCompleteFirstReceiptAcrossReadCorrelationRetries()
      throws Exception {
    Fixture fixture = fixture();
    var tuple = originalTuple(fixture);
    var first =
        request(
            fixture,
            tuple,
            GS_READ_ID,
            WORLD_READ_ID,
            ACTIVATION_ID,
            0L,
            CurrentGameInstanceStatus.STARTING);
    var firstEvidence =
        evidence(
            first,
            captureReference(tuple),
            List.of(source(fixture.selector.canonicalTenantId(), "source")));
    var input =
        new AccountStartSessionActivationProtectionAcquisitionInput(
            tuple.canonicalBytes(),
            GAME_SESSION_MUTATION,
            GAME_SESSION_ATTEMPT,
            first.gameSessionOwnerFence(),
            first.worldActivationRequest());
    String namespace = fixture.selector.targetNamespace();
    var wire =
        AccountStartSessionActivationProtectionAcquisitionGrpcCodec.toRequest(input, namespace);
    var decoded =
        AccountStartSessionActivationProtectionAcquisitionGrpcCodec.fromRequestWireBytes(
            wire.toByteArray(), namespace);
    assertThat(decoded.originalPostAuthorizationTuple()).containsExactly(tuple.canonicalBytes());
    assertThat(decoded.worldActivationRequest().preparingEvidenceBytes())
        .containsExactly(first.worldActivationRequest().preparingEvidenceBytes());

    var retry =
        request(
            fixture,
            tuple,
            UUID.randomUUID(),
            UUID.randomUUID(),
            ACTIVATION_ID,
            0L,
            CurrentGameInstanceStatus.STARTING);
    var retryInput =
        new AccountStartSessionActivationProtectionAcquisitionInput(
            tuple.canonicalBytes(),
            GAME_SESSION_MUTATION,
            GAME_SESSION_ATTEMPT,
            first.gameSessionOwnerFence(),
            retry.worldActivationRequest());
    var response =
        AccountStartSessionActivationProtectionAcquisitionGrpcCodec.toResponse(
            retryInput, namespace, firstEvidence);
    var returned =
        AccountStartSessionActivationProtectionAcquisitionGrpcCodec.fromResponseWireBytes(
            retryInput, namespace, response.toByteArray());
    assertThat(returned.canonicalBytes()).containsExactly(firstEvidence.canonicalBytes());
    assertThat(returned.request().worldActivationRequest().preparingEvidenceBytes())
        .containsExactly(first.worldActivationRequest().preparingEvidenceBytes())
        .isNotEqualTo(retry.worldActivationRequest().preparingEvidenceBytes());
  }

  @Test
  void acquisitionCodecRejectsAChangedActivationOrOwnerAttempt() throws Exception {
    Fixture fixture = fixture();
    var tuple = originalTuple(fixture);
    var first =
        request(
            fixture,
            tuple,
            GS_READ_ID,
            WORLD_READ_ID,
            ACTIVATION_ID,
            0L,
            CurrentGameInstanceStatus.STARTING);
    var firstEvidence =
        evidence(
            first,
            captureReference(tuple),
            List.of(source(fixture.selector.canonicalTenantId(), "source")));
    String namespace = fixture.selector.targetNamespace();
    var differentActivation =
        new AccountStartSessionActivationProtectionAcquisitionInput(
            tuple.canonicalBytes(),
            GAME_SESSION_MUTATION,
            GAME_SESSION_ATTEMPT,
            first.gameSessionOwnerFence(),
            new WorldCanonicalInstanceActivation.Request(
                UUID.randomUUID(), first.worldActivationRequest().preparing()));
    var differentAttempt =
        new AccountStartSessionActivationProtectionAcquisitionInput(
            tuple.canonicalBytes(),
            GAME_SESSION_MUTATION,
            UUID.randomUUID(),
            first.gameSessionOwnerFence(),
            first.worldActivationRequest());
    for (var changed : List.of(differentActivation, differentAttempt)) {
      assertThatThrownBy(
              () ->
                  AccountStartSessionActivationProtectionAcquisitionGrpcCodec.toResponse(
                      changed, namespace, firstEvidence))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("exact original activation acquisition binding");
    }
  }

  @Test
  void roundTripsFullOriginalLaunchPreparingAndAccountSourceEvidence() throws Exception {
    Fixture fixture = fixture();
    StartSessionPostAuthorizationExecutionTuple tuple = originalTuple(fixture);
    AccountStartSessionActivationProtectionRequest request =
        request(
            fixture,
            tuple,
            GS_READ_ID,
            WORLD_READ_ID,
            ACTIVATION_ID,
            7L,
            CurrentGameInstanceStatus.STARTING);
    byte[] capture = captureReference(tuple);
    SourceEvidence source = source(fixture.selector.canonicalTenantId(), "complete-source-before");
    AccountStartSessionActivationProtectionEvidence evidence =
        evidence(request, capture, List.of(source));

    AccountStartSessionActivationProtectionRequest decodedRequest =
        AccountStartSessionActivationProtectionRequest.decode(request.canonicalBytes());
    AccountStartSessionActivationProtectionEvidence decodedEvidence =
        AccountStartSessionActivationProtectionEvidence.decode(evidence.canonicalBytes());

    assertThat(decodedRequest.canonicalBytes()).containsExactly(request.canonicalBytes());
    assertThat(request.requestDigest())
        .isEqualTo("sha256:" + sha256(request.operationIdentityBytes()));
    assertThat(decodedRequest.gameSessionLaunchReadEvidenceBytes())
        .containsExactly(request.gameSessionLaunchReadEvidenceBytes());
    assertThat(decodedRequest.launchAssociation().request().readRequestId()).isEqualTo(GS_READ_ID);
    assertThat(request.originalAttemptResponseBytes())
        .containsExactly(
            OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(
                    request.originalCurrentAttempt())
                .toByteArray());
    assertThat(
            OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(
                    request.originalCurrentAttempt())
                .getRequest()
                .getReadRequestId())
        .isEqualTo(CURRENT_ATTEMPT_READ_ID.toString());
    byte[] escapedCurrentAttemptBytes = request.originalAttemptResponseBytes();
    escapedCurrentAttemptBytes[0] ^= 1;
    assertThat(request.originalAttemptResponseBytes())
        .containsExactly(
            OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(
                    request.originalCurrentAttempt())
                .toByteArray());
    assertThatThrownBy(decodedRequest::originalAttemptResponseBytes)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("do not reconstruct");
    assertThat(decodedRequest.worldActivationRequest().preparingEvidenceBytes())
        .containsExactly(request.worldActivationRequest().preparingEvidenceBytes());
    assertThat(decodedRequest.originalPostAuthorizationTupleBytes())
        .containsExactly(tuple.canonicalBytes());
    assertThat(decodedEvidence.canonicalBytes()).containsExactly(evidence.canonicalBytes());
    assertThat(decodedEvidence.request()).isEqualTo(request);
    assertThat(decodedEvidence.accountProtectionId()).isEqualTo(ACCOUNT_PROTECTION);
    assertThat(decodedEvidence.accountProtectionFence()).isEqualTo(Long.MAX_VALUE);
    assertThat(decodedEvidence.acquiredAt()).isEqualTo(ACQUIRED_AT);
    assertThat(decodedEvidence.originalSourceCaptureReferenceBytes()).containsExactly(capture);
    assertThat(decodedEvidence.originalSourceCaptureReferenceSha256()).isEqualTo(sha256(capture));
    assertThat(decodedEvidence.sourceEvidenceVector())
        .extracting(SourceEvidence::key)
        .containsExactly("ACCOUNT:" + fixture.selector.canonicalTenantId());

    byte[] escapedRequestBytes = request.canonicalBytes();
    escapedRequestBytes[0] ^= 1;
    byte[] escapedCaptureBytes = evidence.originalSourceCaptureReferenceBytes();
    escapedCaptureBytes[0] ^= 1;
    assertThat(request.canonicalBytes()).containsExactly(decodedRequest.canonicalBytes());
    assertThat(evidence.originalSourceCaptureReferenceBytes()).containsExactly(capture);
  }

  @Test
  void normalizesOwnerReadCorrelationIdsInRetryIdentity() throws Exception {
    Fixture fixture = fixture();
    StartSessionPostAuthorizationExecutionTuple tuple = originalTuple(fixture);
    AccountStartSessionActivationProtectionRequest original =
        request(
            fixture,
            tuple,
            GS_READ_ID,
            WORLD_READ_ID,
            ACTIVATION_ID,
            7L,
            CurrentGameInstanceStatus.STARTING);
    AccountStartSessionActivationProtectionRequest retry =
        request(
            fixture,
            tuple,
            uuid("b2b2b2b2-b2b2-42b2-82b2-b2b2b2b2b2b2"),
            uuid("c3c3c3c3-c3c3-43c3-83c3-c3c3c3c3c3c3"),
            ACTIVATION_ID,
            7L,
            CurrentGameInstanceStatus.STARTING);

    assertThat(retry.operationIdentityBytes()).containsExactly(original.operationIdentityBytes());
    assertThat(retry.requestDigest()).isEqualTo(original.requestDigest());
    assertThat(retry.canonicalBytes()).isNotEqualTo(original.canonicalBytes());
    assertThat(retry).isNotEqualTo(original);

    byte[] capture = captureReference(tuple);
    var originalEvidence =
        evidence(
            original, capture, List.of(source(fixture.selector.canonicalTenantId(), "source")));
    var retryEvidence =
        evidence(retry, capture, List.of(source(fixture.selector.canonicalTenantId(), "source")));
    assertThat(retryEvidence.operationIdentityBytes())
        .containsExactly(originalEvidence.operationIdentityBytes());
    assertThat(retryEvidence.requestDigest()).isEqualTo(originalEvidence.requestDigest());
    assertThat(retryEvidence.canonicalBytes()).isNotEqualTo(originalEvidence.canonicalBytes());

    var changedCurrentRead =
        AccountStartSessionActivationProtectionRequest.create(
            ACCOUNT_PARTICIPATION,
            Long.MAX_VALUE,
            original.launchAssociation(),
            currentAttempt(
                original,
                uuid("e5e5e5e5-e5e5-45e5-85e5-e5e5e5e5e5e5"),
                original.accountRedemptionProjectionBytes(),
                ORIGINAL_LEASE_EXPIRY,
                GAME_SESSION_MUTATION,
                GAME_SESSION_ATTEMPT,
                Long.MAX_VALUE),
            original.worldActivationRequest());
    assertThat(changedCurrentRead.operationIdentityBytes())
        .containsExactly(original.operationIdentityBytes());
    assertThat(changedCurrentRead.canonicalBytes()).containsExactly(original.canonicalBytes());
    assertThat(changedCurrentRead.originalAttemptResponseBytes())
        .isNotEqualTo(original.originalAttemptResponseBytes());
  }

  @Test
  void substantiveTupleRowActivationAndSourceChangesChangeStableIdentity() throws Exception {
    Fixture fixture = fixture();
    StartSessionPostAuthorizationExecutionTuple tuple = originalTuple(fixture);
    var original =
        request(
            fixture,
            tuple,
            GS_READ_ID,
            WORLD_READ_ID,
            ACTIVATION_ID,
            7L,
            CurrentGameInstanceStatus.STARTING);
    var changedRow =
        request(
            fixture,
            tuple,
            GS_READ_ID,
            WORLD_READ_ID,
            ACTIVATION_ID,
            8L,
            CurrentGameInstanceStatus.STARTING);
    var changedActivation =
        request(
            fixture,
            tuple,
            GS_READ_ID,
            WORLD_READ_ID,
            uuid("d4d4d4d4-d4d4-44d4-84d4-d4d4d4d4d4d4"),
            7L,
            CurrentGameInstanceStatus.STARTING);
    var changedTuple =
        request(
            fixture,
            originalTuple(fixture, "a different original audit reason"),
            GS_READ_ID,
            WORLD_READ_ID,
            ACTIVATION_ID,
            7L,
            CurrentGameInstanceStatus.STARTING);
    Fixture changedDescriptorFixture = fixture("another-launch-descriptor");
    var changedDescriptor =
        request(
            changedDescriptorFixture,
            originalTuple(changedDescriptorFixture),
            GS_READ_ID,
            WORLD_READ_ID,
            ACTIVATION_ID,
            7L,
            CurrentGameInstanceStatus.STARTING);

    assertThat(changedRow.operationIdentityBytes()).isNotEqualTo(original.operationIdentityBytes());
    assertThat(changedActivation.operationIdentityBytes())
        .isNotEqualTo(original.operationIdentityBytes());
    assertThat(changedTuple.operationIdentityBytes())
        .isNotEqualTo(original.operationIdentityBytes());
    assertThat(changedDescriptor.operationIdentityBytes())
        .isNotEqualTo(original.operationIdentityBytes());

    byte[] capture = captureReference(tuple);
    var originalEvidence =
        evidence(
            original,
            capture,
            List.of(source(fixture.selector.canonicalTenantId(), "source-before")));
    var changedSourceEvidence =
        evidence(
            original,
            capture,
            List.of(source(fixture.selector.canonicalTenantId(), "source-after")));
    assertThat(changedSourceEvidence.operationIdentityBytes())
        .isNotEqualTo(originalEvidence.operationIdentityBytes());
  }

  @Test
  void rejectsSubstitutedProjectionLeaseOwnerStateCaptureAndCanonicalBytes() throws Exception {
    Fixture fixture = fixture();
    StartSessionPostAuthorizationExecutionTuple tuple = originalTuple(fixture);
    byte[] projection = StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple);
    AccountStartSessionActivationProtectionRequest valid =
        request(
            fixture,
            tuple,
            GS_READ_ID,
            WORLD_READ_ID,
            ACTIVATION_ID,
            7L,
            CurrentGameInstanceStatus.STARTING);
    byte[] substitutedProjection = projection.clone();
    substitutedProjection[substitutedProjection.length - 2] ^= 1;
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionRequest.create(
                    ACCOUNT_PARTICIPATION,
                    Long.MAX_VALUE,
                    valid.launchAssociation(),
                    currentAttempt(
                        valid,
                        substitutedProjection,
                        ORIGINAL_LEASE_EXPIRY,
                        GAME_SESSION_MUTATION,
                        GAME_SESSION_ATTEMPT,
                        Long.MAX_VALUE),
                    valid.worldActivationRequest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("projection differs");

    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionRequest.create(
                    ACCOUNT_PARTICIPATION,
                    Long.MAX_VALUE,
                    valid.launchAssociation(),
                    currentAttempt(
                        valid,
                        projection,
                        ORIGINAL_LEASE_EXPIRY.plusNanos(1),
                        GAME_SESSION_MUTATION,
                        GAME_SESSION_ATTEMPT,
                        Long.MAX_VALUE),
                    valid.worldActivationRequest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("microsecond");

    assertThatThrownBy(
            () ->
                request(
                    fixture,
                    tuple,
                    GS_READ_ID,
                    WORLD_READ_ID,
                    ACTIVATION_ID,
                    7L,
                    CurrentGameInstanceStatus.RUNNING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Original Account operation differs from its current owner or launch target");
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionRequest.create(
                    new UUID(0L, 0L),
                    Long.MAX_VALUE,
                    valid.launchAssociation(),
                    valid.originalCurrentAttempt(),
                    valid.worldActivationRequest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-nil UUID");

    var wrongAttemptAssociation =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Result(
            valid.launchAssociation().request(),
            valid.launchAssociation().playableStateNamespaceId(),
            valid.launchAssociation().playableStateScope(),
            valid.launchAssociation().publicProduction(),
            valid.launchAssociation().currentGameInstanceStatus(),
            valid.launchAssociation().currentRowVersion(),
            uuid("e5e5e5e5-e5e5-45e5-85e5-e5e5e5e5e5e5"),
            valid.launchAssociation().ownerFence(),
            valid.launchAssociation().canonicalRealmId(),
            valid.launchAssociation().catalogRevision(),
            valid.launchAssociation().launchBindingEvidence());
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionRequest.create(
                    ACCOUNT_PARTICIPATION,
                    Long.MAX_VALUE,
                    wrongAttemptAssociation,
                    valid.originalCurrentAttempt(),
                    valid.worldActivationRequest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tuple differs");

    var wrongOwnerFenceAssociation =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Result(
            valid.launchAssociation().request(),
            valid.launchAssociation().playableStateNamespaceId(),
            valid.launchAssociation().playableStateScope(),
            valid.launchAssociation().publicProduction(),
            valid.launchAssociation().currentGameInstanceStatus(),
            valid.launchAssociation().currentRowVersion(),
            valid.launchAssociation().ownerAttemptId(),
            valid.launchAssociation().ownerFence() - 1L,
            valid.launchAssociation().canonicalRealmId(),
            valid.launchAssociation().catalogRevision(),
            valid.launchAssociation().launchBindingEvidence());
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionRequest.create(
                    ACCOUNT_PARTICIPATION,
                    Long.MAX_VALUE,
                    wrongOwnerFenceAssociation,
                    valid.originalCurrentAttempt(),
                    valid.worldActivationRequest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tuple differs");

    Map<String, Object> extra = readObject(valid.canonicalBytes());
    extra.put("unsupportedAuthority", "must not be retained");
    assertThatThrownBy(
            () -> AccountStartSessionActivationProtectionRequest.decode(canonical(extra)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    byte[] noncanonical = Arrays.copyOf(valid.canonicalBytes(), valid.canonicalBytes().length + 1);
    noncanonical[noncanonical.length - 1] = (byte) ' ';
    assertThatThrownBy(() -> AccountStartSessionActivationProtectionRequest.decode(noncanonical))
        .isInstanceOf(IllegalArgumentException.class);

    var malformedEvidence =
        evidence(
            valid,
            captureReference(tuple),
            List.of(source(fixture.selector.canonicalTenantId(), "source")));
    Map<String, Object> extraEvidenceField = readObject(malformedEvidence.canonicalBytes());
    extraEvidenceField.put("unsupported", "field");
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionEvidence.decode(
                    canonical(extraEvidenceField)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                request(
                    fixture,
                    originalTuple(fixture, "another-world-runtime", "namespace substitution"),
                    GS_READ_ID,
                    WORLD_READ_ID,
                    ACTIVATION_ID,
                    7L,
                    CurrentGameInstanceStatus.STARTING))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsTheActualFreshStartingLaunchAtRowVersionZero() throws Exception {
    Fixture fixture = fixture();
    var request =
        request(
            fixture,
            originalTuple(fixture),
            GS_READ_ID,
            WORLD_READ_ID,
            ACTIVATION_ID,
            0L,
            CurrentGameInstanceStatus.STARTING);
    assertThat(request.launchAssociation().currentRowVersion()).isZero();
    assertThat(
            AccountStartSessionActivationProtectionRequest.decode(request.canonicalBytes())
                .canonicalBytes())
        .containsExactly(request.canonicalBytes());
  }

  @Test
  void rejectsChangedCaptureAndAcquisitionAfterOriginalLease() throws Exception {
    Fixture fixture = fixture();
    StartSessionPostAuthorizationExecutionTuple tuple = originalTuple(fixture);
    var request =
        request(
            fixture,
            tuple,
            GS_READ_ID,
            WORLD_READ_ID,
            ACTIVATION_ID,
            7L,
            CurrentGameInstanceStatus.STARTING);
    byte[] capture = captureReference(tuple);
    SourceEvidence source = source(fixture.selector.canonicalTenantId(), "source");
    assertThatThrownBy(() -> evidence(request, capture, List.of(source), ORIGINAL_LEASE_EXPIRY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("before the original lease expires");
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionEvidence.create(
                    request,
                    ACCOUNT_PROTECTION,
                    1L,
                    ACQUIRED_AT,
                    capture,
                    "0".repeat(64),
                    List.of(source)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digest differs");
    Map<String, Object> malformed = readObject(capture);
    malformed.put("controlPlaneRequestId", "another-original-request");
    byte[] wrongCapture = canonical(malformed);
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionEvidence.create(
                    request,
                    ACCOUNT_PROTECTION,
                    1L,
                    ACQUIRED_AT,
                    wrongCapture,
                    sha256(wrongCapture),
                    List.of(source)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("another original request");
  }

  @Test
  void retainedLaunchOperationEchoIsExactAndCannotOmitOwnerBinding() throws Exception {
    var fixture = fixture();
    var tuple = originalTuple(fixture);
    var first =
        request(
            fixture,
            tuple,
            GS_READ_ID,
            WORLD_READ_ID,
            ACTIVATION_ID,
            7L,
            CurrentGameInstanceStatus.STARTING);
    var old = first.launchAssociation();
    var selector = old.request();
    var operation =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.OriginalOperation(
            tuple.canonicalBytes(), GAME_SESSION_MUTATION, GAME_SESSION_ATTEMPT, Long.MAX_VALUE);
    var boundSelector =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            selector.readRequestId(),
            selector.targetNamespace(),
            selector.canonicalTenantId(),
            selector.worldSlug(),
            selector.gameInstanceUuid(),
            selector.controlPlaneRequestId(),
            selector.launchDescriptorId(),
            selector.expectedDescriptorRequestDigest(),
            selector.expectedDescriptorResultDigest(),
            selector.expectedReleaseAttestationEvidenceDigest(),
            operation);
    var bound =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Result(
            boundSelector,
            old.playableStateNamespaceId(),
            old.playableStateScope(),
            old.publicProduction(),
            old.currentGameInstanceStatus(),
            old.currentRowVersion(),
            old.ownerAttemptId(),
            old.ownerFence(),
            old.canonicalRealmId(),
            old.catalogRevision(),
            old.launchBindingEvidence());
    var valid =
        AccountStartSessionActivationProtectionRequest.create(
            ACCOUNT_PARTICIPATION,
            Long.MAX_VALUE,
            bound,
            first.originalCurrentAttempt(),
            first.worldActivationRequest());
    var decoded = AccountStartSessionActivationProtectionRequest.decode(valid.canonicalBytes());
    assertThat(decoded.launchAssociation().request().originalOperation())
        .isEqualTo(valid.launchAssociation().request().originalOperation());
    var omittedOperationSelector =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            selector.readRequestId(),
            selector.targetNamespace(),
            selector.canonicalTenantId(),
            selector.worldSlug(),
            selector.gameInstanceUuid(),
            selector.controlPlaneRequestId(),
            selector.launchDescriptorId(),
            selector.expectedDescriptorRequestDigest(),
            selector.expectedDescriptorResultDigest(),
            selector.expectedReleaseAttestationEvidenceDigest());
    var omittedOperation =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Result(
            omittedOperationSelector,
            old.playableStateNamespaceId(),
            old.playableStateScope(),
            old.publicProduction(),
            old.currentGameInstanceStatus(),
            old.currentRowVersion(),
            old.ownerAttemptId(),
            old.ownerFence(),
            old.canonicalRealmId(),
            old.catalogRevision(),
            old.launchBindingEvidence());
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionRequest.create(
                    ACCOUNT_PARTICIPATION,
                    Long.MAX_VALUE,
                    omittedOperation,
                    first.originalCurrentAttempt(),
                    first.worldActivationRequest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete original operation");
    var wire =
        net.firedevops.firemud.common.gamesession
            .CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toResponse(
            valid.launchAssociation());
    var tree = readObject(valid.canonicalBytes());
    tree.put(
        "gameSessionLaunchReadEvidenceBytesBase64",
        java.util.Base64.getEncoder()
            .encodeToString(wire.toBuilder().clearExpectedOwnerMutationId().build().toByteArray()));
    byte[] incomplete = canonical(tree);
    assertThatThrownBy(() -> AccountStartSessionActivationProtectionRequest.decode(incomplete))
        .isInstanceOf(IllegalArgumentException.class);
    tree.put(
        "gameSessionLaunchReadEvidenceBytesBase64",
        java.util.Base64.getEncoder()
            .encodeToString(
                wire.toBuilder()
                    .setExpectedOwnerMutationId(UUID.randomUUID().toString())
                    .build()
                    .toByteArray()));
    byte[] substituted = canonical(tree);
    assertThatThrownBy(() -> AccountStartSessionActivationProtectionRequest.decode(substituted))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static AccountStartSessionActivationProtectionEvidence evidence(
      AccountStartSessionActivationProtectionRequest request,
      byte[] capture,
      List<SourceEvidence> sources) {
    return evidence(request, capture, sources, ACQUIRED_AT);
  }

  private static AccountStartSessionActivationProtectionEvidence evidence(
      AccountStartSessionActivationProtectionRequest request,
      byte[] capture,
      List<SourceEvidence> sources,
      Instant acquiredAt) {
    return AccountStartSessionActivationProtectionEvidence.create(
        request, ACCOUNT_PROTECTION, Long.MAX_VALUE, acquiredAt, capture, sha256(capture), sources);
  }

  private static AccountStartSessionActivationProtectionRequest request(
      Fixture fixture,
      StartSessionPostAuthorizationExecutionTuple tuple,
      UUID gsReadId,
      UUID worldReadId,
      UUID activationId,
      long gameSessionRowVersion,
      CurrentGameInstanceStatus status) {
    var gsRequest =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            gsReadId,
            fixture.selector.targetNamespace(),
            fixture.selector.canonicalTenantId(),
            fixture.selector.worldSlug(),
            fixture.selector.gameInstanceUuid(),
            fixture.selector.controlPlaneRequestId(),
            fixture.selector.launchDescriptorId(),
            fixture.selector.expectedDescriptorRequestDigest(),
            fixture.selector.expectedDescriptorResultDigest(),
            fixture.selector.expectedReleaseAttestationEvidenceDigest(),
            new CanonicalGameInstanceLaunchAssociationReadEvidence.OriginalOperation(
                tuple.canonicalBytes(),
                GAME_SESSION_MUTATION,
                GAME_SESSION_ATTEMPT,
                Long.MAX_VALUE));
    var gsResult =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Result(
            gsRequest,
            fixture.launch.playableStateNamespaceId(),
            fixture.launch.playableStateScope(),
            fixture.launch.publicProduction(),
            status,
            gameSessionRowVersion,
            GAME_SESSION_ATTEMPT,
            Long.MAX_VALUE,
            fixture.launch.canonicalRealmId(),
            fixture.launch.catalogRevision(),
            fixture.binding);
    var currentAttemptRequest =
        new OriginalStartSessionCurrentAttemptEvidence.Request(
            CURRENT_ATTEMPT_READ_ID,
            fixture.selector.targetNamespace(),
            tuple.canonicalBytes(),
            GAME_SESSION_ATTEMPT,
            GAME_SESSION_MUTATION,
            Long.MAX_VALUE);
    var currentAttempt =
        new OriginalStartSessionCurrentAttemptEvidence.Result(
            currentAttemptRequest,
            ORIGINAL_LEASE_EXPIRY,
            StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple));

    var oldRequest = fixture.preparing.request();
    var worldRequest =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            oldRequest.schemaVersion(),
            worldReadId,
            oldRequest.targetNamespace(),
            oldRequest.canonicalTenantId(),
            oldRequest.worldSlug(),
            oldRequest.canonicalGameInstanceId(),
            oldRequest.playableStateNamespaceId(),
            oldRequest.playableStateScope(),
            oldRequest.publicProduction(),
            oldRequest.controlPlaneRequestId(),
            oldRequest.canonicalVersionId(),
            oldRequest.expectedDescriptorRequestDigest(),
            oldRequest.expectedDescriptorResultDigest(),
            oldRequest.expectedReleaseAttestationDigest());
    var preparing =
        new WorldCanonicalInstanceLifecycleEvidence(
            worldRequest,
            fixture.preparing.launchBinding(),
            fixture.preparing.startLocation(),
            fixture.preparing.runtimeRoomInstanceId(),
            fixture.preparing.lifecycleStatus(),
            fixture.preparing.lifecycleEpoch(),
            fixture.preparing.rowVersion(),
            fixture.preparing.captureId(),
            fixture.preparing.graphSha256(),
            fixture.preparing.preparationInputDigest(),
            fixture.preparing.operationalRegionAssignments());
    var activation = new WorldCanonicalInstanceActivation.Request(activationId, preparing);

    return AccountStartSessionActivationProtectionRequest.create(
        ACCOUNT_PARTICIPATION, Long.MAX_VALUE, gsResult, currentAttempt, activation);
  }

  private static OriginalStartSessionCurrentAttemptEvidence.Result currentAttempt(
      AccountStartSessionActivationProtectionRequest request,
      byte[] projection,
      Instant leaseExpiry,
      UUID mutationId,
      UUID attemptId,
      long fence) {
    var original = request.originalCurrentAttempt();
    return currentAttempt(
        request,
        original.request().readRequestId(),
        projection,
        leaseExpiry,
        mutationId,
        attemptId,
        fence);
  }

  private static OriginalStartSessionCurrentAttemptEvidence.Result currentAttempt(
      AccountStartSessionActivationProtectionRequest request,
      UUID readRequestId,
      byte[] projection,
      Instant leaseExpiry,
      UUID mutationId,
      UUID attemptId,
      long fence) {
    var selector = request.originalCurrentAttempt().request();
    return new OriginalStartSessionCurrentAttemptEvidence.Result(
        new OriginalStartSessionCurrentAttemptEvidence.Request(
            readRequestId,
            selector.targetNamespace(),
            selector.canonicalPostAuthorizationTuple(),
            attemptId,
            mutationId,
            fence),
        leaseExpiry,
        projection);
  }

  private static Fixture fixture() throws Exception {
    return fixture("activation-test-launch-descriptor");
  }

  private static Fixture fixture(String launchDescriptorId) throws Exception {
    WorldPublishedStartLocationEvidence selected =
        WorldPublishedStartLocationGrpcCodecTest.evidence();
    var selectedRequest = selected.request();
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            selectedRequest.targetNamespace(),
            "activation-original-request",
            selectedRequest.canonicalTenantId(),
            "activation-test-world",
            uuid("abababab-abab-4bab-8bab-abababababab"),
            "sha256:" + "1".repeat(64),
            91L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
            launchDescriptorId,
            42L,
            false,
            null,
            "{}",
            "generation-revision",
            9L,
            7L,
            "release-bundle",
            false,
            null);
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        selectedRequest.appliedCommitId(),
                        selectedRequest.contentDigest(),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner, AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "2".repeat(64) : null))
            .toList();
    AuthoredWorldReleaseAttestationEvidence release =
        AuthoredWorldReleaseAttestationEvidence.create(
            descriptor.targetNamespace(),
            descriptor.resultDigest(),
            descriptor.canonicalTenantId(),
            selectedRequest.canonicalVersionId(),
            descriptor.worldSlug(),
            descriptor.authoredWorldSourceOperationId(),
            descriptor.authoredWorldSourceEvidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            selectedRequest.publishWorkflowId(),
            selectedRequest.appliedCommitId(),
            participants,
            "sha256:" + "3".repeat(64),
            1,
            List.of(),
            List.of(),
            List.of(),
            descriptor.generationConfigRevision(),
            selected);
    CompleteLaunchBindingEvidence binding = new CompleteLaunchBindingEvidence(descriptor, release);
    WorldDraftStartLocationEvidence selectorReceipt =
        WorldDraftStartLocationEvidence.fromStored(selected.selectorReceiptBytes());
    UUID activationReadId = WORLD_READ_ID;
    var worldRequest =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
            activationReadId,
            descriptor.targetNamespace(),
            descriptor.canonicalTenantId(),
            descriptor.worldSlug(),
            GAME_INSTANCE_ID,
            PLAYABLE_STATE_NAMESPACE_ID,
            "SHARED",
            true,
            descriptor.controlPlaneRequestId(),
            release.canonicalVersionId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest());
    WorldCanonicalInstanceLifecycleEvidence preparing =
        new WorldCanonicalInstanceLifecycleEvidence(
            worldRequest,
            binding,
            selectorReceipt.startLocation(),
            1042L,
            "PREPARING",
            3L,
            0L,
            uuid("bcbcbcbc-bcbc-4bcb-8bcb-bcbcbcbcbcbc"),
            selectorReceipt.graphDigest().substring("sha256:".length()),
            "sha256:" + "4".repeat(64),
            Map.of(
                uuid("11111111-1111-4111-8111-111111111111"),
                uuid("22222222-2222-4222-8222-222222222222")));
    var gsRequest =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            GS_READ_ID,
            descriptor.targetNamespace(),
            descriptor.canonicalTenantId(),
            descriptor.worldSlug(),
            GAME_INSTANCE_ID,
            descriptor.controlPlaneRequestId(),
            descriptor.launchDescriptorId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest());
    var gsResult =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Result(
            gsRequest,
            PLAYABLE_STATE_NAMESPACE_ID,
            RealmEntryPolicy.StateScope.SHARED,
            true,
            CurrentGameInstanceStatus.STARTING,
            7L,
            GAME_SESSION_ATTEMPT,
            Long.MAX_VALUE,
            // Explicit fixture-owned realm/catalog values, not authenticated GS owner output.
            uuid("e8e8e8e8-e8e8-48e8-88e8-e8e8e8e8e8e8"),
            17L,
            binding);
    return new Fixture(gsRequest, gsResult, binding, preparing);
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple(Fixture fixture) {
    return originalTuple(fixture, "original StartSession activation attempt");
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple(
      Fixture fixture, String auditReason) {
    return originalTuple(fixture, fixture.selector.targetNamespace(), auditReason);
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple(
      Fixture fixture, String namespace, String auditReason) {
    var request = fixture.selector;
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "activation-original-request",
            uuid("a4f5f4eb-8243-4d42-903a-33495456a622"),
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(request.canonicalTenantId(), namespace),
                new StartSessionOperatorAction.Target(
                    fixture.binding.descriptor().gameTemplateId(),
                    uuid("dededede-dede-4ede-8ede-dededededede")),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                auditReason));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        preTuple,
        "spiffe://firemud/ns/" + namespace + "/sa/logging-admin-service",
        "arfp/v1/test-key/" + "5".repeat(64),
        uuid("efefefef-efef-4fef-8fef-efefefefefef"),
        19L,
        authorityBundle(preTuple),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple) {
    String tenant = tuple.action().scope().tenantId().toString();
    Map<String, Object> projection =
        Map.of(
            "sourceType", "ACCOUNT",
            "sourceEvidenceId", "sha256:" + "a".repeat(64),
            "sourceEvidenceVersion", "17",
            "projectionStatus", "CURRENT",
            "evaluatedAt", "2026-10-09T00:00:00Z",
            "expiresAt", "2026-10-09T00:05:00Z");
    Map<String, Object> operation =
        Map.of(
            "issuanceOperationId", "f5d044bd-7e5f-4e2d-9859-9025cbdcc60f",
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    tuple.controlPlaneRequestId()),
            "mutationDigest", tuple.mutationDigest());
    Map<String, Object> authority =
        Map.of(
            "issuerAuthGeneration", 1L,
            "accountAuthorityGeneration", 2L,
            "tenantAuthorityGeneration", Map.of(tenant, 3L),
            "membershipAuthorityGeneration", Map.of(tenant, 4L),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> issuanceEvidence =
        Map.of(
            "evidenceType", StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId", "a4f5f4eb-8243-4d42-903a-33495456a622",
            "controlUiTokenJti", "a681bba7-c215-4cf1-a35b-14348912cbdc",
            "role", "tenantAdmin",
            "accountGeneration", "2",
            "tenantGeneration", "3");
    Map<String, Object> bundle =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope",
                Map.of(
                    "tenantId",
                    tenant,
                    "targetNamespace",
                    tuple.action().scope().targetNamespace()),
                "actionFamily",
                tuple.actionFamily(),
                "applicableAccountId",
                "a4f5f4eb-8243-4d42-903a-33495456a622",
                "applicableTenantId",
                tenant),
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            operation,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authority,
            "membershipVersion",
            Map.of(tenant, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            issuanceEvidence);
    return canonical(bundle);
  }

  private static SourceEvidence source(UUID tenant, String sourceValue) {
    return new SourceEvidence(
        SourceKind.ACCOUNT,
        tenant.toString(),
        "2",
        "17",
        null,
        null,
        sourceValue.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] canonical(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static byte[] captureReference(StartSessionPostAuthorizationExecutionTuple tuple) {
    return canonical(
        Map.of(
            "schema", "account-start-session-authority-capture/v1",
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "capturedAt", "2026-10-09T10:11:12.123Z",
            "bundleReference",
                Map.of(
                    "bundleVersion", StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                    "sourceVersion", tuple.bundleReference().sourceVersion(),
                    "sourceFence", tuple.bundleReference().sourceFence(),
                    "linearization", tuple.bundleReference().linearization()),
            "snapshotSha256", "6".repeat(64)));
  }

  private static Map<String, Object> readObject(byte[] bytes) throws IOException {
    return JSON.readValue(bytes, new TypeReference<>() {});
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record Fixture(
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request selector,
      CanonicalGameInstanceLaunchAssociationReadEvidence.Result launch,
      CompleteLaunchBindingEvidence binding,
      WorldCanonicalInstanceLifecycleEvidence preparing) {}
}
