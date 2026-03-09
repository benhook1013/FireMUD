package net.firedevops.firemud.worldmanagement.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.worldmanagement.dto.RegionDto;
import net.firedevops.firemud.worldmanagement.mapper.RegionMapper;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.service.RegionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected dependencies are not exposed")
@Service
@RequiredArgsConstructor
public class RegionServiceImpl implements RegionService {
  private final RegionRepository regionRepository;
  private final RegionMapper regionMapper;

  @Override
  @Timed(value = "region.list")
  public List<RegionDto> listRegions(Long tenantId) {
    return regionRepository.findByTenantId(tenantId).stream().map(regionMapper::toDto).toList();
  }

  @Override
  @Transactional
  @Timed(value = "region.move")
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
