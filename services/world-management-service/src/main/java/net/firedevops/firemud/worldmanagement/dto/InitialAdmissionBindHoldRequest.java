package net.firedevops.firemud.worldmanagement.dto;

public record InitialAdmissionBindHoldRequest(
    long tenantId,
    long gameInstanceId,
    long versionId,
    long activeLifecycleEpoch,
    String initialAdmissionRequestId,
    String requestDigest,
    String realmUuid,
    String playableStateNamespaceUuid,
    String playableStateScope,
    boolean expectedNoPriorPointer,
    long expectedCatalogRevision) {}
