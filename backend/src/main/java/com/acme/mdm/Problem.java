package com.acme.mdm;

import com.fasterxml.jackson.databind.JsonNode;

public class Problem extends RuntimeException {
  public final int status;
  public final String code;
  public final JsonNode errors;

  public Problem(int s, String c, String m) {
    this(s, c, m, Json.arr());
  }

  public Problem(int s, String c, String m, JsonNode e) {
    super(m);
    status = s;
    code = c;
    errors = e;
  }

  public static void require(boolean c, int s, String code, String msg) {
    if (!c) throw new Problem(s, code, msg);
  }
}
