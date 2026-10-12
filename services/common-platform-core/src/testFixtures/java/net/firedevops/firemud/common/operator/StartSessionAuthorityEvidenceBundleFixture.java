package net.firedevops.firemud.common.operator;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.json.JsonMapper;

/** Test data builder for canonical human StartSession authority evidence bundles. */
public final class StartSessionAuthorityEvidenceBundleFixture {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private StartSessionAuthorityEvidenceBundleFixture() {}

  /** Values which vary across synthetic authority-bundle scenarios. */
  public record Values(
      UUID issuanceOperationId,
      String sourceEvidenceId,
      String sourceEvidenceVersion,
      String projectionStatus,
      Instant evaluatedAt,
      Instant expiresAt,
      long issuerAuthGeneration,
      long accountAuthorityGeneration,
      long tenantAuthorityGeneration,
      long membershipAuthorityGeneration,
      long membershipVersion,
      long issuanceFence,
      UUID controlUiTokenJti,
      String accountGeneration,
      String tenantGeneration) {
    public Values {
      Objects.requireNonNull(issuanceOperationId);
      Objects.requireNonNull(sourceEvidenceId);
      Objects.requireNonNull(sourceEvidenceVersion);
      Objects.requireNonNull(projectionStatus);
      Objects.requireNonNull(evaluatedAt);
      Objects.requireNonNull(expiresAt);
      Objects.requireNonNull(controlUiTokenJti);
      Objects.requireNonNull(accountGeneration);
      Objects.requireNonNull(tenantGeneration);
    }
  }

  /** Builds canonical bytes without invoking the production decoder or its validation rules. */
  public static byte[] canonicalBytes(
      StartSessionPreAuthorizationReservationTuple tuple, Values values) {
    Objects.requireNonNull(tuple);
    Objects.requireNonNull(values);
    String actorId = tuple.actor().accountId().toString();
    String tenantId = tuple.action().scope().tenantId().toString();
    Map<String, Object> value =
        Map.of(
            "bundleVersion", StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
                Map.of(
                    "scope",
                    Map.of(
                        "tenantId",
                        tenantId,
                        "targetNamespace",
                        tuple.action().scope().targetNamespace()),
                    "actionFamily",
                    tuple.actionFamily(),
                    "applicableAccountId",
                    actorId,
                    "applicableTenantId",
                    tenantId),
            "accountProjectionEvidence",
                Map.of(
                    "sourceType",
                    "ACCOUNT",
                    "sourceEvidenceId",
                    values.sourceEvidenceId(),
                    "sourceEvidenceVersion",
                    values.sourceEvidenceVersion(),
                    "projectionStatus",
                    values.projectionStatus(),
                    "evaluatedAt",
                    values.evaluatedAt().toString(),
                    "expiresAt",
                    values.expiresAt().toString()),
            "issuanceOperationIdentity",
                Map.of(
                    "issuanceOperationId",
                    values.issuanceOperationId().toString(),
                    "controlPlaneRequestId",
                    tuple.controlPlaneRequestId(),
                    "actionFamilyRequestIdentity",
                    Map.of(
                        "requestIdentityKind",
                        "controlPlaneRequestId",
                        "requestId",
                        tuple.controlPlaneRequestId()),
                    "mutationDigest",
                    tuple.mutationDigest()),
            "issuanceKind",
            "human_operator",
            "authorityTuple",
                Map.of(
                    "issuerAuthGeneration",
                    values.issuerAuthGeneration(),
                    "accountAuthorityGeneration",
                    values.accountAuthorityGeneration(),
                    "tenantAuthorityGeneration",
                    Map.of(tenantId, values.tenantAuthorityGeneration()),
                    "membershipAuthorityGeneration",
                    Map.of(tenantId, values.membershipAuthorityGeneration()),
                    "privateRealmGrantVersions",
                    List.of()),
            "membershipVersion", Map.of(tenantId, values.membershipVersion()),
            "issuanceFence", Long.toString(values.issuanceFence()),
            "issuanceEvidence",
                Map.of(
                    "evidenceType", StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
                    "actorAccountId", actorId,
                    "controlUiTokenJti", values.controlUiTokenJti().toString(),
                    "role", "tenantAdmin",
                    "accountGeneration", values.accountGeneration(),
                    "tenantGeneration", values.tenantGeneration()));
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
