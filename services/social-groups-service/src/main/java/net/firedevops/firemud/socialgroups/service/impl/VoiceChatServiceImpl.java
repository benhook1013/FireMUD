package net.firedevops.firemud.socialgroups.service.impl;

import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.socialgroups.dto.VoiceTokenDto;
import net.firedevops.firemud.socialgroups.dto.VoiceTokenRequestDto;
import net.firedevops.firemud.socialgroups.service.VoiceChatService;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Default implementation of {@link VoiceChatService}. */
@Service
@RequiredArgsConstructor
public class VoiceChatServiceImpl implements VoiceChatService {
  private static final Logger logger = LoggingUtil.getLogger(VoiceChatServiceImpl.class);

  private final JwtUtil jwtUtil;

  @Value("${firemud.voice.token-expiration-ms:300000}")
  private long expirationMs;

  @Override
  @Timed(value = "voice.token.create")
  public VoiceTokenDto createToken(VoiceTokenRequestDto request) {
    logger.info("Issuing voice token for account {}", request.accountId());
    String token =
        jwtUtil.generateToken(
            request.accountId().toString(),
            Map.of("tenantId", request.tenantId(), "channelId", request.channelId()));
    Instant expiresAt = Instant.ofEpochMilli(System.currentTimeMillis() + expirationMs);
    return new VoiceTokenDto(token, expiresAt);
  }
}
