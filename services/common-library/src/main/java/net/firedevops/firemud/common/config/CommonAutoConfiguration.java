package net.firedevops.firemud.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ServiceEndpointsProperties.class)
public class CommonAutoConfiguration {
}
