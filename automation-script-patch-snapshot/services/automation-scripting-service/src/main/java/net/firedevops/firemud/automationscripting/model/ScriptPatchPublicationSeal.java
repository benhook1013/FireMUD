package net.firedevops.firemud.automationscripting.model;

import java.time.OffsetDateTime;

/** Immutable Automation-owned snapshot for one exact tenant/base/patch publication identity. */
public record ScriptPatchPublicationSeal(
    Long tenantId,
    String baseVersionId,
    String scriptPatchVersion,
    int manifestSchemaVersion,
    int contentDigestSchemaVersion,
    String contentDigest,
    String snapshotJson,
    OffsetDateTime sealedAt) {}
