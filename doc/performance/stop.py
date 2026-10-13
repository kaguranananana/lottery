#!/usr/bin/env python3
"""根据本轮保存的 PID 停止专用应用；核对进程命令，避免 PID 复用误伤。"""
from pathlib import Path
import os
import signal
import subprocess
import sys
import time
pid=int((Path(sys.argv[1])/'application.pid').read_text())
process=subprocess.check_output(['ps','-p',str(pid),'-o','comm=,args=']).strip()
# 本轮 macOS 的 comm 实际返回 java；按可执行文件名识别，不能要求一定包含路径分隔符。
executable=Path(process.split(None,1)[0].decode()).name
# 仍同时核对 Java 与专用主类，不能因进程名解析修复而放松 PID 身份约束。
assert executable=='java' and b' PerfHarness ' in process,'PID 不属于专用压测应用'
os.kill(pid,signal.SIGTERM)
# 应用正常关闭消费者后再停止中间件，保留未完成业务的数据便于诊断。
deadline=time.time()+30
while subprocess.run(['kill','-0',str(pid)],stderr=subprocess.DEVNULL).returncode==0:
    if time.time()>deadline:raise RuntimeError('专用应用未完成关闭，请检查线程与日志')
    time.sleep(.5)
print('专用压测应用已停止')
