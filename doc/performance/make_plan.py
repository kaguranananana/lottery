#!/usr/bin/env python3
"""复用旧 JMX 的 HTTP 连接配置，重建真实请求与独立统计语义。"""
from pathlib import Path
import xml.etree.ElementTree as E
BASE = Path(__file__).resolve().parent
root = E.parse(BASE.parent/'jmeter/lottery-draw-pressure-test.jmx')
plan = root.find('.//TestPlan')
plan.set('testname', 'Lottery 真实抽奖压测')
plan.find("stringProp[@name='TestPlan.comments']").text = '隔离环境；预热与稳定窗口由运行器记录；所有独立操作使用新的请求号。'
# 删除历史变量、CSV 和 GUI 监听器，命令行参数作为唯一配置来源。
for parent in root.iter():
    children = list(parent)
    for index, child in enumerate(children):
        if child.tag in ('CSVDataSet','ResultCollector','ResponseAssertion','JSR223Assertion'):
            parent.remove(child)
            if index+1 < len(children) and children[index+1].tag == 'hashTree':
                parent.remove(children[index+1])
variables = plan.find("elementProp[@name='TestPlan.user_defined_variables']/collectionProp")
variables.clear(); variables.set('name','Arguments.arguments')
tg = root.find('.//ThreadGroup')
for name, value in [('ThreadGroup.num_threads','${__P(threads,10)}'),('ThreadGroup.ramp_time','${__P(ramp,5)}')]:
    tg.find(f"stringProp[@name='{name}']").text = value
loop=tg.find('.//stringProp[@name="LoopController.loops"]'); loop.text='-1'
E.SubElement(tg,'boolProp',name='ThreadGroup.scheduler').text='true'
E.SubElement(tg,'stringProp',name='ThreadGroup.duration').text='${__P(duration,80)}'
E.SubElement(tg,'stringProp',name='ThreadGroup.delay').text='0'
for name,value in [('HTTPSampler.domain','127.0.0.1'),('HTTPSampler.port','18090'),('HTTPSampler.protocol','http'),('HTTPSampler.connect_timeout','3000'),('HTTPSampler.response_timeout','10000')]:
    root.find(f'.//ConfigTestElement/stringProp[@name="{name}"]').text=value
sampler=root.find('.//HTTPSamplerProxy');sampler.set('testname','POST /api/v1/draw')
sampler.find("stringProp[@name='HTTPSampler.path']").text='/api/v1/draw'
sampler.find(".//stringProp[@name='Argument.value']").text='{"userId":"${uId}","activityId":${activityId},"requestId":"${requestId}"}'
# 预处理器在实际采样前分配本轮唯一用户，禁止 CSV 耗尽后悄悄停止线程。
parent=next(p for p in root.iter() if sampler in list(p))
pre=E.Element('JSR223PreProcessor',guiclass='TestBeanGUI',testclass='JSR223PreProcessor',testname='真实独立抽奖身份')
def props(node, script):
    for name,value in [('cacheKey','true'),('filename',''),('parameters',''),('scriptLanguage','groovy'),('script',script)]:
        E.SubElement(node,'stringProp',name=name).text=value
props(pre, '''// 所有线程共享单调序号，用户只访问自己的真实路由分片。
long index = org.apache.jmeter.util.JMeterUtils.getJMeterProperties().computeIfAbsent('perf.counter', {new java.util.concurrent.atomic.AtomicLong()}).getAndIncrement()
// 预置用户不足必须使运行失败，不能复用样本掩盖数据不足。
if (index >= Long.parseLong(props.getProperty('users'))) {
    // 预处理器的普通异常会被 JMeter 记录后继续采样，必须显式停止线程与引擎。
    ctx.getThread().stop()
    // 停止整个负载，避免继续使用上次 uId/requestId 制造幂等回放吞吐。
    ctx.getEngine().stopTest(true)
    throw new IllegalStateException('[PERF_USER_EXHAUSTED] 压测用户耗尽')
}
vars.put('uId', props.getProperty('prefix') + String.format('%07d', index))
vars.put('activityId', props.getProperty('activity'))
// 每次独立操作使用新的业务幂等号，不把回放吞吐混入新抽奖吞吐。
vars.put('requestId', props.getProperty('run') + '-' + index)
''')
i=list(parent).index(sampler);parent.insert(i,pre);parent.insert(i+1,E.Element('hashTree'))
post=E.Element('JSR223PostProcessor',guiclass='TestBeanGUI',testclass='JSR223PostProcessor',testname='记录业务结果与持久ID')
props(post, '''// HTTP 200 只说明传输完成，业务码决定是否完成抽奖。
def result = new groovy.json.JsonSlurper().parseText(prev.getResponseDataAsString())
vars.put('businessCode', String.valueOf(result.code))
vars.put('takeId', result.takeId == null ? '' : String.valueOf(result.takeId))
vars.put('orderId', result.orderId == null ? '' : String.valueOf(result.orderId))
// 正常压测只允许成功或真实未中奖；库存竞争允许预期活动售罄。
def accepted = props.getProperty('finite', 'false') == 'true' ? ['0000','D001','D004'] : ['0000','D001']
if (!accepted.contains(result.code)) { prev.setSuccessful(false); prev.setResponseMessage('业务码:' + result.code) }
''')
i=list(parent).index(sampler);tree=list(parent)[i+1];tree.append(post);tree.append(E.Element('hashTree'))
E.indent(root,space='  ')
root.write(BASE/'draw.jmx',encoding='UTF-8',xml_declaration=True)
# 无等待基准完全不装配定速器，避免零目标速率被解释成无限等待。
timer=E.Element('JSR223Timer',guiclass='TestBeanGUI',testclass='JSR223Timer',testname='单调时钟固定到达速率')
props(timer, '''// 全线程组共享绝对时隙，避免相对sleep误差逐次累积导致目标速率不足。
long interval = Math.round(60000000000d / Double.parseDouble(props.getProperty('targetRpm')))
// 首个请求确定单调时钟起点，每次请求只取得一个时隙。
def slots = props.computeIfAbsent('perf.slots', {new java.util.concurrent.atomic.AtomicLong(System.nanoTime())})
long due = slots.getAndAdd(interval)
// parkNanos只等待距离本请求时隙的余量，唤醒偏差不会改变后续绝对时隙。
long remaining = due - System.nanoTime()
while (remaining > 0) {
    java.util.concurrent.locks.LockSupport.parkNanos(remaining)
    if (Thread.currentThread().isInterrupted()) throw new InterruptedException('压测速率等待被中断')
    remaining = due - System.nanoTime()
}
return 0L
''')
parent.insert(0,timer);parent.insert(1,E.Element('hashTree'))
E.indent(root,space='  ')
root.write(BASE/'paced.jmx',encoding='UTF-8',xml_declaration=True)
