package com.acme.mdm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.util.*;

public final class Json {
  public static final ObjectMapper M =
      new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  public static JsonNode read(String s) {
    try {
      return M.readTree(s);
    } catch (Exception e) {
      throw new Problem(400, "BAD_JSON", "JSON格式不正确");
    }
  }

  public static String str(Object n) {
    try {
      return M.writeValueAsString(n);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static ObjectNode obj() {
    return M.createObjectNode();
  }

  public static ArrayNode arr() {
    return M.createArrayNode();
  }

  public static ObjectNode object(Object... pairs) {
    ObjectNode o = obj();
    for (int i = 0; i < pairs.length; i += 2)
      o.set(pairs[i].toString(), M.valueToTree(pairs[i + 1]));
    return o;
  }

  public static String text(JsonNode n, String k) {
    return n.path(k).asText("");
  }

  public static boolean truth(JsonNode n) {
    return n != null && !n.isNull() && (n.isBoolean() ? n.asBoolean() : true);
  }

  public static BigDecimal decimal(JsonNode n) {
    if (n == null || n.isNull()) throw new Problem(422, "DEPENDENCY_MISSING", "缺少数值依赖");
    if (n.isObject()) n = n.get("value");
    try {
      return new BigDecimal(n.asText());
    } catch (Exception e) {
      throw new Problem(422, "INVALID_NUMBER", "请输入十进制数值");
    }
  }

  public static JsonNode path(JsonNode n, String p) {
    for (String s : p.split("\\.")) {
      if (n == null) return NullNode.instance;
      n = n.get(s);
    }
    return n == null ? NullNode.instance : n;
  }

  public static String hash(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String canonical(JsonNode n) {
    if (n.isObject()) {
      TreeMap<String, JsonNode> t = new TreeMap<>();
      n.fields().forEachRemaining(e -> t.put(e.getKey(), read(canonical(e.getValue()))));
      return str(t);
    }
    if (n.isArray()) {
      ArrayNode a = arr();
      n.forEach(x -> a.add(read(canonical(x))));
      return str(a);
    }
    if (n.isNumber()) return n.decimalValue().stripTrailingZeros().toPlainString();
    return str(n);
  }
}
