#!/usr/bin/env python3
"""用标准绘图库展示真实查询次数与积压，不将主机抖动包装为性能收益。"""
import json
from pathlib import Path
import sys
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

def plot(directory):
    results=json.loads((directory/'comparisons.json').read_text())['runs']
    names=['quiet-before-rate80','quiet-after-rate80','outbox-rate80-confirm']
    colors=['#ad4936','#267788','#5a9471']
    fig,axes=plt.subplots(1,2,figsize=(11,4.2))
    values=[results[n]['asyncOrderReadsPerOrder'] for n in names[:2]]
    bars=axes[0].bar(['Before','After'],values,color=colors[:2],width=.5)
    axes[0].bar_label(bars,fmt='%.3f',padding=4)
    axes[0].set_ylim(0,5)
    axes[0].set_ylabel('Actual asynchronous order SELECTs / unique order')
    axes[0].set_title('Database work removed')
    for name,color,label in zip(names,colors,['Before, no parallel tests (180s)','After, no parallel tests (180s)','After confirmation (300s)']):
        backlog=results[name]['summary']['backlog']
        # 曲线从该窗口的首个积压采样起计时，保留真实不等间隔。
        first=backlog[0][0]
        axes[1].plot([(t-first)/1000 for t,_ in backlog],[s['grantPending'] for _,s in backlog],label=label,color=color,linewidth=1.8)
    axes[1].set_xlabel('Seconds from first backlog sample')
    axes[1].set_ylabel('Orders awaiting final fulfillment')
    axes[1].set_title('Backlog at 80 requests/s')
    axes[1].legend(fontsize=8)
    for ax in axes:
        ax.spines[['top','right']].set_visible(False)
        ax.grid(axis='y',alpha=.2)
        ax.set_axisbelow(True)
    fig.suptitle('Lottery: real SQL counts and complete-chain backlog',fontsize=13)
    fig.text(.5,.015,'Shared host with active swapping; timing changes are observations, not isolated speedup estimates.',ha='center',fontsize=8)
    fig.tight_layout(rect=(0,.06,1,.93))
    fig.savefig(directory/'optimization-evidence.png',dpi=180)
    fig.savefig(directory/'optimization-evidence.svg')
    plt.close(fig)

if __name__=='__main__':plot(Path(sys.argv[1]))
