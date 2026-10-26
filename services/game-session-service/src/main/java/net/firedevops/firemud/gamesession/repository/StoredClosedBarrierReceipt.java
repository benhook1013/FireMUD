package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Plain historical SQL row view; it is not a current-incarnation or installed-fence capability. */
record StoredClosedBarrierReceipt(
    UUID operationId,
    UUID requestId,
    UUID accountId,
    BigInteger operationFence,
    UUID plannedAccountAdmissionFence,
    BigInteger inventorySnapshotRevision,
    String cohortDigest,
    String barrierTargetDigest,
    long targetOrdinal,
    UUID tenantId,
    UUID playableStateNamespaceId,
    String tenantGameplayTag,
    String workloadNamespace,
    int allocationVersion,
    String barrierKey,
    String barrierSchema,
    String incarnationDigest,
    String barrierState,
    String acceptedAttestationDigest,
    String barrierFieldsDigest,
    String beforeSourcePayloadDigest,
    long beforeObservationIssuedAtEpochMillis,
    long beforeObservationObservedAtEpochMillis,
    long beforeObservationExpiresAtEpochMillis,
    String afterSourcePayloadDigest,
    long afterObservationIssuedAtEpochMillis,
    long afterObservationObservedAtEpochMillis,
    long afterObservationExpiresAtEpochMillis,
    OffsetDateTime createdAt) {}
