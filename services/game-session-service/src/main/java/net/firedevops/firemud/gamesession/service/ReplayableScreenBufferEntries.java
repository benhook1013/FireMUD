package net.firedevops.firemud.gamesession.service;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.websocket.WebSocketOutputProjector;
import org.springframework.util.StringUtils;

/** Canonical builder for replayable hot-buffer entries from structured player output. */
public final class ReplayableScreenBufferEntries {
  private ReplayableScreenBufferEntries() {}

  public static List<ScreenBufferService.BufferedEntry> fromOutputs(
      List<PlayerOutput> outputs,
      WebSocketOutputProjector outputProjector,
      String localeTag,
      PresentationProperties effectivePresentation) {
    return outputs.stream()
        .map(output -> fromOutput(output, outputProjector, localeTag, effectivePresentation))
        .flatMap(Optional::stream)
        .toList();
  }

  public static Optional<ScreenBufferService.BufferedEntry> fromOutput(
      PlayerOutput output,
      WebSocketOutputProjector outputProjector,
      String localeTag,
      PresentationProperties effectivePresentation) {
    if (output == null || !output.screenBufferEligible()) {
      return Optional.empty();
    }
    String rendered =
        outputProjector.renderClassicPlayerOutput(output, localeTag, effectivePresentation);
    return fromRenderedOutput(output, outputProjector, rendered);
  }

  public static Optional<ScreenBufferService.BufferedEntry> fromRenderedOutput(
      PlayerOutput output, WebSocketOutputProjector outputProjector, String rendered) {
    if (output == null || !output.screenBufferEligible()) {
      return Optional.empty();
    }
    if (!StringUtils.hasText(rendered)) {
      return Optional.empty();
    }
    return Optional.of(outputProjector.toBufferedEntry(output, rendered + "\n"));
  }
}
