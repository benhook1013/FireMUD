package net.firedevops.firemud.gamesession.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.config.FiremudReconnectionProperties;
import net.firedevops.firemud.common.config.ReconnectionSettingsResolver;
import net.firedevops.firemud.common.settings.ScopedSettingsOverrides;
import net.firedevops.firemud.common.settings.SharedEffectiveSettingsResolver;
import net.firedevops.firemud.common.settings.SharedSettingsAuthorityReader;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification =
        "Constructor validation fails fast for injected settings collaborators without exposing a"
            + " partially initialized resolver.")
@Component
public class EffectiveReconnectionSettingsResolver implements ReconnectionSettingsResolver {
  private static final String INVALID_BYTE_BOUNDS_REASON =
      "effective buffer hardMaxBytes must be at least softMaxBytes";
  private static final int MAX_DIAGNOSTICS = 2;
  private static final Logger logger =
      LoggingUtil.getLogger(EffectiveReconnectionSettingsResolver.class);

  private final FiremudReconnectionProperties defaults;
  private final SharedEffectiveSettingsResolver sharedEffectiveSettingsResolver;

  @Autowired
  public EffectiveReconnectionSettingsResolver(
      FiremudReconnectionProperties defaults,
      SharedEffectiveSettingsResolver sharedEffectiveSettingsResolver) {
    this.defaults = Objects.requireNonNull(defaults, "defaults must not be null");
    this.sharedEffectiveSettingsResolver =
        Objects.requireNonNull(
            sharedEffectiveSettingsResolver, "sharedEffectiveSettingsResolver must not be null");
    validateDefaultSettings();
  }

  public EffectiveReconnectionSettingsResolver(
      FiremudReconnectionProperties defaults,
      SharedSettingsAuthorityReader settingsAuthorityReader) {
    this(defaults, new SharedEffectiveSettingsResolver(settingsAuthorityReader));
  }

  public FiremudReconnectionProperties reconnection(SessionContext context) {
    return resolvedReconnection(context).effective();
  }

  public ResolvedValue<FiremudReconnectionProperties> resolvedReconnection(SessionContext context) {
    long tenantId = context == null ? 0L : context.tenantId();
    Long gameInstanceId = resolveGameInstanceId(context);
    SharedEffectiveSettingsResolver.ResolvedScopedSettings persistedOverrides =
        tenantId <= 0L
            ? new SharedEffectiveSettingsResolver.ResolvedScopedSettings(
                ScopedSettingsOverrides.empty(),
                ScopedSettingsOverrides.empty(),
                ScopedSettingsOverrides.empty())
            : sharedEffectiveSettingsResolver.resolve(tenantId, gameInstanceId);
    List<String> sources = new ArrayList<>();
    sources.add("operatorDefaults");
    List<String> diagnostics = new ArrayList<>(MAX_DIAGNOSTICS);
    FiremudReconnectionProperties effective =
        applyLayer(
            defaults,
            persistedOverrides.tenantOverrides().reconnection(),
            "tenantPersistedOverride:" + tenantId,
            sources,
            diagnostics,
            tenantId,
            gameInstanceId);
    effective =
        applyLayer(
            effective,
            persistedOverrides.gameInstanceOverrides().reconnection(),
            "gameInstancePersistedOverride:" + gameInstanceId,
            sources,
            diagnostics,
            tenantId,
            gameInstanceId);
    return new ResolvedValue<>(effective, sources, diagnostics);
  }

  @Override
  public FiremudReconnectionProperties resolve(long tenantId, long gameInstanceId) {
    return resolvedReconnection(
            new SessionContext(
                0L, tenantId, 0L, null, 0L, null, gameInstanceId, null, null, null, gameInstanceId))
        .effective();
  }

  public record ResolvedValue<T>(T effective, List<String> sources, List<String> diagnostics) {
    public ResolvedValue(T effective, List<String> sources) {
      this(effective, sources, List.of());
    }

    public ResolvedValue {
      sources = sources == null ? List.of() : List.copyOf(sources);
      diagnostics =
          diagnostics == null
              ? List.of()
              : List.copyOf(diagnostics.stream().limit(MAX_DIAGNOSTICS).toList());
    }
  }

  private FiremudReconnectionProperties applyLayer(
      FiremudReconnectionProperties base,
      ScopedSettingsOverrides.ReconnectionOverride override,
      String source,
      List<String> sources,
      List<String> diagnostics,
      long tenantId,
      Long gameInstanceId) {
    if (override == null || override.isEmpty()) {
      return base;
    }
    FiremudReconnectionProperties candidate = merge(base, override);
    if (!hasValidByteBounds(candidate)) {
      String diagnostic = "Ignored " + source + " override: " + INVALID_BYTE_BOUNDS_REASON;
      if (diagnostics.size() < MAX_DIAGNOSTICS) {
        diagnostics.add(diagnostic);
      }
      logger.warn(
          "Ignoring invalid persisted reconnection override "
              + "source={} tenantId={} gameInstanceId={} reason={}",
          source,
          tenantId,
          gameInstanceId,
          INVALID_BYTE_BOUNDS_REASON);
      return base;
    }
    sources.add(source);
    return candidate;
  }

  private FiremudReconnectionProperties merge(
      FiremudReconnectionProperties base, ScopedSettingsOverrides.ReconnectionOverride override) {
    if (override == null) {
      return base;
    }
    FiremudReconnectionProperties.Policy policy =
        override.policy() == null
            ? base.policy()
            : new FiremudReconnectionProperties.Policy(
                override.policy().resumeWindowMs() != null
                    ? override.policy().resumeWindowMs()
                    : base.policy().resumeWindowMs(),
                override.policy().staleResumeFallsThroughToFreshEntry() != null
                    ? override.policy().staleResumeFallsThroughToFreshEntry()
                    : base.policy().staleResumeFallsThroughToFreshEntry());
    FiremudReconnectionProperties.Buffer buffer =
        override.buffer() == null
            ? base.buffer()
            : new FiremudReconnectionProperties.Buffer(
                override.buffer().ttlMs() != null
                    ? override.buffer().ttlMs()
                    : base.buffer().ttlMs(),
                override.buffer().maxEntries() != null
                    ? override.buffer().maxEntries()
                    : base.buffer().maxEntries(),
                override.buffer().minMessages() != null
                    ? override.buffer().minMessages()
                    : base.buffer().minMessages(),
                override.buffer().minLines() != null
                    ? override.buffer().minLines()
                    : base.buffer().minLines(),
                override.buffer().softMaxBytes() != null
                    ? override.buffer().softMaxBytes()
                    : base.buffer().softMaxBytes(),
                override.buffer().hardMaxBytes() != null
                    ? override.buffer().hardMaxBytes()
                    : base.buffer().hardMaxBytes());
    return new FiremudReconnectionProperties(policy, buffer);
  }

  private void validateDefaultSettings() {
    if (!hasValidByteBounds(defaults)) {
      throw new IllegalStateException(
          "Operator reconnection buffer hardMaxBytes must be at least softMaxBytes");
    }
  }

  private boolean hasValidByteBounds(FiremudReconnectionProperties effective) {
    return effective.buffer().hardMaxBytes() >= effective.buffer().softMaxBytes();
  }

  private Long resolveGameInstanceId(SessionContext context) {
    if (context == null) {
      return null;
    }
    if (context.gameInstanceId() > 0L) {
      return context.gameInstanceId();
    }
    if (context.bootstrapGameInstanceId() > 0L) {
      return context.bootstrapGameInstanceId();
    }
    return null;
  }
}
