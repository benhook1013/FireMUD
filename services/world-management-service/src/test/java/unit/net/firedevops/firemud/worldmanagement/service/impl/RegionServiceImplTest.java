package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.Region;
import net.firedevops.firemud.worldmanagement.mapper.RegionMapper;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RegionServiceImplTest {
  private RegionRepository repository;
  private RegionMapper mapper;
  private WorldDesignPublicationFenceRepository fence;
  private RegionServiceImpl service;

  @BeforeEach
  void setUp() {
    repository = mock(RegionRepository.class);
    mapper = mock(RegionMapper.class);
    fence = mock(WorldDesignPublicationFenceRepository.class);
    service = new RegionServiceImpl(repository, mapper, fence);
  }

  @Test
  void locksLocatedVersionThenRereadsBeforeMovingRegion() {
    Region current = region(1L, 7L);
    when(repository.findVersionIdByTenantIdAndId(1L, 44L)).thenReturn(Optional.of(7L));
    when(repository.findByIdForUpdate(44L)).thenReturn(Optional.of(current));
    when(repository.save(current)).thenReturn(current);

    service.moveRegion(1L, 44L, 3);

    var order = inOrder(repository, fence, mapper);
    order.verify(repository).findVersionIdByTenantIdAndId(1L, 44L);
    order.verify(fence).lockExistingOpenByPrivateKeys(1L, 7L);
    order.verify(repository).findByIdForUpdate(44L);
    order.verify(repository).save(current);
    order.verify(mapper).toDto(current);
    assertEquals(3, current.getShardId());
  }

  @Test
  void deniedOwnerPreventsRereadAndMutation() {
    when(repository.findVersionIdByTenantIdAndId(1L, 44L)).thenReturn(Optional.of(7L));
    doThrow(new WorldDesignPublicationFenceRepository.ConflictException("Owner unavailable"))
        .when(fence)
        .lockExistingOpenByPrivateKeys(1L, 7L);

    assertThrows(
        WorldDesignPublicationFenceRepository.ConflictException.class,
        () -> service.moveRegion(1L, 44L, 3));
    verify(repository).findVersionIdByTenantIdAndId(1L, 44L);
    verify(repository, never()).findByIdForUpdate(any());
    verify(repository, never()).save(any());
    verifyNoInteractions(mapper);
  }

  @Test
  void changedTenantOrVersionAfterOwnerLockCannotMoveRegion() {
    for (Region current : new Region[] {region(2L, 7L), region(1L, 8L)}) {
      when(repository.findVersionIdByTenantIdAndId(1L, 44L)).thenReturn(Optional.of(7L));
      when(repository.findByIdForUpdate(44L)).thenReturn(Optional.of(current));
      assertThrows(
          WorldDesignPublicationFenceRepository.ConflictException.class,
          () -> service.moveRegion(1L, 44L, 3));
      assertEquals(0, current.getShardId());
    }
    verify(repository, never()).save(any());
    verifyNoInteractions(mapper);
  }

  @Test
  void wrongTenantCannotAcquireAnOwnerLock() {
    when(repository.findVersionIdByTenantIdAndId(1L, 44L)).thenReturn(Optional.empty());
    assertThrows(IllegalArgumentException.class, () -> service.moveRegion(1L, 44L, 3));
    verifyNoInteractions(fence, mapper);
    verify(repository, never()).save(any());
  }

  private static Region region(long tenant, long version) {
    Region region = new Region();
    region.setId(44L);
    region.setTenantId(tenant);
    region.setVersionId(version);
    return region;
  }
}
