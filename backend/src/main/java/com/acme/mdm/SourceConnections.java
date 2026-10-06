package com.acme.mdm;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deployment-owned destinations and credential bindings cannot be expanded by model designers. */
@Component
public class SourceConnections {
  final Db db;

  @Value(
      "${mdm.source-destinations:jdbc:postgresql://localhost:5432/mdm_erp_v3;http://127.0.0.1:9092;http://localhost:9092}")
  String destinations;

  @Value(
      "${mdm.credential-bindings:*|ERP_READ_USER|jdbc:postgresql://localhost:5432/mdm_erp_v3;*|ERP_READ_PASSWORD|jdbc:postgresql://localhost:5432/mdm_erp_v3}")
  String bindings;

  public SourceConnections(Db d) {
    db = d;
  }

  String canonical(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  public void destination(String target) {
    Problem.require(
        Arrays.stream(destinations.split(";"))
            .anyMatch(x -> canonical(x.trim()).equals(canonical(target))),
        403,
        "SOURCE_DESTINATION_NOT_ALLOWED",
        "来源/回写地址未在部署允许清单中登记");
  }

  public void binding(String name, String target, String tenant) {
    destination(target);
    String code =
        db.one("select code from tenant where id=?", SourceMetadata.id(tenant))
            .path("code")
            .asText();
    boolean allowed =
        Arrays.stream(bindings.split(";"))
            .map(x -> x.trim().split("\\|", 3))
            .anyMatch(
                x ->
                    x.length == 3
                        && (x[0].equals("*") || x[0].equals(code))
                        && x[1].equals(name)
                        && canonical(x[2]).equals(canonical(target)));
    Problem.require(allowed, 403, "CREDENTIAL_BINDING_FORBIDDEN", "凭据引用未授权给当前租户及登记地址");
  }
}
