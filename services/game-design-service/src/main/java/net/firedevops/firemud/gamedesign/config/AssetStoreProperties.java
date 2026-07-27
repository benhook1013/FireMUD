package net.firedevops.firemud.gamedesign.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "asset.store")
public class AssetStoreProperties {
  private static final int DEFAULT_FROZEN_SNAPSHOT_CACHE_MAX_ENTRIES = 256;

  private String endpoint;
  private String bucket;
  private String region;
  private String accessKey;
  private String secretKey;

  @Min(1)
  private int frozenSnapshotCacheMaxEntries = DEFAULT_FROZEN_SNAPSHOT_CACHE_MAX_ENTRIES;
}
