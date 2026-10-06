package com.acme.mdm;

import java.util.*;

public record Ctx(
    String tenant, String user, Set<String> actions, Set<String> scope, String trace) {
  public UUID tid() {
    return UUID.fromString(tenant);
  }

  public UUID uid() {
    return UUID.fromString(user);
  }

  public boolean allows(String category) {
    return scope.contains("*") || scope.contains(category);
  }

  public void check(String action, String category) {
    Problem.require(actions.contains(action), 403, "ACTION_FORBIDDEN", "当前成员没有此操作权限");
    if (category != null)
      Problem.require(allows(category), 403, "CATEGORY_FORBIDDEN", "类别不在成员授权范围");
  }
}
