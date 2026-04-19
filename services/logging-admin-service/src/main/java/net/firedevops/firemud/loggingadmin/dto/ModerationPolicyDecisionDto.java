package net.firedevops.firemud.loggingadmin.dto;

import java.time.Instant;

public record ModerationPolicyDecisionDto(
    boolean allowed, String action, String reason, Instant expiresAt) {}
