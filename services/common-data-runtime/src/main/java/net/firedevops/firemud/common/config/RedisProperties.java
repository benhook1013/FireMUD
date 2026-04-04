package net.firedevops.firemud.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "firemud.redis")
public class RedisProperties {
  private String host = "redis";
  private int port = 6379;
  private int database = 0;
  private String username;
  private String password;
  private boolean useSsl;
  private boolean startTls;
  private boolean verifyPeer = true;
  private boolean enabled = true;
}
