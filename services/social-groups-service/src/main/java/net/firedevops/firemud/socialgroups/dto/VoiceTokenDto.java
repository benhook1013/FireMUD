package net.firedevops.firemud.socialgroups.dto;

import java.time.Instant;

/** Response containing a WebRTC token and expiration. */
public record VoiceTokenDto(String token, Instant expiresAt) {}
