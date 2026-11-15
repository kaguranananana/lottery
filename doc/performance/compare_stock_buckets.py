#!/usr/bin/env python3
"""对两轮不同运行顺序的真实 JMeter 证据做可复核的库存分桶比较。"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "doc/performance/results"
CASES = [
    ("A→B", "baseline", BASE / "stock-buckets-ab-20261115/baseline-300rps-5prizes"),
    ("A→B", "buckets8", BASE / "stock-buckets-ab-20261115/buckets8-300rps-5prizes"),
    ("B→A", "buckets8", BASE / "stock-buckets-ab-repeat-20261115/repeat-buckets8-300rps"),
    ("B→A", "baseline", BASE / "stock-buckets-ab-repeat-20261115/repeat-baseline-300rps"),
]

def read_case(sequence, mode, path):
    summary = json.loads((path / "summary.json").read_text())
    stability = json.loads((path / "stability.json").read_text())
    assert summary["audit"] == "PASS", f"{path}: business audit failed"
    params = json.loads((path / "parameters.json").read_text())
    assert params["target_rps"] == 300 and params["award_kinds"] == 5
    assert params["stock_buckets"] == (0 if mode == "baseline" else 8)
    return {
        "sequence": sequence, "mode": mode, "path": str(path.relative_to(ROOT)),
        "rps": round(summary["newDrawRps"], 3),
        "stockRps": round(summary["settlementRps"], 3),
        "httpP99Ms": summary["p99Ms"],
        "stockP95UpperMs": summary["stockLatencyBoundsMs"]["0.95"][1],
        "asyncP95UpperMs": summary["asyncP95UpperMs"],
        "rowLockWaits": summary["mysqlDelta"]["Innodb_row_lock_waits"],
        "rowLockTimeMs": summary["mysqlDelta"]["Innodb_row_lock_time"],
        "maxBacklog": stability["backlogPeak"],
        "backlogMeanGrowth": round(stability["backlogMeanGrowth"], 3),
        "stableChecks": sum(bool(v) for v in stability["checks"].values()),
        "stable": stability["stable"],
        "businessErrorRate": summary["systemErrorRate"],
        "audit": summary["audit"],
        "orders": summary["ordersTotal"],
    }

EXTRA_CASES = [
    ("290RPS / 128MiB / #1", BASE / "stock-buckets-capacity-20261115/buckets8-rate290-run1"),
    ("290RPS / 128MiB / #2", BASE / "stock-buckets-capacity-20261115/buckets8-rate290-run2"),
    ("300RPS / 512MiB / #1", BASE / "stock-buckets-tuned-20261115/buckets8-buffer512-rate300-run1"),
    ("300RPS / 512MiB / #2", BASE / "stock-buckets-tuned-20261115/buckets8-buffer512-rate300-run2"),
]

def extra_result(name, path):
    summary = json.loads((path / "summary.json").read_text())
    stable = json.loads((path / "stability.json").read_text())
    assert summary["audit"] == "PASS", f"{path}: invalid audit"
    assert not summary["systemErrorRate"], f"{path}: business errors"
    return {
        "name": name, "path": str(path.relative_to(ROOT)),
        "httpRps": round(summary["newDrawRps"], 3),
        "stockRps": round(summary["settlementRps"], 3),
        "rowLockWaits": summary["mysqlDelta"]["Innodb_row_lock_waits"],
        "physicalReads": summary["mysqlDelta"]["Innodb_buffer_pool_reads"],
        "p99Ms": summary["p99Ms"], "backlogMeanGrowth": round(stable["backlogMeanGrowth"], 3),
        "stableChecks": sum(stable["checks"].values()), "stable": stable["stable"],
        "audit": summary["audit"],
    }

def main():
    results = [read_case(*case) for case in CASES]
    extra = [extra_result(*case) for case in EXTRA_CASES]
    extra_table = [
        "| 新试验（8 桶） | 实际RPS | 结算RPS | 行锁等待(次) | 物理读次数 | P99(ms) | 积压增长 | 稳定性 |",
        "|---|---:|---:|---:|---:|---:|---:|---|",
    ]
    for item in extra:
        extra_table.append("| {} | {} | {} | {} | {} | {} | {} | {}/9 {} |".format(
            item["name"], item["httpRps"], item["stockRps"], item["rowLockWaits"],
            item["physicalReads"], item["p99Ms"], item["backlogMeanGrowth"],
            item["stableChecks"], "PASS" if item["stable"] else "FAIL"))
    table = [
        "| 顺序 | 库存模式 | 实际RPS | 结算RPS | 行锁等待(次) | 锁等待(s) | 库存P95上界(ms) | 积压增长 | 稳定性 |",
        "|---|---|---:|---:|---:|---:|---:|---:|---|",
    ]
    for r in results:
        table.append("| {} | {} | {} | {} | {} | {:.3f} | {} | {} | {}/9 {} |".format(
            r["sequence"], r["mode"], r["rps"], r["stockRps"],
            r["rowLockWaits"], r["rowLockTimeMs"] / 1000,
            r["stockP95UpperMs"], r["backlogMeanGrowth"], r["stableChecks"],
            "PASS" if r["stable"] else "FAIL"))
    comparisons = []
    for seq in ("A→B", "B→A"):
        a = next(r for r in results if r["sequence"] == seq and r["mode"] == "baseline")
        b = next(r for r in results if r["sequence"] == seq and r["mode"] == "buckets8")
        comparisons.append({
            "sequence": seq,
            "rowLockWaitReductionPct": round((a["rowLockWaits"]-b["rowLockWaits"])/a["rowLockWaits"]*100, 2),
            "rowLockTimeReductionPct": round((a["rowLockTimeMs"]-b["rowLockTimeMs"])/a["rowLockTimeMs"]*100, 2),
            "stockThroughputChange": round(b["stockRps"]-a["stockRps"], 3),
        })
    out = BASE / "stock-buckets-ab-repeat-20261115/comparison.json"
    out.write_text(json.dumps({"runs": results, "pairs": comparisons, "extraRuns": extra}, ensure_ascii=False, indent=2))
    note = "\n".join([
        "# 库存分桶收尾：两轮交错顺序对照（2026-11-15）",
        "",
        "## 控制条件和原始证据",
        "",
        "单机 Spring + RocketMQ + Redis + MySQL 隔离压测，固定 5 种奖品、8 个 MQ 消费线程、Broker 2GiB、MySQL Buffer Pool 128MiB、300 RPS 目标、205秒请求阶段（15秒预热）、72个 JMeter 线程。每轮创建新活动、预置 65000 个用户，不混用库存或订单 ID；默认入口限流为关闭的实验配置。",
        "",
        "## 全部结果（不筛掉失败实验）",
        "",
        *table,
        "",
        "## 同顺序配对变化",
        "",
        *[f"- {c['sequence']}：库存桶相对单行行锁等待减少 {c['rowLockWaitReductionPct']}%，行锁总时间减少 {c['rowLockTimeReductionPct']}%，结算吞吐变化 {c['stockThroughputChange']} 单/秒。" for c in comparisons],
        "",
        "## 290 RPS 保守容量及 512MiB Buffer Pool 复测",
        "",
        *extra_table,
        "",
        "四轮补充压测均保留结果，不删除未通过的样本。128MiB 下 290 RPS 连续两轮积压超标；512MiB 下 300 RPS 虽然物理读显著减少，依然出现事务提交与异步结算积压，无法以增大缓存代替容量验证。所有业务全量对账依旧通过。",
        "",
        "## 裁决",
        "",
        "已验证：两轮单行与八桶场景全量 JMeter 请求、订单、唯一结算账本、库存和 Redis 最终对账全部通过。八桶方案在两个执行顺序下都减少了 MySQL 行锁等待。",
        "",
        "未验证：不能依据其中某一轮 9/9 达标声称 300 RPS 对任意运行顺序和宿主机背景负载都稳定。准入速率还需按本机实际验证容量加余量配置。单机两轮实验不能支持外推集群容量或生产 SLA。",
        "",
        "原始数据：每轮目录的 samples.jtl、audit.json、stability.json、resources.jsonl、sql-delta.json、resource-summary.json；机器磁盘剩余空间、缓存热度和测试顺序可能影响误差。重复实验顺序为 A→B 和 B→A，不是统计学意义上的随机化多样本容量测试。",
        "",
    ])
    (ROOT / "docs/stock-bucket-closure-evidence-20261115.md").write_text(note)
    print("\n".join(table))
    print("COMPARISONS:", json.dumps(comparisons, ensure_ascii=False))
    print("WROTE", out)
    print("WROTE", ROOT / "docs/stock-bucket-closure-evidence-20261115.md")

if __name__ == "__main__":
    main()
