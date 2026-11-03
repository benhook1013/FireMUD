package net.firedevops.firemud.socialgroups.runtimecatalog;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalog;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalogCodec;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Manual owner-local schema-census capture and independent exact committed readback. */
public final class SocialRuntimeFamilyCatalogRepository {
  private final DSLContext dsl;
  private final String schema;
  private final String namespace;
  private final TransactionTemplate capture;
  private final TransactionTemplate readback;

  public SocialRuntimeFamilyCatalogRepository(
      DSLContext dsl, PlatformTransactionManager transactions, String schema, String namespace) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    if (schema == null
        || !schema.matches("[a-z][a-z0-9_]{0,62}")
        || !GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Fixed Social schema and workload namespace required");
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

  /** Each exact request identity retains one immutable catalog and response correlation. */
  public Evidence read(Request request) {
    requireIndependent(request);
    byte[] committed =
        capture.execute(
            status -> {
              SocialRuntimeFamilyCatalog catalog = currentCatalog();
              byte[] requestBytes = SocialRuntimeFamilyCatalogCodec.requestBytes(request);
              Evidence proposed = new Evidence(request, UUID.randomUUID(), catalog);
              byte[] evidenceBytes = SocialRuntimeFamilyCatalogCodec.evidenceBytes(proposed);
              dsl.execute(
                  "INSERT INTO "
                      + table("social_runtime_family_catalog_snapshot")
                      + " (read_request_id,target_namespace,request_bytes,request_digest,snapshot_id,"
                      + "catalog_epoch,catalog_digest,evidence_bytes,evidence_digest) VALUES (?,?,?,?,?,?,?,?,?)"
                      + " ON CONFLICT (read_request_id) DO NOTHING",
                  request.readRequestId(),
                  request.targetNamespace(),
                  requestBytes,
                  SocialRuntimeFamilyCatalog.sha256(requestBytes),
                  proposed.snapshotId(),
                  catalog.catalogEpoch(),
                  catalog.digest(),
                  evidenceBytes,
                  proposed.digest());
              return retained(request, catalog);
            });
    Evidence result = readExact(request);
    if (!Arrays.equals(committed, SocialRuntimeFamilyCatalogCodec.evidenceBytes(result))) {
      throw new CatalogConflictException("Committed Social catalog readback changed");
    }
    return result;
  }

  /** Reads existing exact evidence only; drift or missing evidence is never empty success. */
  public Evidence readExact(Request request) {
    requireIndependent(request);
    return readback.execute(
        status ->
            SocialRuntimeFamilyCatalogCodec.evidenceFromStored(
                retained(request, currentCatalog())));
  }

  private SocialRuntimeFamilyCatalog currentCatalog() {
    Record row =
        dsl.fetchOne(
            "SELECT catalog_epoch,catalog_bytes,catalog_digest,"
                + schema
                + ".social_runtime_family_catalog_bytes(catalog_epoch) AS current_bytes FROM "
                + table("social_runtime_family_catalog_epoch")
                + " WHERE "
                + schema
                + ".social_runtime_family_catalog_installed()"
                + " ORDER BY catalog_epoch DESC LIMIT 1");
    if (row == null) throw new CatalogConflictException("No durable Social catalog epoch");
    byte[] sealed = row.get("catalog_bytes", byte[].class);
    if (!Arrays.equals(sealed, row.get("current_bytes", byte[].class))) {
      throw new CatalogConflictException(
          "Social schema/classification drift invalidates its epoch");
    }
    SocialRuntimeFamilyCatalog catalog = SocialRuntimeFamilyCatalog.fromStored(sealed);
    if (catalog.catalogEpoch() != row.get("catalog_epoch", Long.class)
        || !catalog.digest().equals(row.get("catalog_digest", String.class))) {
      throw new CatalogConflictException("Social catalog epoch/digest binding is inconsistent");
    }
    return catalog;
  }

  private byte[] retained(Request request, SocialRuntimeFamilyCatalog current) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + table("social_runtime_family_catalog_snapshot")
                + " WHERE read_request_id=?",
            request.readRequestId());
    if (row == null) throw new CatalogConflictException("Exact Social catalog snapshot is absent");
    byte[] bytes = row.get("evidence_bytes", byte[].class);
    Evidence result = SocialRuntimeFamilyCatalogCodec.evidenceFromStored(bytes);
    byte[] requestBytes = SocialRuntimeFamilyCatalogCodec.requestBytes(request);
    if (!request.equals(result.request())
        || !request.targetNamespace().equals(row.get("target_namespace", String.class))
        || !Arrays.equals(requestBytes, row.get("request_bytes", byte[].class))
        || !SocialRuntimeFamilyCatalog.sha256(requestBytes)
            .equals(row.get("request_digest", String.class))
        || !result.snapshotId().equals(row.get("snapshot_id", UUID.class))
        || result.catalog().catalogEpoch() != row.get("catalog_epoch", Long.class)
        || !result.catalog().digest().equals(row.get("catalog_digest", String.class))
        || !result.digest().equals(row.get("evidence_digest", String.class))
        || !Arrays.equals(current.exactBytes(), result.catalog().exactBytes())) {
      throw new CatalogConflictException("Changed request or invalidated Social catalog snapshot");
    }
    return bytes;
  }

  private String table(String name) {
    return schema + "." + name;
  }

  private void requireIndependent(Request request) {
    Objects.requireNonNull(request, "request");
    if (!namespace.equals(request.targetNamespace())) {
      throw new CatalogConflictException("Wrong Social workload namespace");
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Social catalog evidence requires independent owner transactions");
    }
  }

  public static final class CatalogConflictException extends IllegalStateException {
    public CatalogConflictException(String message) {
      super(message);
    }
  }
}
