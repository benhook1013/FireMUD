package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
            : entries.stream().filter(entry -> StringUtils.hasText(entry.text())).toList();
    if (filtered.isEmpty()) {
      return;
    }

    FiremudReconnectionProperties.Buffer buffer =
        settingsResolver.resolve(tenantId, gameInstanceId).buffer();
    expireIfConfigured(tenantId, gameInstanceId, characterId, buffer);
    repository.saveAll(
        filtered.stream()
            .map(entry -> toEntity(tenantId, gameInstanceId, characterId, entry))
            .toList());
    trim(tenantId, gameInstanceId, characterId, buffer);
    appendToHotCache(tenantId, gameInstanceId, characterId, filtered);
  }

  @Override
  @Transactional
  public Optional<BufferedScreen> get(long tenantId, long gameInstanceId, long characterId) {
    FiremudReconnectionProperties.Buffer buffer =
        settingsResolver.resolve(tenantId, gameInstanceId).buffer();
    boolean expired = expireIfConfigured(tenantId, gameInstanceId, characterId, buffer);
    if (expired) {
      clearHotCache(tenantId, gameInstanceId, characterId);
    } else {
      Optional<BufferedScreen> cached = getFromHotCache(tenantId, gameInstanceId, characterId);
      if (cached.isPresent()) {
        return cached;
      }
    }

    List<ResumeTranscriptEntry> entries =
        repository.findByScope(tenantId, gameInstanceId, characterId);
    if (entries.isEmpty()) {
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
    repository.deleteByScope(tenantId, gameInstanceId, characterId);
    clearHotCache(tenantId, gameInstanceId, characterId);
  }

  private boolean expireIfConfigured(
      long tenantId,
      long gameInstanceId,
      long characterId,
      FiremudReconnectionProperties.Buffer buffer) {
    if (buffer.ttlMs() == 0L) {
      return false;
    }
    return repository.deleteExpired(
            tenantId, gameInstanceId, characterId, Instant.now().minusMillis(buffer.ttlMs()))
        > 0;
  }

  private void trim(
      long tenantId,
      long gameInstanceId,
      long characterId,
      FiremudReconnectionProperties.Buffer buffer) {
    List<ResumeTranscriptEntry> retained =
        new ArrayList<>(repository.findByScope(tenantId, gameInstanceId, characterId));
    List<Long> discardedIds = new ArrayList<>();
    while (retained.size() > 1
        && totalBytes(retained) > buffer.softMaxBytes()
        && retained.size() > buffer.minMessages()
        && totalLines(retained) > buffer.minLines()) {
      discardedIds.add(retained.remove(0).getId());
    }
    while (retained.size() > 1 && totalBytes(retained) > buffer.hardMaxBytes()) {
      discardedIds.add(retained.remove(0).getId());
    }
    repository.deleteByIds(discardedIds);
  }

  private int totalBytes(List<ResumeTranscriptEntry> entries) {
    return entries.stream().mapToInt(ResumeTranscriptEntry::getByteSize).sum();
  }

  private int totalLines(List<ResumeTranscriptEntry> entries) {
    return entries.stream().mapToInt(ResumeTranscriptEntry::getLineCount).sum();
  }

  private ResumeTranscriptEntry toEntity(
      long tenantId, long gameInstanceId, long characterId, BufferedEntry entry) {
    ResumeTranscriptEntry entity = new ResumeTranscriptEntry();
    entity.setTenantId(tenantId);
    entity.setGameInstanceId(gameInstanceId);
    entity.setCharacterId(characterId);
    entity.setProtocolText(entry.text());
    entity.setLineCount(entry.lineCount());
    entity.setByteSize(entry.byteSize());
    entity.setAppendedAt(Instant.ofEpochMilli(entry.appendedAtMs()));
    entity.setOutputKind(entry.outputKind());
    entity.setReplayPolicy(entry.replayPolicy());
    entity.setBriefRenderPolicy(entry.briefRenderPolicy());
    entity.setPayloadType(entry.payloadType());
    entity.setPayloadJson(entry.payloadJson());
    return entity;
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

  private Optional<BufferedScreen> getFromHotCache(
      long tenantId, long gameInstanceId, long characterId) {
    try {
      return hotCache.get(tenantId, gameInstanceId, characterId);
    } catch (RuntimeException ex) {
      log.warn("Failed to read reconnect transcript cache", ex);
      return Optional.empty();
    }
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
