package net.firedevops.firemud.worldmanagement.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers world-related configuration properties. */
@Configuration
@EnableConfigurationProperties(WorldProperties.class)
public class WorldConfig {}
