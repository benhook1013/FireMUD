package net.firedevops.firemud.gamedesign.repository;

import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.LaunchDescriptor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LaunchDescriptorRepository extends JpaRepository<LaunchDescriptor, Long> {
  Optional<LaunchDescriptor> findByTenantIdAndGameTemplateIdAndControlPlaneRequestId(
      String tenantId, Long gameTemplateId, String controlPlaneRequestId);
}
