package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  private final ResumeTranscriptEntryRepository repository;
  private final ReconnectionSettingsResolver settingsResolver;
  private final ScreenBufferService hotCache;

  public DurableScreenBufferService(
      ResumeTranscriptEntryRepository repository,
      ReconnectionSettingsResolver settingsResolver,
      ScreenBufferService hotCache) {
    this.repository = repository;
    this.settingsResolver = settingsResolver;
    this.hotCache = hotCache;
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
    if (buffer.ttlMs() > 0L) {
      expireExpiredEntries(tenantId, gameInstanceId, characterId);
    }
    Instant scopeExpiresAt = scopeExpiresAt(filtered, buffer);
    repository.updateExpiryByScope(tenantId, gameInstanceId, characterId, scopeExpiresAt);
    repository.saveAll(
        filtered.stream()
            .map(entry -> toEntity(tenantId, gameInstanceId, characterId, entry, scopeExpiresAt))
            .toList());
    trim(tenantId, gameInstanceId, characterId, buffer);
    appendToHotCache(tenantId, gameInstanceId, characterId, filtered);
  }

  @Override
  @Transactional
  public Optional<BufferedScreen> get(long tenantId, long gameInstanceId, long characterId) {
    // Redis only caches the durable scope and cannot authoritatively enforce its inactivity expiry.
    List<ResumeTranscriptEntry> entries =
        repository.findActiveByScope(tenantId, gameInstanceId, characterId, Instant.now());
    if (entries.isEmpty()) {
      clearHotCache(tenantId, gameInstanceId, characterId);
      return Optional.empty();
    }
    List<BufferedEntry> bufferedEntries = entries.stream().map(this::toBufferedEntry).toList();
    appendToHotCache(tenantId, gameInstanceId, characterId, bufferedEntries);
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

  private boolean expireExpiredEntries(long tenantId, long gameInstanceId, long characterId) {
    return repository.deleteExpired(tenantId, gameInstanceId, characterId, Instant.now()) > 0;
  }

  private void trim(
      long tenantId,
      long gameInstanceId,
      long characterId,
      FiremudReconnectionProperties.Buffer buffer) {
    Deque<ResumeTranscriptEntry> retained =
        new ArrayDeque<>(
            repository.findActiveByScope(tenantId, gameInstanceId, characterId, Instant.now()));
    List<Long> discardedIds = new ArrayList<>();
    Map<ResumeTranscriptEntry, Integer> entryByteSizes = new IdentityHashMap<>();
    int currentBytes = 0;
    int currentLines = 0;
    for (ResumeTranscriptEntry entry : retained) {
      int entryByteSize =
          toBufferedEntry(entry).canonicalByteSize(tenantId, gameInstanceId, characterId);
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
    if (buffer.ttlMs() == 0L) {
      return null;
    }
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
        entry.getPayloadJson());
  }

  private void appendToHotCache(
      long tenantId, long gameInstanceId, long characterId, List<BufferedEntry> entries) {
    try {
      hotCache.append(tenantId, gameInstanceId, characterId, entries);
    } catch (RuntimeException ex) {
      log.warn("Failed to update reconnect transcript cache", ex);
    }
  }

  private void clearHotCache(long tenantId, long gameInstanceId, long characterId) {
    try {
      hotCache.clear(tenantId, gameInstanceId, characterId);
    } catch (RuntimeException ex) {
      log.warn("Failed to clear reconnect transcript cache", ex);
    }
  }
}
