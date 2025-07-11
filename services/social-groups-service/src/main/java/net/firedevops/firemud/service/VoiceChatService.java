package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.VoiceTokenDto;
import net.firedevops.firemud.dto.VoiceTokenRequestDto;

/** Service for issuing WebRTC tokens for voice chat. */
public interface VoiceChatService {
  VoiceTokenDto createToken(VoiceTokenRequestDto request);
}
