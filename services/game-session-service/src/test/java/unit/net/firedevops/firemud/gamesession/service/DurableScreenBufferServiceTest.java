package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.common.config.FiremudReconnectionProperties;
import net.firedevops.firemud.common.config.ReconnectionSettingsResolver;
import net.firedevops.firemud.gamesession.entity.ResumeTranscriptEntry;
import net.firedevops.firemud.gamesession.repository.ResumeTranscriptEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class DurableScreenBufferServiceTest {
  private final ResumeTranscriptEntryRepository repository =
      Mockito.mock(ResumeTranscriptEntryRepository.class);
  private final ReconnectionSettingsResolver settingsResolver =
      Mockito.mock(ReconnectionSettingsResolver.class);
  private final ScreenBufferService hotCache = Mockito.mock(ScreenBufferService.class);
  private final DurableScreenBufferService service =
      new DurableScreenBufferService(repository, settingsResolver, hotCache);

  @BeforeEach
  void setUp() {
    when(settingsResolver.resolve(22L, 7L))
        .thenReturn(
            new FiremudReconnectionProperties(
                new FiremudReconnectionProperties.Policy(180_000L, true),
                new FiremudReconnectionProperties.Buffer(0L, 1, 1, 100, 200)));
  }

  @Test
  void appendPersistsStructuredEntriesBeforeUpdatingHotCache() {
    ScreenBufferService.BufferedEntry entry =
        ScreenBufferService.BufferedEntry.fromStructuredOutput(
            "You say, \"hello\"\n",
            "COMMUNICATION",
            "REPLAY",
            "FULL",
            "player-output",
            "{\"kind\":\"SAY\"}");
    when(repository.findByScope(22L, 7L, 13L)).thenReturn(List.of());

    service.append(22L, 7L, 13L, List.of(entry));

    ArgumentCaptor<List<ResumeTranscriptEntry>> entries = ArgumentCaptor.captor();
    verify(repository).saveAll(entries.capture());
    ResumeTranscriptEntry persisted = entries.getValue().getFirst();
    assertThat(persisted.getTenantId()).isEqualTo(22L);
    assertThat(persisted.getGameInstanceId()).isEqualTo(7L);
    assertThat(persisted.getCharacterId()).isEqualTo(13L);
    assertThat(persisted.getProtocolText()).isEqualTo("You say, \"hello\"\n");
    assertThat(persisted.getPayloadJson()).isEqualTo("{\"kind\":\"SAY\"}");
    assertThat(persisted.getExpiresAt()).isNull();
    verify(repository).deleteExpired(eq(22L), eq(7L), eq(13L), any());
    verify(hotCache).append(22L, 7L, 13L, List.of(entry));
  }

  @Test
  void getRehydratesTheHotCacheFromDurableStructuredEntries() {
    ResumeTranscriptEntry entry =
        entry(1L, "Recent room line\n", Instant.parse("2026-07-12T01:02:03Z"));
    entry.setOutputKind("VIEW");
    entry.setReplayPolicy("REPLAY");
    entry.setBriefRenderPolicy("FULL");
    entry.setPayloadType("look-view");
    entry.setPayloadJson("{\"room\":\"R-1\"}");
    when(hotCache.get(22L, 7L, 13L)).thenReturn(Optional.empty());
    when(repository.findByScope(22L, 7L, 13L)).thenReturn(List.of(entry));

    ScreenBufferService.BufferedScreen screen = service.get(22L, 7L, 13L).orElseThrow();

    assertThat(screen.protocolText()).isEqualTo("Recent room line\n");
    assertThat(screen.entries().getFirst().payloadJson()).isEqualTo("{\"room\":\"R-1\"}");
    verify(hotCache).append(eq(22L), eq(7L), eq(13L), eq(screen.entries()));
  }

  @Test
  void getDropsExpiredDurableEntriesAndInvalidatesTheHotCache() {
    when(settingsResolver.resolve(22L, 7L))
        .thenReturn(
            new FiremudReconnectionProperties(
                new FiremudReconnectionProperties.Policy(180_000L, true),
                new FiremudReconnectionProperties.Buffer(1L, 1, 1, 100, 200)));
    when(repository.deleteExpired(eq(22L), eq(7L), eq(13L), any())).thenReturn(1);
    when(repository.findByScope(22L, 7L, 13L)).thenReturn(List.of());

    assertThat(service.get(22L, 7L, 13L)).isEmpty();

    verify(hotCache).clear(22L, 7L, 13L);
    verify(hotCache, never()).get(22L, 7L, 13L);
  }

  @Test
  void appendTrimsOldestEntriesUsingExistingBufferBounds() {
    ResumeTranscriptEntry first = entry(1L, "old", Instant.parse("2026-07-12T01:00:00Z"));
    first.setByteSize(80);
    ResumeTranscriptEntry second = entry(2L, "new", Instant.parse("2026-07-12T01:01:00Z"));
    second.setByteSize(80);
    when(repository.findByScope(22L, 7L, 13L)).thenReturn(List.of(first, second));

    service.append(22L, 7L, 13L, List.of(ScreenBufferService.BufferedEntry.fromText("new")));

    verify(repository).deleteByIds(List.of(1L));
  }

  @Test
  void appendPersistsConfiguredEntryExpiry() {
    when(settingsResolver.resolve(22L, 7L))
        .thenReturn(
            new FiremudReconnectionProperties(
                new FiremudReconnectionProperties.Policy(180_000L, true),
                new FiremudReconnectionProperties.Buffer(1_000L, 1, 1, 100, 200)));
    when(repository.findByScope(22L, 7L, 13L)).thenReturn(List.of());
    ScreenBufferService.BufferedEntry entry =
        new ScreenBufferService.BufferedEntry(
            "Recent room line\n", 1, 17, 1_000L, null, null, null, null, null);

    service.append(22L, 7L, 13L, List.of(entry));

    ArgumentCaptor<List<ResumeTranscriptEntry>> entries = ArgumentCaptor.captor();
    verify(repository).saveAll(entries.capture());
    assertThat(entries.getValue().getFirst().getExpiresAt())
        .isEqualTo(Instant.ofEpochMilli(2_000L));
  }

  private ResumeTranscriptEntry entry(Long id, String text, Instant appendedAt) {
    ResumeTranscriptEntry entry = new ResumeTranscriptEntry();
    entry.setId(id);
    entry.setTenantId(22L);
    entry.setGameInstanceId(7L);
    entry.setCharacterId(13L);
    entry.setProtocolText(text);
    entry.setLineCount(1);
    entry.setByteSize(text.length());
    entry.setAppendedAt(appendedAt);
    return entry;
  }
}
