package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalogCodec;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manual owner-local catalog capture and independent exact committed readback; never a manifest.
 */
public final class WorldRuntimeFamilyCatalogRepository {
  private final DSLContext dsl;
  private final String schema;
  private final String namespace;
  private final TransactionTemplate capture;
  private final TransactionTemplate readback;

  public WorldRuntimeFamilyCatalogRepository(
      DSLContext dsl, PlatformTransactionManager transactions, String schema, String namespace) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    if (schema == null
        || !schema.matches("[a-z][a-z0-9_]{0,62}")
        || !GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Fixed owner schema and workload namespace required");
    }
    this.schema = schema;
    this.namespace = namespace;
    capture = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
    capture.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    capture.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    readback = new TransactionTemplate(transactions);
    readback.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    readback.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    readback.setReadOnly(true);
  }

  /**
   * Each request identity retains one immutable exact as-of snapshot, including response identity.
   */
  public Evidence read(Request request) {
    requireIndependent(request);
    byte[] committed =
        capture.execute(
            status -> {
              WorldRuntimeFamilyCatalog catalog = currentCatalog();
              byte[] requestBytes = WorldRuntimeFamilyCatalogCodec.requestBytes(request);
              var proposed = new Evidence(request, UUID.randomUUID(), catalog);
              byte[] evidenceBytes = WorldRuntimeFamilyCatalogCodec.evidenceBytes(proposed);
              dsl.execute(
                  "INSERT INTO "
                      + table("world_runtime_family_catalog_snapshot")
                      + " (read_request_id,target_namespace,request_bytes,request_digest,snapshot_id,"
                      + "catalog_epoch,catalog_digest,evidence_bytes,evidence_digest) VALUES (?,?,?,?,?,?,?,?,?)"
                      + " ON CONFLICT (read_request_id) DO NOTHING",
                  request.readRequestId(),
                  request.targetNamespace(),
                  requestBytes,
                  WorldRuntimeFamilyCatalog.sha256(requestBytes),
                  proposed.snapshotId(),
                  catalog.catalogEpoch(),
                  catalog.digest(),
                  evidenceBytes,
                  proposed.digest());
              return retained(request, catalog);
            });
    Evidence result = readExact(request);
    if (!Arrays.equals(committed, WorldRuntimeFamilyCatalogCodec.evidenceBytes(result))) {
      throw new CatalogConflictException("Committed catalog readback changed");
    }
    return result;
  }

  /**
   * Reads an existing exact snapshot only; missing or invalidated evidence is never empty success.
   */
  public Evidence readExact(Request request) {
    requireIndependent(request);
    return readback.execute(
        status ->
            WorldRuntimeFamilyCatalogCodec.evidenceFromStored(retained(request, currentCatalog())));
  }

  private WorldRuntimeFamilyCatalog currentCatalog() {
    Record row =
        dsl.fetchOne(
            "SELECT catalog_epoch,catalog_bytes,catalog_digest,"
                + schema
                + ".world_runtime_family_catalog_bytes(catalog_epoch) AS current_bytes FROM "
                + table("world_runtime_family_catalog_epoch")
                + " WHERE "
                + schema
                + ".world_runtime_family_catalog_installed()"
                + " ORDER BY catalog_epoch DESC LIMIT 1");
    if (row == null) throw new CatalogConflictException("No durable World catalog epoch");
    byte[] sealed = row.get("catalog_bytes", byte[].class);
    if (!Arrays.equals(sealed, row.get("current_bytes", byte[].class))) {
      throw new CatalogConflictException("World schema/classification drift invalidates its epoch");
    }
    WorldRuntimeFamilyCatalog catalog = WorldRuntimeFamilyCatalog.fromStored(sealed);
    if (catalog.catalogEpoch() != row.get("catalog_epoch", Long.class)
        || !catalog.digest().equals(row.get("catalog_digest", String.class))) {
      throw new CatalogConflictException("World catalog epoch/digest binding is inconsistent");
    }
    return catalog;
  }

  private byte[] retained(Request request, WorldRuntimeFamilyCatalog current) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + table("world_runtime_family_catalog_snapshot")
                + " WHERE read_request_id=?",
            request.readRequestId());
    if (row == null) throw new CatalogConflictException("Exact World catalog snapshot is absent");
    byte[] bytes = row.get("evidence_bytes", byte[].class);
    Evidence result = WorldRuntimeFamilyCatalogCodec.evidenceFromStored(bytes);
    byte[] requestBytes = WorldRuntimeFamilyCatalogCodec.requestBytes(request);
    if (!request.equals(result.request())
        || !request.targetNamespace().equals(row.get("target_namespace", String.class))
        || !Arrays.equals(requestBytes, row.get("request_bytes", byte[].class))
        || !WorldRuntimeFamilyCatalog.sha256(requestBytes)
            .equals(row.get("request_digest", String.class))
        || !result.snapshotId().equals(row.get("snapshot_id", UUID.class))
        || result.catalog().catalogEpoch() != row.get("catalog_epoch", Long.class)
        || !result.catalog().digest().equals(row.get("catalog_digest", String.class))
        || !result.digest().equals(row.get("evidence_digest", String.class))
        || !Arrays.equals(current.exactBytes(), result.catalog().exactBytes())) {
      throw new CatalogConflictException("Changed request or invalidated World catalog snapshot");
    }
    return bytes;
  }

  private String table(String name) {
    return schema + "." + name;
  }

  private void requireIndependent(Request request) {
    Objects.requireNonNull(request, "request");
    if (!namespace.equals(request.targetNamespace()))
      throw new CatalogConflictException("Wrong workload namespace");
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Catalog evidence requires independent owner transactions");
    }
  }

  public static final class CatalogConflictException extends IllegalStateException {
    public CatalogConflictException(String message) {
      super(message);
    }
  }
}
