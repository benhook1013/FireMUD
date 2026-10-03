package net.firedevops.firemud.worldmanagement.entity;

import java.time.Instant;

public record InitialAdmissionBindHold(
    String holdId,
    String holdFence,
    long tenantId,
    String realmUuid,
    String playableStateNamespaceUuid,
    String playableStateScope,
    long gameInstanceId,
    long versionId,
    long activeLifecycleEpoch,
    String initialAdmissionRequestId,
    String requestDigest,
    boolean expectedNoPriorPointer,
    long expectedCatalogRevision,
    String status,
    Instant diagnosticExpiresAt,
    String ownerProofId,
    String ownerProofDigest,
    String ownerPointerAuditId,
    Long ownerPointerVersion,
    String reconciliationError,
    Instant createdAt,
    Instant updatedAt,
    Instant terminalAt,
    long rowVersion) {}
