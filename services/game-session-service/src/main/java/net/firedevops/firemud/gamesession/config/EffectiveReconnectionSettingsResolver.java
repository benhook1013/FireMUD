package net.firedevops.firemud.gamesession.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.config.FiremudReconnectionProperties;
import net.firedevops.firemud.common.config.ReconnectionSettingsResolver;
import net.firedevops.firemud.common.settings.ScopedSettingsOverrides;
import net.firedevops.firemud.common.settings.SharedEffectiveSettingsResolver;
import net.firedevops.firemud.common.settings.SharedSettingsAuthorityReader;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification =
        "Constructor validation fails fast for injected settings collaborators without exposing a"
            + " partially initialized resolver.")
@Component
public class EffectiveReconnectionSettingsResolver implements ReconnectionSettingsResolver {
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
    SharedEffectiveSettingsResolver.ResolvedScopedSettings persistedOverrides =
        context == null || context.tenantId() <= 0L
            ? new SharedEffectiveSettingsResolver.ResolvedScopedSettings(
                ScopedSettingsOverrides.empty(),
                ScopedSettingsOverrides.empty(),
                ScopedSettingsOverrides.empty())
            : sharedEffectiveSettingsResolver.resolve(
                context.tenantId(), resolveGameInstanceId(context));
    FiremudReconnectionProperties effective =
        validateEffective(merge(defaults, persistedOverrides.effectiveOverrides().reconnection()));
    List<String> sources = new ArrayList<>();
    sources.add("operatorDefaults");
    sources.addAll(
        persistedOverrides.sourcesFor(
            ScopedSettingsOverrides.SettingsDomain.RECONNECTION,
            context == null ? 0L : context.tenantId(),
            resolveGameInstanceId(context)));
    return new ResolvedValue<>(effective, sources);
  }

  @Override
  public FiremudReconnectionProperties resolve(long tenantId, long gameInstanceId) {
    return resolvedReconnection(
            new SessionContext(
                0L, tenantId, 0L, null, 0L, null, gameInstanceId, null, null, null, gameInstanceId))
        .effective();
  }

  public record ResolvedValue<T>(T effective, List<String> sources) {
    public ResolvedValue {
      sources = sources == null ? List.of() : List.copyOf(sources);
    }
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

  private FiremudReconnectionProperties validateEffective(FiremudReconnectionProperties effective) {
    if (effective.buffer().hardMaxBytes() < effective.buffer().softMaxBytes()) {
      throw new IllegalStateException(
          "Effective reconnection buffer hardMaxBytes must be at least softMaxBytes");
    }
    return effective;
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
