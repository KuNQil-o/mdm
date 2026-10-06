package com.acme.mdm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
      "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://localhost:5432/mdm_test}",
      "mdm.worker-delay=60000"
    })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatasetEngineTest {
  @Autowired Db db;
  @Autowired Auth auth;
  @Autowired Dev dev;
  @Autowired SourceMetadata sources;
  @Autowired DatasetEngine engine;
  @Autowired ReleasePackages releases;
  Ctx c;
  String sid, tag = "DS" + UUID.randomUUID().toString().substring(0, 8);
  Map<String, String> ids = new HashMap<>();

  @BeforeAll
  void setup() {
    dev.seed();
    c =
        auth.worker(
            Json.text(db.one("select id from tenant where code='TENANT-A'"), "id"),
            Json.text(db.one("select id from business_user where code='editor'"), "id"));
    var sys =
        db.tx(
            () ->
                sources.save(
                    c,
                    Json.object(
                        "code",
                        tag,
                        "connectionProfile",
                        Json.object(
                            "jdbcUrl",
                            "jdbc:postgresql://localhost:5432/mdm_erp_v3",
                            "userEnv",
                            "ERP_READ_USER",
                            "passwordEnv",
                            "ERP_READ_PASSWORD")),
                    null,
                    null));
    sid = sys.path("id").asText();
    var list = Json.arr();
    for (String t :
        List.of(
            "cloth",
            "manufacturer",
            "treatment",
            "cloth_weight",
            "cloth_type",
            "width_ref",
            "form_ref",
            "grade_ref",
            "duplicate_ref")) list.add(Json.object("objectName", t));
    var objects = db.tx(() -> sources.discover(c, sid, Json.object("objects", list)));
    objects.forEach(o -> ids.put(o.path("objectName").asText(), o.path("id").asText()));
  }

  ObjectNode join(String table, String alias, String left, String right) {
    return Json.object(
        "objectVersionId",
        ids.get(table),
        "alias",
        alias,
        "type",
        "LEFT",
        "cardinality",
        "N:1",
        "nullPolicy",
        "ERROR",
        "on",
        Json.arr().add(Json.object("left", left, "right", right)),
        "activeField",
        "active",
        "activeValue",
        true);
  }

  ObjectNode definition() {
    return Json.object(
        "root",
        Json.object("objectVersionId", ids.get("cloth"), "alias", "g"),
        "sourceKey",
        Json.arr().add("g.id"),
        "grain",
        "ONE_ROOT_ROW_ONE_MATERIAL",
        "joins",
        Json.arr()
            .add(join("manufacturer", "m", "g.manufacturer_id", "m.id"))
            .add(join("cloth_weight", "bw", "g.weight_id", "bw.id"))
            .add(join("cloth_type", "t", "bw.type_id", "t.id"))
            .add(join("treatment", "tr", "g.treatment_id", "tr.id"))
            .add(join("width_ref", "w", "g.width_id", "w.id")),
        "output",
        Json.object(
            "manufacturer",
            "m.code",
            "clothCode",
            "bw.cloth_code",
            "type",
            "t.code",
            "treatment",
            "tr.code",
            "width",
            "w.width",
            "widthUnit",
            "w.unit",
            "weight",
            "bw.weight",
            "weightUnit",
            "bw.unit",
            "remarks",
            "g.remarks"),
        "existingNoField",
        "g.material_no",
        "sourceVersionField",
        "g.version",
        "incremental",
        Json.object("strategy", "VERSION_COLUMN", "referenceStrategy", "RESCAN_UNISSUED"));
  }

  ObjectNode cfg(JsonNode d) {
    return Json.object(
        "id", UUID.randomUUID(), "code", tag, "sourceSystemId", sid, "definition", d);
  }

  void root(String id, String manufacturer, String width) {
    try (var con =
            DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/mdm_erp_v3", "mdm", "mdm_dev_only");
        var st =
            con.prepareStatement(
                "insert into"
                    + " cloth(id,weight_id,manufacturer_id,treatment_id,width_id,form_id,grade_id)"
                    + " values(?,'BW210',?,'T01',?,'F01','G01')")) {
      st.setString(1, id);
      st.setString(2, manufacturer);
      st.setString(3, width);
      st.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void jdbcReadOnlyAndMultiLevelJoin() {
    assertTrue(sources.test(c, sid).path("readOnly").asBoolean());
    var result = engine.preview(c, cfg(definition()), "100001", null);
    assertEquals("HH", result.path("output").path("manufacturer").asText());
    assertEquals("7628", result.path("output").path("clothCode").asText());
    assertEquals(1270, result.path("output").path("width").asInt());
    assertEquals(5, result.path("joins").size());
    assertTrue(result.path("readPlan").asText().startsWith("SELECT"));
  }

  @Test
  void sourceMissingAndInactiveDoNotChooseDefaults() {
    String missing = tag + "MISS";
    root(missing, "NONEXISTENT", "W1270");
    var e = assertThrows(Problem.class, () -> engine.preview(c, cfg(definition()), missing, null));
    assertEquals("SOURCE_INCOMPLETE", e.code);
    assertTrue(e.errors.toString().contains("/joins/m"));
    String inactive = tag + "INACTIVE";
    root(inactive, "M01", tag + "W");
    try (var con =
            DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/mdm_erp_v3", "mdm", "mdm_dev_only");
        var st = con.prepareStatement("insert into width_ref values(?,1270,'mm',false)")) {
      st.setString(1, tag + "W");
      st.executeUpdate();
    } catch (SQLException x) {
      throw new RuntimeException(x);
    }
    assertEquals(
        "REFERENCE_INACTIVE",
        assertThrows(Problem.class, () -> engine.preview(c, cfg(definition()), inactive, null))
            .code);
  }

  @Test
  void duplicateJoinIsGrainConflictAndCompositeJoinCanNarrow() {
    String rid = tag + "DUP";
    root(rid, tag, "W1270");
    try (var con =
            DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/mdm_erp_v3", "mdm", "mdm_dev_only");
        var st = con.prepareStatement("insert into duplicate_ref values(?,?,?),(?,?,?)")) {
      st.setString(1, tag + "A");
      st.setString(2, tag);
      st.setString(3, "T01");
      st.setString(4, tag + "B");
      st.setString(5, tag);
      st.setString(6, "T02");
      st.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    var d = definition();
    var j =
        Json.object(
            "objectVersionId",
            ids.get("duplicate_ref"),
            "alias",
            "d",
            "type",
            "LEFT",
            "cardinality",
            "N:1",
            "nullPolicy",
            "ERROR",
            "on",
            Json.arr().add(Json.object("left", "g.manufacturer_id", "right", "d.business_key")));
    d.set("joins", Json.arr().add(j));
    d.set("output", Json.object("value", "d.value"));
    assertEquals(
        "GRAIN_CONFLICT",
        assertThrows(Problem.class, () -> engine.preview(c, cfg(d), rid, null)).code);
    ((ArrayNode) j.path("on")).add(Json.object("left", "g.treatment_id", "right", "d.value"));
    assertEquals("T01", engine.preview(c, cfg(d), rid, null).path("output").path("value").asText());
  }

  @Test
  void publishPrerequisitesTypeAndRootKey() {
    var empty = definition();
    empty.set("sourceKey", Json.arr());
    assertEquals(
        "SOURCE_KEY_REQUIRED", assertThrows(Problem.class, () -> engine.plan(c, cfg(empty))).code);
    var bad = definition();
    ((ObjectNode) bad.path("joins").path(0).path("on").path(0)).put("left", "g.version");
    assertEquals(
        "JOIN_TYPE_MISMATCH", assertThrows(Problem.class, () -> engine.plan(c, cfg(bad))).code);
    var single = definition();
    single.set("joins", Json.arr());
    single.set("output", Json.object("key", "g.id"));
    assertEquals(
        "100001",
        engine.preview(c, cfg(single), "100001", null).path("output").path("key").asText());
  }
}
