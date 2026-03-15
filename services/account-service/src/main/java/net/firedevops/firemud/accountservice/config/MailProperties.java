package net.firedevops.firemud.accountservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "firemud.mail")
public class MailProperties {
  private String from;
  private String verificationUrl;
  private String resetUrl;
}
