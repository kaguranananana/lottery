#!/usr/bin/env python3
"""只操作经过 Docker 项目、服务和端口确认的专用压测实例。"""
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
COMPOSE = ['docker', 'compose', '-p', 'lottery-perf', '-f', str(ROOT / 'doc/performance/compose.yml')]

def verify():
    # 以 Docker 容器标签和实际绑定端口确定身份，不能只凭数据库名判断环境。
    info = json.loads(subprocess.check_output(['docker', 'inspect', 'lottery-perf-mysql-1']))[0]
    labels = info['Config']['Labels']
    binding = info['HostConfig']['PortBindings']['3306/tcp'][0]
    assert labels['com.docker.compose.project'] == 'lottery-perf'
    assert labels['com.docker.compose.service'] == 'mysql'
    assert binding == {'HostIp': '127.0.0.1', 'HostPort': '13329'}

def sql(query):
    verify()
    process = subprocess.run(['docker', 'exec', '-i', 'lottery-perf-mysql-1', 'mysql', '-uroot', '-pperf-only', '-N', '-B'], input=query.encode(), capture_output=True)
    if process.returncode:
        # SQL 失败必须带原始诊断抛出；正常客户端密码提示不混入测量输出。
        raise RuntimeError(process.stderr.decode())
    return process.stdout.decode()

def initialize():
    # 空环境只提取基线 DDL，不导入历史业务数据，也不执行基线 DROP。
    assert sql("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name IN ('lottery','lottery_01','lottery_02');").strip() == '0', '初始化需要全新专用实例；已有结果须先保存'
    chunks = ['SET NAMES utf8mb4;']
    for schema in ('lottery', 'lottery_01', 'lottery_02'):
        source = ROOT / 'doc/sql' / ('lottery.sql' if schema == 'lottery' else 'lottery_01.sql')
        chunks += [f'CREATE DATABASE {schema}; USE {schema};']
        chunks += re.findall(r'CREATE TABLE .*?;(?=\s*--)', source.read_text(), re.S)
        if schema != 'lottery':
            chunks += [f'ALTER TABLE user_strategy_export_{i:03d} ADD take_id BIGINT NULL;' for i in range(4)]
    sql('\n'.join(chunks))
    # 按当前交付迁移补齐库存 Outbox、唯一账本与请求归属约束。
    for name in ('doc/sql/20260315_async_award_stock_outbox.sql', 'docs/sql/V2__architecture_simplification.sql', 'docs/sql/V3__draw_request_ownership.sql'):
        sql((ROOT / name).read_text())

def apply_stock_index():
    # 复用仓库已有库存索引定义，只处理全局库存表，不混入发奖扫描索引实验。
    source = (ROOT / 'doc/sql/20260314_high_concurrency_index.sql').read_text()
    statement = re.search(r'ALTER TABLE `strategy_detail`.*?;', source, re.S).group()
    indexes = sql("SELECT index_name,GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema='lottery' AND table_name='strategy_detail' GROUP BY index_name;")
    # 已有相同前导列时不重复增加等价索引。
    if any(row.split('\t')[1].split(',')[:2] == ['strategy_id','award_id'] for row in indexes.strip().splitlines()):
        print('库存条件索引已存在')
        return
    sql('USE lottery; ' + statement)
    print('已应用现有库存组合索引定义')

if __name__ == '__main__':
    action = sys.argv[1]
    if action == 'up':
        subprocess.run(COMPOSE + ['up', '-d', '--wait'], check=True)
    elif action == 'init':
        initialize()
    elif action == 'stock-index':
        apply_stock_index()
    elif action == 'stop':
        verify()
        subprocess.run(COMPOSE + ['stop'], check=True)
    elif action == 'clean':
        # down -v 只针对此专用项目；保留仓库中的全部原始结果。
        verify()
        subprocess.run(COMPOSE + ['down', '-v'], check=True)
    else:
        raise ValueError(action)
