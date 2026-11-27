package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.Objects;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * Pre-v1 gate waiver (ADR 0185): re-observes the live Coordination Redis {@code run_id} and the
 * Game Session PostgreSQL system identity on every call. It stands in for the trusted migration
 * factory's Kubernetes, Pod and volume evidence, which is owed before v1.
 */
final class PreV1CoordinationStorageObserver {
  private final DSLContext dsl;
  private final RedisClient redis;

  PreV1CoordinationStorageObserver(DSLContext dsl, RedisClient redis) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.redis = Objects.requireNonNull(redis, "redis");
  }

  String redisRunId() {
    String info;
    try (StatefulRedisConnection<String, String> connection = redis.connect()) {
      info = connection.sync().info("server");
    } catch (RuntimeException failure) {
      throw new IllegalStateException("Coordination Redis run_id is unavailable", failure);
    }
    if (info != null) {
      for (String line : info.split("\r?\n")) {
        if (line.startsWith("run_id:")) {
          // Validates the exact 40-hex shape before the value is used anywhere.
          return PreV1CoordinationIncarnation.forRedisRunId(line.substring("run_id:".length()))
              .redisRunId();
        }
      }
    }
    throw new IllegalStateException("Coordination Redis run_id is unavailable");
  }

  PostgresIdentity postgresIdentity() {
    Record system =
        dsl.fetchOne("SELECT system_identifier::text AS id FROM pg_catalog.pg_control_system()");
    Record database =
        dsl.fetchOne(
            "SELECT oid::bigint AS oid FROM pg_catalog.pg_database"
                + " WHERE datname = pg_catalog.current_database()");
    String systemIdentifier = system == null ? null : system.get("id", String.class);
    Long databaseOid = database == null ? null : database.get("oid", Long.class);
    if (systemIdentifier == null
        || !systemIdentifier.matches("[1-9][0-9]{0,19}")
        || databaseOid == null
        || databaseOid <= 0L
        || databaseOid > 0xffff_ffffL) {
      throw new IllegalStateException("Game Session PostgreSQL identity is unavailable");
    }
    return new PostgresIdentity(systemIdentifier, databaseOid);
  }

  record PostgresIdentity(String systemIdentifier, long databaseOid) {}
}
