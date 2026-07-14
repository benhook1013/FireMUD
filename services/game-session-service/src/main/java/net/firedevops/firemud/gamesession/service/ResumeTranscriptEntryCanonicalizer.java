package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.cache.ResumeTranscriptCanonicalizer;
import net.firedevops.firemud.gamesession.entity.ResumeTranscriptEntry;

/** Defines the deterministic structured envelope used for durable transcript byte accounting. */
final class ResumeTranscriptEntryCanonicalizer {
  private ResumeTranscriptEntryCanonicalizer() {}

  static int byteSize(ResumeTranscriptEntry entry) {
    return ResumeTranscriptCanonicalizer.byteSize(
        entry.getTenantId(),
        entry.getGameInstanceId(),
        entry.getCharacterId(),
        orderingToken(entry),
        entry.getAppendedAt().toEpochMilli(),
        entry.getOutputKind(),
        entry.getReplayPolicy(),
        entry.getBriefRenderPolicy(),
        entry.getPayloadType(),
        entry.getPayloadJson(),
        entry.getProtocolText());
  }

  static String canonicalJson(ResumeTranscriptEntry entry) {
    return ResumeTranscriptCanonicalizer.canonicalJson(
        entry.getTenantId(),
        entry.getGameInstanceId(),
        entry.getCharacterId(),
        orderingToken(entry),
        entry.getAppendedAt().toEpochMilli(),
        entry.getOutputKind(),
        entry.getReplayPolicy(),
        entry.getBriefRenderPolicy(),
        entry.getPayloadType(),
        entry.getPayloadJson(),
        entry.getProtocolText());
  }

  private static long orderingToken(ResumeTranscriptEntry entry) {
    if (entry.getId() == null) {
      throw new IllegalArgumentException(
          "Transcript ordering token must be assigned before sizing");
    }
    return entry.getId();
  }
}
