package net.firedevops.firemud.socialgroups.service;

import net.firedevops.firemud.socialgroups.dto.VoiceTokenDto;
import net.firedevops.firemud.socialgroups.dto.VoiceTokenRequestDto;

/** Service for issuing WebRTC tokens for voice chat. */
public interface VoiceChatService {
  VoiceTokenDto createToken(VoiceTokenRequestDto request);
}
