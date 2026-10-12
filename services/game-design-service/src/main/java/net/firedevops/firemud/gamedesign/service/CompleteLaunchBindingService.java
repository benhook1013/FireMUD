package net.firedevops.firemud.gamedesign.service;

import java.util.UUID;
import net.firedevops.firemud.gamedesign.dto.CompleteLaunchBindingDto;

public interface CompleteLaunchBindingService {
  CompleteLaunchBindingDto getCompleteLaunchBinding(
      UUID readRequestId,
      UUID canonicalTenantId,
      String worldSlug,
      String controlPlaneRequestId,
      String expectedRequestDigest,
      String expectedResultDigest);
}
