package net.firedevops.firemud.service.impl;

import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.dto.RegionDto;
import net.firedevops.firemud.mapper.RegionMapper;
import net.firedevops.firemud.repository.RegionRepository;
import net.firedevops.firemud.service.RegionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RegionServiceImpl implements RegionService {
  private final RegionRepository regionRepository;
  private final RegionMapper regionMapper;

  @Override
  public List<RegionDto> listRegions(Long tenantId) {
    return regionRepository.findByTenantId(tenantId).stream().map(regionMapper::toDto).toList();
  }

  @Override
  @Transactional
  public RegionDto moveRegion(Long tenantId, Long regionId, Integer shardId) {
    var region =
        regionRepository
            .findById(regionId)
            .filter(r -> r.getTenantId().equals(tenantId))
            .orElseThrow(() -> new IllegalArgumentException("Region not found"));
    region.setShardId(shardId);
    return regionMapper.toDto(regionRepository.save(region));
  }
}
