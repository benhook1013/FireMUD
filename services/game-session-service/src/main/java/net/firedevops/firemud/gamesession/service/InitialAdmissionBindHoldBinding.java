package net.firedevops.firemud.gamesession.service;

/** Exact World-issued hold identity and immutable request tuple passed to GS owner operations. */
public record InitialAdmissionBindHoldBinding(
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
    long expectedCatalogRevision) {}
