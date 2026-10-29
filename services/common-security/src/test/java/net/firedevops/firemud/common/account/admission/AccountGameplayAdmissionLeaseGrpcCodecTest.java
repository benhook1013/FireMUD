package net.firedevops.firemud.common.account.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AbortGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.AccountGameplayAdmissionLeaseResponse;
import net.firedevops.firemud.account.v1.AccountGameplayAdmissionLeaseState;
import net.firedevops.firemud.account.v1.AcquireGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.AbortResolutionEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.AcquireRequest;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.OriginalAckReceiptEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.OriginalNewCredential;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.State;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.OriginKind;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.OwnerProofOutcome;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.gamesession.v1.CanonicalPlayableTargetEvidence;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Shape and tuple checks only; these carriers grant no Account or gameplay authority. */
class AccountGameplayAdmissionLeaseGrpcCodecTest {
  private static final String HIGH = "9007199254740993";
  private static final String JWT = "header.payload.signature";
  private static final UUID REQUEST_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID OTHER = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID DECISION_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final UUID CLEANUP_ID = UUID.fromString("88888888-8888-4888-8888-888888888888");
  private static final UUID VERSION_ID = UUID.fromString("99999999-9999-4999-8999-999999999999");
  private static final UUID HOLD_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID HOLD_FENCE = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void roundTripsExactAcquireLeaseBytesAndCompleteAckWithoutClaimingAuthority() {
    var lease = lease();
    var request = request(lease, candidate(lease), target(lease));
    var receipt =
        OriginalAckReceiptEvidence.fromFields(
            1,
            REQUEST_ID,
            ACCOUNT_ID,
            TENANT_ID,
            lease.leaseFence().longValueExact(),
            lease.sha256(),
            DECISION_ID,
            1_015_000L,
            "101",
            1_014_999L,
            "102");

    var response =
        AccountGameplayAdmissionLeaseGrpcCodec.toAcquireResponse(
            request, lease, State.COMMITTED, DECISION_ID, null, receipt);
    var decoded = AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireResponse(request, response);

    assertThat(response.getOriginalLeaseEvidenceUtf8(0).toByteArray())
        .containsExactly(lease.canonicalJson().getBytes(StandardCharsets.UTF_8));
    assertThat(decoded.originalLeaseEvidence()).isEqualTo(lease);
    assertThat(decoded.state()).isEqualTo(State.COMMITTED);
    assertThat(decoded.originalAckReceipt().requestId()).isEqualTo(REQUEST_ID);
    assertThat(decoded.originalAckReceipt().evidenceSha256()).isEqualTo(lease.sha256());
    assertThat(request.bindingGeneration()).isEqualTo(new BigInteger(HIGH));
    assertThat(request.toString()).doesNotContain(JWT);
    assertThat(response.toString()).doesNotContain(JWT);
    assertThat(decoded.toString()).doesNotContain(JWT);
    assertThat(candidate(lease).regionEpoch()).isEqualTo(new BigInteger(HIGH));
    assertThat(target(lease).catalogRevision()).isEqualTo(Long.parseLong(HIGH));
  }

  @Test
  void correlatesEveryLeaseScopeFieldAndIssuerGenerationToAcquireRequest() {
    var lease = lease();
    var wire = wireRequest(lease, candidate(lease), target(lease));
    var candidate = wire.getExpectedCandidate(0);
    var target = wire.getExpectedTarget(0);
    String next = new BigInteger(HIGH).add(BigInteger.ONE).toString();
    var response = pendingResponse(lease);

    List<AcquireGameplayAdmissionLeaseRequest> mismatches =
        List.of(
            wire.toBuilder().setRequestUuid(0, OTHER.toString()).build(),
            wire.toBuilder()
                .setExpectedCandidate(0, candidate.toBuilder().setAccountUuid(0, OTHER.toString()))
                .build(),
            wire.toBuilder()
                .setExpectedCandidate(0, candidate.toBuilder().setTenantUuid(0, OTHER.toString()))
                .setExpectedTarget(
                    0,
                    withResealedHoldDigest(
                        target.toBuilder().setCanonicalTenantUuid(0, OTHER.toString())))
                .build(),
            wire.toBuilder()
                .setExpectedTarget(
                    0, withResealedHoldDigest(target.toBuilder().setRealmUuid(0, OTHER.toString())))
                .build(),
            wire.toBuilder()
                .setExpectedTarget(
                    0, withResealedHoldDigest(target.toBuilder().setWorldSlug(0, "another-world")))
                .build(),
            wire.toBuilder()
                .setExpectedTarget(0, target.toBuilder().setRealmSlug(0, "another-realm"))
                .build(),
            wire.toBuilder()
                .setExpectedCandidate(
                    0, candidate.toBuilder().setPlayableStateNamespaceUuid(0, OTHER.toString()))
                .setExpectedTarget(
                    0,
                    withResealedHoldDigest(
                        target.toBuilder().setPlayableStateNamespaceUuid(0, OTHER.toString())))
                .build(),
            wire.toBuilder()
                .setExpectedCandidate(
                    0, candidate.toBuilder().setGameInstanceUuid(0, OTHER.toString()))
                .setExpectedTarget(
                    0,
                    withResealedHoldDigest(
                        target.toBuilder().setCanonicalGameInstanceUuid(0, OTHER.toString())))
                .build(),
            wire.toBuilder()
                .setExpectedCandidate(
                    0, candidate.toBuilder().setCharacterUuid(0, OTHER.toString()))
                .build(),
            wire.toBuilder()
                .setExpectedCandidate(0, candidate.toBuilder().setSessionUuid(0, OTHER.toString()))
                .build(),
            wire.toBuilder().setBindingGeneration(0, next).build(),
            wire.toBuilder()
                .setExpectedTarget(
                    0, withResealedHoldDigest(target.toBuilder().setCatalogRevision(0, next)))
                .build(),
            wire.toBuilder()
                .setExpectedTarget(0, target.toBuilder().setPointerVersion(0, next))
                .build(),
            wire.toBuilder()
                .setExpectedCandidate(0, candidate.toBuilder().setRegionUuid(0, OTHER.toString()))
                .build(),
            wire.toBuilder()
                .setExpectedCandidate(0, candidate.toBuilder().setRegionEpoch(0, next))
                .build(),
            wire.toBuilder()
                .setExpectedCandidate(0, candidate.toBuilder().setIssuerAuthGeneration(0, next))
                .build());

    for (AcquireGameplayAdmissionLeaseRequest mismatch : mismatches) {
      var parsedMismatch = AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireRequest(mismatch);
      assertThat(parsedMismatch).isNotNull();
      assertRejected(parsedMismatch, response);
    }

    // The existing World request only accepts SHARED, so vary the valid lease-side scope here.
    var validRequest = AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireRequest(wire);
    var isolatedLeaseCarrier = AccountGameplayAdmissionLeaseEvidenceTest.fixture();
    mutableObject(isolatedLeaseCarrier, "bindingScope").put("playableStateScope", "ISOLATED");
    mutableObject(isolatedLeaseCarrier, "tokenIdentityEvidence").put("tokenSHA256", jwtSha256(JWT));
    var isolatedLease = AccountGameplayAdmissionLeaseEvidence.fromCarrier(isolatedLeaseCarrier);
    assertRejected(validRequest, pendingResponse(isolatedLease));
  }

