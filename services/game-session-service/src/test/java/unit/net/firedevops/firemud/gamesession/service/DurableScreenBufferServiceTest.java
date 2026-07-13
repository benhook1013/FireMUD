package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
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
  private static final long TENANT_ID = 22L;
  private static final long GAME_INSTANCE_ID = 7L;
  private static final long CHARACTER_ID = 13L;

  private final ResumeTranscriptEntryRepository repository =
      Mockito.mock(ResumeTranscriptEntryRepository.class);
  private final ReconnectionSettingsResolver settingsResolver =
      Mockito.mock(ReconnectionSettingsResolver.class);
  private final ScreenBufferService hotCache = Mockito.mock(ScreenBufferService.class);
  private final List<ResumeTranscriptEntry> persistedEntries = new ArrayList<>();
  private final DurableScreenBufferService service =
      new DurableScreenBufferService(repository, settingsResolver, hotCache);

  @BeforeEach
  void setUp() {
    configureBuffer(0L, 256, 1, 1, 1_000, 2_000);
    AtomicLong nextOrderingToken = new AtomicLong(100L);
    doAnswer(
            invocation -> {
              List<ResumeTranscriptEntry> entries = invocation.getArgument(0);
              entries.forEach(entry -> entry.setId(nextOrderingToken.getAndIncrement()));
              return null;
            })
        .when(repository)
        .assignOrderingTokens(any());
    doAnswer(
            invocation -> {
              persistedEntries.addAll(invocation.getArgument(0));
              return null;
            })
        .when(repository)
        .saveAll(any());
    when(repository.findActiveByScope(eq(TENANT_ID), eq(GAME_INSTANCE_ID), eq(CHARACTER_ID), any()))
        .thenAnswer(invocation -> List.copyOf(persistedEntries));
  }

  @Test
  void appendPersistsStructuredEntriesBeforeReplacingHotCache() {
    ScreenBufferService.BufferedEntry entry =
        ScreenBufferService.BufferedEntry.fromStructuredOutput(
            "You say, \"hello\"\n",
            "COMMUNICATION",
            "REPLAY",
            "FULL",
            "player-output",
            "{\"kind\":\"SAY\"}");

    service.append(TENANT_ID, GAME_INSTANCE_ID, CHARACTER_ID, List.of(entry));

    ArgumentCaptor<List<ResumeTranscriptEntry>> entries = ArgumentCaptor.captor();
    verify(repository).saveAll(entries.capture());
    ResumeTranscriptEntry persisted = entries.getValue().getFirst();
    assertThat(persisted.getTenantId()).isEqualTo(TENANT_ID);
    assertThat(persisted.getGameInstanceId()).isEqualTo(GAME_INSTANCE_ID);
    assertThat(persisted.getCharacterId()).isEqualTo(CHARACTER_ID);
    assertThat(persisted.getProtocolText()).isEqualTo("You say, \"hello\"\n");
    assertThat(persisted.getPayloadJson()).isEqualTo("{\"kind\":\"SAY\"}");
    assertThat(persisted.getByteSize())
        .isEqualTo(ResumeTranscriptEntryCanonicalizer.byteSize(persisted))
        .isGreaterThan(entry.byteSize());
    assertThat(persisted.getExpiresAt()).isNull();
    verify(repository, never()).deleteExpired(eq(TENANT_ID), eq(GAME_INSTANCE_ID), eq(CHARACTER_ID), any());
    verify(repository, never())
        .updateExpiryByScope(eq(TENANT_ID), eq(GAME_INSTANCE_ID), eq(CHARACTER_ID), any());

    ArgumentCaptor<List<ScreenBufferService.BufferedEntry>> hotCacheEntries = ArgumentCaptor.captor();
    verify(hotCache).clear(TENANT_ID, GAME_INSTANCE_ID, CHARACTER_ID);
    verify(hotCache).append(eq(TENANT_ID), eq(GAME_INSTANCE_ID), eq(CHARACTER_ID), hotCacheEntries.capture());
    ScreenBufferService.BufferedEntry cached = hotCacheEntries.getValue().getFirst();
    assertThat(cached.text()).isEqualTo(entry.text());
    assertThat(cached.orderingToken()).isEqualTo(100L);
    assertThat(cached.byteSize()).isEqualTo(persisted.getByteSize());
  }

  @Test
  void getRehydratesTheHotCacheFromDurableStructuredEntries() {
    ResumeTranscriptEntry entry = entry(1L, "Recent room line\n", Instant.parse("2026-07-12T01:02:03Z"));
    entry.setOutputKind("VIEW");
    entry.setReplayPolicy("REPLAY");
    entry.setBriefRenderPolicy("FULL");
    entry.setPayloadType("look-view");
    entry.setPayloadJson("{\"room\":\"R-1\"}");
    persistedEntries.add(entry);

    ScreenBufferService.BufferedScreen screen =
        service.get(TENANT_ID, GAME_INSTANCE_ID, CHARACTER_ID).orElseThrow();

    assertThat(screen.protocolText()).isEqualTo("Recent room line\n");
    assertThat(screen.entries().getFirst().payloadJson()).isEqualTo("{\"room\":\"R-1\"}");
    assertThat(screen.entries().getFirst().orderingToken()).isEqualTo(1L);
    verify(hotCache).clear(TENANT_ID, GAME_INSTANCE_ID, CHARACTER_ID);
    verify(hotCache).append(TENANT_ID, GAME_INSTANCE_ID, CHARACTER_ID, screen.entries());
    verify(hotCache, never()).get(TENANT_ID, GAME_INSTANCE_ID, CHARACTER_ID);
  }

  @Test
  void getDoesNotReplayExpiredDurableEntriesAndInvalidatesTheHotCache() {
    assertThat(service.get(TENANT_ID, GAME_INSTANCE_ID, CHARACTER_ID)).isEmpty();

    verify(hotCache).clear(TENANT_ID, GAME_INSTANCE_ID, CHARACTER_ID);
    verify(repository, never()).deleteExpired(eq(TENANT_ID), eq(GAME_INSTANCE_ID), eq(CHARACTER_ID), any());
    verify(hotCache, never()).get(TENANT_ID, GAME_INSTANCE_ID, CHARACTER_ID);
  }

  @Test
  void appendTrimsOldestEntriesUsingExistingBufferBounds() {
    configureBuffer(0L, 256, 2, 1, 1_000, 2_000);
    persistedEntries.add(entry(1L, "old".repeat(350), Instant.parse("2026-07-12T01:00:00Z")));
    persistedEntries.add(entry(2L, "new".repeat(350), Instant.parse("2026-07-12T01:01:00Z")));

    service.append(
        TENANT_ID,
        GAME_INSTANCE_ID,
        CHARACTER_ID,
        List.of(ScreenBufferService.BufferedEntry.fromText("current")));

    verify(repository).deleteByIds(List.of(1L));
  }

  @Test
  void appendPersistsConfiguredEntryExpiry() {
    configureBuffer(1_000L, 256, 1, 1, 1_000, 2_000);
    ScreenBufferService.BufferedEntry entry =
        new ScreenBufferService.BufferedEntry(
            "Recent room line\n", 1, 17, 1_000L, null, null, null, null, null);

    service.append(TENANT_ID, GAME_INSTANCE_ID, CHARACTER_ID, List.of(entry));

    ArgumentCaptor<List<ResumeTranscriptEntry>> entries = ArgumentCaptor.captor();
    verify(repository).saveAll(entries.capture());
    assertThat(entries.getValue().getFirst().getExpiresAt()).isEqualTo(Instant.ofEpochMilli(2_000L));
    verify(repository)
        .updateExpiryByScope(TENANT_ID, GAME_INSTANCE_ID, CHARACTER_ID, Instant.ofEpochMilli(2_000L));
  }

  @Test
  void appendRefreshesTheExpiryForEveryRetainedEntryInTheScope() {
    configureBuffer(1_000L, 256, 1, 1, 1_000, 2_000);
    ResumeTranscriptEntry retained = entry(1L, "Earlier line\n", Instant.ofEpochMilli(1_000L));
    retained.setExpiresAt(Instant.ofEpochMilli(2_000L));
    persistedEntries.add(retained);
    ScreenBufferService.BufferedEntry newEntry =
        new ScreenBufferService.BufferedEntry(
            "Current line\n", 1, 13, 3_000L, null, null, null, null, null);

    service.append(TENANT_ID, GAME_INSTANCE_ID, CHARACTER_ID, List.of(newEntry));

    verify(repository)
        .updateExpiryByScope(TENANT_ID, GAME_INSTANCE_ID, CHARACTER_ID, Instant.ofEpochMilli(4_000L));
    ArgumentCaptor<List<ResumeTranscriptEntry>> entries = ArgumentCaptor.captor();
    verify(repository).saveAll(entries.capture());
    assertThat(entries.getValue().getFirst().getExpiresAt()).isEqualTo(Instant.ofEpochMilli(4_000L));
  }

  @Test
  void appendDropsSingleEntryWhoseCanonicalStructuredEnvelopeExceedsHardLimit() {
    ScreenBufferService.BufferedEntry oversized =
        ScreenBufferService.BufferedEntry.fromStructuredOutput(
            "short\n",
            "VIEW",
            "REPLAY",
            "FULL",
            "look-view",
            "{\"description\":\"" + "x".repeat(4_096) + "\"}");

    service.append(TENANT_ID, GAME_INSTANCE_ID, CHARACTER_ID, List.of(oversized));

    verify(repository, never()).saveAll(any());
    verify(repository, never())
        .updateExpiryByScope(eq(TENANT_ID), eq(GAME_INSTANCE_ID), eq(CHARACTER_ID), any());
    verify(hotCache, never()).append(anyLong(), anyLong(), anyLong(), any());
  }

  @Test
  void appendTrimsOldestEntriesWhenConfiguredEntryLimitIsExceeded() {
    configureBuffer(0L, 1, 1, 1, 1_000, 2_000);
    persistedEntries.add(entry(1L, "old", Instant.parse("2026-07-12T01:00:00Z")));
    persistedEntries.add(entry(2L, "new", Instant.parse("2026-07-12T01:01:00Z")));

    service.append(
        TENANT_ID,
        GAME_INSTANCE_ID,
        CHARACTER_ID,
        List.of(ScreenBufferService.BufferedEntry.fromText("current")));

    verify(repository).deleteByIds(List.of(1L, 2L));
  }

  @Test
  void appendReaccountsLegacyStructuredEntriesBeforeApplyingHardLimit() {
    ResumeTranscriptEntry legacy = entry(1L, "short\n", Instant.parse("2026-07-12T01:00:00Z"));
    legacy.setOutputKind("VIEW");
    legacy.setReplayPolicy("REPLAY");
    legacy.setBriefRenderPolicy("FULL");
    legacy.setPayloadType("look-view");
    legacy.setPayloadJson("{\"description\":\"" + "x".repeat(4_096) + "\"}");
    persistedEntries.add(legacy);

    service.append(
        TENANT_ID,
        GAME_INSTANCE_ID,
        CHARACTER_ID,
        List.of(ScreenBufferService.BufferedEntry.fromText("new\n")));

    assertThat(legacy.getByteSize()).isGreaterThan(2_000);
    verify(repository).updateByteSizes(List.of(legacy));
    verify(repository).deleteByIds(List.of(1L));
  }

  private void configureBuffer(
      long ttlMs,
      int maxEntries,
      int minMessages,
      int minLines,
      int softMaxBytes,
      int hardMaxBytes) {
    when(settingsResolver.resolve(TENANT_ID, GAME_INSTANCE_ID))
        .thenReturn(
            new FiremudReconnectionProperties(
                new FiremudReconnectionProperties.Policy(180_000L, true),
                new FiremudReconnectionProperties.Buffer(
                    ttlMs, maxEntries, minMessages, minLines, softMaxBytes, hardMaxBytes)));
  }

  private ResumeTranscriptEntry entry(Long id, String text, Instant appendedAt) {
    ResumeTranscriptEntry entry = new ResumeTranscriptEntry();
    entry.setId(id);
    entry.setTenantId(TENANT_ID);
    entry.setGameInstanceId(GAME_INSTANCE_ID);
    entry.setCharacterId(CHARACTER_ID);
    entry.setProtocolText(text);
    entry.setLineCount(1);
    entry.setByteSize(text.length());
    entry.setAppendedAt(appendedAt);
    return entry;
  }
}
