package net.firedevops.firemud.gamelogic.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.settings.ScopedSettingsOverrides;
import net.firedevops.firemud.common.settings.SharedEffectiveSettingsResolver;
import net.firedevops.firemud.common.settings.SharedSettingsAuthorityReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Bounded effective-settings read path for the surfaced communication domain. */
@Component
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification = "Fail-fast startup is intentional if communication settings wiring is invalid.")
public class EffectiveCommunicationSettingsResolver {
  private final CommunicationProperties communicationDefaults;
  private final SharedEffectiveSettingsResolver sharedEffectiveSettingsResolver;

  @Autowired
  public EffectiveCommunicationSettingsResolver(
      CommunicationProperties communicationDefaults,
      SharedEffectiveSettingsResolver sharedEffectiveSettingsResolver) {
    this.communicationDefaults =
        Objects.requireNonNull(communicationDefaults, "communicationDefaults must not be null");
    this.sharedEffectiveSettingsResolver =
        Objects.requireNonNull(
            sharedEffectiveSettingsResolver, "sharedEffectiveSettingsResolver must not be null");
  }

  public EffectiveCommunicationSettingsResolver(
      CommunicationProperties communicationDefaults,
      SharedSettingsAuthorityReader settingsAuthorityReader) {
    this(communicationDefaults, new SharedEffectiveSettingsResolver(settingsAuthorityReader));
  }

  public CommunicationProperties communication() {
    return communication(null, null);
  }

  public CommunicationProperties communication(Long tenantId, Long gameInstanceId) {
    return resolvedCommunication(tenantId, gameInstanceId).effective();
  }

  public ResolvedValue<CommunicationProperties> resolvedCommunication() {
    return resolvedCommunication(null, null);
  }

  public ResolvedValue<CommunicationProperties> resolvedCommunication(
      Long tenantId, Long gameInstanceId) {
    if (tenantId == null || tenantId <= 0L) {
      return new ResolvedValue<>(communicationDefaults, List.of("operatorDefaults"));
    }
    SharedEffectiveSettingsResolver.ResolvedScopedSettings persistedOverrides =
        sharedEffectiveSettingsResolver.resolve(tenantId, gameInstanceId);
    ScopedSettingsOverrides.CommunicationOverride override =
        persistedOverrides.effectiveOverrides().communication();
    if (override == null) {
      return new ResolvedValue<>(communicationDefaults, List.of("operatorDefaults"));
    }
    return new ResolvedValue<>(
        merge(communicationDefaults, override),
        sources(persistedOverrides, tenantId, gameInstanceId));
  }

  private List<String> sources(
      SharedEffectiveSettingsResolver.ResolvedScopedSettings persistedOverrides,
      long tenantId,
      Long gameInstanceId) {
    java.util.ArrayList<String> sources = new java.util.ArrayList<>();
    sources.add("operatorDefaults");
    sources.addAll(
        persistedOverrides.sourcesFor(
            ScopedSettingsOverrides.SettingsDomain.COMMUNICATION, tenantId, gameInstanceId));
    return List.copyOf(sources);
  }

  private CommunicationProperties merge(
      CommunicationProperties base, ScopedSettingsOverrides.CommunicationOverride override) {
    return new CommunicationProperties(
        override.maxMessageLength() != null ? override.maxMessageLength() : base.maxMessageLength(),
        override.whisperObserverMetadataEnabled() != null
            ? override.whisperObserverMetadataEnabled()
            : base.whisperObserverMetadataEnabled());
  }

  public record ResolvedValue<T>(T effective, List<String> sources) {
    public ResolvedValue {
      sources = sources == null ? List.of() : List.copyOf(sources);
    }
  }
}
