package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WorldCanonicalInstanceStartLocationReadbackTest {
  private static final long WORLD_INSTANCE_ID = 501L;
  private static final long PRIVATE_TENANT_KEY = 601L;
  private static final long PRIVATE_GAME_INSTANCE_KEY = 701L;
  private static final long RUNTIME_ROOM_INSTANCE_ID = 801L;
  private static final UUID GAME_INSTANCE_ID =
      UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void exactOriginalSelectorReadbackBindsItsReceiptIdentityAndComparesAllRetainedBytes()
      throws Exception {
    var fixture = fixture();
    var dsl = mock(DSLContext.class);
    AtomicReference<String> query = new AtomicReference<>();
    AtomicReference<Object[]> bindings = new AtomicReference<>();
    when(dsl.fetchOne(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              query.set(invocation.getArgument(0));
              bindings.set((Object[]) invocation.getRawArguments()[1]);
              return row(originalRow(fixture));
            });

    WorldCanonicalInstanceStartLocationReadback.requireExactOriginalSelector(
        dsl, fixture.selector(), fixture.receipt(), fixture.graphBytes());

    assertThat(query.get())
        .contains("JOIN world_draft_graph_application")
        .contains("JOIN world_topology_draft_commit")
        .contains("a.authorization_fence_id=s.authorization_fence_id");
    assertThat(bindings.get())
        .containsExactly(
            fixture.receipt().operationId(),
            fixture.receipt().targetNamespace(),
            fixture.receipt().startLocation().tenantId(),
            fixture.receipt().startLocation().versionId(),
            fixture.receipt().startLocation().roomTemplateId());

    for (var changed :
        List.of(
            Map.entry(
                "receipt_bytes", bytesWithLastByteChanged(fixture.receipt().canonicalBytes())),
            Map.entry(
                "account_binding_bytes",
                bytesWithLastByteChanged(fixture.selector().originalAccountBindingBytes())),
            Map.entry(
                "result_bytes", bytesWithLastByteChanged(fixture.selector().appliedResultBytes())),
            Map.entry("graph_bytes", bytesWithLastByteChanged(fixture.graphBytes())),
            Map.entry("graph_digest", "sha256:" + "f".repeat(64)))) {
      Map<String, Object> changedRow = originalRow(fixture);
      changedRow.put(changed.getKey(), changed.getValue());
      var changedDsl = mock(DSLContext.class);
      when(changedDsl.fetchOne(anyString(), any(Object[].class))).thenReturn(row(changedRow));

      assertThatThrownBy(
              () ->
                  WorldCanonicalInstanceStartLocationReadback.requireExactOriginalSelector(
                      changedDsl, fixture.selector(), fixture.receipt(), fixture.graphBytes()))
          .as("changed original %s", changed.getKey())
          .isInstanceOf(
              WorldCanonicalInstanceStartLocationReadback.InvalidStartLocationReadbackException
                  .class);
    }
  }

  @Test
  void mappedRoomReadbackAcceptsTheExactScopedRoomAndRejectsIndependentFieldChanges()
      throws Exception {
    var fixture = fixture();
    var association = association(fixture);
    var selected = mappedRoomRow(fixture);
    var dsl = mock(DSLContext.class);
    AtomicReference<String> query = new AtomicReference<>();
    AtomicReference<Object[]> bindings = new AtomicReference<>();
    when(dsl.fetchOne(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              query.set(invocation.getArgument(0));
              bindings.set((Object[]) invocation.getRawArguments()[1]);
              return row(selected);
            });

    assertThat(
            WorldCanonicalInstanceStartLocationReadback.requireMappedRoom(
                dsl,
                association,
                fixture.receipt().startLocation(),
                fixture.selector().canonicalBytes()))
        .isEqualTo(RUNTIME_ROOM_INSTANCE_ID);
    assertThat(query.get())
        .contains("m.family='ROOM'")
        .contains("JOIN room_instance r ON r.id=m.runtime_row_id")
        .contains("JOIN region_instance ri ON ri.id=r.region_instance_id")
        .contains("m.runtime_row_id AS mapped_room_row_id")
        .contains("m.template_id AS mapped_template_id")
        .contains("ri.game_instance_id AS region_game_instance_key")
        .contains("ri.world_instance_id AS room_world_instance_id");
    assertThat(bindings.get()).containsExactly(GAME_INSTANCE_ID);

    List<Map.Entry<String, Object>> changedFields =
        List.of(
            Map.entry("room_tenant_key", PRIVATE_TENANT_KEY + 1),
            Map.entry("room_game_instance_key", PRIVATE_GAME_INSTANCE_KEY + 1),
            Map.entry("region_tenant_key", PRIVATE_TENANT_KEY + 1),
            Map.entry("region_game_instance_key", PRIVATE_GAME_INSTANCE_KEY + 1),
            Map.entry("world_instance_id", WORLD_INSTANCE_ID + 1),
            Map.entry("mapped_world_instance_id", WORLD_INSTANCE_ID + 1),
            Map.entry("room_world_instance_id", WORLD_INSTANCE_ID + 1),
            Map.entry("canonical_game_instance_id", UUID.randomUUID()),
            Map.entry("mapped_game_instance_id", UUID.randomUUID()),
            Map.entry("canonical_tenant_id", UUID.randomUUID()),
            Map.entry("canonical_version_id", UUID.randomUUID()),
            Map.entry("room_template_id", UUID.randomUUID()),
            Map.entry("mapped_template_id", UUID.randomUUID()),
            Map.entry("runtime_room_instance_id", RUNTIME_ROOM_INSTANCE_ID + 1),
            Map.entry("mapped_room_instance_id", RUNTIME_ROOM_INSTANCE_ID + 1),
            Map.entry("mapped_room_row_id", RUNTIME_ROOM_INSTANCE_ID + 1),
            Map.entry("actual_room_instance_id", RUNTIME_ROOM_INSTANCE_ID + 1),
            Map.entry("mapped_family", "REGION"),
            Map.entry(
                "evidence_bytes", bytesWithLastByteChanged(fixture.selector().canonicalBytes())),
            Map.entry("receipt_digest", "sha256:" + "e".repeat(64)),
            Map.entry("graph_digest", "sha256:" + "d".repeat(64)));

    for (var changed : changedFields) {
      Map<String, Object> changedRow = mappedRoomRow(fixture);
      changedRow.put(changed.getKey(), changed.getValue());
      var changedDsl = mock(DSLContext.class);
      when(changedDsl.fetchOne(anyString(), any(Object[].class))).thenReturn(row(changedRow));

      assertThatThrownBy(
              () ->
                  WorldCanonicalInstanceStartLocationReadback.requireMappedRoom(
                      changedDsl,
                      association,
                      fixture.receipt().startLocation(),
                      fixture.selector().canonicalBytes()))
          .as("changed mapped ROOM field %s", changed.getKey())
          .isInstanceOf(
              WorldCanonicalInstanceStartLocationReadback.InvalidStartLocationReadbackException
                  .class);
    }
  }

  @Test
  void bothReadbackQueriesPassThroughRawStorageFailure() throws Exception {
    var fixture = fixture();
    var storageFailure = new org.jooq.exception.DataAccessException("raw storage failure");
    var dsl = mock(DSLContext.class);
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenThrow(storageFailure);

    assertThatThrownBy(
            () ->
                WorldCanonicalInstanceStartLocationReadback.requireMappedRoom(
                    dsl,
                    association(fixture),
                    fixture.receipt().startLocation(),
                    fixture.selector().canonicalBytes()))
        .isSameAs(storageFailure);

    assertThatThrownBy(
            () ->
                WorldCanonicalInstanceStartLocationReadback.requireExactOriginalSelector(
                    dsl, fixture.selector(), fixture.receipt(), fixture.graphBytes()))
        .isSameAs(storageFailure);
  }

  private static Fixture fixture() throws Exception {
    WorldPublishedStartLocationEvidence selector = AuthoringFixtures.startLocationEvidence();
    WorldDraftStartLocationEvidence receipt =
        WorldDraftStartLocationEvidence.fromStored(selector.selectorReceiptBytes());
    byte[] graphBytes =
        Base64.getDecoder()
            .decode(
                JSON.readTree(selector.appliedResultBytes()).get("graphBytesBase64").textValue());
    return new Fixture(selector, receipt, graphBytes);
  }

  private static WorldCanonicalInstanceAssociation association(Fixture fixture) {
    var association = mock(WorldCanonicalInstanceAssociation.class);
    var identity = mock(WorldCanonicalInstanceAssociation.CanonicalIdentity.class);
    var prepareFields = mock(WorldCanonicalInstanceAssociation.WorldPrepareFields.class);
    when(association.identity()).thenReturn(identity);
    when(association.worldPrepareFields()).thenReturn(prepareFields);
    when(association.worldInstanceId()).thenReturn(WORLD_INSTANCE_ID);
    when(association.canonicalVersionId())
        .thenReturn(fixture.receipt().startLocation().versionId());
    when(identity.canonicalGameInstanceId()).thenReturn(GAME_INSTANCE_ID);
    when(identity.canonicalTenantId()).thenReturn(fixture.receipt().startLocation().tenantId());
    when(prepareFields.privateTenantKey()).thenReturn(PRIVATE_TENANT_KEY);
    when(prepareFields.privateGameInstanceKey()).thenReturn(PRIVATE_GAME_INSTANCE_KEY);
    return association;
  }

  private static Map<String, Object> originalRow(Fixture fixture) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("receipt_bytes", fixture.selector().selectorReceiptBytes());
    values.put("account_binding_bytes", fixture.selector().originalAccountBindingBytes());
    values.put("result_bytes", fixture.selector().appliedResultBytes());
    values.put("graph_bytes", fixture.graphBytes());
    values.put("graph_digest", fixture.receipt().graphDigest());
    return values;
  }

  private static Map<String, Object> mappedRoomRow(Fixture fixture) {
    UUID tenantId = fixture.receipt().startLocation().tenantId();
    UUID versionId = fixture.receipt().startLocation().versionId();
    UUID roomTemplateId = fixture.receipt().startLocation().roomTemplateId();
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("canonical_game_instance_id", GAME_INSTANCE_ID);
    values.put("world_instance_id", WORLD_INSTANCE_ID);
    values.put("canonical_tenant_id", tenantId);
    values.put("canonical_version_id", versionId);
    values.put("room_template_id", roomTemplateId);
    values.put("runtime_room_instance_id", RUNTIME_ROOM_INSTANCE_ID);
    values.put("receipt_digest", fixture.receipt().receiptDigest());
    values.put("graph_digest", fixture.receipt().graphDigest());
    values.put("evidence_bytes", fixture.selector().canonicalBytes());
    values.put("mapped_game_instance_id", GAME_INSTANCE_ID);
    values.put("mapped_world_instance_id", WORLD_INSTANCE_ID);
    values.put("mapped_family", "ROOM");
    values.put("mapped_room_instance_id", RUNTIME_ROOM_INSTANCE_ID);
    values.put("mapped_room_row_id", RUNTIME_ROOM_INSTANCE_ID);
    values.put("mapped_template_id", roomTemplateId);
    values.put("actual_room_instance_id", RUNTIME_ROOM_INSTANCE_ID);
    values.put("room_tenant_key", PRIVATE_TENANT_KEY);
    values.put("room_game_instance_key", PRIVATE_GAME_INSTANCE_KEY);
    values.put("region_tenant_key", PRIVATE_TENANT_KEY);
    values.put("region_game_instance_key", PRIVATE_GAME_INSTANCE_KEY);
    values.put("room_world_instance_id", WORLD_INSTANCE_ID);
    return values;
  }

  private static Record row(Map<String, Object> values) {
    return mock(
        Record.class,
        invocation -> {
          if ("get".equals(invocation.getMethod().getName())
              && invocation.getArguments().length == 2
              && invocation.getArgument(0) instanceof String field) {
            return values.get(field);
          }
          return RETURNS_DEFAULTS.answer(invocation);
        });
  }

  private static byte[] bytesWithLastByteChanged(byte[] value) {
    byte[] changed = value.clone();
    changed[changed.length - 1] ^= 0x01;
    return changed;
  }

  private record Fixture(
      WorldPublishedStartLocationEvidence selector,
      WorldDraftStartLocationEvidence receipt,
      byte[] graphBytes) {}
}
