package net.firedevops.firemud.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "firemud.postgres")
public class PostgresProperties {
  private String host = "postgres";
  private int port = 5432;
  private String database = "firemud";
  private String username = "firemud";
  private String password = "firemud";
}
