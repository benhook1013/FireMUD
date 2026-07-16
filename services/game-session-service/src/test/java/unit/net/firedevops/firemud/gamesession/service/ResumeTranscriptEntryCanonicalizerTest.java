package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.gamesession.entity.ResumeTranscriptEntry;
import org.junit.jupiter.api.Test;

class ResumeTranscriptEntryCanonicalizerTest {
  @Test
  void canonicalizesStructuredEnvelopeFieldOrderUnicodeAndDecimalPayloadValues() {
    ResumeTranscriptEntry entry = new ResumeTranscriptEntry();
    entry.setId(19L);
    entry.setTenantId(22L);
    entry.setGameInstanceId(7L);
    entry.setCharacterId(13L);
    entry.setAppendedAt(Instant.parse("2026-07-14T01:02:03.004Z"));
    entry.setOutputKind("VIEW");
    entry.setReplayPolicy("REPLAY");
    entry.setBriefRenderPolicy("FULL");
    entry.setPayloadType("look-view");
    entry.setPayloadJson("{\"z\":1.0,\"a\":{\"y\":\"e\\u0301\",\"x\":true}}");
    entry.setProtocolText("Cafe\u0301\n");

    assertThat(ResumeTranscriptEntryCanonicalizer.canonicalJson(entry))
        .isEqualTo(
            "{\"characterId\":13,\"gameInstanceId\":7,\"occurredAt\":\"2026-07-14T01:02:03.004Z\",\"orderingToken\":19,\"outputKind\":\"VIEW\",\"payload\":{\"briefRenderPolicy\":\"FULL\",\"payload\":{\"a\":{\"x\":true,\"y\":\"é\"},\"z\":1},\"payloadType\":\"look-view\",\"replayPolicy\":\"REPLAY\"},\"renderedText\":\"Café\\n\",\"tenantId\":22}");
  }

  @Test
  void sharesCanonicalByteSizingWithTheRedisTranscriptEnvelope() {
    ResumeTranscriptEntry entry = new ResumeTranscriptEntry();
    entry.setId(19L);
    entry.setTenantId(22L);
    entry.setGameInstanceId(7L);
    entry.setCharacterId(13L);
    entry.setAppendedAt(Instant.parse("2026-07-14T01:02:03.004Z"));
    entry.setOutputKind("VIEW");
    entry.setReplayPolicy("REPLAY");
    entry.setBriefRenderPolicy("FULL");
    entry.setPayloadType("look-view");
    entry.setPayloadJson("{\"z\":1.0,\"a\":{\"y\":\"e\\u0301\",\"x\":true}}");
    entry.setProtocolText("Cafe\u0301\n");
    ScreenBufferService.BufferedEntry buffered =
        new ScreenBufferService.BufferedEntry(
            entry.getProtocolText(),
            1,
            0,
            entry.getAppendedAt().toEpochMilli(),
            entry.getOutputKind(),
            entry.getReplayPolicy(),
            entry.getBriefRenderPolicy(),
            entry.getPayloadType(),
            entry.getPayloadJson(),
            entry.getId());

    assertThat(buffered.canonicalByteSize(22L, 7L, 13L))
        .isEqualTo(ResumeTranscriptEntryCanonicalizer.byteSize(entry));
  }
}
