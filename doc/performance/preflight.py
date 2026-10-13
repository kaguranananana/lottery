#!/usr/bin/env python3
"""只读记录压测前的真实资源条件；不启动、停止或清理任何实例。"""
import argparse
import json
from pathlib import Path
import re
import time

from run import command, write


def inventory() -> dict:
    # 查看所有实例，以便区别专用压测资源与不可操作的背景负载。
    names = command(['docker', 'ps', '-a', '--format', '{{.Names}}']).splitlines()
    containers = []
    for item in json.loads(command(['docker', 'inspect', *names])):
        # 仅保存身份与资源字段，避免将环境变量中的凭据写入证据。
        host = item['HostConfig']
        labels = item['Config']['Labels']
        containers.append({
            'name': item['Name'].lstrip('/'), 'image': item['Config']['Image'],
            'state': {key: item['State'][key] for key in ('Status', 'ExitCode', 'OOMKilled')},
            'project': labels.get('com.docker.compose.project'),
            'service': labels.get('com.docker.compose.service'),
            'resources': {key: host[key] for key in (
                'NanoCpus', 'CpuQuota', 'CpuPeriod', 'CpusetCpus',
                'Memory', 'MemorySwap', 'MemoryReservation')},
            'ports': host['PortBindings'],
            'mounts': [{'type': mount['Type'], 'destination': mount['Destination'],
                        'volumeName': mount.get('Name')} for mount in item['Mounts']],
        })
    return {
        'host': command(['sysctl', 'hw.ncpu', 'hw.memsize']).strip(),
        'docker': json.loads(command(['docker', 'info', '--format',
            '{"cpus":{{.NCPU}},"memoryBytes":{{.MemTotal}},"version":"{{.ServerVersion}}","architecture":"{{.Architecture}}"}'])),
        'containers': containers,
    }


def sample() -> dict:
    # vm_stat 原文同时保存，页大小从实际输出读取，不能假定所有机器均为 16 KiB。
    raw = command(['vm_stat'])
    # 提取当前机器实际页大小，后续页计数按此换算为 MiB。
    page_bytes = int(re.search(r'page size of (\d+) bytes', raw).group(1))
    # 保存各个字段的原始页计数，Swapins/Swapouts 用于首末差值。
    pages = {key: int(value) for key, value in re.findall(r'^(.*?):\s*(\d+)\.', raw, re.M)}
    # 只记录进程名与资源数字，不记录可能包含凭据的完整命令行。
    processes = []
    for line in command(['ps', '-axo', 'pid,pcpu,rss,comm']).splitlines()[1:]:
        pid, cpu, rss, name = line.split(None, 3)
        processes.append({'pid': int(pid), 'cpuPercent': float(cpu),
                          'rssKiB': int(rss), 'name': Path(name).name})
    return {
        'timeMs': int(time.time() * 1000), 'pageBytes': page_bytes,
        'pages': pages, 'vmStat': raw,
        'swapUsage': command(['sysctl', 'vm.swapusage']).strip(),
        'topRssProcesses': sorted(processes, key=lambda item: item['rssKiB'], reverse=True)[:12],
    }


def collect(directory: Path, seconds: float, interval: float) -> dict:
    # 新目录防止覆盖旧证据；采样期间不执行压测、Maven 或环境修改。
    directory.mkdir(parents=True, exist_ok=False)
    write(directory / 'environment.json', inventory())
    write(directory / 'docker-before.json', [json.loads(line) for line in
        command(['docker', 'stats', '--no-stream', '--format', '{{json .}}']).splitlines()])
    samples = []
    started = time.monotonic()
    with (directory / 'host-samples.jsonl').open('w') as output:
        while True:
            current = sample()
            samples.append(current)
            # 每次刷新原始采样，命令失败时已完成的证据仍保留，异常直接传播。
            output.write(json.dumps(current, ensure_ascii=False) + '\n')
            output.flush()
            remaining = seconds - (time.monotonic() - started)
            if remaining <= 0:
                break
            time.sleep(min(interval, remaining))
    write(directory / 'docker-after.json', [json.loads(line) for line in
        command(['docker', 'stats', '--no-stream', '--format', '{{json .}}']).splitlines()])
    intervals = []
    for before, after in zip(samples, samples[1:]):
        # 增量区分静态 Swap 存量与本观察窗口正在发生的换页。
        intervals.append({
            'seconds': (after['timeMs'] - before['timeMs']) / 1000,
            'swapInMiB': (after['pages']['Swapins'] - before['pages']['Swapins']) * after['pageBytes'] / 1048576,
            'swapOutMiB': (after['pages']['Swapouts'] - before['pages']['Swapouts']) * after['pageBytes'] / 1048576,
        })
    result = {
        'samplingSeconds': (samples[-1]['timeMs'] - samples[0]['timeMs']) / 1000,
        'sampleCount': len(samples), 'intervals': intervals,
        'swapInMiB': sum(item['swapInMiB'] for item in intervals),
        'swapOutMiB': sum(item['swapOutMiB'] for item in intervals),
        'observationOnly': True,
    }
    # 资源观察不替代业务稳定性验收，不自动降低或新增通过门槛。
    write(directory / 'resource-summary.json', result)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--seconds', type=float, default=60)
    parser.add_argument('--interval', type=float, default=5)
    args = parser.parse_args()
    if args.seconds <= 0 or args.interval <= 0:
        parser.error('采样时长与间隔必须大于零')
    print(json.dumps(collect(args.directory, args.seconds, args.interval), ensure_ascii=False, indent=2))
