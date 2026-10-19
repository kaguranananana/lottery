// 当前源码教学内容；T1/O1与库存数值均为教学示例。
window.LOTTERY_GUIDE = {
  "views": [
    {
      "chapter": "overview",
      "id": "01-layers",
      "title": "代码分层",
      "summary": "先看每层负责什么。箭头展示主要代码关系；运行时调用在后续时序图展开。",
      "facts": [
        {
          "title": "interfaces：请求翻译与启动装配",
          "body": "校验HTTP、转换DTO；启动模块装入其他模块。Mapper XML物理存放于interfaces资源目录，职责仍是持久化SQL。",
          "why": "让外部契约与业务规则分开变化。",
          "sources": [
            {
              "path": "lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/LotteryController.java",
              "line": 24,
              "end_line": 44
            },
            {
              "path": "lottery-interfaces/pom.xml",
              "line": 19,
              "end_line": 70
            }
          ]
        },
        {
          "title": "application：安排先后与结果",
          "body": "串联请求幂等、参与、抽奖、下单、回放；Kafka收发与XXL Job也位于应用层。",
          "why": "多领域服务完成同一个用例，需要统一编排。",
          "sources": [
            {
              "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java",
              "line": 62,
              "end_line": 180
            },
            {
              "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/worker/LotteryXxlJob.java",
              "line": 193,
              "end_line": 229
            }
          ]
        },
        {
          "title": "domain：定义业务规则与端口",
          "body": "参与资格、概率、订单、活动状态、发奖抽象和Repository接口。仍使用Spring注解及部分框架依赖。",
          "why": "注入的仓储实现执行存储动作，业务规则不必拼SQL。",
          "sources": [
            {
              "path": "lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/partake/BaseActivityPartake.java",
              "line": 23,
              "end_line": 72
            },
            {
              "path": "lottery-domain/src/main/java/com/anontokyo/lottery/domain/strategy/service/draw/impl/DrawExecutor.java",
              "line": 31,
              "end_line": 61
            }
          ]
        },
        {
          "title": "infrastructure：执行副作用",
          "body": "Repository、DAO/PO、事务、路由、RedisUtil、Guard与缓存实现。",
          "why": "将领域端口落实为SQL/Redis动作，封装分片上下文。",
          "sources": [
            {
              "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/OrderRepository.java",
              "line": 38,
              "end_line": 65
            },
            {
              "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/StrategyRepository.java",
              "line": 87,
              "end_line": 110
            }
          ]
        },
        {
          "title": "common：共同值与枚举",
          "body": "Result、业务码、状态、RedisKey；五个Maven模块组装成同一Lottery应用。",
          "why": "统一跨层语义；五模块并非五个微服务。",
          "sources": [
            {
              "path": "lottery-common/src/main/java/com/anontokyo/lottery/common/Constants.java",
              "line": 10,
              "end_line": 23
            },
            {
              "path": "pom.xml",
              "line": 12,
              "end_line": 18
            }
          ]
        }
      ]
    },
    {
      "chapter": "overview",
      "id": "02-runtime",
      "title": "运行组件与存储",
      "summary": "Redis/MySQL/Kafka是外部服务；Caffeine和dbRouter是应用内依赖。运行边界与代码分层分别阅读。",
      "facts": [
        {
          "title": "Redis：实时销售、缓存和短锁",
          "body": "活动/奖品库存预热后不设缓存TTL；配置有TTL；并发锁、投递标记、发奖标记分别3/60/300秒。",
          "why": "DECR承接竞争；缓存减少读取，锁不记录最终完成。",
          "sources": [
            {
              "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/ActivityRepository.java",
              "line": 195,
              "end_line": 218
            },
            {
              "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/ActivityRepository.java",
              "line": 264,
              "end_line": 285
            },
            {
              "path": "lottery-common/src/main/java/com/anontokyo/lottery/common/Constants.java",
              "line": 335,
              "end_line": 398
            }
          ]
        },
        {
          "title": "MySQL：全局事实与用户事实",
          "body": "db00保存activity/strategy/detail/award和库存账本。lottery_01/02保存参与、次数、中奖订单。",
          "why": "唯一键与事务形成最终幂等事实。",
          "sources": [
            {
              "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/StrategyRepository.java",
              "line": 75,
              "end_line": 110
            },
            {
              "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/UserTakeActivityRepository.java",
              "line": 93,
              "end_line": 117
            }
          ]
        },
        {
          "title": "dbRouterForGem：选库与订单表",
          "body": "外部starter按uId选库；参与/次数只分库，订单每库4张_000..003。后台显式遍历dbIdx/tbIdx。",
          "why": "同用户数据同库可开本地事务；它不是跨库事务协调器或网络网关。",
          "sources": [
            {
              "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/dao/IUserTakeActivityDao.java",
              "line": 11,
              "end_line": 24
            },
            {
              "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/dao/IUserStrategyExportDao.java",
              "line": 16,
              "end_line": 24
            },
            {
              "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/OrderRepository.java",
              "line": 171,
              "end_line": 188
            }
          ]
        },
        {
          "title": "Kafka：两个异步topic",
          "body": "lottery_strategy_award_stock和lottery_invoice，消息key都是orderId，不同topic位点独立。",
          "why": "脱离请求线程；允许重复投递，数据库保证重复执行安全。",
          "sources": [
            {
              "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/mq/producer/KafkaProducer.java",
              "line": 18,
              "end_line": 41
            },
            {
              "path": "lottery-interfaces/src/main/resources/application.yml",
              "line": 47,
              "end_line": 70
            }
          ]
        },
        {
          "title": "XXL-Job：三类任务",
          "body": "活动状态/预热、库存Outbox投递、发奖补偿；执行器在应用内，admin在外部。触发计划需在调度中心配置。",
          "why": "代码注册不代表环境已经调度；持久责任需要任务实际执行。",
          "sources": [
            {
              "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/worker/LotteryXxlJob.java",
              "line": 61,
              "end_line": 114
            },
            {
              "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/worker/LotteryXxlJob.java",
              "line": 115,
              "end_line": 184
            },
            {
              "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/worker/LotteryXxlJob.java",
              "line": 193,
              "end_line": 229
            }
          ]
        },
        {
          "title": "Caffeine：只缓存策略快照",
          "body": "L1默认60秒/10000项，Redis L2=600秒+0~120秒；活动只用单级Redis。",
          "why": "高频配置编译结果复用；没有跨实例广播，TTL内可能读到旧配置。",
          "sources": [
            {
              "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/cache/TwoLevelCacheService.java",
              "line": 17,
              "end_line": 22
            },
            {
              "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/cache/TwoLevelCacheService.java",
              "line": 39,
              "end_line": 90
            },
            {
              "path": "lottery-interfaces/src/main/resources/application.yml",
              "line": 96,
              "end_line": 108
            }
          ]
        }
      ]
    },
    {
      "chapter": "draw",
      "id": "03-participation",
      "title": "参与与资格",
      "summary": "首次请求：活动100份、优惠券5份、用户3次机会。T1/O1是实际Long类型Snowflake ID的教学代号。",
      "facts": []
    },
    {
      "chapter": "draw",
      "id": "04-draw-order",
      "title": "中奖与订单",
      "summary": "沿T1继续：策略采样、奖品预扣、TX2与HTTP返回。同步成功仍未完成MySQL奖品结算或发奖。",
      "facts": []
    },
    {
      "chapter": "draw",
      "id": "14-no-prize",
      "title": "未中奖分支",
      "summary": "未中奖消费take并返回D001；活动机会与次数消耗，但不产生中奖订单或异步消息。",
      "facts": []
    },
    {
      "chapter": "async",
      "id": "05-outbox-dispatch",
      "title": "Outbox 投递",
      "summary": "从正常中奖订单O1继续。Job扫用户库后发送库存消息，HTTP没有直接发送Kafka。",
      "facts": []
    },
    {
      "chapter": "async",
      "id": "06-stock-settlement",
      "title": "库存账本结算",
      "summary": "全局库事务绑定唯一账本与奖品库存；用户订单状态独立提交，由重放衔接。",
      "facts": []
    },
    {
      "chapter": "async",
      "id": "07-fulfillment",
      "title": "发奖与闭环",
      "summary": "四类handler当前都是模拟渠道。本地grant与mq可以收口，外部实际交付尚未实现。",
      "facts": []
    },
    {
      "chapter": "async",
      "id": "08-stock-states",
      "title": "库存消息状态",
      "summary": "INIT、SENT、COMPLETE、FAIL与BLOCKED表达不同责任。",
      "facts": [
        {
          "title": "发送和消费可以交错",
          "body": "消费从0/2/3/4→1；迟到发送通知不能覆盖1或4。发送成功0/2→3，失败仅0→2。",
          "why": "数据库业务完成事实优先于迟到回调。",
          "sources": [
            {
              "path": "lottery-interfaces/src/main/resources/mybatis/mapper/UserStrategyExport_Mapper.xml",
              "line": 52,
              "end_line": 67
            }
          ]
        },
        {
          "title": "SENT 超时恢复",
          "body": "Job扫描共享update_time超10分钟的SENT；已有SENT重复回调不刷新时钟。",
          "why": "可能重复发送；库存安全仍来自唯一账本。",
          "sources": [
            {
              "path": "lottery-interfaces/src/main/resources/mybatis/mapper/UserStrategyExport_Mapper.xml",
              "line": 112,
              "end_line": 120
            },
            {
              "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/StrategyRepository.java",
              "line": 95,
              "end_line": 110
            }
          ]
        }
      ]
    },
    {
      "chapter": "async",
      "id": "09-invoice-states",
      "title": "发奖消息状态",
      "summary": "mq_state负责消息，grant_state负责本地发奖事实。SENT不能当作发奖完成。",
      "facts": [
        {
          "title": "补偿同时看三列",
          "body": "stock=1且grant!=1；FAIL立即扫，INIT创建超30分钟，SENT更新超10分钟；历史mq=1但grant!=1也按10分钟恢复。每表LIMIT100。",
          "why": "库存未结算不进入发奖；历史误写消息完成不能漏掉未发奖订单。",
          "sources": [
            {
              "path": "lottery-interfaces/src/main/resources/mybatis/mapper/UserStrategyExport_Mapper.xml",
              "line": 100,
              "end_line": 110
            },
            {
              "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/worker/LotteryXxlJob.java",
              "line": 149,
              "end_line": 174
            }
          ]
        },
        {
          "title": "grant 已完成而 mq 未收口",
          "body": "重放先查grant=1，写mq=1后ack；当前主链路没有写grant=FAIL(2)。",
          "why": "修复发奖与位点之间的空隙；本地CAS不证明外部恰好一次。",
          "sources": [
            {
              "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/LotteryInvoiceListener.java",
              "line": 71,
              "end_line": 107
            }
          ]
        }
      ]
    },
    {
      "chapter": "recovery",
      "id": "10-request-retry",
      "title": "重复与中断请求",
      "summary": "区分并发D006、原请求回放/继续、不同请求遇旧take的D007。",
      "facts": [
        {
          "title": "同请求已完成",
          "body": "take=1，有订单回放同O1和奖品；无订单回放D001，不重复扣库存或次数。",
          "why": "网络超时后取回原结果。",
          "sources": [
            {
              "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java",
              "line": 208,
              "end_line": 229
            }
          ]
        },
        {
          "title": "同请求未完成",
          "body": "take=0，继续原strategyId/takeId；尚未持久完成的采样可能再次执行。",
          "why": "不重复创建机会，但不保证重放尚未落库的随机结果。",
          "sources": [
            {
              "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java",
              "line": 98,
              "end_line": 105
            },
            {
              "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java",
              "line": 129,
              "end_line": 130
            }
          ]
        },
        {
          "title": "不同请求不能接管",
          "body": "B遇A返回D007和pendingRequestId=A。无归属旧take返回D008核实。",
          "why": "保护机会归属。",
          "sources": [
            {
              "path": "lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/partake/BaseActivityPartake.java",
              "line": 74,
              "end_line": 90
            }
          ]
        },
        {
          "title": "唯一键和消费CAS",
          "body": "V3为UNIQUE(u_id,activity_id,request_id)；生成列唯一限制一条未完成参与。锁过期竞争用take CAS决定消费权。",
          "why": "不能只依赖3秒短锁。查询异常直接中断，不当作无记录。",
          "sources": [
            {
              "path": "docs/sql/V3__draw_request_ownership.sql",
              "line": 35,
              "end_line": 61
            },
            {
              "path": "lottery-interfaces/src/main/resources/mybatis/mapper/UserTakeActivity_Mapper.xml",
              "line": 28,
              "end_line": 30
            },
            {
              "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java",
              "line": 183,
              "end_line": 201
            }
          ]
        }
      ]
    },
    {
      "chapter": "recovery",
      "id": "11-stock-key-missing",
      "title": "Redis 库存缺失",
      "summary": "活动Key在take前检查；奖品Key在take创建后检查。运行期不以MySQL自动回填。",
      "facts": [
        {
          "title": "活动 Key 缺失",
          "body": "D005 + error，不建新take、不扣用户次数。activity数据库不随每次请求实时扣减。",
          "why": "不能把已消耗活动库存重新上架。",
          "sources": [
            {
              "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/ActivityRepository.java",
              "line": 195,
              "end_line": 224
            },
            {
              "path": "lottery-interfaces/src/main/resources/mybatis/mapper/Activity_Mapper.xml",
              "line": 44,
              "end_line": 49
            }
          ]
        },
        {
          "title": "奖品 Key 缺失与连接异常",
          "body": "Key缺失soldOut→FAIL→消费take→D001；读连接异常抛出，保留未消费take由原requestId恢复。",
          "why": "两种现有分支不能混画成自动补库存。",
          "sources": [
            {
              "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/StrategyRepository.java",
              "line": 122,
              "end_line": 142
            },
            {
              "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java",
              "line": 132,
              "end_line": 150
            }
          ]
        },
        {
          "title": "启动能补齐，运行恢复未实现",
          "body": "PASS活动行锁内SETNX全部库存，再CAS DOING；失败保持PASS，已写Key保留，下轮补齐。DOING不能再次预热。",
          "why": "运行期有未结算责任，需人工核实；没有自动对账算法。",
          "sources": [
            {
              "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/ActivityRepository.java",
              "line": 113,
              "end_line": 133
            },
            {
              "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/ActivityRepository.java",
              "line": 243,
              "end_line": 285
            }
          ]
        }
      ]
    },
    {
      "chapter": "recovery",
      "id": "12-consumer-crash",
      "title": "重复消息与宕机",
      "summary": "同orderId崩溃前后靠持久账本接续，不依赖Redis DONE。",
      "facts": [
        {
          "title": "TX3 提交后状态没完成",
          "body": "INSERT相同orderId唯一冲突，成功短路不再扣减；继续stock=1、invoice、ack。",
          "why": "覆盖库存已扣但应用退出的窗口。",
          "sources": [
            {
              "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/StrategyRepository.java",
              "line": 87,
              "end_line": 110
            },
            {
              "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/LotteryStrategyAwardStockListener.java",
              "line": 81,
              "end_line": 101
            }
          ]
        },
        {
          "title": "stock=1 后 invoice 没发",
          "body": "库存消息重放直接ack；发奖补偿要求stock=1、grant!=1，FAIL或超时INIT等可恢复。",
          "why": "订单继续保留发奖责任。",
          "sources": [
            {
              "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/LotteryStrategyAwardStockListener.java",
              "line": 63,
              "end_line": 67
            },
            {
              "path": "lottery-interfaces/src/main/resources/mybatis/mapper/UserStrategyExport_Mapper.xml",
              "line": 100,
              "end_line": 110
            }
          ]
        },
        {
          "title": "Kafka 发送失败",
          "body": "INIT→FAIL，下轮Job重发；已有SENT不被失败回调回退，10分钟可再投。发送成功但落状态前宕机可产生重复消息。",
          "why": "Outbox解决漏发责任，账本解决重复执行。",
          "sources": [
            {
              "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/worker/LotteryXxlJob.java",
              "line": 247,
              "end_line": 271
            },
            {
              "path": "lottery-interfaces/src/main/resources/mybatis/mapper/UserStrategyExport_Mapper.xml",
              "line": 52,
              "end_line": 60
            }
          ]
        },
        {
          "title": "部署前提",
          "body": "手动位点提交、异常不ack；没有配置专用死信队列或永久无限重试保证。超时扫描依赖Job真实运行。",
          "why": "源码恢复路径不等价于未经验证的部署可靠性承诺。",
          "sources": [
            {
              "path": "lottery-interfaces/src/main/resources/application.yml",
              "line": 47,
              "end_line": 70
            },
            {
              "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/LotteryInvoiceListener.java",
              "line": 103,
              "end_line": 110
            }
          ]
        }
      ]
    },
    {
      "chapter": "recovery",
      "id": "13-blocked-reconcile",
      "title": "库存分歧与人工对账",
      "summary": "扣减失败是业务分歧：回滚账本、持久BLOCKED、停止自动扫描，等待人工核实。",
      "facts": [
        {
          "title": "先持久化事实才 ack",
          "body": "独立CAS写4；零行回读确认BLOCKED/COMPLETE，否则抛异常、不ack。",
          "why": "避免应结算订单没有任何待处理事实。",
          "sources": [
            {
              "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/OrderRepository.java",
              "line": 126,
              "end_line": 142
            },
            {
              "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/LotteryStrategyAwardStockListener.java",
              "line": 81,
              "end_line": 91
            }
          ]
        },
        {
          "title": "对账后释放原 Outbox",
          "body": "人工修复后在正确用户分片按同orderId CAS 4→0，由Job重投。没有自动对账接口或运行期通用修复脚本。",
          "why": "先修复原因，保持同一个账本幂等键；盲目重试不修复库存事实。",
          "sources": [
            {
              "path": "docs/architecture.md",
              "line": 44,
              "end_line": 48
            },
            {
              "path": "lottery-interfaces/src/main/resources/mybatis/mapper/UserStrategyExport_Mapper.xml",
              "line": 112,
              "end_line": 120
            }
          ]
        }
      ]
    }
  ],
  "sync": [
    {
      "id": "s01",
      "title": "HTTP 接收三个关键字段",
      "layer": "interfaces",
      "method": "LotteryController.draw / DrawRequest",
      "inputs": "requestId=learn-req-001；userId=u1；activityId=100001",
      "action": "@Validated 校验请求号非空且≤64字符、用户号非空且≤32字符、活动号非null；日志记录三个关联字段。",
      "outputs": "DrawRequest表示客户端这一次逻辑抽奖。",
      "why": "请求号让网络超时重试可被识别为同一次抽奖。HTTP校验不负责库存与抽样。",
      "next": "Controller → IActivityDrawProcess.doDrawProcess",
      "sources": [
        {
          "path": "lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/LotteryController.java",
          "line": 37,
          "end_line": 51
        },
        {
          "path": "lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/dto/DrawRequest.java",
          "line": 11,
          "end_line": 23
        }
      ],
      "diagram": "03-participation",
      "relation": "s01",
      "changes": {}
    },
    {
      "id": "s02",
      "title": "将外部 DTO 转为应用请求",
      "layer": "interfaces → application",
      "method": "LotteryController.draw → DrawProcessReq",
      "inputs": "HTTP 的 userId / activityId / requestId",
      "action": "new DrawProcessReq(userId, activityId, requestId)。外部userId在内部叫uId，值未改变。",
      "outputs": "DrawProcessReq{uId,activityId,requestId}",
      "why": "外部HTTP契约与内部用例分开变化；Controller保持薄层。",
      "next": "ActivityProcessImpl.doDrawProcess",
      "sources": [
        {
          "path": "lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/LotteryController.java",
          "line": 41,
          "end_line": 44
        }
      ],
      "diagram": "03-participation",
      "relation": "s02",
      "changes": {}
    },
    {
      "id": "s03",
      "title": "获得用户与活动维度的短锁",
      "layer": "application → infrastructure → Redis",
      "method": "IActivityDrawProcessGuard.tryClaim",
      "inputs": "activityId + uId；本次执行新生成的UUID token",
      "action": "SETNX lottery_draw_inflight_100001_u1，写token并设3秒TTL；占用时返回D006。",
      "outputs": "本线程暂时获得进入主链路的资格；token不是requestId。",
      "why": "短锁减少并发重复执行，会过期；最终幂等由数据库约束和CAS保证。",
      "next": "查询原请求的take",
      "sources": [
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java",
          "line": 69,
          "end_line": 75
        },
        {
          "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/lock/RedisActivityDrawProcessGuard.java",
          "line": 24,
          "end_line": 45
        }
      ],
      "diagram": "03-participation",
      "relation": "s03",
      "changes": {
        "lock": "token 持有（TTL 3秒）"
      }
    },
    {
      "id": "s04",
      "title": "先查询 requestId 的持久结果",
      "layer": "application → infrastructure → 用户 MySQL",
      "method": "findTakeByRequestId → queryTakeActivityByRequestId",
      "inputs": "uId=u1 + activityId=100001 + requestId=learn-req-001",
      "action": "按uId路由，精确查询user_take_activity。正常首次请求得到null。SQL异常直接传播，不等价于无记录。",
      "outputs": "无记录才新参与；state=1回放；state=0继续原take。",
      "why": "避免超时重试重复扣活动库存和次数；唯一键仍负责并发竞争。",
      "next": "IActivityPartake.doPartake",
      "sources": [
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java",
          "line": 82,
          "end_line": 127
        },
        {
          "path": "lottery-interfaces/src/main/resources/mybatis/mapper/UserTakeActivity_Mapper.xml",
          "line": 42,
          "end_line": 47
        },
        {
          "path": "docs/sql/V3__draw_request_ownership.sql",
          "line": 35,
          "end_line": 61
        }
      ],
      "diagram": "03-participation",
      "relation": "s04sql",
      "changes": {}
    },
    {
      "id": "s05",
      "title": "核对其他未完成参与的归属",
      "layer": "domain → infrastructure → 用户 MySQL",
      "method": "BaseActivityPartake.doPartake / resolveUnfinishedTake",
      "inputs": "当前uId + activityId + requestId",
      "action": "查用户活动state=0的take。原requestId才能继续；B遇到A的take返回D007与pendingRequestId=A。",
      "outputs": "本例无未完成记录，可以检查活动。未知请求归属的旧take返回D008。",
      "why": "take代表已经消耗的机会，不能让后来的不同请求接管。",
      "next": "queryActivityBill",
      "sources": [
        {
          "path": "lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/partake/BaseActivityPartake.java",
          "line": 29,
          "end_line": 39
        },
        {
          "path": "lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/partake/BaseActivityPartake.java",
          "line": 74,
          "end_line": 90
        }
      ],
      "diagram": "03-participation",
      "relation": "s05pending",
      "changes": {}
    },
    {
      "id": "s06",
      "title": "读取活动单据并检查资格",
      "layer": "domain → infrastructure → Redis / MySQL",
      "method": "queryActivityBill → ActivityPartakeImpl.checkActivityBill",
      "inputs": "activityId；PartakeReq中的参与时间",
      "action": "读取活动Redis配置缓存，miss时读MySQL activity；检查DOING(5)、开始结束时间、单据库存>0。",
      "outputs": "ActivityBillVO含strategyId=10001、takeCount、库存、状态、时间窗和uId。",
      "why": "活动配置决定是否允许参与；配置库存不替代下一步Redis实时库存检查。用户次数由TX1条件UPDATE校验。",
      "next": "substractionActivityStockByRedis",
      "sources": [
        {
          "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/ActivityRepository.java",
          "line": 150,
          "end_line": 169
        },
        {
          "path": "lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/partake/impl/ActivityPartakeImpl.java",
          "line": 29,
          "end_line": 46
        }
      ],
      "diagram": "03-participation",
      "relation": "s06cache",
      "changes": {}
    },
    {
      "id": "s07",
      "title": "预扣一份活动库存",
      "layer": "domain → infrastructure → Redis",
      "method": "substractionActivityStockByRedis",
      "inputs": "lottery_activity_stock_count_100001=100（教学值）",
      "action": "GET确认Key存在且>0，再DECR 1。并发扣成负数则INCR回补并返回D004；Key缺失或操作异常返回D005。",
      "outputs": "Redis活动剩余100→99；MySQL activity仍为100。",
      "why": "Redis原子扣减控制可创建的参与机会。运行期不能用滞后数据库值回填。",
      "next": "生成takeId并调用参与仓储",
      "sources": [
        {
          "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/ActivityRepository.java",
          "line": 190,
          "end_line": 225
        },
        {
          "path": "lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/partake/BaseActivityPartake.java",
          "line": 42,
          "end_line": 52
        }
      ],
      "diagram": "03-participation",
      "relation": "s07redis",
      "changes": {
        "activityRedis": 99
      }
    },
    {
      "id": "s08",
      "title": "TX1 扣次数并创建 take",
      "layer": "domain → infrastructure → 用户 MySQL",
      "method": "IdGenerator.nextId → takeParticipation",
      "inputs": "ActivityBillVO + requestId + Snowflake takeId=T1；用户余次数3",
      "action": "按uId选库再开本地事务；必要时初始化次数行，条件扣left_count，再INSERT take(state=0,request_id)。finally清路由。",
      "outputs": "次数3→2；持久take T1关联用户、活动、策略和requestId。返回PartakeResult。",
      "why": "次数与take同事务。失败回滚TX1并回补活动Redis；回补错误只记日志，不能宣称补偿必达。",
      "next": "应用拿到strategyId / takeId → DrawExecutor",
      "sources": [
        {
          "path": "lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/partake/BaseActivityPartake.java",
          "line": 49,
          "end_line": 71
        },
        {
          "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/UserTakeActivityRepository.java",
          "line": 88,
          "end_line": 117
        },
        {
          "path": "lottery-interfaces/src/main/resources/mybatis/mapper/UserTakeActivityCount_Mapper.xml",
          "line": 13,
          "end_line": 25
        }
      ],
      "diagram": "03-participation",
      "relation": "s08tx",
      "changes": {
        "quota": 2,
        "take": "T1 · 未消费(0)"
      }
    },
    {
      "id": "s09",
      "title": "策略快照与固定概率采样",
      "layer": "application → domain → infrastructure",
      "method": "DrawExecutor.doDrawExec / WeightedRandomSelector.select",
      "inputs": "DrawReq{uId=u1,strategyId=10001,uuid=String.valueOf(T1)}",
      "action": "快照来源Caffeine L1 → Redis L2 → MySQL。概率×1,000,000转整数前缀和，随机点二分采样；差额为NO_PRIZE。",
      "outputs": "本例命中awardId=3。这里DrawReq.uuid是takeId字符串，订单uuid稍后才产生。",
      "why": "编译快照避免热路径反复查详情和算概率。售罄不重摇、不把概率分给其他奖品。",
      "next": "StrategyRepository.deductStockByRedis",
      "sources": [
        {
          "path": "lottery-domain/src/main/java/com/anontokyo/lottery/domain/strategy/service/draw/impl/DrawExecutor.java",
          "line": 31,
          "end_line": 46
        },
        {
          "path": "lottery-domain/src/main/java/com/anontokyo/lottery/domain/strategy/service/snapshot/StrategySnapshotFactory.java",
          "line": 36,
          "end_line": 84
        },
        {
          "path": "lottery-domain/src/main/java/com/anontokyo/lottery/domain/strategy/service/draw/WeightedRandomSelector.java",
          "line": 19,
          "end_line": 38
        }
      ],
      "diagram": "04-draw-order",
      "relation": "s09snapshot",
      "changes": {}
    },
    {
      "id": "s10",
      "title": "预扣命中奖品的 Redis 库存",
      "layer": "domain → infrastructure → Redis",
      "method": "StrategyRepository.deductStockByRedis",
      "inputs": "lottery_strategy_award_stock_count_10001_3=5（教学值）",
      "action": "GET存在且>0后DECR 1；负数回补并判不可售。Key缺失记error并不可售；读取Redis异常向上抛出，保留未完成take。",
      "outputs": "奖品Redis 5→4；DrawAwardInfo含奖品详情与发奖配置；MySQL奖品仍为5。",
      "why": "概率命中不等于库存足够，Redis控制实时销售，MySQL库存后续异步结算。",
      "next": "应用层 → AwardOrderImpl.placeOrder",
      "sources": [
        {
          "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/StrategyRepository.java",
          "line": 117,
          "end_line": 145
        },
        {
          "path": "lottery-domain/src/main/java/com/anontokyo/lottery/domain/strategy/service/draw/impl/DrawExecutor.java",
          "line": 49,
          "end_line": 60
        }
      ],
      "diagram": "04-draw-order",
      "relation": "s10redis",
      "changes": {
        "prizeRedis": 4,
        "award": "awardId=3 · 优惠券类型3"
      }
    },
    {
      "id": "s11",
      "title": "生成中奖订单标识",
      "layer": "application → domain",
      "method": "buildAwardOrderReq → AwardOrderImpl.buildDrawOrder",
      "inputs": "uId + activityId + strategyId + takeId=T1 + DrawAwardInfo",
      "action": "应用组AwardOrderReq；订单领域服务生成新的Snowflake orderId=O1，三个状态grant/mq/stock_mq都置0。",
      "outputs": "DrawOrderVO带takeId和奖品快照；orderId与takeId是两个独立ID。",
      "why": "take证明参与机会，order证明中奖履约责任；未中奖有take但无订单。",
      "next": "OrderRepository.saveDrawOrderWithTakeLock",
      "sources": [
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java",
          "line": 237,
          "end_line": 250
        },
        {
          "path": "lottery-domain/src/main/java/com/anontokyo/lottery/domain/award/service/order/impl/AwardOrderImpl.java",
          "line": 107,
          "end_line": 124
        }
      ],
      "diagram": "04-draw-order",
      "relation": "s11repo",
      "changes": {
        "orderDraft": "O1 · 尚未提交"
      }
    },
    {
      "id": "s12",
      "title": "TX2 消费 take 并提交订单 Outbox",
      "layer": "infrastructure → 用户 MySQL",
      "method": "OrderRepository.saveDrawOrderWithTakeLock",
      "inputs": "DrawOrderVO{T1,O1,uId,活动,策略,奖品,三个状态}",
      "action": "同一用户库事务：CAS take 0→1，再INSERT中奖订单分表；uuid=String(orderId)。任一业务失败整体回滚。",
      "outputs": "take已消费；O1落库；stock_mq_state=0同时留下待发送责任。",
      "why": "take消费与订单提交原子完成。Outbox是订单字段，不是另一张表；HTTP链路不直接发Kafka。",
      "next": "返回InvoiceVO；XXL-Job后续扫描原订单",
      "sources": [
        {
          "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/OrderRepository.java",
          "line": 38,
          "end_line": 65
        },
        {
          "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/OrderRepository.java",
          "line": 87,
          "end_line": 105
        },
        {
          "path": "lottery-interfaces/src/main/resources/mybatis/mapper/UserStrategyExport_Mapper.xml",
          "line": 27,
          "end_line": 35
        }
      ],
      "diagram": "04-draw-order",
      "relation": "s12",
      "changes": {
        "take": "T1 · 已消费(1)",
        "order": "O1 · 已提交",
        "orderDraft": null,
        "stockState": "INIT(0)",
        "invoiceState": "INIT(0)",
        "grantState": "INIT(0)"
      }
    },
    {
      "id": "s13",
      "title": "释放本请求的短锁",
      "layer": "application → infrastructure → Redis",
      "method": "finally → RedisActivityDrawProcessGuard.release",
      "inputs": "原activityId + uId + token",
      "action": "Lua比较原token，相同才删除；过期或已归新线程时不删除对方锁。",
      "outputs": "当前请求结束，锁释放或自然过期。",
      "why": "异常也走finally。属主校验避免慢线程删除后来请求的锁；MySQL保留结果。",
      "next": "Controller.toResponse",
      "sources": [
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java",
          "line": 169,
          "end_line": 180
        },
        {
          "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/lock/RedisActivityDrawProcessGuard.java",
          "line": 39,
          "end_line": 49
        }
      ],
      "diagram": "04-draw-order",
      "relation": "s13",
      "changes": {
        "lock": "已释放 / 已过期"
      }
    },
    {
      "id": "s14",
      "title": "HTTP 返回中奖，异步继续",
      "layer": "application → interfaces → 客户端",
      "method": "LotteryController.toResponse",
      "inputs": "DrawProcessResult{code=0000,takeId=T1,orderId=O1,DrawAwardInfo}",
      "action": "回带requestId、takeId、orderId；仅code成功且有奖品时win=true。返回不等待库存结算和发奖。",
      "outputs": "客户端收到中奖结果；三个订单状态仍是INIT(0)。",
      "why": "减少请求延迟；网络超时用原requestId回放。win=true不能证明用户已收到奖品。",
      "next": "原订单Outbox → 下一轮已配置的XXL-Job",
      "sources": [
        {
          "path": "lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/LotteryController.java",
          "line": 72,
          "end_line": 95
        },
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java",
          "line": 169,
          "end_line": 177
        }
      ],
      "diagram": "04-draw-order",
      "relation": "s14",
      "changes": {
        "response": "0000 · win=true；异步尚未闭环"
      }
    }
  ],
  "losing": [
    {
      "id": "s01",
      "title": "HTTP 接收三个关键字段",
      "layer": "interfaces",
      "method": "LotteryController.draw / DrawRequest",
      "inputs": "requestId=learn-req-001；userId=u1；activityId=100001",
      "action": "@Validated 校验请求号非空且≤64字符、用户号非空且≤32字符、活动号非null；日志记录三个关联字段。",
      "outputs": "DrawRequest表示客户端这一次逻辑抽奖。",
      "why": "请求号让网络超时重试可被识别为同一次抽奖。HTTP校验不负责库存与抽样。",
      "next": "Controller → IActivityDrawProcess.doDrawProcess",
      "sources": [
        {
          "path": "lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/LotteryController.java",
          "line": 37,
          "end_line": 51
        },
        {
          "path": "lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/dto/DrawRequest.java",
          "line": 11,
          "end_line": 23
        }
      ],
      "diagram": "03-participation",
      "relation": "s01",
      "changes": {}
    },
    {
      "id": "s02",
      "title": "将外部 DTO 转为应用请求",
      "layer": "interfaces → application",
      "method": "LotteryController.draw → DrawProcessReq",
      "inputs": "HTTP 的 userId / activityId / requestId",
      "action": "new DrawProcessReq(userId, activityId, requestId)。外部userId在内部叫uId，值未改变。",
      "outputs": "DrawProcessReq{uId,activityId,requestId}",
      "why": "外部HTTP契约与内部用例分开变化；Controller保持薄层。",
      "next": "ActivityProcessImpl.doDrawProcess",
      "sources": [
        {
          "path": "lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/LotteryController.java",
          "line": 41,
          "end_line": 44
        }
      ],
      "diagram": "03-participation",
      "relation": "s02",
      "changes": {}
    },
    {
      "id": "s03",
      "title": "获得用户与活动维度的短锁",
      "layer": "application → infrastructure → Redis",
      "method": "IActivityDrawProcessGuard.tryClaim",
      "inputs": "activityId + uId；本次执行新生成的UUID token",
      "action": "SETNX lottery_draw_inflight_100001_u1，写token并设3秒TTL；占用时返回D006。",
      "outputs": "本线程暂时获得进入主链路的资格；token不是requestId。",
      "why": "短锁减少并发重复执行，会过期；最终幂等由数据库约束和CAS保证。",
      "next": "查询原请求的take",
      "sources": [
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java",
          "line": 69,
          "end_line": 75
        },
        {
          "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/lock/RedisActivityDrawProcessGuard.java",
          "line": 24,
          "end_line": 45
        }
      ],
      "diagram": "03-participation",
      "relation": "s03",
      "changes": {
        "lock": "token 持有（TTL 3秒）"
      }
    },
    {
      "id": "s04",
      "title": "先查询 requestId 的持久结果",
      "layer": "application → infrastructure → 用户 MySQL",
      "method": "findTakeByRequestId → queryTakeActivityByRequestId",
      "inputs": "uId=u1 + activityId=100001 + requestId=learn-req-001",
      "action": "按uId路由，精确查询user_take_activity。正常首次请求得到null。SQL异常直接传播，不等价于无记录。",
      "outputs": "无记录才新参与；state=1回放；state=0继续原take。",
      "why": "避免超时重试重复扣活动库存和次数；唯一键仍负责并发竞争。",
      "next": "IActivityPartake.doPartake",
      "sources": [
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java",
          "line": 82,
          "end_line": 127
        },
        {
          "path": "lottery-interfaces/src/main/resources/mybatis/mapper/UserTakeActivity_Mapper.xml",
          "line": 42,
          "end_line": 47
        },
        {
          "path": "docs/sql/V3__draw_request_ownership.sql",
          "line": 35,
          "end_line": 61
        }
      ],
      "diagram": "03-participation",
      "relation": "s04sql",
      "changes": {}
    },
    {
      "id": "s05",
      "title": "核对其他未完成参与的归属",
      "layer": "domain → infrastructure → 用户 MySQL",
      "method": "BaseActivityPartake.doPartake / resolveUnfinishedTake",
      "inputs": "当前uId + activityId + requestId",
      "action": "查用户活动state=0的take。原requestId才能继续；B遇到A的take返回D007与pendingRequestId=A。",
      "outputs": "本例无未完成记录，可以检查活动。未知请求归属的旧take返回D008。",
      "why": "take代表已经消耗的机会，不能让后来的不同请求接管。",
      "next": "queryActivityBill",
      "sources": [
        {
          "path": "lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/partake/BaseActivityPartake.java",
          "line": 29,
          "end_line": 39
        },
        {
          "path": "lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/partake/BaseActivityPartake.java",
          "line": 74,
          "end_line": 90
        }
      ],
      "diagram": "03-participation",
      "relation": "s05pending",
      "changes": {}
    },
    {
      "id": "s06",
      "title": "读取活动单据并检查资格",
      "layer": "domain → infrastructure → Redis / MySQL",
      "method": "queryActivityBill → ActivityPartakeImpl.checkActivityBill",
      "inputs": "activityId；PartakeReq中的参与时间",
      "action": "读取活动Redis配置缓存，miss时读MySQL activity；检查DOING(5)、开始结束时间、单据库存>0。",
      "outputs": "ActivityBillVO含strategyId=10001、takeCount、库存、状态、时间窗和uId。",
      "why": "活动配置决定是否允许参与；配置库存不替代下一步Redis实时库存检查。用户次数由TX1条件UPDATE校验。",
      "next": "substractionActivityStockByRedis",
      "sources": [
        {
          "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/ActivityRepository.java",
          "line": 150,
          "end_line": 169
        },
        {
          "path": "lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/partake/impl/ActivityPartakeImpl.java",
          "line": 29,
          "end_line": 46
        }
      ],
      "diagram": "03-participation",
      "relation": "s06cache",
      "changes": {}
    },
    {
      "id": "s07",
      "title": "预扣一份活动库存",
      "layer": "domain → infrastructure → Redis",
      "method": "substractionActivityStockByRedis",
      "inputs": "lottery_activity_stock_count_100001=100（教学值）",
      "action": "GET确认Key存在且>0，再DECR 1。并发扣成负数则INCR回补并返回D004；Key缺失或操作异常返回D005。",
      "outputs": "Redis活动剩余100→99；MySQL activity仍为100。",
      "why": "Redis原子扣减控制可创建的参与机会。运行期不能用滞后数据库值回填。",
      "next": "生成takeId并调用参与仓储",
      "sources": [
        {
          "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/ActivityRepository.java",
          "line": 190,
          "end_line": 225
        },
        {
          "path": "lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/partake/BaseActivityPartake.java",
          "line": 42,
          "end_line": 52
        }
      ],
      "diagram": "03-participation",
      "relation": "s07redis",
      "changes": {
        "activityRedis": 99
      }
    },
    {
      "id": "s08",
      "title": "TX1 扣次数并创建 take",
      "layer": "domain → infrastructure → 用户 MySQL",
      "method": "IdGenerator.nextId → takeParticipation",
      "inputs": "ActivityBillVO + requestId + Snowflake takeId=T1；用户余次数3",
      "action": "按uId选库再开本地事务；必要时初始化次数行，条件扣left_count，再INSERT take(state=0,request_id)。finally清路由。",
      "outputs": "次数3→2；持久take T1关联用户、活动、策略和requestId。返回PartakeResult。",
      "why": "次数与take同事务。失败回滚TX1并回补活动Redis；回补错误只记日志，不能宣称补偿必达。",
      "next": "应用拿到strategyId / takeId → DrawExecutor",
      "sources": [
        {
          "path": "lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/partake/BaseActivityPartake.java",
          "line": 49,
          "end_line": 71
        },
        {
          "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/UserTakeActivityRepository.java",
          "line": 88,
          "end_line": 117
        },
        {
          "path": "lottery-interfaces/src/main/resources/mybatis/mapper/UserTakeActivityCount_Mapper.xml",
          "line": 13,
          "end_line": 25
        }
      ],
      "diagram": "03-participation",
      "relation": "s08tx",
      "changes": {
        "quota": 2,
        "take": "T1 · 未消费(0)"
      }
    },
    {
      "id": "l09",
      "title": "NO_PRIZE：正常未中奖",
      "layer": "domain → application",
      "method": "WeightedRandomSelector.select → DrawExecutor.doDrawExec",
      "inputs": "T1与同一策略快照；本次随机命中NO_PRIZE",
      "action": "返回DrawState.FAIL；命中奖品售罄或Key缺失也走FAIL，不重摇。",
      "outputs": "本例无有效奖品、无奖品Redis预扣。",
      "why": "保留原概率与未中奖语义，不转移售罄奖品的概率。",
      "next": "IActivityPartake.consumeTakeActivity",
      "sources": [
        {
          "path": "lottery-domain/src/main/java/com/anontokyo/lottery/domain/strategy/service/draw/impl/DrawExecutor.java",
          "line": 32,
          "end_line": 46
        },
        {
          "path": "lottery-domain/src/main/java/com/anontokyo/lottery/domain/strategy/service/snapshot/StrategySnapshotFactory.java",
          "line": 69,
          "end_line": 84
        }
      ],
      "diagram": "14-no-prize",
      "relation": "l09",
      "changes": {}
    },
    {
      "id": "l10",
      "title": "消费本次 take",
      "layer": "application → domain → infrastructure → 用户 MySQL",
      "method": "consumeTakeActivity → consumeTake → lockTakeActivity",
      "inputs": "uId + activityId + T1；state=0",
      "action": "单条CAS UPDATE state=1 WHERE ... state=0。零行说明消费权被其他线程取得，必须回读最终结果。",
      "outputs": "take 0→1；次数与活动库存保持已消耗；没有中奖订单。",
      "why": "不消费take会让同requestId下次恢复再次抽样，改变已完成结果。",
      "next": "返回D001，并finally释放锁",
      "sources": [
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java",
          "line": 132,
          "end_line": 150
        },
        {
          "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/UserTakeActivityRepository.java",
          "line": 120,
          "end_line": 128
        },
        {
          "path": "lottery-interfaces/src/main/resources/mybatis/mapper/UserTakeActivity_Mapper.xml",
          "line": 28,
          "end_line": 30
        }
      ],
      "diagram": "14-no-prize",
      "relation": "l10sql",
      "changes": {
        "take": "T1 · 已消费(1)"
      }
    },
    {
      "id": "l11",
      "title": "结束请求并释放短锁",
      "layer": "application → infrastructure → Redis",
      "method": "finally → release(activityId,uId,token)",
      "inputs": "原短锁token",
      "action": "只删除属主匹配的锁。",
      "outputs": "本次调用结束。",
      "why": "正常和异常结束都要释放并发控制责任。",
      "next": "Controller.toResponse",
      "sources": [
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java",
          "line": 178,
          "end_line": 180
        }
      ],
      "diagram": "14-no-prize",
      "relation": "l11",
      "changes": {
        "lock": "已释放 / 已过期"
      }
    },
    {
      "id": "l12",
      "title": "返回未中奖，无异步订单",
      "layer": "interfaces → 客户端",
      "method": "LotteryController.toResponse",
      "inputs": "code=D001,takeId=T1，无订单无奖品",
      "action": "win=false；orderId和award为空。同requestId重试：take已消费且无订单，回放D001。",
      "outputs": "活动Redis99、次数2、take已消费；奖品Redis/MySQL仍5；无Outbox、账本与发奖。",
      "why": "参与记录与中奖订单分开建模才能表达这条结束路径。",
      "next": "本逻辑请求结束",
      "sources": [
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java",
          "line": 208,
          "end_line": 217
        },
        {
          "path": "lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/LotteryController.java",
          "line": 72,
          "end_line": 95
        }
      ],
      "diagram": "14-no-prize",
      "relation": "l12",
      "changes": {
        "response": "D001 · win=false；流程结束"
      }
    }
  ],
  "async": [
    {
      "id": "a01",
      "title": "扫描库存 Outbox",
      "layer": "application → infrastructure → 用户 MySQL",
      "method": "awardStockSettlementOutboxJobHandler → scanAwardStockMqStateByShard",
      "inputs": "已提交O1；XXL-Job参数1或1,2指定库号",
      "action": "逐库逐订单分表扫stock=0/2，以及update_time超10分钟的3；配置每表最多200条，按create_time排序。",
      "outputs": "AwardStockSettlementVO{uId,orderId,strategyId,awardId}，不带requestId或takeId。",
      "why": "应用退出后仍能从数据库发现未完成工作。实际触发频率由admin配置。",
      "next": "领取DISPATCHING标记",
      "sources": [
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/worker/LotteryXxlJob.java",
          "line": 193,
          "end_line": 225
        },
        {
          "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/OrderRepository.java",
          "line": 155,
          "end_line": 189
        },
        {
          "path": "lottery-interfaces/src/main/resources/mybatis/mapper/UserStrategyExport_Mapper.xml",
          "line": 112,
          "end_line": 120
        }
      ],
      "diagram": "05-outbox-dispatch",
      "relation": "a01sql",
      "changes": {}
    },
    {
      "id": "a02",
      "title": "减少同轮重复投递",
      "layer": "application → infrastructure → Redis",
      "method": "tryClaimDispatch(orderId)",
      "inputs": "orderId=O1",
      "action": "SETNX lottery_strategy_award_stock_mq_dispatching_O1，TTL60秒；未领取则留给后续任务。",
      "outputs": "短期投递标记，不表示结算完成。",
      "why": "多worker可能扫到同订单；标记减少重复发送，账本负责最终安全。",
      "next": "KafkaProducer.sendStrategyAwardStock",
      "sources": [
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/worker/LotteryXxlJob.java",
          "line": 231,
          "end_line": 244
        },
        {
          "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/lock/RedisStrategyAwardStockMqGuard.java",
          "line": 55,
          "end_line": 83
        }
      ],
      "diagram": "05-outbox-dispatch",
      "relation": "a02",
      "changes": {
        "dispatchLock": "O1 · DISPATCHING"
      }
    },
    {
      "id": "a03",
      "title": "发送库存结算消息",
      "layer": "application → Kafka",
      "method": "KafkaProducer.sendStrategyAwardStock",
      "inputs": "库存VO{uId=u1,orderId=O1,strategyId=10001,awardId=3}",
      "action": "Fastjson序列化字符串，发送topic=lottery_strategy_award_stock，key=String(orderId)，返回Future。",
      "outputs": "Kafka收到消息不是业务消费完成。",
      "why": "结算脱离请求线程；orderId关联消息和账本，uId仍用于查订单路由。",
      "next": "回调与消费者可交错执行",
      "sources": [
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/mq/producer/KafkaProducer.java",
          "line": 34,
          "end_line": 41
        },
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/worker/LotteryXxlJob.java",
          "line": 243,
          "end_line": 266
        }
      ],
      "diagram": "05-outbox-dispatch",
      "relation": "a03",
      "changes": {
        "stockMessage": "已发送，等待回调/消费"
      }
    },
    {
      "id": "a04",
      "title": "发送回调推进 SENT",
      "layer": "application → infrastructure → 用户 MySQL / Redis",
      "method": "Future.onSuccess → updateStockMqState",
      "inputs": "uId + O1；发送成功回调",
      "action": "仅INIT/FAIL→SENT(3)，清投递标记。发送失败仅INIT→FAIL(2)，不覆盖SENT/COMPLETE/BLOCKED。",
      "outputs": "本例stock=3；消费者可能先完成，SQL保护终态。",
      "why": "发送成功不是消费完成；迟到回调不能回退，重复SENT不刷新超时时钟。",
      "next": "LotteryStrategyAwardStockListener",
      "sources": [
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/worker/LotteryXxlJob.java",
          "line": 247,
          "end_line": 264
        },
        {
          "path": "lottery-interfaces/src/main/resources/mybatis/mapper/UserStrategyExport_Mapper.xml",
          "line": 52,
          "end_line": 60
        }
      ],
      "diagram": "05-outbox-dispatch",
      "relation": "a04sql",
      "changes": {
        "stockState": "SENT(3)",
        "dispatchLock": "已清理"
      }
    },
    {
      "id": "a05",
      "title": "库存消费者先查订单事实",
      "layer": "application → domain → infrastructure → 用户 MySQL",
      "method": "LotteryStrategyAwardStockListener.onMessage",
      "inputs": "库存消息 + 手动Acknowledgment",
      "action": "反序列化VO，查询stock是否COMPLETE；已完成直接ack。再确认订单存在，才进入结算；不存在的旧消息记warn并ack。",
      "outputs": "有效O1进入settleAwardStock。",
      "why": "避免重复主流程；当前库存消费者没有调用Redis PROCESSING或DONE锁。",
      "next": "StrategyRepository.settleAwardStock",
      "sources": [
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/LotteryStrategyAwardStockListener.java",
          "line": 50,
          "end_line": 81
        },
        {
          "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/OrderRepository.java",
          "line": 218,
          "end_line": 228
        }
      ],
      "diagram": "06-stock-settlement",
      "relation": "a05read",
      "changes": {}
    },
    {
      "id": "a06",
      "title": "TX3：唯一账本与奖品库存",
      "layer": "infrastructure → 全局 MySQL db00",
      "method": "StrategyRepository.settleAwardStock",
      "inputs": "库存VO，O1；strategy_detail剩余5",
      "action": "事务先INSERT award_stock_settlement（order_id唯一），再条件UPDATE库存-1。唯一冲突直接成功；扣减0行回滚账本。",
      "outputs": "账本新增O1；MySQL奖品5→4。Redis保持已预扣的4。",
      "why": "同库原子提交跨越扣减后宕机的窗口；同O1重放不再扣库存。",
      "next": "独立更新用户库stock状态",
      "sources": [
        {
          "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/StrategyRepository.java",
          "line": 87,
          "end_line": 110
        },
        {
          "path": "docs/sql/V2__architecture_simplification.sql",
          "line": 14,
          "end_line": 29
        },
        {
          "path": "lottery-interfaces/src/main/resources/mybatis/mapper/StrategyDetail_Mapper.xml",
          "line": 43,
          "end_line": 48
        }
      ],
      "diagram": "06-stock-settlement",
      "relation": "a06tx",
      "changes": {
        "prizeMysql": 4,
        "ledger": "O1 · 已结算（1行）"
      }
    },
    {
      "id": "a07",
      "title": "确认库存结算完成",
      "layer": "application → domain → infrastructure → 用户 MySQL",
      "method": "updateStockMqState(...,COMPLETE)",
      "inputs": "TX3成功或账本证明早已成功",
      "action": "订单分表独立CAS stock从0/2/3/4→1；不是TX3的一部分，不存在跨库大事务。",
      "outputs": "stock_mq_state=COMPLETE(1)。",
      "why": "账本防二次扣减，重放负责跨两个数据库提交补齐状态。",
      "next": "sendInvoiceMessage",
      "sources": [
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/LotteryStrategyAwardStockListener.java",
          "line": 94,
          "end_line": 97
        },
        {
          "path": "lottery-interfaces/src/main/resources/mybatis/mapper/UserStrategyExport_Mapper.xml",
          "line": 52,
          "end_line": 60
        }
      ],
      "diagram": "06-stock-settlement",
      "relation": "a07sql",
      "changes": {
        "stockState": "COMPLETE(1)"
      }
    },
    {
      "id": "a08",
      "title": "投递发奖消息与记录发送结果",
      "layer": "application → Kafka / 用户 MySQL",
      "method": "sendInvoiceMessage → sendLotteryInvoice",
      "inputs": "订单InvoiceVO：uId、O1、T1、奖品和grant配置",
      "action": "发lottery_invoice，key=O1字符串。成功回调mq=3，失败mq=2。发送开关关闭也置FAIL，补偿Job同时跳过。",
      "outputs": "本例发奖消息成功、mq=SENT(3)，这里示意成功回调已到达。",
      "why": "结算和发奖分成两个责任；失败保存为可补偿状态，下一轮Job重发同订单。",
      "next": "ack库存消息；发奖独立接收",
      "sources": [
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/LotteryStrategyAwardStockListener.java",
          "line": 105,
          "end_line": 139
        },
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/mq/producer/KafkaProducer.java",
          "line": 26,
          "end_line": 31
        }
      ],
      "diagram": "06-stock-settlement",
      "relation": "a08",
      "changes": {
        "invoiceState": "SENT(3)",
        "invoiceMessage": "已发送（示意成功回调已到达）"
      }
    },
    {
      "id": "a09",
      "title": "提交库存消费位点",
      "layer": "application → Kafka",
      "method": "Acknowledgment.acknowledge",
      "inputs": "库存已完成，invoice发送已发起或失败状态已记录",
      "action": "manual_immediate，先持久化业务，再ack库存位点；异常不ack并抛出。发奖回调不必先完成。",
      "outputs": "库存消息在本消费者组已确认。",
      "why": "stock已完成的重放直接跳过；invoice缺失由发奖补偿修复。",
      "next": "LotteryInvoiceListener.onMessage",
      "sources": [
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/LotteryStrategyAwardStockListener.java",
          "line": 94,
          "end_line": 101
        },
        {
          "path": "lottery-interfaces/src/main/resources/application.yml",
          "line": 47,
          "end_line": 70
        }
      ],
      "diagram": "06-stock-settlement",
      "relation": "a09",
      "changes": {
        "stockAck": "已 ack"
      }
    },
    {
      "id": "a10",
      "title": "发奖消费者查询 grant 状态",
      "layer": "application → infrastructure → 用户 MySQL",
      "method": "LotteryInvoiceListener / isAwardCompleted",
      "inputs": "invoice topic中的InvoiceVO",
      "action": "先查grant。已是1则只收口mq=1后ack，不执行handler；未完成继续领取PROCESSING。",
      "outputs": "本例grant=0，进入发奖。",
      "why": "Redis临时标记不是发奖事实；重放可以修复grant完成而mq未收口的窗口。",
      "next": "IInvoiceMqProcessGuard.tryClaimProcessing",
      "sources": [
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/LotteryInvoiceListener.java",
          "line": 68,
          "end_line": 81
        }
      ],
      "diagram": "07-fulfillment",
      "relation": "a10read",
      "changes": {}
    },
    {
      "id": "a11",
      "title": "发奖 PROCESSING 短标记",
      "layer": "application → infrastructure → Redis",
      "method": "RedisInvoiceMqProcessGuard.tryClaimProcessing",
      "inputs": "O1",
      "action": "SETNX lottery_invoice_mq_processing_O1，TTL300秒。未取得锁再查grant，仍未完成则抛异常、不ack。",
      "outputs": "本例获得标记；宕机遗留锁等TTL到期。",
      "why": "减少并发handler调用；最终完成仍看数据库。",
      "next": "handlerRegistry按awardType选处理器",
      "sources": [
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/LotteryInvoiceListener.java",
          "line": 81,
          "end_line": 101
        },
        {
          "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/lock/RedisInvoiceMqProcessGuard.java",
          "line": 24,
          "end_line": 50
        }
      ],
      "diagram": "07-fulfillment",
      "relation": "a11",
      "changes": {
        "invoiceLock": "O1 · PROCESSING"
      }
    },
    {
      "id": "a12",
      "title": "模拟渠道与本地发奖 CAS",
      "layer": "application → domain → infrastructure → 用户 MySQL",
      "method": "CouponFulfillmentHandler → AbstractAwardFulfillmentHandler.fulfill",
      "inputs": "awardType=3，O1、awardId=3、awardContent",
      "action": "四类handler按supportType注册；当前只记模拟日志，再CAS grant!=1→1，grant_date=now；无第三方调用。",
      "outputs": "grant_state=COMPLETE(1)；返回AwardFulfillmentResult。",
      "why": "统一不同奖品类型入口；未来需orderId外部幂等契约，本地CAS不证明外部奖品已交付。",
      "next": "释放标记并收口消息",
      "sources": [
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/LotteryInvoiceListener.java",
          "line": 45,
          "end_line": 54
        },
        {
          "path": "lottery-domain/src/main/java/com/anontokyo/lottery/domain/award/service/fulfillment/impl/AbstractAwardFulfillmentHandler.java",
          "line": 24,
          "end_line": 39
        },
        {
          "path": "lottery-interfaces/src/main/resources/mybatis/mapper/UserStrategyExport_Mapper.xml",
          "line": 37,
          "end_line": 40
        }
      ],
      "diagram": "07-fulfillment",
      "relation": "a12sql",
      "changes": {
        "grantState": "COMPLETE(1) · 本地模拟",
        "grantDate": "消费时刻 now()"
      }
    },
    {
      "id": "a13",
      "title": "释放发奖标记",
      "layer": "application → infrastructure → Redis",
      "method": "invoiceMqProcessGuard.releaseProcessing",
      "inputs": "O1；handler成功",
      "action": "成功后删除PROCESSING；途中异常不走成功释放，锁由TTL过期。",
      "outputs": "短期发奖锁释放。",
      "why": "持久grant=1判定重复消息，临时标记只减少并发。",
      "next": "updateInvoiceMqState(...,COMPLETE)",
      "sources": [
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/LotteryInvoiceListener.java",
          "line": 88,
          "end_line": 107
        },
        {
          "path": "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/lock/RedisInvoiceMqProcessGuard.java",
          "line": 38,
          "end_line": 49
        }
      ],
      "diagram": "07-fulfillment",
      "relation": "a13",
      "changes": {
        "invoiceLock": "已释放"
      }
    },
    {
      "id": "a14",
      "title": "收口发奖消息",
      "layer": "application → infrastructure → 用户 MySQL",
      "method": "OrderRepository.updateInvoiceMqState",
      "inputs": "grant=1、mq=3；uId + O1",
      "action": "SQL仅grant=1且mq=0/2/3时写COMPLETE(1)，迟到回调不能回退。",
      "outputs": "mq_state=COMPLETE(1)。",
      "why": "区别发送成功、发奖事实与消费完成；补偿发送不能冒充已发奖。",
      "next": "ack invoice位点",
      "sources": [
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/LotteryInvoiceListener.java",
          "line": 103,
          "end_line": 107
        },
        {
          "path": "lottery-interfaces/src/main/resources/mybatis/mapper/UserStrategyExport_Mapper.xml",
          "line": 42,
          "end_line": 50
        }
      ],
      "diagram": "07-fulfillment",
      "relation": "a14sql",
      "changes": {
        "invoiceState": "COMPLETE(1)"
      }
    },
    {
      "id": "a15",
      "title": "本地异步流程闭环",
      "layer": "application → Kafka",
      "method": "LotteryInvoiceListener → ack",
      "inputs": "账本O1存在；stock=1、grant=1、mq=1",
      "action": "消息状态持久化返回后再ack；grant已更新后崩溃，重放按grant=1修复mq并ack。",
      "outputs": "本地实现闭环；真实外部奖品交付尚未实现。",
      "why": "完成判定晚于HTTP中奖响应，需要结合账本、订单状态和消费位点。",
      "next": "结束；同requestId仍回放原O1",
      "sources": [
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/LotteryInvoiceListener.java",
          "line": 71,
          "end_line": 78
        },
        {
          "path": "lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/LotteryInvoiceListener.java",
          "line": 103,
          "end_line": 110
        }
      ],
      "diagram": "07-fulfillment",
      "relation": "a15",
      "changes": {
        "invoiceAck": "已 ack",
        "response": "本地异步闭环；渠道为模拟"
      }
    }
  ],
  "initial": {
    "activityRedis": 100,
    "activityMysql": 100,
    "prizeRedis": 5,
    "prizeMysql": 5,
    "quota": 3,
    "take": "尚未创建",
    "order": "尚未创建",
    "stockState": "—",
    "invoiceState": "—",
    "grantState": "—",
    "ledger": "无结算账本",
    "lock": "无",
    "response": "尚未返回",
    "dispatchLock": "无",
    "invoiceLock": "无",
    "stockAck": "未确认",
    "invoiceAck": "未确认"
  },
  "sources": {
    "docs/architecture.md": {
      "id": "docs_architecture_md",
      "label": "architecture.md"
    },
    "docs/sql/V2__architecture_simplification.sql": {
      "id": "docs_sql_V2__architecture_simplification_sql",
      "label": "V2__architecture_simplification.sql"
    },
    "docs/sql/V3__draw_request_ownership.sql": {
      "id": "docs_sql_V3__draw_request_ownership_sql",
      "label": "V3__draw_request_ownership.sql"
    },
    "lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/LotteryInvoiceListener.java": {
      "id": "lottery-application_src_main_java_com_anontokyo_lottery_application_mq_consumer_LotteryInvoiceListener_java",
      "label": "LotteryInvoiceListener.java"
    },
    "lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/LotteryStrategyAwardStockListener.java": {
      "id": "lottery-application_src_main_java_com_anontokyo_lottery_application_mq_consumer_LotteryStrategyAwardStockListener_java",
      "label": "LotteryStrategyAwardStockListener.java"
    },
    "lottery-application/src/main/java/com/anontokyo/lottery/application/mq/producer/KafkaProducer.java": {
      "id": "lottery-application_src_main_java_com_anontokyo_lottery_application_mq_producer_KafkaProducer_java",
      "label": "KafkaProducer.java"
    },
    "lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java": {
      "id": "lottery-application_src_main_java_com_anontokyo_lottery_application_process_draw_impl_ActivityProcessImpl_java",
      "label": "ActivityProcessImpl.java"
    },
    "lottery-application/src/main/java/com/anontokyo/lottery/application/worker/LotteryXxlJob.java": {
      "id": "lottery-application_src_main_java_com_anontokyo_lottery_application_worker_LotteryXxlJob_java",
      "label": "LotteryXxlJob.java"
    },
    "lottery-common/src/main/java/com/anontokyo/lottery/common/Constants.java": {
      "id": "lottery-common_src_main_java_com_anontokyo_lottery_common_Constants_java",
      "label": "Constants.java"
    },
    "lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/lifecycle/ActivityLifecycleService.java": {
      "id": "lottery-domain_src_main_java_com_anontokyo_lottery_domain_activity_service_lifecycle_ActivityLifecycleService_java",
      "label": "ActivityLifecycleService.java"
    },
    "lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/partake/BaseActivityPartake.java": {
      "id": "lottery-domain_src_main_java_com_anontokyo_lottery_domain_activity_service_partake_BaseActivityPartake_java",
      "label": "BaseActivityPartake.java"
    },
    "lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/partake/impl/ActivityPartakeImpl.java": {
      "id": "lottery-domain_src_main_java_com_anontokyo_lottery_domain_activity_service_partake_impl_ActivityPartakeImpl_java",
      "label": "ActivityPartakeImpl.java"
    },
    "lottery-domain/src/main/java/com/anontokyo/lottery/domain/award/service/fulfillment/impl/AbstractAwardFulfillmentHandler.java": {
      "id": "lottery-domain_src_main_java_com_anontokyo_lottery_domain_award_service_fulfillment_impl_AbstractAwardFulfillmentHandler_java",
      "label": "AbstractAwardFulfillmentHandler.java"
    },
    "lottery-domain/src/main/java/com/anontokyo/lottery/domain/award/service/order/impl/AwardOrderImpl.java": {
      "id": "lottery-domain_src_main_java_com_anontokyo_lottery_domain_award_service_order_impl_AwardOrderImpl_java",
      "label": "AwardOrderImpl.java"
    },
    "lottery-domain/src/main/java/com/anontokyo/lottery/domain/strategy/service/draw/WeightedRandomSelector.java": {
      "id": "lottery-domain_src_main_java_com_anontokyo_lottery_domain_strategy_service_draw_WeightedRandomSelector_java",
      "label": "WeightedRandomSelector.java"
    },
    "lottery-domain/src/main/java/com/anontokyo/lottery/domain/strategy/service/draw/impl/DrawExecutor.java": {
      "id": "lottery-domain_src_main_java_com_anontokyo_lottery_domain_strategy_service_draw_impl_DrawExecutor_java",
      "label": "DrawExecutor.java"
    },
    "lottery-domain/src/main/java/com/anontokyo/lottery/domain/strategy/service/snapshot/StrategySnapshotFactory.java": {
      "id": "lottery-domain_src_main_java_com_anontokyo_lottery_domain_strategy_service_snapshot_StrategySnapshotFactory_java",
      "label": "StrategySnapshotFactory.java"
    },
    "lottery-domain/src/main/java/com/anontokyo/lottery/domain/support/ids/SnowflakeIdGenerator.java": {
      "id": "lottery-domain_src_main_java_com_anontokyo_lottery_domain_support_ids_SnowflakeIdGenerator_java",
      "label": "SnowflakeIdGenerator.java"
    },
    "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/cache/TwoLevelCacheService.java": {
      "id": "lottery-infrastructure_src_main_java_com_anontokyo_lottery_infrastructure_cache_TwoLevelCacheService_java",
      "label": "TwoLevelCacheService.java"
    },
    "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/dao/IUserStrategyExportDao.java": {
      "id": "lottery-infrastructure_src_main_java_com_anontokyo_lottery_infrastructure_dao_IUserStrategyExportDao_java",
      "label": "IUserStrategyExportDao.java"
    },
    "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/dao/IUserTakeActivityDao.java": {
      "id": "lottery-infrastructure_src_main_java_com_anontokyo_lottery_infrastructure_dao_IUserTakeActivityDao_java",
      "label": "IUserTakeActivityDao.java"
    },
    "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/lock/RedisActivityDrawProcessGuard.java": {
      "id": "lottery-infrastructure_src_main_java_com_anontokyo_lottery_infrastructure_lock_RedisActivityDrawProcessGuard_java",
      "label": "RedisActivityDrawProcessGuard.java"
    },
    "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/lock/RedisInvoiceMqProcessGuard.java": {
      "id": "lottery-infrastructure_src_main_java_com_anontokyo_lottery_infrastructure_lock_RedisInvoiceMqProcessGuard_java",
      "label": "RedisInvoiceMqProcessGuard.java"
    },
    "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/lock/RedisStrategyAwardStockMqGuard.java": {
      "id": "lottery-infrastructure_src_main_java_com_anontokyo_lottery_infrastructure_lock_RedisStrategyAwardStockMqGuard_java",
      "label": "RedisStrategyAwardStockMqGuard.java"
    },
    "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/ActivityRepository.java": {
      "id": "lottery-infrastructure_src_main_java_com_anontokyo_lottery_infrastructure_repository_ActivityRepository_java",
      "label": "ActivityRepository.java"
    },
    "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/OrderRepository.java": {
      "id": "lottery-infrastructure_src_main_java_com_anontokyo_lottery_infrastructure_repository_OrderRepository_java",
      "label": "OrderRepository.java"
    },
    "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/StrategyRepository.java": {
      "id": "lottery-infrastructure_src_main_java_com_anontokyo_lottery_infrastructure_repository_StrategyRepository_java",
      "label": "StrategyRepository.java"
    },
    "lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/UserTakeActivityRepository.java": {
      "id": "lottery-infrastructure_src_main_java_com_anontokyo_lottery_infrastructure_repository_UserTakeActivityRepository_java",
      "label": "UserTakeActivityRepository.java"
    },
    "lottery-interfaces/pom.xml": {
      "id": "lottery-interfaces_pom_xml",
      "label": "lottery-interfaces/pom.xml"
    },
    "lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/LotteryController.java": {
      "id": "lottery-interfaces_src_main_java_com_anontokyo_lottery_interfaces_web_LotteryController_java",
      "label": "LotteryController.java"
    },
    "lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/dto/DrawRequest.java": {
      "id": "lottery-interfaces_src_main_java_com_anontokyo_lottery_interfaces_web_dto_DrawRequest_java",
      "label": "DrawRequest.java"
    },
    "lottery-interfaces/src/main/resources/application.yml": {
      "id": "lottery-interfaces_src_main_resources_application_yml",
      "label": "application.yml"
    },
    "lottery-interfaces/src/main/resources/mybatis/mapper/Activity_Mapper.xml": {
      "id": "lottery-interfaces_src_main_resources_mybatis_mapper_Activity_Mapper_xml",
      "label": "Activity_Mapper.xml"
    },
    "lottery-interfaces/src/main/resources/mybatis/mapper/StrategyDetail_Mapper.xml": {
      "id": "lottery-interfaces_src_main_resources_mybatis_mapper_StrategyDetail_Mapper_xml",
      "label": "StrategyDetail_Mapper.xml"
    },
    "lottery-interfaces/src/main/resources/mybatis/mapper/UserStrategyExport_Mapper.xml": {
      "id": "lottery-interfaces_src_main_resources_mybatis_mapper_UserStrategyExport_Mapper_xml",
      "label": "UserStrategyExport_Mapper.xml"
    },
    "lottery-interfaces/src/main/resources/mybatis/mapper/UserTakeActivityCount_Mapper.xml": {
      "id": "lottery-interfaces_src_main_resources_mybatis_mapper_UserTakeActivityCount_Mapper_xml",
      "label": "UserTakeActivityCount_Mapper.xml"
    },
    "lottery-interfaces/src/main/resources/mybatis/mapper/UserTakeActivity_Mapper.xml": {
      "id": "lottery-interfaces_src_main_resources_mybatis_mapper_UserTakeActivity_Mapper_xml",
      "label": "UserTakeActivity_Mapper.xml"
    },
    "pom.xml": {
      "id": "pom_xml",
      "label": "pom.xml"
    }
  }
};
