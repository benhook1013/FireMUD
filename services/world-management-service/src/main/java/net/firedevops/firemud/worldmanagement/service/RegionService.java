package net.firedevops.firemud.worldmanagement.service;

import java.util.List;
import net.firedevops.firemud.worldmanagement.dto.RegionDto;

/** Region management and shard assignment. */
public interface RegionService {
  /** List all regions for a tenant. */
  List<RegionDto> listRegions(Long tenantId);

  /** Move a region to a new shard. */
  RegionDto moveRegion(Long tenantId, Long regionId, Integer shardId);
}
