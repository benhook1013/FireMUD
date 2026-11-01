package net.firedevops.firemud.accountservice.repository;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountInitialTenantBillingOwnerRepository.InitialRequest;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import tools.jackson.databind.json.JsonMapper;

/** Versioned canonical evidence and digest grammar for initial tenant billing-owner storage. */
final class AccountInitialTenantBillingOwnerDigest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final long INITIAL_OWNER_VERSION = 1L;

  private AccountInitialTenantBillingOwnerDigest() {}

  static EncodedRequest request(InitialRequest request) {
    FreshTenantCreatorEvidence creator = request.creatorEvidence();
    var creation = creator.creationEvidence();
    byte[] creatorPayload = AccountTenantCreationBootstrapDigest.creatorEvidencePayload(creator);
    byte[] consentPayload =
        consentPayload(
            request.requestId(),
            request.consentOperationId(),
            request.consentStatementVersion(),
            creation.canonicalTenantId(),
            creator.initiatingAccountId(),
            creation.creationRequestId(),
            creation.operationId(),
            creator.evidenceDigest());
    String consentDigest = sha256(consentPayload);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", "account-initial-tenant-billing-owner-request/v1");
    payload.put("requestId", request.requestId().toString());
    payload.put("tenantId", creation.canonicalTenantId().toString());
    payload.put("ownerAccountId", creator.initiatingAccountId().toString());
    payload.put("creatorEvidence", Base64.getEncoder().encodeToString(creatorPayload));
    payload.put("creatorEvidenceDigest", creator.evidenceDigest());
    payload.put("consentOperationId", request.consentOperationId().toString());
    payload.put("consentStatementVersion", Integer.toString(request.consentStatementVersion()));
    payload.put("affirmativeConsent", true);
    payload.put("consentPayload", Base64.getEncoder().encodeToString(consentPayload));
    payload.put("consentDigest", consentDigest);
    byte[] requestPayload = canonical(payload);
    return new EncodedRequest(
        creatorPayload,
        sha256(creatorPayload),
        consentPayload,
        consentDigest,
        requestPayload,
        sha256(requestPayload));
  }

  static byte[] resultPayload(
      InitialRequest request, EncodedRequest encoded, UUID ownerGeneration) {
    var creation = request.creatorEvidence().creationEvidence();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schemaVersion", "account-initial-tenant-billing-owner-result/v1");
    result.put("requestId", request.requestId().toString());
    result.put("requestDigest", encoded.requestDigest());
    result.put("tenantId", creation.canonicalTenantId().toString());
    result.put("ownerAccountId", request.creatorEvidence().initiatingAccountId().toString());
    result.put("ownerVersion", Long.toString(INITIAL_OWNER_VERSION));
    result.put("ownerGeneration", ownerGeneration.toString());
    result.put("creatorEvidenceDigest", request.creatorEvidence().evidenceDigest());
    result.put("consentOperationId", request.consentOperationId().toString());
    result.put("consentStatementVersion", Integer.toString(request.consentStatementVersion()));
    result.put("consentDigest", encoded.consentDigest());
    result.put("consentPayload", Base64.getEncoder().encodeToString(encoded.consentPayload()));
    return canonical(result);
  }

  static String sha256(byte[] payload) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static byte[] consentPayload(
      UUID requestId,
      UUID consentOperationId,
      int statementVersion,
      UUID tenantUuid,
      UUID ownerAccountUuid,
      UUID creationRequestId,
      UUID creationOperationId,
      String creatorEvidenceDigest) {
    Map<String, Object> consent = new LinkedHashMap<>();
    consent.put("schemaVersion", "account-initial-tenant-billing-owner-consent/v1");
    consent.put("requestId", requestId.toString());
    consent.put("consentOperationId", consentOperationId.toString());
    consent.put("tenantId", tenantUuid.toString());
    consent.put("initiatingAccountId", ownerAccountUuid.toString());
    consent.put("creationRequestId", creationRequestId.toString());
    consent.put("creationOperationId", creationOperationId.toString());
    consent.put("creatorEvidenceDigest", creatorEvidenceDigest);
    consent.put("statementVersion", Integer.toString(statementVersion));
    consent.put("affirmative", true);
    consent.put("consent", "ACCEPT_INITIAL_BILLING_OWNER");
    return canonical(consent);
  }

  private static byte[] canonical(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException | RuntimeException failure) {
      throw new IllegalStateException("Initial billing-owner evidence encoding failed", failure);
    }
  }

  record EncodedRequest(
      byte[] creatorEvidencePayload,
      String creatorEvidencePayloadDigest,
      byte[] consentPayload,
      String consentDigest,
      byte[] requestPayload,
      String requestDigest) {
    EncodedRequest {
      creatorEvidencePayload = creatorEvidencePayload.clone();
      consentPayload = consentPayload.clone();
      requestPayload = requestPayload.clone();
    }

    @Override
    public byte[] creatorEvidencePayload() {
      return creatorEvidencePayload.clone();
    }

    @Override
    public byte[] consentPayload() {
      return consentPayload.clone();
    }

    @Override
    public byte[] requestPayload() {
      return requestPayload.clone();
    }
  }
}