  @Test
  void refusesLegacyUuidIssuerEvenWhenIssuerGenerationMatches() {
    var lease = lease();
    var wire = wireRequest(lease, candidate(lease), target(lease));
    var canonicalCandidate = wire.getExpectedCandidate(0);
    var legacyIssuerCandidate =
        canonicalCandidate.toBuilder()
            .setIssuerId(0, "77777777-7777-4777-8777-777777777777")
            .build();
    assertThat(legacyIssuerCandidate.getIssuerAuthGeneration(0))
        .isEqualTo(canonicalCandidate.getIssuerAuthGeneration(0));

    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireRequest(
                    wire.toBuilder().setExpectedCandidate(0, legacyIssuerCandidate).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported issuer identity")
        .hasMessageContaining("legacy UUID issuer identities require explicit recovery");
  }

  @Test
  void rejectsAcquireResponseWhenTokenDigestDiffersFromPresentedCredential() {
    var lease = lease();
    var request = request(lease, candidate(lease), target(lease));
    var changedCarrier = AccountGameplayAdmissionLeaseEvidenceTest.fixture();
    mutableObject(changedCarrier, "tokenIdentityEvidence").put("tokenSHA256", "f".repeat(64));
    var changedLease = AccountGameplayAdmissionLeaseEvidence.fromCarrier(changedCarrier);

    assertRejected(request, pendingResponse(changedLease));
  }

  @Test
  void originalNewCredentialCarrierKeepsHandleOpaqueAndRejectsUnboundClaims() throws Exception {
    var lease = lease();
    byte[] leaseBytes = lease.canonicalJson().getBytes(StandardCharsets.UTF_8);
    var request =
        AccountGameplayAdmissionLeaseGrpcCodec.parseIssueOriginalNewCredentialRequest(
            AccountGameplayAdmissionLeaseGrpcCodec.toIssueOriginalNewCredentialRequest(
                JWT, leaseBytes));
    Map<?, ?> carrier = lease.carrier();
    Map<?, ?> scope = object(carrier, "bindingScope");
    UUID requestId = UUID.fromString((String) carrier.get("requestId"));
    UUID accountId = UUID.fromString((String) scope.get("accountId"));
    UUID leaseId = UUID.fromString((String) carrier.get("leaseId"));
    long leaseFence = lease.leaseFence().longValueExact();
    UUID lineageId = UUID.randomUUID();
    UUID tokenJti = UUID.randomUUID();
    while (tokenJti.equals(lineageId)
        || tokenJti
            .toString()
            .equals(String.valueOf(object(carrier, "tokenIdentityEvidence").get("tokenJti")))) {
      tokenJti = UUID.randomUUID();
    }
    long issuedAt = Instant.now().getEpochSecond();
    Map<String, Object> claims = boundClaims(lease, tokenJti, issuedAt);
    String boundJwt = compactJwt(claims);
    byte[] handle = new byte[32];
    java.util.Arrays.fill(handle, (byte) 0x2a);
    String checkpointsSha256 = checkpointsSha256(carrier.get("outboxCheckpoints"));
    OriginalNewCredential credential =
        AccountGameplayAdmissionLeaseGrpcCodec.originalNewCredential(
            boundJwt,
            handle,
            lineageId,
            tokenJti,
            jwtSha256(boundJwt),
            Long.parseLong((String) carrier.get("issuanceFence")),
            leaseId,
            leaseFence,
            "102",
            checkpointsSha256);
    var ack =
        OriginalAckReceiptEvidence.fromFields(
            1,
            requestId,
            accountId,
            leaseId,
            leaseFence,
            lease.sha256(),
            DECISION_ID,
            1_015_000L,
            "101",
            1_014_999L,
            "102");

    var response =
        AccountGameplayAdmissionLeaseGrpcCodec.toIssueOriginalNewCredentialResponse(
            request, lease, State.COMMITTED, DECISION_ID, ack, credential);
    var resolutionWire =
        AccountGameplayAdmissionLeaseGrpcCodec.toResolvedAbortResponse(
                abortRequest(lease, DECISION_ID), lease, CLEANUP_ID, abortResolution(lease))
            .getAbortResolution(0);
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.parseIssueOriginalNewCredentialResponse(
                    request, response.toBuilder().addAbortResolution(resolutionWire).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertAbortRejected(
        abortRequest(lease, DECISION_ID),
        response.toBuilder()
            .clearOriginalNewCredential()
            .addAbortResolution(resolutionWire)
            .build());
    OriginalNewCredential parsed =
        AccountGameplayAdmissionLeaseGrpcCodec.parseIssueOriginalNewCredentialResponse(
            request, response);
    byte[] firstHandleRead = parsed.opaqueRebindHandle();
    String handleHex = HexFormat.of().formatHex(handle);
    assertThat(parsed.lineageId()).isEqualTo(lineageId);
    assertThat(parsed.signedBoundGsACompactJwt()).isEqualTo(boundJwt);
    assertThat(firstHandleRead).containsExactly(handle);
    assertThat(parsed.toString()).doesNotContain(boundJwt, handleHex);
    assertThat(request.toString()).doesNotContain(JWT, handleHex);
    java.util.Arrays.fill(firstHandleRead, (byte) 0);
    byte[] secondHandleRead = parsed.opaqueRebindHandle();
    assertThat(secondHandleRead).containsExactly(handle);
    java.util.Arrays.fill(secondHandleRead, (byte) 0);
    parsed.close();
    assertThatThrownBy(parsed::opaqueRebindHandle)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Credential result is closed");

    for (String changedClaim :
        java.util.List.of("missingTenantId", "tenantId", "sub", "accountId", "tokenGeneration")) {
      Map<String, Object> changedClaims = new LinkedHashMap<>(claims);
      if (changedClaim.equals("missingTenantId")) {
        changedClaims.remove("tenantId");
      } else {
        changedClaims.put(
            changedClaim,
            changedClaim.equals("tokenGeneration") ? "2" : UUID.randomUUID().toString());
      }
      String changedJwt = compactJwt(changedClaims);
      try (OriginalNewCredential changedCredential =
          AccountGameplayAdmissionLeaseGrpcCodec.originalNewCredential(
              changedJwt,
              handle,
              lineageId,
              tokenJti,
              jwtSha256(changedJwt),
              Long.parseLong((String) carrier.get("issuanceFence")),
              leaseId,
              leaseFence,
              "102",
              checkpointsSha256)) {
        assertThatThrownBy(
                () ->
                    AccountGameplayAdmissionLeaseGrpcCodec.toIssueOriginalNewCredentialResponse(
                        request, lease, State.COMMITTED, DECISION_ID, ack, changedCredential))
            .as("independently rejects changed %s", changedClaim)
            .isInstanceOf(IllegalArgumentException.class);
      }
    }
    credential.close();
    java.util.Arrays.fill(handle, (byte) 0);
    java.util.Arrays.fill(leaseBytes, (byte) 0);
  }

  @Test
  void rejectsMissingDuplicateAndUnknownAcquireAndResponseFields() {
    var lease = lease();
    var wireRequest = wireRequest(lease, candidate(lease), target(lease));
    var response = pendingResponse(lease);
    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();

    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireRequest(
                    wireRequest.toBuilder().clearExpectedCandidate().build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireRequest(
                    wireRequest.toBuilder().addSchemaVersion(1).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireRequest(
                    wireRequest.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireRequest(
                    wireRequest.toBuilder()
                        .setExpectedCandidate(
                            0,
                            wireRequest.getExpectedCandidate(0).toBuilder()
                                .addAccountUuid(OTHER.toString()))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireRequest(
                    wireRequest.toBuilder()
                        .setExpectedTarget(
                            0,
                            wireRequest.getExpectedTarget(0).toBuilder().setUnknownFields(unknown))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.toAcquireRequest(
                    REQUEST_ID,
                    JWT,
                    withGameInstance(candidate(lease), OTHER),
                    target(lease),
                    new BigInteger(HIGH)))
        .isInstanceOf(IllegalArgumentException.class);

    var parsedRequest = AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireRequest(wireRequest);
    assertRejected(parsedRequest, response.toBuilder().clearOriginalLeaseEvidenceUtf8().build());
    assertRejected(
        parsedRequest,
        response.toBuilder()
            .addState(
                AccountGameplayAdmissionLeaseState.ACCOUNT_GAMEPLAY_ADMISSION_LEASE_STATE_PENDING)
            .build());
    assertRejected(parsedRequest, response.toBuilder().setUnknownFields(unknown).build());
    assertRejected(
        parsedRequest,
        response.toBuilder()
            .addOriginalLeaseEvidenceUtf8(
                ByteString.copyFrom(lease.canonicalJson().getBytes(StandardCharsets.UTF_8)))
            .build());
  }

  @Test
  void rejectsCommittedWithoutCompleteAckAndKeepsAbortDecisionUnknownExplicit() {
    var lease = lease();
    var acquireRequest = request(lease, candidate(lease), target(lease));
    var committedWithoutAck =
        pendingResponse(lease).toBuilder()
            .setState(
                0,
                AccountGameplayAdmissionLeaseState.ACCOUNT_GAMEPLAY_ADMISSION_LEASE_STATE_COMMITTED)
            .addBindingDecisionUuid(DECISION_ID.toString())
            .build();
    assertRejected(acquireRequest, committedWithoutAck);

    byte[] originalBytes = lease.canonicalJson().getBytes(StandardCharsets.UTF_8);
    AbortGameplayAdmissionLeaseRequest abortWire =
        AccountGameplayAdmissionLeaseGrpcCodec.toAbortRequest(JWT, originalBytes, null);
    var abortRequest = AccountGameplayAdmissionLeaseGrpcCodec.parseAbortRequest(abortWire);
    assertThat(abortRequest.bindingDecisionId()).isNull();

    var aborted =
        AccountGameplayAdmissionLeaseGrpcCodec.toAbortResponse(
            abortRequest, lease, State.ABORTED, null, CLEANUP_ID, null);
    var decodedAbort =
        AccountGameplayAdmissionLeaseGrpcCodec.parseAbortResponse(abortRequest, aborted);
    assertThat(decodedAbort.state()).isEqualTo(State.ABORTED);
    assertThat(decodedAbort.bindingDecisionId()).isNull();
    assertThat(decodedAbort.orphanCleanupId()).isEqualTo(CLEANUP_ID);
    assertThat(decodedAbort.originalAckReceipt()).isNull();
    assertThat(aborted.getBindingDecisionUuidCount()).isZero();
    assertThat(aborted.getOriginalLeaseEvidenceUtf8(0).toByteArray())
        .containsExactly(originalBytes);
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.parseAbortRequest(
                    abortWire.toBuilder()
                        .addBindingDecisionUuid(DECISION_ID.toString())
                        .addBindingDecisionUuid(OTHER.toString())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resolvedAbortPreservesUnknownTerminalAndMatchesOnlyItsExactTransition() {
    var lease = lease();
    byte[] originalBytes = lease.canonicalJson().getBytes(StandardCharsets.UTF_8);
    var request = abortRequest(lease, DECISION_ID);
    var resolution = abortResolution(lease);
    var response =
        AccountGameplayAdmissionLeaseGrpcCodec.toResolvedAbortResponse(
            request, lease, CLEANUP_ID, resolution);
    var parsed = AccountGameplayAdmissionLeaseGrpcCodec.parseAbortResponse(request, response);
    assertThat(parsed.state()).isEqualTo(State.ABORTED);
    assertThat(parsed.bindingDecisionId()).isNull();
    assertThat(parsed.originalAckReceipt()).isNull();
    assertThat(parsed.orphanCleanupId()).isEqualTo(CLEANUP_ID);
    assertThat(parsed.abortResolution()).isEqualTo(resolution);
    assertThat(parsed.matchesAbortTransition(DECISION_ID)).isTrue();
    assertThat(parsed.matchesAbortTransition(OTHER)).isFalse();
    assertThat(parsed.matchesAbortTransition(null)).isFalse();
    assertThat(response.getBindingDecisionUuidCount()).isZero();
    assertThat(response.getOriginalAckReceiptCount()).isZero();
    assertThat(response.getOriginalLeaseEvidenceUtf8(0).toByteArray())
        .containsExactly(originalBytes);
    assertThat(parsed.toString()).doesNotContain(DECISION_ID.toString(), lease.sha256());
    assertThat(resolution.toString())
        .doesNotContain(
            resolution.resolutionId().toString(), DECISION_ID.toString(), lease.sha256());

    var unresolved = response.toBuilder().clearAbortResolution().build();
    var unknown = AccountGameplayAdmissionLeaseGrpcCodec.parseAbortResponse(request, unresolved);
    assertThat(unknown.abortResolution()).isNull();
    assertThat(unknown.bindingDecisionId()).isNull();
    assertThat(unknown.matchesAbortTransition(DECISION_ID)).isFalse();

    var knownResponse =
        AccountGameplayAdmissionLeaseGrpcCodec.toAbortResponse(
            request, lease, State.ABORTED, DECISION_ID, CLEANUP_ID, null);
    var known = AccountGameplayAdmissionLeaseGrpcCodec.parseAbortResponse(request, knownResponse);
    assertThat(known.abortResolution()).isNull();
    assertThat(known.matchesAbortTransition(DECISION_ID)).isTrue();
    assertThat(known.matchesAbortTransition(OTHER)).isFalse();
    var pending =
        AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireResponse(
            request(lease, candidate(lease), target(lease)), pendingResponse(lease));
    assertThat(pending.matchesAbortTransition(DECISION_ID)).isFalse();
  }

  @Test
  void rejectsAbortResolutionWithDifferentLeaseCleanupOrSelectedDecision() {
    var lease = lease();
    var request = abortRequest(lease, DECISION_ID);
    var response =
        AccountGameplayAdmissionLeaseGrpcCodec.toResolvedAbortResponse(
            request, lease, CLEANUP_ID, abortResolution(lease));
    var resolution = response.getAbortResolution(0);
    for (var changed :
        List.of(
            resolution.toBuilder().setOriginalLeaseUuid(0, OTHER.toString()).build(),
            resolution.toBuilder()
                .setOriginalLeaseFence(0, lease.leaseFence().add(BigInteger.ONE).toString())
                .build(),
            resolution.toBuilder().setOriginalLeaseSha256(0, "0".repeat(64)).build(),
            resolution.toBuilder().setOrphanCleanupUuid(0, OTHER.toString()).build(),
            resolution.toBuilder().setBindingDecisionUuid(0, OTHER.toString()).build())) {
      assertAbortRejected(request, response.toBuilder().setAbortResolution(0, changed).build());
    }
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.toResolvedAbortResponse(
                    request, lease, OTHER, abortResolution(lease)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMissingDuplicateUnknownAndNoncanonicalAbortResolutionFields() {
    var lease = lease();
    var request = abortRequest(lease, DECISION_ID);
    var response =
        AccountGameplayAdmissionLeaseGrpcCodec.toResolvedAbortResponse(
            request, lease, CLEANUP_ID, abortResolution(lease));
    var resolution = response.getAbortResolution(0);
    assertAbortRejected(request, response.toBuilder().addAbortResolution(resolution).build());
    for (var field : resolution.getDescriptorForType().getFields()) {
      var missing = resolution.toBuilder().clearField(field).build();
      var duplicate =
          resolution.toBuilder()
              .addRepeatedField(field, resolution.getRepeatedField(field, 0))
              .build();
      assertAbortRejected(request, response.toBuilder().setAbortResolution(0, missing).build());
      assertAbortRejected(request, response.toBuilder().setAbortResolution(0, duplicate).build());
    }
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    assertAbortRejected(
        request,
        response.toBuilder()
            .setAbortResolution(0, resolution.toBuilder().setUnknownFields(unknown))
            .build());
    for (var changed :
        List.of(
            resolution.toBuilder().setSchemaVersion(0, 2).build(),
            resolution.toBuilder().setOriginalLeaseFence(0, "01").build(),
            resolution.toBuilder().setOriginalLeaseFence(0, "0").build(),
            resolution.toBuilder().setOriginalLeaseFence(0, "+1").build(),
            resolution.toBuilder().setOriginalLeaseSha256(0, "A".repeat(64)).build(),
            resolution.toBuilder().setOriginalLeaseSha256(0, "0".repeat(63)).build())) {
      assertAbortRejected(request, response.toBuilder().setAbortResolution(0, changed).build());
    }
    for (var fieldName :
        List.of(
            "resolution_uuid",
            "original_lease_uuid",
            "orphan_cleanup_uuid",
            "binding_decision_uuid")) {
      var field = resolution.getDescriptorForType().findFieldByName(fieldName);
      for (var invalidUuid :
          List.of(
              "00000000-0000-0000-0000-000000000000",
              "44444444-4444-1444-8444-444444444444",
              "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")) {
        var changed = resolution.toBuilder().setRepeatedField(field, 0, invalidUuid).build();
        assertAbortRejected(request, response.toBuilder().setAbortResolution(0, changed).build());
      }
    }
    assertThatThrownBy(
            () ->
                new AbortResolutionEvidence(
                    OTHER, OTHER, BigInteger.ZERO, lease.sha256(), CLEANUP_ID, DECISION_ID))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AbortResolutionEvidence(
                    new UUID(0L, 0L),
                    OTHER,
                    BigInteger.ONE,
                    lease.sha256(),
                    CLEANUP_ID,
                    DECISION_ID))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAbortResolutionOnUnselectedAbortOtherPurposesAndKnownTerminal() {
    var lease = lease();
    byte[] leaseBytes = lease.canonicalJson().getBytes(StandardCharsets.UTF_8);
    var request = abortRequest(lease, DECISION_ID);
    var response =
        AccountGameplayAdmissionLeaseGrpcCodec.toResolvedAbortResponse(
            request, lease, CLEANUP_ID, abortResolution(lease));
    assertAbortRejected(abortRequest(lease, null), response);
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.toResolvedAbortResponse(
                    abortRequest(lease, null), lease, CLEANUP_ID, abortResolution(lease)))
        .isInstanceOf(IllegalArgumentException.class);
    var read =
        AccountGameplayAdmissionLeaseGrpcCodec.parseReadRequest(
            AccountGameplayAdmissionLeaseGrpcCodec.toReadRequest(JWT, leaseBytes));
    var finalize =
        AccountGameplayAdmissionLeaseGrpcCodec.parseFinalizeRequest(
            AccountGameplayAdmissionLeaseGrpcCodec.toFinalizeRequest(JWT, leaseBytes, DECISION_ID));
    assertThatThrownBy(
            () -> AccountGameplayAdmissionLeaseGrpcCodec.parseReadResponse(read, response))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> AccountGameplayAdmissionLeaseGrpcCodec.parseFinalizeResponse(finalize, response))
        .isInstanceOf(IllegalArgumentException.class);
    assertRejected(request(lease, candidate(lease), target(lease)), response);
    assertAbortRejected(
        request, response.toBuilder().addBindingDecisionUuid(DECISION_ID.toString()).build());
    assertAbortRejected(
        request,
        pendingResponse(lease).toBuilder()
            .addAbortResolution(response.getAbortResolution(0))
            .build());
    assertAbortRejected(
        request,
        response.toBuilder()
            .addOriginalNewCredential(
                net.firedevops.firemud.account.v1.OriginalNewGameplayCredentialResult
                    .getDefaultInstance())
            .build());
    var credentialRequest =
        AccountGameplayAdmissionLeaseGrpcCodec.parseIssueOriginalNewCredentialRequest(
            AccountGameplayAdmissionLeaseGrpcCodec.toIssueOriginalNewCredentialRequest(
                JWT, leaseBytes));
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.parseIssueOriginalNewCredentialResponse(
                    credentialRequest,
                    response.toBuilder()
                        .addOriginalNewCredential(
                            net.firedevops.firemud.account.v1.OriginalNewGameplayCredentialResult
                                .getDefaultInstance())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static AccountGameplayAdmissionLeaseGrpcCodec.OriginalLeaseRequest abortRequest(
      AccountGameplayAdmissionLeaseEvidence lease, UUID decisionId) {
    return AccountGameplayAdmissionLeaseGrpcCodec.parseAbortRequest(
        AccountGameplayAdmissionLeaseGrpcCodec.toAbortRequest(
            JWT, lease.canonicalJson().getBytes(StandardCharsets.UTF_8), decisionId));
  }

  private static AbortResolutionEvidence abortResolution(
      AccountGameplayAdmissionLeaseEvidence lease) {
    return new AbortResolutionEvidence(
        OTHER,
        UUID.fromString((String) lease.carrier().get("leaseId")),
        lease.leaseFence(),
        lease.sha256(),
        CLEANUP_ID,
        DECISION_ID);
  }

  private static void assertAbortRejected(
      AccountGameplayAdmissionLeaseGrpcCodec.OriginalLeaseRequest request,
      AccountGameplayAdmissionLeaseResponse response) {
    assertThatThrownBy(
            () -> AccountGameplayAdmissionLeaseGrpcCodec.parseAbortResponse(request, response))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void preservesOriginalBytesAcrossFinalizeAndReadAndRejectsChangedReadback() {
    var lease = lease();
    byte[] originalBytes = lease.canonicalJson().getBytes(StandardCharsets.UTF_8);

    var finalizeRequest =
        AccountGameplayAdmissionLeaseGrpcCodec.parseFinalizeRequest(
            AccountGameplayAdmissionLeaseGrpcCodec.toFinalizeRequest(
                JWT, originalBytes, DECISION_ID));
    var finalizeResponse =
        AccountGameplayAdmissionLeaseGrpcCodec.toFinalizeResponse(
            finalizeRequest, lease, State.PENDING, null, null, null);
    assertThat(finalizeResponse.getOriginalLeaseEvidenceUtf8(0).toByteArray())
        .containsExactly(originalBytes);
    assertThat(
            AccountGameplayAdmissionLeaseGrpcCodec.parseFinalizeResponse(
                    finalizeRequest, finalizeResponse)
                .originalLeaseEvidence())
        .isEqualTo(lease);

    var readRequest =
        AccountGameplayAdmissionLeaseGrpcCodec.parseReadRequest(
            AccountGameplayAdmissionLeaseGrpcCodec.toReadRequest(JWT, originalBytes));
    var readResponse =
        AccountGameplayAdmissionLeaseGrpcCodec.toReadResponse(
            readRequest, lease, State.PENDING, null, null, null);
    assertThat(readResponse.getOriginalLeaseEvidenceUtf8(0).toByteArray())
        .containsExactly(originalBytes);
    assertThat(
            AccountGameplayAdmissionLeaseGrpcCodec.parseReadResponse(readRequest, readResponse)
                .originalLeaseEvidence())
        .isEqualTo(lease);

    var changedCarrier = AccountGameplayAdmissionLeaseEvidenceTest.fixture();
    changedCarrier.put("evaluatedAt", "1000001");
    var changedLease = AccountGameplayAdmissionLeaseEvidence.fromCarrier(changedCarrier);
    var changedReadback =
        readResponse.toBuilder()
            .setOriginalLeaseEvidenceUtf8(
                0,
                ByteString.copyFrom(changedLease.canonicalJson().getBytes(StandardCharsets.UTF_8)))
            .build();
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.parseReadResponse(
                    readRequest, changedReadback))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validatesCompleteAckFenceAndUnsigned64TransactionIds() {
    var lease = lease();
    var request = request(lease, candidate(lease), target(lease));
    String uint64Max = "18446744073709551615";
    String uint64MaxMinusOne = "18446744073709551614";
    String uint64MaxPlusOne = "18446744073709551616";
    var leaseId = UUID.fromString((String) lease.carrier().get("leaseId"));
    var receipt =
        OriginalAckReceiptEvidence.fromFields(
            1,
            REQUEST_ID,
            ACCOUNT_ID,
            leaseId,
            lease.leaseFence().longValueExact(),
            lease.sha256(),
            DECISION_ID,
            1_015_000L,
            uint64MaxMinusOne,
            1_014_999L,
            uint64Max);

    var response =
        AccountGameplayAdmissionLeaseGrpcCodec.toAcquireResponse(
            request, lease, State.COMMITTED, DECISION_ID, null, receipt);
    var parsed = AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireResponse(request, response);
    assertThat(parsed.originalAckReceipt().leaseFence())
        .isEqualTo(lease.leaseFence().longValueExact());
    assertThat(parsed.originalAckReceipt().finalizationXid()).isEqualTo(uint64MaxMinusOne);
    assertThat(parsed.originalAckReceipt().receiptXid()).isEqualTo(uint64Max);

    assertThatThrownBy(
            () ->
                OriginalAckReceiptEvidence.fromFields(
                    1,
                    REQUEST_ID,
                    ACCOUNT_ID,
                    leaseId,
                    lease.leaseFence().longValueExact(),
                    lease.sha256(),
                    DECISION_ID,
                    1_015_000L,
                    uint64MaxPlusOne,
                    1_014_999L,
                    uint64Max))
        .isInstanceOf(IllegalArgumentException.class);

    var wrongFence =
        OriginalAckReceiptEvidence.fromFields(
            1,
            REQUEST_ID,
            ACCOUNT_ID,
            leaseId,
            lease.leaseFence().longValueExact() + 1,
            lease.sha256(),
            DECISION_ID,
            1_015_000L,
            "101",
            1_014_999L,
            "102");
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.toAcquireResponse(
                    request, lease, State.COMMITTED, DECISION_ID, null, wrongFence))
        .isInstanceOf(IllegalArgumentException.class);

    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    var wireReceipt = response.getOriginalAckReceipt(0);
    assertRejected(
        request,
        response.toBuilder()
            .setOriginalAckReceipt(0, wireReceipt.toBuilder().clearReceiptXid())
            .build());
    assertRejected(
        request,
        response.toBuilder()
            .setOriginalAckReceipt(
                0,
                wireReceipt.toBuilder()
                    .addLeaseFence(Long.toString(lease.leaseFence().longValueExact())))
            .build());
    assertRejected(
        request,
        response.toBuilder()
            .setOriginalAckReceipt(0, wireReceipt.toBuilder().setUnknownFields(unknown))
            .build());
  }

  @Test
  void rejectsResumeAndExpectedOldGenerationLeaseForms() {
    var resumeCarrier = AccountGameplayAdmissionLeaseEvidenceTest.fixture();
    resumeCarrier.put("leaseKind", "RESUME");
    resumeCarrier.put("resumeEpisodeId", OTHER.toString());
    resumeCarrier.put("expectedOldBindingGeneration", "7");
    var resume = AccountGameplayAdmissionLeaseEvidence.fromCarrier(resumeCarrier);
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.toReadRequest(
                    JWT, resume.canonicalJson().getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);

    var oldGenerationCarrier = AccountGameplayAdmissionLeaseEvidenceTest.fixture();
    oldGenerationCarrier.put("expectedOldBindingGeneration", "7");
    var oldGeneration = AccountGameplayAdmissionLeaseEvidence.fromCarrier(oldGenerationCarrier);
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.toReadRequest(
                    JWT, oldGeneration.canonicalJson().getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void enforcesAcquireOriginalLeaseRequestAndResponseWireBounds() {
    var lease = lease();
    var candidate = candidate(lease);
    var target = target(lease);
    var acquire = wireRequest(lease, candidate, target);
    UnknownFieldSet oversizedAcquireField =
        UnknownFieldSet.newBuilder()
            .addField(
                99,
                UnknownFieldSet.Field.newBuilder()
                    .addLengthDelimited(
                        ByteString.copyFrom(
                            new byte
                                [AccountGameplayAdmissionLeaseGrpcCodec
                                    .MAX_ACQUIRE_REQUEST_WIRE_BYTES]))
                    .build())
            .build();
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireRequest(
                    acquire.toBuilder().setUnknownFields(oversizedAcquireField).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wire bound");

    byte[] originalLease = lease.canonicalJson().getBytes(StandardCharsets.UTF_8);
    var finalizeRequest =
        AccountGameplayAdmissionLeaseGrpcCodec.toFinalizeRequest(JWT, originalLease, DECISION_ID);
    var readRequest = AccountGameplayAdmissionLeaseGrpcCodec.toReadRequest(JWT, originalLease);
    var abortRequest =
        AccountGameplayAdmissionLeaseGrpcCodec.toAbortRequest(JWT, originalLease, null);
    UnknownFieldSet oversizedOriginalLeaseField =
        UnknownFieldSet.newBuilder()
            .addField(
                99,
                UnknownFieldSet.Field.newBuilder()
                    .addLengthDelimited(
                        ByteString.copyFrom(
                            new byte
                                [AccountGameplayAdmissionLeaseGrpcCodec
                                    .MAX_ORIGINAL_LEASE_REQUEST_WIRE_BYTES]))
                    .build())
            .build();
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.parseFinalizeRequest(
                    finalizeRequest.toBuilder()
                        .setUnknownFields(oversizedOriginalLeaseField)
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wire bound");
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.parseReadRequest(
                    readRequest.toBuilder().setUnknownFields(oversizedOriginalLeaseField).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wire bound");
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.parseAbortRequest(
                    abortRequest.toBuilder().setUnknownFields(oversizedOriginalLeaseField).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wire bound");

    var parsedAcquire = AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireRequest(acquire);
    var parsedFinalize =
        AccountGameplayAdmissionLeaseGrpcCodec.parseFinalizeRequest(finalizeRequest);
    var parsedRead = AccountGameplayAdmissionLeaseGrpcCodec.parseReadRequest(readRequest);
    var parsedAbort = AccountGameplayAdmissionLeaseGrpcCodec.parseAbortRequest(abortRequest);
    var oversizedResponse =
        pendingResponse(lease).toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(
                        99,
                        UnknownFieldSet.Field.newBuilder()
                            .addLengthDelimited(
                                ByteString.copyFrom(
                                    new byte
                                        [AccountGameplayAdmissionLeaseGrpcCodec
                                            .MAX_RESPONSE_WIRE_BYTES]))
                            .build())
                    .build())
            .build();
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireResponse(
                    parsedAcquire, oversizedResponse))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wire bound");
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.parseFinalizeResponse(
                    parsedFinalize, oversizedResponse))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wire bound");
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.parseReadResponse(
                    parsedRead, oversizedResponse))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wire bound");
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseGrpcCodec.parseAbortResponse(
                    parsedAbort, oversizedResponse))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wire bound");
  }

  private static void assertRejected(
      AcquireRequest request, AccountGameplayAdmissionLeaseResponse response) {
    assertThatThrownBy(
            () -> AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireResponse(request, response))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static AcquireRequest request(
      AccountGameplayAdmissionLeaseEvidence lease, Candidate candidate, Target target) {
    return AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireRequest(
        wireRequest(lease, candidate, target));
  }

  private static AcquireGameplayAdmissionLeaseRequest wireRequest(
      AccountGameplayAdmissionLeaseEvidence lease, Candidate candidate, Target target) {
    return AccountGameplayAdmissionLeaseGrpcCodec.toAcquireRequest(
        UUID.fromString((String) lease.carrier().get("requestId")),
        JWT,
        candidate,
        target,
        new BigInteger(HIGH));
  }

  private static AccountGameplayAdmissionLeaseResponse pendingResponse(
      AccountGameplayAdmissionLeaseEvidence lease) {
    return AccountGameplayAdmissionLeaseResponse.newBuilder()
        .addSchemaVersion(1)
        .addOriginalLeaseEvidenceUtf8(
            ByteString.copyFrom(lease.canonicalJson().getBytes(StandardCharsets.UTF_8)))
        .addState(AccountGameplayAdmissionLeaseState.ACCOUNT_GAMEPLAY_ADMISSION_LEASE_STATE_PENDING)
        .build();
  }

  private static AccountGameplayAdmissionLeaseEvidence lease() {
    Map<String, Object> carrier = AccountGameplayAdmissionLeaseEvidenceTest.fixture();
    mutableObject(carrier, "tokenIdentityEvidence").put("tokenSHA256", jwtSha256(JWT));
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier);
  }

  private static Candidate candidate(AccountGameplayAdmissionLeaseEvidence lease) {
    Map<?, ?> scope = object(lease.carrier(), "bindingScope");
    Map<?, ?> authority = object(lease.carrier(), "authorityTuple");
    return new Candidate(
        uuid(scope, "accountId"),
        uuid(scope, "tenantId"),
        uuid(scope, "playableStateNamespaceId"),
        uuid(scope, "characterId"),
        (String) scope.get("playableStateScope"),
        uuid(scope, "gameInstanceId"),
        71,
        uuid(scope, "sessionId"),
        uuid(scope, "regionId"),
        new BigInteger((String) scope.get("regionEpoch")),
        GameSessionAccountDelegationProfile.ISSUER,
        new BigInteger((String) authority.get("issuerAuthGeneration")),
        new BigInteger(HIGH),
        new BigInteger(HIGH),
        new BigInteger(HIGH));
  }

  private static Candidate withGameInstance(Candidate c, UUID gameInstanceId) {
    return new Candidate(
        c.accountId(),
        c.tenantId(),
        c.playableStateNamespaceId(),
        c.characterId(),
        c.playableStateScope(),
        gameInstanceId,
        c.runtimeGameInstanceId(),
        c.sessionId(),
        c.regionId(),
        c.regionEpoch(),
        c.issuerId(),
        c.issuerAuthGeneration(),
        c.issuerIndexLayoutVersion(),
        c.issuerIndexPartitionCount(),
        c.issuerIndexPartitionCapacity());
  }

  private static Target target(AccountGameplayAdmissionLeaseEvidence lease) {
    Map<?, ?> carrier = lease.carrier();
    Map<?, ?> scope = object(carrier, "bindingScope");
    UUID tenantId = uuid(scope, "tenantId");
    UUID realmId = uuid(scope, "realmId");
    UUID namespaceId = uuid(scope, "playableStateNamespaceId");
    UUID gameInstanceId = uuid(scope, "gameInstanceId");
    long catalogRevision = Long.parseLong((String) scope.get("catalogRevision"));
    long pointerVersion = Long.parseLong((String) scope.get("pointerVersion"));
    long activeWorldEpoch = Long.parseLong(HIGH);
    String requestId = (String) carrier.get("requestId");
    String requestDigest = "c".repeat(64);
    WorldCanonicalInitialAdmissionHold.Request ownerRequest =
        new WorldCanonicalInitialAdmissionHold.Request(
            (String) carrier.get("targetNamespace"),
            tenantId,
            (String) scope.get("worldSlug"),
            realmId,
            namespaceId,
            (String) scope.get("playableStateScope"),
            gameInstanceId,
            VERSION_ID,
            activeWorldEpoch,
            requestId,
            requestDigest,
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            catalogRevision,
            null);
    return new Target(
        (String) carrier.get("targetNamespace"),
        "tenant",
        tenantId,
        (String) scope.get("worldSlug"),
        "World",
        realmId,
        (String) scope.get("realmSlug"),
        "Realm",
        11,
        71,
        namespaceId,
        (String) scope.get("playableStateScope"),
        gameInstanceId,
        VERSION_ID,
        99,
        catalogRevision,
        pointerVersion,
        "d".repeat(64),
        activeWorldEpoch,
        requestId,
        requestDigest,
        OriginKind.NO_PRIOR_POINTER,
        null,
        HOLD_ID,
        HOLD_FENCE,
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(ownerRequest, HOLD_ID, HOLD_FENCE)
            .holdBindingDigest(),
        101,
        "sha256:" + "a".repeat(64),
        OwnerProofOutcome.COMMITTED,
        false,
        Instant.parse("2026-10-25T00:00:00Z"),
        "PRESEEDED_ONLY");
  }

  private static CanonicalPlayableTargetEvidence withResealedHoldDigest(
      CanonicalPlayableTargetEvidence.Builder builder) {
    CanonicalPlayableTargetEvidence target = builder.build();
    WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin origin =
        switch (target.getInitialAdmissionOriginKind(0)) {
          case CANONICAL_GAMEPLAY_ADMISSION_ORIGIN_KIND_NO_PRIOR_POINTER ->
              WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER;
          case CANONICAL_GAMEPLAY_ADMISSION_ORIGIN_KIND_EXPECT_CLOSED ->
              WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.EXPECT_CLOSED;
          default -> throw new IllegalArgumentException("Unsupported initial admission origin");
        };
    Long expectedPriorPointerVersion =
        target.getExpectedPriorPointerVersionCount() == 0
            ? null
            : Long.parseLong(target.getExpectedPriorPointerVersion(0));
    var request =
        new WorldCanonicalInitialAdmissionHold.Request(
            target.getTargetNamespace(0),
            UUID.fromString(target.getCanonicalTenantUuid(0)),
            target.getWorldSlug(0),
            UUID.fromString(target.getRealmUuid(0)),
            UUID.fromString(target.getPlayableStateNamespaceUuid(0)),
            target.getPlayableStateScope(0),
            UUID.fromString(target.getCanonicalGameInstanceUuid(0)),
            UUID.fromString(target.getCanonicalVersionUuid(0)),
            Long.parseLong(target.getActiveWorldEpoch(0)),
            target.getInitialAdmissionRequestId(0),
            target.getInitialAdmissionRequestDigest(0),
            origin,
            Long.parseLong(target.getCatalogRevision(0)),
            expectedPriorPointerVersion);
    var hold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            request,
            UUID.fromString(target.getHoldUuid(0)),
            UUID.fromString(target.getHoldFenceUuid(0)));
    return target.toBuilder().setHoldBindingDigest(0, hold.holdBindingDigest()).build();
  }

  private static String jwtSha256(String jwt) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(jwt.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static Map<String, Object> boundClaims(
      AccountGameplayAdmissionLeaseEvidence lease, UUID tokenJti, long issuedAt) {
    Map<?, ?> carrier = lease.carrier();
    Map<?, ?> scope = object(carrier, "bindingScope");
    Map<?, ?> baseline = object(carrier, "membershipBaseline");
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", GameSessionAccountDelegationProfile.ISSUER);
    claims.put("sub", scope.get("accountId"));
    claims.put("accountId", scope.get("accountId"));
    claims.put("jti", tokenJti.toString());
    claims.put("aud", GameSessionAccountDelegationProfile.AUDIENCE);
    claims.put("iat", issuedAt);
    claims.put("nbf", issuedAt);
    claims.put("exp", issuedAt + 120L);
    claims.put("tokenGeneration", "1");
    claims.put("authorityTuple", carrier.get("authorityTuple"));
    claims.put("membershipVersion", baseline.get("membershipVersion"));
    claims.put("issuanceFence", carrier.get("issuanceFence"));
    claims.put("tenantId", scope.get("tenantId"));
    return claims;
  }

  private static String compactJwt(Map<String, Object> claims) throws Exception {
    String header =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                "{\"alg\":\"RS256\",\"kid\":\"controlled-test\"}".getBytes(StandardCharsets.UTF_8));
    String payload =
        Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(claims));
    return header + "." + payload + ".c2ln";
  }

  private static String checkpointsSha256(Object checkpoints) throws Exception {
    byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(checkpoints));
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } finally {
      java.util.Arrays.fill(canonical, (byte) 0);
    }
  }

  private static Map<?, ?> object(Map<?, ?> carrier, String field) {
    return (Map<?, ?>) carrier.get(field);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mutableObject(Map<String, Object> carrier, String field) {
    return (Map<String, Object>) carrier.get(field);
  }

  private static UUID uuid(Map<?, ?> source, String field) {
    return UUID.fromString((String) source.get(field));
  }
}
