package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.common.config.FiremudReconnectionProperties;
import net.firedevops.firemud.common.config.ReconnectionSettingsResolver;
import net.firedevops.firemud.gamesession.entity.ResumeTranscriptEntry;
import net.firedevops.firemud.gamesession.repository.ResumeTranscriptEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Durable source of truth for reconnect context, with Redis retained only as an optional hot cache.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "Injected repository, settings resolver, and cache are shared Spring collaborators.")
public class DurableScreenBufferService implements ScreenBufferService {
  private static final Logger log = LoggerFactory.getLogger(DurableScreenBufferService.class);
  private static final long NO_TTL_MILLIS = 0L;
  private static final String HOT_CACHE_SYNC_FAILURES_METRIC =
      "gamesession.reconnect_transcript.hot_cache_sync_failures";

  private final ResumeTranscriptEntryRepository repository;
  private final ReconnectionSettingsResolver settingsResolver;
  private final ScreenBufferService hotCache;
  private final Counter replaceHotCacheFailureCounter;
  private final Counter clearHotCacheFailureCounter;

  public DurableScreenBufferService(
      ResumeTranscriptEntryRepository repository,
      ReconnectionSettingsResolver settingsResolver,
      ScreenBufferService hotCache,
      MeterRegistry meterRegistry) {
    this.repository = repository;
    this.settingsResolver = settingsResolver;
    this.hotCache = hotCache;
    this.replaceHotCacheFailureCounter =
        meterRegistry.counter(HOT_CACHE_SYNC_FAILURES_METRIC, "operation", "replace");
    this.clearHotCacheFailureCounter =
        meterRegistry.counter(HOT_CACHE_SYNC_FAILURES_METRIC, "operation", "clear");
  }

  @Override
  @Transactional
  public void append(
      long tenantId, long gameInstanceId, long characterId, List<BufferedEntry> entries) {
    List<BufferedEntry> filtered =
        entries == null
            ? List.of()
            : entries.stream()
                .filter(entry -> StringUtils.hasText(entry.text()))
                .map(entry -> entry.withCanonicalByteSize(tenantId, gameInstanceId, characterId))
                .toList();
    if (filtered.isEmpty()) {
      return;
    }

    FiremudReconnectionProperties.Buffer buffer =
        settingsResolver.resolve(tenantId, gameInstanceId).buffer();
    repository.lockScope(tenantId, gameInstanceId, characterId);
    if (buffer.ttlMs() > NO_TTL_MILLIS) {
      expireExpiredEntries(tenantId, gameInstanceId, characterId);
    } else {
      repository.updateExpiryByScope(tenantId, gameInstanceId, characterId, null);
    }
    Instant scopeExpiresAt =
        buffer.ttlMs() > NO_TTL_MILLIS ? scopeExpiresAt(filtered, buffer) : null;
    List<ResumeTranscriptEntry> retainedEntries =
        filtered.stream()
            .map(entry -> toEntity(tenantId, gameInstanceId, characterId, entry, scopeExpiresAt))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    repository.assignOrderingTokens(retainedEntries);
    retainedEntries.forEach(
        entry -> entry.setByteSize(ResumeTranscriptEntryCanonicalizer.byteSize(entry)));
    if (scopeExpiresAt != null) {
      repository.updateExpiryByScope(tenantId, gameInstanceId, characterId, scopeExpiresAt);
    }
    if (retainedEntries.isEmpty()) {
      replaceHotCache(
          tenantId,
          gameInstanceId,
          characterId,
          trim(tenantId, gameInstanceId, characterId, buffer, Set.of()).stream()
              .map(this::toBufferedEntry)
              .toList());
      return;
    }
    repository.saveAll(retainedEntries);
    Set<Long> freshlyCanonicalEntryIds =
        retainedEntries.stream()
            .map(ResumeTranscriptEntry::getId)
            .collect(java.util.stream.Collectors.toSet());
    List<ResumeTranscriptEntry> effectiveEntries =
        trim(tenantId, gameInstanceId, characterId, buffer, freshlyCanonicalEntryIds);
    replaceHotCache(
        tenantId,
        gameInstanceId,
        characterId,
        effectiveEntries.stream().map(this::toBufferedEntry).toList());
  }

  @Override
  @Transactional
  public Optional<BufferedScreen> get(long tenantId, long gameInstanceId, long characterId) {
    // Redis only caches the durable scope and cannot authoritatively enforce its inactivity expiry.
    FiremudReconnectionProperties.Buffer buffer =
        settingsResolver.resolve(tenantId, gameInstanceId).buffer();
    repository.lockScope(tenantId, gameInstanceId, characterId);
    if (buffer.ttlMs() == NO_TTL_MILLIS) {
      repository.updateExpiryByScope(tenantId, gameInstanceId, characterId, null);
    }
    List<ResumeTranscriptEntry> entries =
        trim(tenantId, gameInstanceId, characterId, buffer, Set.of());
    if (entries.isEmpty()) {
      clearHotCache(tenantId, gameInstanceId, characterId);
      return Optional.empty();
    }
    List<BufferedEntry> bufferedEntries = entries.stream().map(this::toBufferedEntry).toList();
    replaceHotCache(tenantId, gameInstanceId, characterId, bufferedEntries);
    return Optional.of(
        new BufferedScreen(
            bufferedEntries,
            bufferedEntries.size(),
            bufferedEntries.stream().mapToInt(BufferedEntry::lineCount).sum(),
            entries.get(entries.size() - 1).getAppendedAt().toEpochMilli()));
  }

  @Override
  @Transactional
  public void clear(long tenantId, long gameInstanceId, long characterId) {
    repository.lockScope(tenantId, gameInstanceId, characterId);
    repository.deleteByScope(tenantId, gameInstanceId, characterId);
    clearHotCache(tenantId, gameInstanceId, characterId);
  }

