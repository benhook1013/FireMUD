package net.firedevops.firemud.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Operator defaults for tenant/game-controlled standard player-command capabilities. */
@ConfigurationProperties(prefix = "firemud.command-capabilities")
public record FiremudCommandCapabilitiesProperties(
    @DefaultValue("true") boolean socialEnabled,
    @DefaultValue("true") boolean presenceEnabled,
    @DefaultValue("true") boolean inventoryEnabled,
    @DefaultValue("true") boolean commandHistoryEnabled) {}
