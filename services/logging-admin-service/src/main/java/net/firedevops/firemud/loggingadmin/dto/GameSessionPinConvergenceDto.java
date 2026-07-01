package net.firedevops.firemud.loggingadmin.dto;

import java.time.Instant;

public record GameSessionPinConvergenceDto(
    long tenantId,
    long gameInstanceId,
    String observedPinnedScriptPatchVersion,
    String lastObservedControlPlaneRequestId,
    Instant observedAt,
    boolean stale,
    PinnedScriptPatchVersionDto.ScriptPatchPublicationLinkDto publication) {}
