#!/usr/bin/env python3
"""使用原始汇总数据生成可导出的性能与积压曲线。"""
from pathlib import Path
import json
import sys
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

session=Path(sys.argv[1])
rounds=[json.loads((session/name/'summary.json').read_text()) for name in ('c10','c30','c50-confirm')]
plt.rcParams.update({'axes.spines.top':False,'axes.spines.right':False,'font.size':11})
fig,axes=plt.subplots(2,2,figsize=(12,8),layout='constrained')
x=[r['observedThreads'] for r in rounds]
axes[0,0].plot(x,[r['newDrawRps'] for r in rounds],'o-',color='#167D8D')
axes[0,0].set(ylabel='New draws / second',xlabel='Concurrent HTTP users',title='Synchronous HTTP throughput')
for r in rounds:axes[0,0].annotate(f"{r['newDrawRps']:.1f}",(r['observedThreads'],r['newDrawRps']),xytext=(0,8),textcoords='offset points',ha='center')
for key,label in [('p50Ms','P50'),('p95Ms','P95'),('p99Ms','P99')]:axes[0,1].plot(x,[r[key] for r in rounds],'o-',label=label)
axes[0,1].set(xlabel='Concurrent HTTP users',ylabel='Milliseconds',title='HTTP response latency');axes[0,1].legend()
axes[1,0].plot(x,[r['drainSeconds'] for r in rounds],'o-',color='#CB5A35')
axes[1,0].set(xlabel='Concurrent HTTP users',ylabel='Seconds',title='Async drain time after HTTP stops')
for r in rounds:
    backlog=r['backlog']
    base=backlog[0][0]
    axes[1,1].plot([(t-base)/1000 for t,_ in backlog],[s['grantPending'] for _,s in backlog],'o-',label=f"{r['observedThreads']} users")
axes[1,1].set(xlabel='Seconds in measured window',ylabel='Orders not fully granted',title='End-to-end backlog under load');axes[1,1].legend()
for ax in axes.flat:ax.grid(alpha=.2)
fig.suptitle('Lottery local single-host benchmark — 2026-10-12',fontsize=15)
fig.savefig(session/'performance-curves.png',dpi=180)
fig.savefig(session/'performance-curves.svg')
# 定速持续测试单独绘制，避免与闭环并发阶梯混合比较。
rate_dirs=[d for d in session.iterdir() if d.is_dir() and d.name.startswith('rate') and (d/'summary.json').exists()]
if rate_dirs:
    fig,ax=plt.subplots(figsize=(10,5),layout='constrained')
    for directory in sorted(rate_dirs):
        r=json.loads((directory/'summary.json').read_text());backlog=r['backlog'];base=backlog[0][0]
        ax.plot([(t-base)/1000 for t,_ in backlog],[s['grantPending'] for _,s in backlog],label=f"{directory.name}: {r['newDrawRps']:.1f} draws/s")
    ax.set(xlabel='Seconds in measured window',ylabel='Orders not fully granted',title='Sustained fixed-arrival-rate tests');ax.grid(alpha=.2);ax.legend()
    fig.savefig(session/'sustained-backlog.png',dpi=180);fig.savefig(session/'sustained-backlog.svg')

# Matplotlib的SVG路径含行尾空格；统一文本格式，保留坐标与XML换行分隔。
for name in ('performance-curves.svg','sustained-backlog.svg'):
    path=session/name
    if path.exists():
        path.write_text('\n'.join(line.rstrip() for line in path.read_text().splitlines())+'\n')
