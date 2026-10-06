package com.acme.mdm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
      "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://localhost:5432/mdm_test}",
      "mdm.worker-delay=60000"
    })
class SourceMetadataTest {
  @Autowired SourceMetadata sources;
  @Autowired Db db;
  @Autowired Dev dev;
  @Autowired Auth auth;
  Ctx editor;

  @BeforeEach
  void setup() {
    dev.seed();
    String tenant = Json.text(db.one("select id from tenant where code='TENANT-A'"), "id"),
        user = Json.text(db.one("select id from business_user where code='editor'"), "id");
    editor = auth.worker(tenant, user);
  }

  @Test
  void ddlFiveObjectsAndConstraintsAreTextOnly() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String ddl =
        "CREATE TABLE GC_"
            + suffix
            + " (ID VARCHAR2(32) PRIMARY KEY, MID VARCHAR2(32) NOT NULL, WEIGHT NUMBER(10,3)"
            + " DEFAULT 210, CONSTRAINT F_M FOREIGN KEY(MID) REFERENCES MANUFACTURER(ID),"
            + " UNIQUE(MID)); CREATE TABLE MANUFACTURER(ID VARCHAR(32) PRIMARY KEY,CODE VARCHAR(20)"
            + " NOT NULL); CREATE TABLE TREATMENT(ID VARCHAR(32) PRIMARY KEY); CREATE TABLE"
            + " WIDTH(ID VARCHAR(32), V NUMBER(12,3)); CREATE TABLE BASIS(ID VARCHAR(32), V"
            + " NUMBER(12,3));";
    long before =
        db.count("select count(*) from information_schema.tables where table_schema='public'");
    var result =
        db.tx(
            () -> {
              var s =
                  sources.save(
                      editor,
                      Json.object(
                          "code",
                          "DDL" + suffix,
                          "name",
                          "DDL验收",
                          "type",
                          "DATABASE",
                          "connectionProfile",
                          Json.object(
                              "jdbcUrl",
                              "jdbc:postgresql://localhost:5432/erp_fixture",
                              "userEnv",
                              "ERP_READ_USER",
                              "passwordEnv",
                              "ERP_READ_PASSWORD")),
                      null,
                      null);
              return sources.importDdl(
                  editor, Json.object("sourceSystemId", s.path("id"), "ddl", ddl));
            });
    assertEquals(5, result.size());
    var def = result.path(0).path("definition");
    assertEquals("ID", def.path("primaryKey").path(0).asText());
    assertEquals("MANUFACTURER", def.path("foreignKeys").path(0).path("targetObject").asText());
    assertEquals(3, def.path("fields").path(2).path("scale").asInt());
    assertEquals(
        before,
        db.count("select count(*) from information_schema.tables where table_schema='public'"));
    assertEquals(
        0,
        db.count(
            "select count(*) from information_schema.tables where lower(table_name)=?",
            "gc_" + suffix.toLowerCase()));
  }

  @Test
  void ddlInvalidAndCredentialsCannotEscapeMetadataBoundary() {
    assertThrows(Problem.class, () -> sources.parseDdl("DROP TABLE material;"));
    assertThrows(
        Problem.class, () -> sources.parseDdl("CREATE TABLE A(ID INTEGER); DELETE FROM tenant;"));
    var def =
        sources.parseDdl("CREATE TABLE A(ID INTEGER PRIMARY KEY,NOTE VARCHAR(50) DEFAULT 'x;y');");
    assertEquals("'x;y'", def.path(0).path("fields").path(1).path("default").asText());
    assertThrows(
        Problem.class,
        () -> sources.rejectCredentials(Json.object("password", "not-a-credential-reference")));
  }

  @Test
  void objectsAreVersionedAndCrossTenantProtected() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    var s =
        db.tx(
            () ->
                sources.save(
                    editor,
                    Json.object(
                        "code",
                        "VER" + suffix,
                        "connectionProfile",
                        Json.object(
                            "jdbcUrl",
                            "jdbc:postgresql://localhost:5432/erp_fixture",
                            "userEnv",
                            "ERP_READ_USER",
                            "passwordEnv",
                            "ERP_READ_PASSWORD")),
                    null,
                    null));
    String sid = s.path("id").asText();
    var a =
        db.tx(
                () ->
                    sources.importDdl(
                        editor,
                        Json.object(
                            "sourceSystemId",
                            sid,
                            "ddl",
                            "CREATE TABLE SAMPLE(ID INTEGER PRIMARY KEY);")))
            .path(0);
    var b =
        db.tx(
                () ->
                    sources.importDdl(
                        editor,
                        Json.object(
                            "sourceSystemId",
                            sid,
                            "ddl",
                            "CREATE TABLE SAMPLE(ID INTEGER PRIMARY KEY, EXTRA VARCHAR(50));")))
            .path(0);
    assertEquals(1, a.path("versionNo").asInt());
    assertEquals(2, b.path("versionNo").asInt());
    assertEquals(
        1,
        sources.diff(editor, a.path("id").asText(), b.path("id").asText()).path("changes").size());
    var other =
        auth.worker(
            Json.text(db.one("select id from tenant where code='TENANT-B'"), "id"), editor.user());
    assertThrows(Problem.class, () -> sources.object(other, a.path("id").asText()));
  }
}
