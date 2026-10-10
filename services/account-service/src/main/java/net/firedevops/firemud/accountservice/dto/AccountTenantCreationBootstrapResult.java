package net.firedevops.firemud.accountservice.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable historical Account owner result for one source-bound creator membership bootstrap. */
public record AccountTenantCreationBootstrapResult(
    UUID operationId,
    String requestDigest,
    UUID initiatingAccountUuid,
    UUID tenantUuid,
    String creatorEvidenceDigest,
    String sourceSnapshotDigest,
    Map<String, String> membershipVersion,
    String membershipAuthorityGeneration,
    List<String> roles,
    boolean gameplayAdmissionAllowed,
    String eventStreamKey,
    String eventRequestId,
    long eventSequence,
    String eventId,
    String eventDigest,
    UUID auditEventId,
    String auditEventType,
    String auditPayloadDigest,
    String resultDigest) {
  public AccountTenantCreationBootstrapResult {
    if (operationId == null
        || initiatingAccountUuid == null
        || tenantUuid == null
        || membershipVersion == null
        || membershipAuthorityGeneration == null
        || roles == null
        || eventStreamKey == null
        || eventRequestId == null
        || eventSequence <= 0L
        || eventId == null
        || eventDigest == null
        || auditEventId == null
        || auditEventType == null
        || auditPayloadDigest == null
        || resultDigest == null) {
      throw new IllegalArgumentException("Committed creator-bootstrap result is incomplete");
    }
    membershipVersion = Map.copyOf(membershipVersion);
    roles = List.copyOf(roles);
  }
}
