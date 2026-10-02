package net.firedevops.firemud.worldmanagement.dto;

import java.time.Instant;

public record InitialAdmissionBindHoldDto(
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
    Instant diagnosticExpiresAt) {}
