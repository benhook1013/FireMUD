package net.firedevops.firemud.service.impl;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.dto.InstanceDto;
import net.firedevops.firemud.entity.Instance;
import net.firedevops.firemud.mapper.InstanceMapper;
import net.firedevops.firemud.repository.InstanceRepository;
import net.firedevops.firemud.service.InstanceService;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InstanceServiceImpl implements InstanceService {
  private final InstanceRepository instanceRepository;
  private final InstanceMapper instanceMapper;

  @Value("${world.instance.expiration-hours:24}")
  private long expirationHours;

  private final Logger logger = org.slf4j.LoggerFactory.getLogger(InstanceServiceImpl.class);

  // Setter used in tests to override expiration hours
  void setExpirationHours(long hours) {
    this.expirationHours = hours;
  }

  @Override
  public InstanceDto createInstance(InstanceDto request) {
    Instance entity = instanceMapper.toEntity(request);
    if (entity.getCreatedAt() == null) {
      entity.setCreatedAt(LocalDateTime.now());
    }
    if (entity.getExpiresAt() == null) {
      entity.setExpiresAt(entity.getCreatedAt().plusHours(expirationHours));
    }
    instanceRepository.save(entity);
    return instanceMapper.toDto(entity);
  }

  @Override
  @Scheduled(cron = "0 0 * * * *")
  public void cleanupExpiredInstances() {
    LocalDateTime now = LocalDateTime.now();
    instanceRepository.findByExpiresAtBefore(now).forEach(instanceRepository::delete);
    logger.debug("Expired instances cleaned up at {}", now);
  }
}
