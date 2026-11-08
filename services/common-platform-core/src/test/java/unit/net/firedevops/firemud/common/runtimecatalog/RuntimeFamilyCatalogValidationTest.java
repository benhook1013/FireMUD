package unit.net.firedevops.firemud.common.runtimecatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import net.firedevops.firemud.common.runtimecatalog.RuntimeFamilyCatalogValidation;
import net.firedevops.firemud.common.runtimecatalog.RuntimeFamilyCatalogValidation.FamilyComponent;
import net.firedevops.firemud.common.runtimecatalog.RuntimeFamilyCatalogValidation.SchemaFact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RuntimeFamilyCatalogValidationTest {
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  @ParameterizedTest
  @ValueSource(strings = {"{\"x\":1,\"x\":2}", "{} {}", "{", "null"})
  void malformedDuplicateTrailingAndNonobjectSourcesRefuse(String source) {
    assertThatThrownBy(() -> RuntimeFamilyCatalogValidation.fields(decode(source), Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void malformedUtf8AndCompleteByteBoundsRefuse() {
    assertThatThrownBy(
            () -> RuntimeFamilyCatalogValidation.decode(new byte[] {(byte) 0xc3, 0x28}, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RuntimeFamilyCatalogValidation.decode(new byte[0], 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RuntimeFamilyCatalogValidation.decode(new byte[11], 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void closedObjectsTypedTextAndIndependentArrayBoundsRefuse() {
    var object = decode("{\"a\":\"ok\",\"b\":1}");
    assertThatThrownBy(() -> RuntimeFamilyCatalogValidation.fields(object, Set.of("a")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RuntimeFamilyCatalogValidation.fields(object, Set.of("a", "b", "c")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RuntimeFamilyCatalogValidation.text(object, "b"))
        .isInstanceOf(IllegalArgumentException.class);
    for (String invalid : List.of("[]", "{}", "null", "[1,2]")) {
      assertThatThrownBy(() -> RuntimeFamilyCatalogValidation.array(decode(invalid), 1))
          .isInstanceOf(IllegalArgumentException.class);
    }
    RuntimeFamilyCatalogValidation.array(decode("[1]"), 1);
  }

  @Test
  void schemaFactOrderingAndDuplicateIdentityAreNotSilentlyNormalized() {
    String table = fact("TABLE", "runtime");
    String column = fact("COLUMN", "runtime.state");
    var ordered =
        RuntimeFamilyCatalogValidation.schemaFacts(decode("[" + column + "," + table + "]"), 2);
    assertThat(ordered).extracting(SchemaFact::kind).containsExactly("COLUMN", "TABLE");
    for (String invalid :
        List.of("[" + table + "," + column + "]", "[" + table + "," + table + "]")) {
      assertThatThrownBy(() -> RuntimeFamilyCatalogValidation.schemaFacts(decode(invalid), 2))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Unordered/duplicate");
    }
    assertThatThrownBy(
            () -> RuntimeFamilyCatalogValidation.schemaFacts(decode("[" + table + "]"), 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> RuntimeFamilyCatalogValidation.schemaFacts(decode("[{\"kind\":\"TABLE\"}]"), 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void everyInstalledTableAndDeclaredComponentNeedsExactFacts() {
    var table = new SchemaFact("TABLE", "runtime", DIGEST);
    var column = new SchemaFact("COLUMN", "runtime.state", DIGEST);
    var root = new FamilyComponent("runtime", "");
    var component = new FamilyComponent("runtime", "state");
    RuntimeFamilyCatalogValidation.requireComplete(
        List.of(root, component), List.of(column, table));
    assertThatThrownBy(
            () ->
                RuntimeFamilyCatalogValidation.requireComplete(
                    List.of(component), List.of(column, table)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Every installed table");
    assertThatThrownBy(
            () ->
                RuntimeFamilyCatalogValidation.requireComplete(
                    List.of(root, component), List.of(table)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing family component");
    assertThatThrownBy(
            () -> RuntimeFamilyCatalogValidation.requireComplete(List.of(root), List.of(column)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing family table");
    assertThatThrownBy(
            () ->
                RuntimeFamilyCatalogValidation.requireComplete(
                    List.of(root), List.of(table, new SchemaFact("TABLE", "unknown", DIGEST))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Every installed table");
  }

  private static tools.jackson.databind.JsonNode decode(String value) {
    return RuntimeFamilyCatalogValidation.decode(value.getBytes(StandardCharsets.UTF_8), 4096);
  }

  private static String fact(String kind, String identity) {
    return "{\"kind\":\""
        + kind
        + "\",\"identity\":\""
        + identity
        + "\",\"definitionDigest\":\""
        + DIGEST
        + "\"}";
  }
}
