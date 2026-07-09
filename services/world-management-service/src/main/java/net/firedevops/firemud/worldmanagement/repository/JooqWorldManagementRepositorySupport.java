package net.firedevops.firemud.worldmanagement.repository;

import java.time.Instant;
import java.time.LocalDateTime;
import net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport;
import net.firedevops.firemud.worldmanagement.entity.Region;
import net.firedevops.firemud.worldmanagement.entity.RegionInstance;
import net.firedevops.firemud.worldmanagement.entity.Room;
import net.firedevops.firemud.worldmanagement.entity.RoomInstance;
import net.firedevops.firemud.worldmanagement.entity.WorldInstance;
import net.firedevops.firemud.worldmanagement.entity.Zone;
import net.firedevops.firemud.worldmanagement.entity.ZoneInstance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

final class JooqWorldManagementRepositorySupport {
  private JooqWorldManagementRepositorySupport() {}

  static <T> Page<T> page(java.util.List<T> content, Pageable pageable, long total) {
    if (pageable == null || pageable.isUnpaged()) {
      return new PageImpl<>(content);
    }
    return new PageImpl<>(content, pageable, total);
  }

  static int limitOrDefault(Pageable pageable, int fallback) {
    return JooqPersistenceSupport.limitOrDefault(pageable, fallback);
  }

  static int offsetOrZero(Pageable pageable) {
    return JooqPersistenceSupport.offsetOrZero(pageable);
  }

  static LocalDateTime toLocalDateTime(Instant instant) {
    return JooqPersistenceSupport.toLocalDateTime(instant);
  }

  static Instant toInstant(LocalDateTime localDateTime) {
    return JooqPersistenceSupport.toInstant(localDateTime);
  }

  static IllegalStateException staleWrite(String table, Object id) {
    return new IllegalStateException("Failed to update " + table + " id=" + id);
  }

  static Region partialRegion(Long id) {
    if (id == null) {
      return null;
    }
    Region region = new Region();
    region.setId(id);
    return region;
  }

  static Zone partialZone(Long id) {
    if (id == null) {
      return null;
    }
    Zone zone = new Zone();
    zone.setId(id);
    return zone;
  }

  static Room partialRoom(Long id) {
    if (id == null) {
      return null;
    }
    Room room = new Room();
    room.setId(id);
    return room;
  }

  static WorldInstance partialWorldInstance(Long id) {
    if (id == null) {
      return null;
    }
    WorldInstance worldInstance = new WorldInstance();
    worldInstance.setId(id);
    return worldInstance;
  }

  static RegionInstance partialRegionInstance(Long id) {
    if (id == null) {
      return null;
    }
    RegionInstance regionInstance = new RegionInstance();
    regionInstance.setId(id);
    return regionInstance;
  }

  static ZoneInstance partialZoneInstance(Long id) {
    if (id == null) {
      return null;
    }
    ZoneInstance zoneInstance = new ZoneInstance();
    zoneInstance.setId(id);
    return zoneInstance;
  }

  static RoomInstance partialRoomInstanceRecord(Long roomInstanceRecordId) {
    if (roomInstanceRecordId == null) {
      return null;
    }
    RoomInstance roomInstance = new RoomInstance();
    roomInstance.setId(roomInstanceRecordId);
    return roomInstance;
  }
}
