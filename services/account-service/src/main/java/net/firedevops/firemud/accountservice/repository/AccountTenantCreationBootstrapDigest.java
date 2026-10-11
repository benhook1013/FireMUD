package net.firedevops.firemud.accountservice.repository;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.NeverJoinedMembershipSnapshot;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AccountSecurityCutoff;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.PrivateRealmGrantVersion;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.TenantBillingCutoff;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;

/** Canonical complete-map encodings and digests for immutable creator-bootstrap owner evidence. */
public final class AccountTenantCreationBootstrapDigest {
  private static final String REQUEST_DOMAIN =
      "firemud/account/tenant-creation-bootstrap/request/v1";
  private static final String RESULT_DOMAIN = "firemud/account/tenant-creation-bootstrap/result/v1";

  private AccountTenantCreationBootstrapDigest() {}

  public static byte[] creatorEvidencePayload(FreshTenantCreatorEvidence evidence) {
    Objects.requireNonNull(evidence, "creator evidence is required");
    Map<String, Object> creator = new LinkedHashMap<>();
    creator.put("schemaVersion", Integer.toString(evidence.schemaVersion()));
    creator.put("creationEvidence", creationEvidenceMap(evidence.creationEvidence()));
    creator.put("initiatingAccountId", evidence.initiatingAccountId().toString());
    creator.put(
        "accountAuthorizationOperationId", evidence.accountAuthorizationOperationId().toString());
    creator.put("accountAuthorizationDigest", evidence.accountAuthorizationDigest());
    creator.put("evidenceDigest", evidence.evidenceDigest());
    return canonicalJson(creator);
  }

