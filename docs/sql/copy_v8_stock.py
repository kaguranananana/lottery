#!/usr/bin/env python3
"""停写并排空后，核对旧 Redis 奖品余额，复制到 V8 活动键；源键保留。"""
import argparse
import csv
import io
import subprocess

from importlib import import_module

migration = import_module('V8__activity_prize')
OLD = 'lottery_strategy_award_stock_count_'
NEW = 'lottery_activity_prize_stock_count_'


def stock_plan(source):
    # MySQL 用十六进制转义奖品标识，TSV 不会被标识中的控制字符打断。
    return f"""SELECT a.activity_id,a.draw_config_id,a.state,HEX(p.award_id),p.award_surplus_count,
        EXISTS(SELECT 1 FROM {migration.identifier(source)}.draw_participation t WHERE t.activity_id=a.activity_id AND t.state=0)
        FROM {migration.identifier(source)}.activity a JOIN {migration.identifier(source)}.draw_config_prize p
        USING(draw_config_id) ORDER BY a.activity_id,p.award_id;"""


def copy_stock(rows, redis, apply):
    problems, writes = [], []
    for activity, config, state, award_hex, balance, pending in rows:
        award = bytes.fromhex(award_hex).decode('utf-8')
        old, new = f'{OLD}{config}_{award}', f'{NEW}{activity}_{award}'
        value, target = redis('GET', old), redis('GET', new)
        if value is None:
            # 已关闭且无未完成请求的活动不再消耗库存；其余已运行奖池缺键须先对账。
            if state == '5' or pending == '1':
                problems.append(f'{old}: 已投入使用但缺键')
            if target is not None:
                problems.append(f'{new}: 无可核对的旧余额')
            continue
        if value != balance or (target is not None and target != balance):
            problems.append(f'{new}: 旧 Redis={value}，新 Redis={target}，MySQL={balance}')
            continue
        writes.append((new, balance))
    if problems:
        raise RuntimeError('Redis 库存核对失败：\n' + '\n'.join(problems))
    if apply:
        for key, balance in writes:
            # 不覆盖已存在的余额；重跑只有仍相同才允许。
            redis('SET', key, balance, 'NX')
            if redis('GET', key) != balance:
                raise RuntimeError(f'{key}: 复制后余额不同，停止切换')
    print(f'核对 {len(rows)} 条奖品记录；{"复制并复查" if apply else "可复制"} {len(writes)} 个库存键')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True)
    parser.add_argument('--mysql', nargs='+', required=True, help='连接旧库的 mysql 命令')
    parser.add_argument('--redis', nargs='+', required=True, help='连接 Redis 的 redis-cli 命令')
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    command = args.mysql + ['--batch', '--skip-column-names', '--default-character-set=utf8mb4']
    subprocess.run(command, input=migration.audit(args.source), text=True, check=True)
    plan = subprocess.check_output(command, input=stock_plan(args.source), text=True)

    def redis(*params):
        # CSV 模式区分不存在、空串和 Redis 错误，后两种不能解释为缺键。
        output = subprocess.check_output(args.redis + ['--csv', *params], text=True).rstrip('\n')
        if output == 'NULL':
            return None
        if output == 'OK':
            return output
        if not output.startswith('"'):
            raise RuntimeError(f'Redis {params[0]} 失败：{output}')
        return next(csv.reader(io.StringIO(output)))[0]

    copy_stock(list(csv.reader(io.StringIO(plan), delimiter='\t')), redis, args.apply)


if __name__ == '__main__':
    main()
