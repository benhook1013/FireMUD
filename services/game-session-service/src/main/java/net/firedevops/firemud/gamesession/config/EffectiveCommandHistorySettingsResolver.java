package net.firedevops.firemud.gamesession.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.config.FiremudCommandHistoryProperties;
import net.firedevops.firemud.common.settings.ScopedSettingsOverrides;
import net.firedevops.firemud.common.settings.SharedEffectiveSettingsResolver;
import net.firedevops.firemud.common.settings.SharedSettingsAuthorityReader;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Resolves the platform and tenant/game command-history retention policy. */
@Component
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification = "Constructor validation only guards injected collaborators before resolution.")
public class EffectiveCommandHistorySettingsResolver {
  private final FiremudCommandHistoryProperties defaults;
  private final SharedEffectiveSettingsResolver sharedEffectiveSettingsResolver;

  @Autowired
  public EffectiveCommandHistorySettingsResolver(
      FiremudCommandHistoryProperties defaults,
      SharedEffectiveSettingsResolver sharedEffectiveSettingsResolver) {
    this.defaults = Objects.requireNonNull(defaults, "defaults must not be null");
    this.sharedEffectiveSettingsResolver =
        Objects.requireNonNull(
            sharedEffectiveSettingsResolver, "sharedEffectiveSettingsResolver must not be null");
  }

  public EffectiveCommandHistorySettingsResolver(
      FiremudCommandHistoryProperties defaults,
      SharedSettingsAuthorityReader settingsAuthorityReader) {
    this(defaults, new SharedEffectiveSettingsResolver(settingsAuthorityReader));
  }

  public FiremudCommandHistoryProperties commandHistory(SessionContext context) {
    return resolvedCommandHistory(context).effective();
  }

  public FiremudCommandHistoryProperties commandHistory(long tenantId, long gameInstanceId) {
    return resolvedCommandHistory(tenantId, gameInstanceId > 0L ? gameInstanceId : null)
        .effective();
  }

  public ResolvedValue<FiremudCommandHistoryProperties> resolvedCommandHistory(
      SessionContext context) {
    return resolvedCommandHistory(
        context == null ? 0L : context.tenantId(), resolveGameInstanceId(context));
  }

  private ResolvedValue<FiremudCommandHistoryProperties> resolvedCommandHistory(
      long tenantId, Long gameInstanceId) {
    SharedEffectiveSettingsResolver.ResolvedScopedSettings persistedOverrides =
        sharedEffectiveSettingsResolver.resolve(tenantId, gameInstanceId);
    FiremudCommandHistoryProperties effective =
        merge(defaults, persistedOverrides.effectiveOverrides().commandHistory());
    List<String> sources = new java.util.ArrayList<>(List.of("operatorDefaults"));
    sources.addAll(
        persistedOverrides.sourcesFor(
            ScopedSettingsOverrides.SettingsDomain.COMMAND_HISTORY, tenantId, gameInstanceId));
    return new ResolvedValue<>(effective, sources);
  }

  private FiremudCommandHistoryProperties merge(
      FiremudCommandHistoryProperties base,
      ScopedSettingsOverrides.CommandHistoryOverride override) {
    if (override == null) {
      return base;
    }
    return new FiremudCommandHistoryProperties(
        override.maxEntries() != null ? override.maxEntries() : base.maxEntries());
  }

  private Long resolveGameInstanceId(SessionContext context) {
    if (context == null) {
      return null;
    }
    if (context.gameInstanceId() > 0L) {
      return context.gameInstanceId();
    }
    return context.bootstrapGameInstanceId() > 0L ? context.bootstrapGameInstanceId() : null;
  }

  public record ResolvedValue<T>(T effective, List<String> sources) {
    public ResolvedValue {
      sources = sources == null ? List.of() : List.copyOf(sources);
    }
  }
}
