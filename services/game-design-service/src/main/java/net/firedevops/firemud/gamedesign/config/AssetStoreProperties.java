package net.firedevops.firemud.gamedesign.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "asset.store")
public class AssetStoreProperties {
  private String endpoint;
  private String bucket;
  private String region;
  private String accessKey;
  private String secretKey;
}
