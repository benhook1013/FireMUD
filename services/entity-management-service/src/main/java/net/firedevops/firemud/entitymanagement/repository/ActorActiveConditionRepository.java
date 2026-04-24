package net.firedevops.firemud.entitymanagement.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.ActorActiveCondition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ActorActiveConditionRepository extends JpaRepository<ActorActiveCondition, Long> {
  @Query(
      """
      select condition
      from ActorActiveCondition condition
      where condition.tenantId = :tenantId
        and condition.gameInstanceId = :gameInstanceId
        and condition.characterId = :characterId
        and (condition.expiresAt is null or condition.expiresAt > :now)
      order by condition.conditionKey asc, condition.startedAt asc, condition.id asc
      """)
  List<ActorActiveCondition> findActiveForCharacter(
      @Param("tenantId") Long tenantId,
      @Param("gameInstanceId") String gameInstanceId,
      @Param("characterId") Long characterId,
      @Param("now") Instant now);

  Optional<ActorActiveCondition>
      findFirstByTenantIdAndGameInstanceIdAndCharacterIdAndSourceTypeAndSourceIdOrderByIdAsc(
          Long tenantId,
          String gameInstanceId,
          Long characterId,
          String sourceType,
          String sourceId);

  @Modifying
  @Query(
      """
      delete from ActorActiveCondition condition
      where condition.expiresAt is not null
        and condition.expiresAt <= :now
      """)
  int deleteExpired(@Param("now") Instant now);
}
