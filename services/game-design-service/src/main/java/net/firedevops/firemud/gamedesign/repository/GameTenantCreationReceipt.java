package net.firedevops.firemud.gamedesign.repository;

import java.util.UUID;

/** Immutable, owner-local evidence for one fresh Game Design tenant creation operation. */
public record GameTenantCreationReceipt(
    int schemaVersion,
    String targetNamespace,
    UUID creationRequestId,
    UUID operationId,
    String requestDigest,
    UUID canonicalTenantId,
    long sourceGameRowId,
    String sourceGameTenantKey,
    String provenanceKind,
    String evidenceDigest) {}
