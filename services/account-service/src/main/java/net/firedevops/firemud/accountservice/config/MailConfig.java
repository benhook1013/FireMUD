package net.firedevops.firemud.accountservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class MailConfig {}
