package net.firedevops.firemud.socialgroups.service.impl;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.socialgroups.dto.VoiceTokenDto;
import net.firedevops.firemud.socialgroups.dto.VoiceTokenRequestDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VoiceChatServiceImplTest {
  private JwtUtil jwtUtil;
  private VoiceChatServiceImpl service;

  @BeforeEach
  void setup() {
    jwtUtil = new JwtUtil("testsecretkeytestsecretkeytest1234", 300000L);
    service = new VoiceChatServiceImpl(jwtUtil);
  }

  @Test
  void createTokenReturnsToken() {
    VoiceTokenRequestDto request = new VoiceTokenRequestDto(1L, 2L, "guild-1");

    VoiceTokenDto dto = service.createToken(request);

    assertNotNull(dto.token());
    assertNotNull(dto.expiresAt());
  }
}
