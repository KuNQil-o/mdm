package com.acme.mdm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class Stats extends OncePerRequestFilter {
  static class Samples {
    final LongAdder total = new LongAdder(), errors = new LongAdder();
    final ArrayDeque<Double> latency = new ArrayDeque<>(),
        rules = new ArrayDeque<>(),
        issues = new ArrayDeque<>(),
        writes = new ArrayDeque<>();
    final LongAdder issueTotal = new LongAdder(),
        issueFailures = new LongAdder(),
        writeTotal = new LongAdder(),
        writeFailures = new LongAdder();
  }

  final ConcurrentHashMap<String, Samples> byTenant = new ConcurrentHashMap<>();

  void add(ArrayDeque<Double> q, double value) {
    synchronized (q) {
      q.addLast(value);
      if (q.size() > 2000) q.removeFirst();
    }
  }

  Object p95(ArrayDeque<Double> q) {
    synchronized (q) {
      if (q.isEmpty()) return "未采集";
      var values = new ArrayList<>(q);
      values.sort(Double::compare);
      return Math.round(values.get(Math.max(0, (int) Math.ceil(values.size() * .95) - 1)) * 1000)
          / 1000.0;
    }
  }

  public void rule(String tenant, long ns) {
    add(byTenant.computeIfAbsent(tenant, x -> new Samples()).rules, ns / 1_000_000.0);
  }

  public void issue(String tenant, long ns, boolean success) {
    var s = byTenant.computeIfAbsent(tenant, k -> new Samples());
    s.issueTotal.increment();
    if (!success) s.issueFailures.increment();
    add(s.issues, ns / 1_000_000.0);
  }

  public void write(String tenant, long ns, boolean failed) {
    var s = byTenant.computeIfAbsent(tenant, k -> new Samples());
    s.writeTotal.increment();
    if (failed) s.writeFailures.increment();
    add(s.writes, ns / 1_000_000.0);
  }

  public ObjectNode numberingMetrics(String tenant) {
    var s = byTenant.computeIfAbsent(tenant, k -> new Samples());
    return Json.object(
        "issueP95Ms",
        p95(s.issues),
        "issueSuccessPercent",
        s.issueTotal.sum() == 0
            ? "未采集"
            : 100.0 * (s.issueTotal.sum() - s.issueFailures.sum()) / s.issueTotal.sum(),
        "writebackP95Ms",
        p95(s.writes),
        "writebackFailurePercent",
        s.writeTotal.sum() == 0 ? "未采集" : 100.0 * s.writeFailures.sum() / s.writeTotal.sum(),
        "scope",
        "当前进程最近2000次发号调用/实际领取回写；含幂等请求，业务最终失败单独统计");
  }

  public ObjectNode metrics(String tenant) {
    var s = byTenant.get(tenant);
    if (s == null)
      return Json.object(
          "apiLatencyP95",
          "未采集",
          "apiErrorRate",
          "未采集",
          "ruleDuration",
          "未采集",
          "metricScope",
          "当前进程，最近最多2000样本");
    return Json.object(
        "apiLatencyP95",
        p95(s.latency),
        "apiErrorRate",
        s.total.sum() == 0 ? "未采集" : s.errors.sum() * 100.0 / s.total.sum(),
        "ruleDuration",
        p95(s.rules),
        "requestsCollected",
        s.total.sum(),
        "metricScope",
        "P95毫秒/错误百分比；当前进程，最近最多2000样本");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    long start = System.nanoTime();
    try {
      chain.doFilter(req, res);
    } finally {
      Object tenant = req.getAttribute("metricsTenantId");
      if (tenant != null) {
        var s = byTenant.computeIfAbsent(tenant.toString(), x -> new Samples());
        s.total.increment();
        if (res.getStatus() >= 400) s.errors.increment();
        add(s.latency, (System.nanoTime() - start) / 1_000_000.0);
      }
    }
  }
}
