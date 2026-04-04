package net.firedevops.firemud.common.runtime;

import java.time.Instant;

/** Shared runtime identity for one running FireMUD service instance. */
public record RuntimeIdentity(
    String service,
    String serviceInstanceId,
    String hostname,
    Instant bootedAt,
    String buildVersion,
    String buildSha,
    String imageTag) {}
