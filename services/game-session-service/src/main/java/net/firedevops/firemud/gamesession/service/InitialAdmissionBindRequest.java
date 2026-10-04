package net.firedevops.firemud.gamesession.service;

/** Runtime-bound GS intent persisted before the coordinator contacts the World hold owner. */
public record InitialAdmissionBindRequest(
    long tenantId,
    String worldSlug,
    String realmSlug,
    String initialAdmissionRequestId,
    String requestDigest,
    long gameInstanceId,
    long versionId,
    long activeLifecycleEpoch) {}