  public static byte[] sourceSnapshotPayload(NeverJoinedMembershipSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "Account baseline snapshot is required");
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("accountId", snapshot.accountId());
    source.put("tenantId", snapshot.tenantId());
    source.put("membershipExists", snapshot.membershipExists());
    source.put("membershipLifecycleState", snapshot.membershipLifecycleState());
    source.put("gameplayAdmissionAllowed", snapshot.gameplayAdmissionAllowed());
    source.put("roles", snapshot.roles());
    source.put("membershipVersion", snapshot.membershipVersion());
    source.put("membershipAuthorityGeneration", snapshot.membershipAuthorityGeneration());
    source.put("authorityTuple", authorityTupleMap(snapshot.authorityTuple()));
    source.put("issuanceFence", snapshot.issuanceFence());
    source.put("evaluatedAt", snapshot.evaluatedAt().toString());
    source.put("outboxStreamKey", snapshot.outboxStreamKey());
    source.put(
        "outboxCheckpoints",
        snapshot.outboxCheckpoints().stream()
            .map(
                checkpoint ->
                    Map.of(
                        "outboxStreamKey", checkpoint.outboxStreamKey(),
                        "outboxSequence", checkpoint.outboxSequence()))
            .toList());
    source.put(
        "outboxSourceEvidence",
        snapshot.outboxSourceEvidence().stream()
            .map(
                evidence ->
                    Map.of(
                        "outboxStreamKey", evidence.outboxStreamKey(),
                        "outboxSequence", evidence.outboxSequence(),
                        "eventId", evidence.eventId(),
                        "eventDigest", evidence.eventDigest(),
                        "canonicalEventJson", evidence.canonicalEventJson()))
            .toList());
    return canonicalJson(source);
  }

  public static byte[] requestPayload(byte[] creatorEvidencePayload, byte[] sourceSnapshotPayload) {
    return frameBytes(
        REQUEST_DOMAIN.getBytes(StandardCharsets.UTF_8),
        creatorEvidencePayload,
        sourceSnapshotPayload);
  }

  public static byte[] resultPayload(
      String requestId,
      String requestDigest,
      String sourceSnapshotDigest,
      UUID tenantUuid,
      Map<String, String> membershipVersion,
      String membershipAuthorityGeneration,
      String eventStreamKey,
      String eventRequestId,
      long eventSequence,
      String eventId,
      String eventDigest,
      byte[] eventPayload,
      UUID auditEventId,
      String auditEventType,
      String auditOccurredAt,
      String auditPayloadDigest,
      byte[] auditPayload,
      byte[] creatorEvidencePayload,
      byte[] sourceSnapshotPayload) {
    Objects.requireNonNull(tenantUuid, "result tenant UUID is required");
    Objects.requireNonNull(membershipVersion, "result membership-version map is required");
    String tenantKey = tenantUuid.toString();
    if (membershipVersion.size() != 1 || !membershipVersion.containsKey(tenantKey)) {
      throw new IllegalArgumentException(
          "Creator-bootstrap result requires exactly its tenant membership-version entry");
    }
    String version = membershipVersion.get(tenantKey);
    try {
      if (version == null
          || !Long.toString(Long.parseLong(version)).equals(version)
          || Long.parseLong(version) <= 0L) {
        throw new IllegalArgumentException("Creator-bootstrap membership version is invalid");
      }
    } catch (NumberFormatException failure) {
      throw new IllegalArgumentException(
          "Creator-bootstrap membership version is invalid", failure);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schemaVersion", "account-tenant-creation-bootstrap-result/v1");
    result.put("requestId", requestId);
    result.put("requestDigest", requestDigest);
    result.put("sourceSnapshotDigest", sourceSnapshotDigest);
    result.put("membershipVersion", membershipVersion);
    result.put("membershipAuthorityGeneration", membershipAuthorityGeneration);
    result.put("eventStreamKey", eventStreamKey);
    result.put("eventRequestId", eventRequestId);
    result.put("eventSequence", Long.toString(eventSequence));
    result.put("eventId", eventId);
    result.put("eventDigest", eventDigest);
    result.put("eventPayload", Base64.getEncoder().encodeToString(eventPayload));
    result.put("auditEventId", auditEventId.toString());
    result.put("auditEventType", auditEventType);
    result.put("auditOccurredAt", auditOccurredAt);
    result.put("auditPayloadDigest", auditPayloadDigest);
    result.put("auditPayload", Base64.getEncoder().encodeToString(auditPayload));
    result.put("creatorEvidence", Base64.getEncoder().encodeToString(creatorEvidencePayload));
    result.put("sourceSnapshot", Base64.getEncoder().encodeToString(sourceSnapshotPayload));
    return frameBytes(RESULT_DOMAIN.getBytes(StandardCharsets.UTF_8), canonicalJson(result));
  }

  public static String sha256(byte[] payload) {
    Objects.requireNonNull(payload, "digest payload is required");
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  public static byte[] authorityTuplePayload(AuthorityTuple tuple) {
    return canonicalJson(authorityTupleMap(tuple));
  }

  public static Map<String, Object> authorityTupleMap(AuthorityTuple tuple) {
    Objects.requireNonNull(tuple, "authority tuple is required");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("issuerAuthGeneration", tuple.issuerAuthGeneration());
    result.put("accountAuthorityGeneration", tuple.accountAuthorityGeneration());
    result.put("tenantAuthorityGeneration", tuple.tenantAuthorityGeneration());
    result.put("membershipAuthorityGeneration", tuple.membershipAuthorityGeneration());
    result.put(
        "privateRealmGrantVersions",
        tuple.privateRealmGrantVersions().stream()
            .map(AccountTenantCreationBootstrapDigest::grantMap)
            .toList());
    tuple
        .accountSecurityCutoff()
        .ifPresent(cutoff -> result.put("accountSecurityCutoff", cutoffMap(cutoff)));
    tuple
        .tenantBillingCutoff()
        .ifPresent(
            cutoffs -> {
              Map<String, Object> encodedCutoffs = new LinkedHashMap<>();
              cutoffs.forEach(
                  (tenantId, cutoff) -> encodedCutoffs.put(tenantId, cutoffMap(cutoff)));
              result.put("tenantBillingCutoff", encodedCutoffs);
            });
    return result;
  }

  private static Map<String, Object> creationEvidenceMap(FreshTenantCreationEvidence evidence) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schemaVersion", Integer.toString(evidence.schemaVersion()));
    result.put("targetNamespace", evidence.targetNamespace());
    result.put("creationRequestId", evidence.creationRequestId().toString());
    result.put("operationId", evidence.operationId().toString());
    result.put("requestDigest", evidence.requestDigest());
    result.put("canonicalTenantId", evidence.canonicalTenantId().toString());
    result.put("sourceGameRowId", Long.toString(evidence.sourceGameRowId()));
    result.put("sourceGameTenantKey", evidence.sourceGameTenantKey());
    result.put("provenanceKind", evidence.provenanceKind());
    result.put("evidenceDigest", evidence.evidenceDigest());
    return result;
  }

  private static Map<String, Object> grantMap(PrivateRealmGrantVersion grant) {
    return Map.of(
        "tenantId", grant.tenantId(),
        "worldSlug", grant.worldSlug(),
        "realmSlug", grant.realmSlug(),
        "playtestLifecycleId", grant.playtestLifecycleId(),
        "grantVersion", grant.grantVersion());
  }

  private static Map<String, Object> cutoffMap(AccountSecurityCutoff cutoff) {
    return Map.of(
        "accountAuthorityGeneration", cutoff.accountAuthorityGeneration(),
        "outboxStreamKey", cutoff.outboxStreamKey(),
        "outboxSequence", cutoff.outboxSequence());
  }

  private static Map<String, Object> cutoffMap(TenantBillingCutoff cutoff) {
    return Map.of(
        "tenantAuthorityGeneration", cutoff.tenantAuthorityGeneration(),
        "tenantBillingSequence", cutoff.tenantBillingSequence(),
        "outboxStreamKey", cutoff.outboxStreamKey(),
        "outboxSequence", cutoff.outboxSequence());
  }

  private static byte[] canonicalJson(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(
          tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value));
    } catch (IOException | RuntimeException failure) {
      throw new IllegalStateException(
          "Creator-bootstrap canonical JSON serialization failed", failure);
    }
  }

  private static byte[] frameBytes(byte[]... values) {
    ByteArrayOutputStream framed = new ByteArrayOutputStream();
    for (byte[] value : values) {
      Objects.requireNonNull(value, "framed value is required");
      framed.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
      framed.writeBytes(value);
    }
    return framed.toByteArray();
  }
}