  @Override
  @Transactional
  public void replace(
      long tenantId, long gameInstanceId, long characterId, List<BufferedEntry> entries) {
    repository.lockScope(tenantId, gameInstanceId, characterId);
    repository.deleteByScope(tenantId, gameInstanceId, characterId);
    if (entries == null || entries.stream().noneMatch(entry -> StringUtils.hasText(entry.text()))) {
      clearHotCache(tenantId, gameInstanceId, characterId);
      return;
    }
    append(tenantId, gameInstanceId, characterId, entries);
  }

  private boolean expireExpiredEntries(long tenantId, long gameInstanceId, long characterId) {
    return repository.deleteExpired(tenantId, gameInstanceId, characterId, Instant.now()) > 0;
  }

  private List<ResumeTranscriptEntry> trim(
      long tenantId,
      long gameInstanceId,
      long characterId,
      FiremudReconnectionProperties.Buffer buffer,
      Set<Long> freshlyCanonicalEntryIds) {
    Deque<ResumeTranscriptEntry> retained =
        new ArrayDeque<>(
            repository.findActiveByScope(tenantId, gameInstanceId, characterId, Instant.now()));
    List<ResumeTranscriptEntry> reaccounted = new ArrayList<>();
    for (ResumeTranscriptEntry entry : retained) {
      if (freshlyCanonicalEntryIds.contains(entry.getId())) {
        continue;
      }
      int canonicalByteSize = ResumeTranscriptEntryCanonicalizer.byteSize(entry);
      if (entry.getByteSize() != canonicalByteSize) {
        entry.setByteSize(canonicalByteSize);
        reaccounted.add(entry);
      }
    }
    if (!reaccounted.isEmpty()) {
      repository.updateByteSizes(reaccounted);
    }
    List<Long> discardedIds = new ArrayList<>();
    while (retained.size() > buffer.maxEntries()) {
      discardedIds.add(retained.removeFirst().getId());
    }
    Map<ResumeTranscriptEntry, Integer> entryByteSizes = new IdentityHashMap<>();
    int currentBytes = 0;
    int currentLines = 0;
    for (ResumeTranscriptEntry entry : retained) {
      int entryByteSize = entry.getByteSize();
      entryByteSizes.put(entry, entryByteSize);
      currentBytes += entryByteSize;
      currentLines += entry.getLineCount();
    }
    while (retained.size() > 1
        && currentBytes > buffer.softMaxBytes()
        && retained.size() > buffer.minMessages()
        && currentLines > buffer.minLines()) {
      ResumeTranscriptEntry removed = retained.removeFirst();
      discardedIds.add(removed.getId());
      currentBytes -= entryByteSizes.get(removed);
      currentLines -= removed.getLineCount();
    }
    while (retained.size() > 1 && currentBytes > buffer.hardMaxBytes()) {
      ResumeTranscriptEntry removed = retained.removeFirst();
      discardedIds.add(removed.getId());
      currentBytes -= entryByteSizes.get(removed);
    }
    if (!discardedIds.isEmpty()) {
      repository.deleteByIds(discardedIds);
    }
    return List.copyOf(retained);
  }

  private ResumeTranscriptEntry toEntity(
      long tenantId,
      long gameInstanceId,
      long characterId,
      BufferedEntry entry,
      Instant expiresAt) {
    ResumeTranscriptEntry entity = new ResumeTranscriptEntry();
    entity.setTenantId(tenantId);
    entity.setGameInstanceId(gameInstanceId);
    entity.setCharacterId(characterId);
    entity.setProtocolText(entry.text());
    entity.setLineCount(entry.lineCount());
    entity.setByteSize(entry.byteSize());
    entity.setAppendedAt(Instant.ofEpochMilli(entry.appendedAtMs()));
    entity.setExpiresAt(expiresAt);
    entity.setOutputKind(entry.outputKind());
    entity.setReplayPolicy(entry.replayPolicy());
    entity.setBriefRenderPolicy(entry.briefRenderPolicy());
    entity.setPayloadType(entry.payloadType());
    entity.setPayloadJson(entry.payloadJson());
    return entity;
  }

  private Instant scopeExpiresAt(
      List<BufferedEntry> entries, FiremudReconnectionProperties.Buffer buffer) {
    long latestAppendedAtMs =
        entries.stream().mapToLong(BufferedEntry::appendedAtMs).max().orElseThrow();
    return Instant.ofEpochMilli(latestAppendedAtMs).plusMillis(buffer.ttlMs());
  }

  private BufferedEntry toBufferedEntry(ResumeTranscriptEntry entry) {
    return new BufferedEntry(
        entry.getProtocolText(),
        entry.getLineCount(),
        entry.getByteSize(),
        entry.getAppendedAt().toEpochMilli(),
        entry.getOutputKind(),
        entry.getReplayPolicy(),
        entry.getBriefRenderPolicy(),
        entry.getPayloadType(),
        entry.getPayloadJson(),
        entry.getId() == null ? 0L : entry.getId());
  }

  private void replaceHotCache(
      long tenantId, long gameInstanceId, long characterId, List<BufferedEntry> entries) {
    try {
      hotCache.replace(tenantId, gameInstanceId, characterId, entries);
    } catch (RuntimeException ex) {
      replaceHotCacheFailureCounter.increment();
      log.warn("Failed to replace reconnect transcript cache", ex);
    }
  }

  private void clearHotCache(long tenantId, long gameInstanceId, long characterId) {
    try {
      hotCache.clear(tenantId, gameInstanceId, characterId);
    } catch (RuntimeException ex) {
      clearHotCacheFailureCounter.increment();
      log.warn("Failed to clear reconnect transcript cache", ex);
    }
  }
}
