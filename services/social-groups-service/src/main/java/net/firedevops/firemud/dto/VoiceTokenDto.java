package net.firedevops.firemud.dto;

import java.time.Instant;

/** Response containing a WebRTC token and expiration. */
public record VoiceTokenDto(String token, Instant expiresAt) {}
