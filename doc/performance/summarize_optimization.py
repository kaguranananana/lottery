#!/usr/bin/env python3
"""汇总本轮实际结果与锁摘录，保留不可比限制，不自动包装性能提升率。"""
import json
from pathlib import Path
import sys

def summarize(directory):
    rows={}
    excerpts={}
    for child in sorted(directory.iterdir()):
        if not (child/'summary.json').is_file():continue
        result=json.loads((child/'summary.json').read_text())
        row={'summary':result}
        for name in ['parameters','resource-summary','stage-summary','stability','measurement-validity']:
            file=child/(name+'.json')
            if file.is_file():row[name]=json.loads(file.read_text())
        # 被审计器拒绝或数据耗尽的轮次保留诊断，不自动混入有效性能对照。
        row['measurementValid']=not (child/'run-error.json').is_file() and row.get('measurement-validity',{}).get('valid',True)
        if 'stage-summary' in row:
            stages=row['stage-summary']['stages']
            # 以唯一中奖订单为分母，而不是 Kafka 消息数；重复扫描的额外读也计入。
            reads=sum(v['count'] for k,v in stages.items() if k.startswith('sql/') and not k.startswith('sql/http/') and k.endswith('.queryUserStrategyExportByUuid'))
            row['asyncOrderReads']=reads
            row['asyncOrderReadsPerOrder']=reads/result['ordersTotal']
            samples=[json.loads(line) for line in (child/'resources.jsonl').read_text().splitlines()]
            records=[]
            for sample in samples:
                for line in sample.get('lockWaits','').splitlines():
                    if line and line not in records:records.append(line)
            # 完整锁现场在原始包，仓库只保留首批可核对的等待/阻塞关系。
            excerpts[child.name]=records[:8]
        rows[child.name]=row
    result={'runs':rows,'environmentComparable':False,
            'limits':['宿主机活跃换页且前后增量不同','原有背景容器和应用保留运行','保留历史数据导致数据库体量增长','顺序执行且没有随机顺序独占环境 A/B'],
            'maximumStableCapacityConfirmed':False}
    (directory/'comparisons.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    (directory/'lock-excerpts.json').write_text(json.dumps(excerpts,ensure_ascii=False,indent=2))
    print('实际结果已汇总，保留环境不可比与最大容量未确认结论')

if __name__=='__main__':summarize(Path(sys.argv[1]))
