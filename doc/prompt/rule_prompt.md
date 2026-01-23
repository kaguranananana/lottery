
> **目标**: 使用设计模式开发一个灵活、可扩展的规则引擎，用于量化人群并控制活动参与资格

---

## 📌 项目背景

你需要开发一个**规则引擎模块**，用于抽奖系统中的人群量化和活动参与控制。系统需要根据用户的属性（性别、年龄等）动态决策用户可以参与哪个活动，实现精准营销。

**核心需求**:

- 支持基于决策树的规则引擎
- 用户属性可灵活配置（性别、年龄、会员等级等）
- 规则配置与代码解耦，支持运营人员配置规则
- 与活动参与流程无缝集成
- 高性能、可扩展

---

## 🏗️ 技术架构要求

### 项目结构

```
lottery-domain/
└── src/main/java/cn/itedus/lottery/domain/
    └── rule/
        ├── model/
        │   ├── aggregates/
        │   │   └── TreeRuleRich.java           # 规则树聚合根
        │   ├── req/
        │   │   └── DecisionMatterReq.java      # 决策物料请求
        │   ├── res/
        │   │   └── EngineResult.java           # 引擎决策结果
        │   └── vo/
        │       ├── TreeRootVO.java             # 规则树根VO
        │       ├── TreeNodeVO.java             # 规则树节点VO
        │       └── TreeNodeLineVO.java         # 规则树连线VO
        ├── repository/
        │   └── IRuleRepository.java            # 规则仓储接口
        └── service/
            ├── engine/
            │   ├── EngineConfig.java           # 引擎配置基类
            │   ├── EngineBase.java             # 引擎基础类（模板方法）
            │   ├── EngineFilter.java           # 引擎过滤器接口
            │   └── impl/
            │       └── RuleEngineHandle.java   # 规则引擎处理器实现
            └── logic/
                ├── LogicFilter.java            # 逻辑过滤器接口
                ├── BaseLogic.java              # 逻辑过滤器基类（模板方法）
                └── impl/
                    ├── UserGenderFilter.java   # 性别过滤器
                    └── UserAgeFilter.java      # 年龄过滤器
```

### 技术栈

- **语言**: Java 8
- **框架**: Spring Boot
- **数据库**: MySQL
- **ORM**: MyBatis
- **日志**: SLF4J + Logback

---

## 🎯 核心设计模式要求

### 1. 策略模式 (Strategy Pattern)

**应用场景**: 逻辑过滤器

不同的用户属性需要不同的提取和比对逻辑，使用策略模式实现可插拔的过滤器。

**实现要求**:

#### 1.1 定义策略接口 `LogicFilter`

```java
public interface LogicFilter {
    /**
     * 逻辑过滤器，根据用户属性值和规则限定值进行匹配
     * @param matterValue 用户属性值
     * @param treeNodeLineInfoList 节点连线集合
     * @return 下一个节点ID
     */
    Long filter(String matterValue, List<TreeNodeLineVO> treeNodeLineInfoList);

    /**
     * 获取用户属性值
     * @param decisionMatter 决策物料
     * @return 属性值
     */
    String matterValue(DecisionMatterReq decisionMatter);
}
```

#### 1.2 具体策略实现

每个用户属性对应一个具体策略：

```java
@Component
public class UserGenderFilter extends BaseLogic {
    @Override
    public String matterValue(DecisionMatterReq decisionMatter) {
        return decisionMatter.getValMap().get("gender").toString();
    }
}

@Component
public class UserAgeFilter extends BaseLogic {
    @Override
    public String matterValue(DecisionMatterReq decisionMatter) {
        return decisionMatter.getValMap().get("age").toString();
    }
}
```

#### 1.3 策略容器配置

使用Map容器管理所有策略，通过ruleKey动态选择：

```java
@Configuration
public class EngineConfig {
    @Resource
    private Map<String, LogicFilter> logicFilterMap;
    
    // Spring会自动注入所有LogicFilter实现类
    // key为bean名称，value为实现类实例
}
```

---

### 2. 模板方法模式 (Template Method Pattern)

**应用场景**: 

- 逻辑过滤器基类 `BaseLogic`
- 规则引擎基类 `EngineBase`
- 活动参与基类 `BaseActivityPartake`

**实现要求**:

#### 2.1 逻辑过滤器模板 `BaseLogic`

```java
public abstract class BaseLogic implements LogicFilter {
    
    @Override
    public Long filter(String matterValue, List<TreeNodeLineVO> treeNodeLineInfoList) {
        // 模板方法：遍历连线并比对规则
        for (TreeNodeLineVO nodeLine : treeNodeLineInfoList) {
            if (decisionLogic(matterValue, nodeLine)) {
                return nodeLine.getNodeIdTo();
            }
        }
        return Constants.Global.TREE_NULL_NODE;
    }

    /**
     * 抽象方法：子类实现具体的属性值提取逻辑
     */
    @Override
    public abstract String matterValue(DecisionMatterReq decisionMatter);

    /**
     * 私有方法：规则比对逻辑（不允许子类修改）
     */
    private boolean decisionLogic(String matterValue, TreeNodeLineVO nodeLine) {
        switch (nodeLine.getRuleLimitType()) {
            case Constants.RuleLimitType.EQUAL:
                return matterValue.equals(nodeLine.getRuleLimitValue());
            case Constants.RuleLimitType.GT:
                return Double.parseDouble(matterValue) > Double.parseDouble(nodeLine.getRuleLimitValue());
            case Constants.RuleLimitType.LT:
                return Double.parseDouble(matterValue) < Double.parseDouble(nodeLine.getRuleLimitValue());
            case Constants.RuleLimitType.GE:
                return Double.parseDouble(matterValue) >= Double.parseDouble(nodeLine.getRuleLimitValue());
            case Constants.RuleLimitType.LE:
                return Double.parseDouble(matterValue) <= Double.parseDouble(nodeLine.getRuleLimitValue());
            default:
                return false;
        }
    }
}
```

**设计要点**:

- `filter()` 是模板方法，定义算法骨架
- `matterValue()` 是抽象方法，子类实现具体逻辑
- `decisionLogic()` 是私有方法，封装不变部分

#### 2.2 规则引擎模板 `EngineBase`

```java
public abstract class EngineBase extends EngineConfig implements EngineFilter {
    
    private Logger logger = LoggerFactory.getLogger(EngineBase.class);

    @Override
    public abstract EngineResult process(DecisionMatterReq matter);

    /**
     * 模板方法：决策树遍历引擎
     */
    protected TreeNodeVO engineDecisionMaker(TreeRuleRich treeRuleRich, DecisionMatterReq matter) {
        TreeRootVO treeRoot = treeRuleRich.getTreeRoot();
        Map<Long, TreeNodeVO> treeNodeMap = treeRuleRich.getTreeNodeMap();

        // 规则树根ID
        Long rootNodeId = treeRoot.getTreeRootNodeId();
        TreeNodeVO treeNodeInfo = treeNodeMap.get(rootNodeId);

        // 节点类型[NodeType]；1子叶、2果实
        while (Constants.NodeType.STEM.equals(treeNodeInfo.getNodeType())) {
            String ruleKey = treeNodeInfo.getRuleKey();
            LogicFilter logicFilter = logicFilterMap.get(ruleKey);
            String matterValue = logicFilter.matterValue(matter);
            Long nextNode = logicFilter.filter(matterValue, treeNodeInfo.getTreeNodeLineInfoList());
            treeNodeInfo = treeNodeMap.get(nextNode);
            
            logger.info("决策树引擎=>{} userId：{} treeId：{} treeNode：{} ruleKey：{} matterValue：{}", 
                treeRoot.getTreeName(), matter.getUserId(), matter.getTreeId(), 
                treeNodeInfo.getTreeNodeId(), ruleKey, matterValue);
        }

        return treeNodeInfo;
    }
}
```

**设计要点**:

- 定义决策树遍历的通用算法
- 使用策略模式动态选择过滤器
- 记录决策路径日志

---

### 3. 责任链模式变体 (Chain of Responsibility)

**应用场景**: 决策树节点的连续决策

虽然不是标准的责任链模式，但决策树的遍历过程体现了责任链的思想：每个节点处理一部分决策逻辑，然后传递给下一个节点。

**实现要点**:

- 每个节点根据规则决定下一个节点
- 直到到达果实节点（终止条件）

---

### 4. 聚合根模式 (Aggregate Root - DDD)

**应用场景**: `TreeRuleRich` 规则树聚合根

**实现要求**:

```java
public class TreeRuleRich {
    /** 树根信息 */
    private TreeRootVO treeRoot;
    
    /** 树节点ID -> 子节点 */
    private Map<Long, TreeNodeVO> treeNodeMap;
    
    // getter/setter
}
```

**设计要点**:

- 聚合根统一管理规则树的所有数据
- 对外提供一致性视图
- 简化数据加载和传递

---

### 5. 仓储模式 (Repository Pattern - DDD)

**应用场景**: 规则数据的持久化和查询

**实现要求**:

```java
public interface IRuleRepository {
    /**
     * 查询规则树聚合
     * @param treeId 规则树ID
     * @return 规则树聚合根
     */
    TreeRuleRich queryTreeRuleRich(Long treeId);
}
```

**设计要点**:

- 封装数据访问逻辑
- 返回领域对象而非PO
- 支持聚合加载

---

## 📊 数据模型设计

### 数据库表设计

#### 1. 规则树表 (rule_tree)

```sql
CREATE TABLE `rule_tree` (
  `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `tree_name` VARCHAR(64) DEFAULT NULL COMMENT '规则树名称',
  `tree_desc` VARCHAR(128) DEFAULT NULL COMMENT '规则树描述',
  `tree_root_node_id` BIGINT(20) DEFAULT NULL COMMENT '规则树根ID',
  `create_time` DATETIME DEFAULT NULL COMMENT '创建时间',
  `update_time` DATETIME DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='规则树配置';
```

#### 2. 规则树节点表 (rule_tree_node)

```sql
CREATE TABLE `rule_tree_node` (
  `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `tree_id` INT(2) DEFAULT NULL COMMENT '规则树ID',
  `node_type` INT(2) DEFAULT NULL COMMENT '节点类型；1子叶、2果实',
  `node_value` VARCHAR(32) DEFAULT NULL COMMENT '节点值[nodeType=2]；果实值',
  `rule_key` VARCHAR(16) DEFAULT NULL COMMENT '规则Key',
  `rule_desc` VARCHAR(32) DEFAULT NULL COMMENT '规则描述',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='规则树节点';
```

#### 3. 规则树节点连线表 (rule_tree_node_line)

```sql
CREATE TABLE `rule_tree_node_line` (
  `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `tree_id` BIGINT(20) DEFAULT NULL COMMENT '规则树ID',
  `node_id_from` BIGINT(20) DEFAULT NULL COMMENT '节点From',
  `node_id_to` BIGINT(20) DEFAULT NULL COMMENT '节点To',
  `rule_limit_type` INT(2) DEFAULT NULL COMMENT '限定类型；1:=;2:>;3:<;4:>=;5<=',
  `rule_limit_value` VARCHAR(32) DEFAULT NULL COMMENT '限定值',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='规则树节点连线';
```

### 领域模型设计

#### DecisionMatterReq - 决策物料请求

```java
public class DecisionMatterReq {
    /** 规则树ID */
    private Long treeId;
    /** 用户ID */
    private String userId;
    /** 决策值映射 */
    private Map<String, Object> valMap;
}
```

#### EngineResult - 引擎决策结果

```java
public class EngineResult {
    /** 执行结果 */
    private boolean isSuccess;
    /** 用户ID */
    private String userId;
    /** 规则树ID */
    private Long treeId;
    /** 果实节点ID */
    private Long nodeId;
    /** 果实节点值（活动ID） */
    private String nodeValue;
}
```

---

## 🔧 代码实现要求

### 1. 规则引擎实现类

```java
@Service("ruleEngineHandle")
public class RuleEngineHandle extends EngineBase {

    @Resource
    private IRuleRepository ruleRepository;

    @Override
    public EngineResult process(DecisionMatterReq matter) {
        // 1. 查询规则树
        TreeRuleRich treeRuleRich = ruleRepository.queryTreeRuleRich(matter.getTreeId());
        if (null == treeRuleRich) {
            throw new RuntimeException("Tree Rule is null!");
        }

        // 2. 执行决策
        TreeNodeVO treeNodeInfo = engineDecisionMaker(treeRuleRich, matter);

        // 3. 返回结果
        return new EngineResult(matter.getUserId(), treeNodeInfo.getTreeId(), 
            treeNodeInfo.getTreeNodeId(), treeNodeInfo.getNodeValue());
    }
}
```

### 2. 与活动参与流程集成

在活动参与流程中，通过规则引擎进行人群量化校验：

```java
@Service
public class ActivityPartakeImpl extends BaseActivityPartake {

    @Resource
    private EngineFilter engineFilter;

    @Override
    protected Result checkActivityBill(PartakeReq partake, ActivityBillVO bill) {
        // 规则引擎量化决策
        DecisionMatterReq req = new DecisionMatterReq();
        req.setTreeId(bill.getRuleTreeId());
        req.setUserId(partake.getuId());
        req.setValMap(buildUserValMap(partake.getuId()));
        
        EngineResult result = engineFilter.process(req);
        
        // 校验决策结果
        if (!result.isSuccess()) {
            return new Result(Constants.ResponseCode.RULE_ERR.getCode(), 
                Constants.ResponseCode.RULE_ERR.getInfo());
        }
        
        // 校验活动ID是否匹配
        if (!String.valueOf(partake.getActivityId()).equals(result.getNodeValue())) {
            return new Result(Constants.ResponseCode.ACTIVITY_MISMATCH.getCode(), 
                "您不符合该活动的参与条件");
        }
        
        // 继续其他校验...
        return new Result(Constants.ResponseCode.SUCCESS.getCode(), 
            Constants.ResponseCode.SUCCESS.getInfo());
    }
    
    private Map<String, Object> buildUserValMap(String userId) {
        // 从用户服务获取用户属性
        Map<String, Object> valMap = new HashMap<>();
        valMap.put("gender", userService.getGender(userId));
        valMap.put("age", userService.getAge(userId));
        return valMap;
    }
}
```

---

## 📝 开发任务清单

### 阶段1: 基础设施搭建

- [ ] 创建数据库表（rule_tree, rule_tree_node, rule_tree_node_line）
- [ ] 创建PO实体类（RuleTree, RuleTreeNode, RuleTreeNodeLine）
- [ ] 创建DAO接口和Mapper XML
- [ ] 创建领域模型（VO、聚合根）

### 阶段2: 规则引擎核心实现

- [ ] 实现 `LogicFilter` 接口
- [ ] 实现 `BaseLogic` 模板类（包含规则比对逻辑）
- [ ] 实现 `UserGenderFilter` 和 `UserAgeFilter`
- [ ] 实现 `EngineConfig` 配置类（注入策略Map）
- [ ] 实现 `EngineBase` 模板类（决策树遍历算法）
- [ ] 实现 `EngineFilter` 接口
- [ ] 实现 `RuleEngineHandle` 规则引擎处理器

### 阶段3: 仓储层实现

- [ ] 实现 `IRuleRepository` 接口
- [ ] 实现 `RuleRepository` 仓储类
- [ ] 实现规则树聚合查询方法

### 阶段4: 与活动参与集成

- [ ] 在活动配置中关联规则树ID
- [ ] 在活动参与流程中调用规则引擎
- [ ] 实现规则校验逻辑
- [ ] 处理规则校验失败的情况

### 阶段5: 测试和优化

- [ ] 编写单元测试（规则引擎决策）
- [ ] 编写集成测试（活动参与流程）
- [ ] 性能测试和优化
- [ ] 规则树缓存实现

---

## 🧪 测试要求

### 单元测试

```java
@SpringBootTest
public class RuleEngineTest {
    
    @Resource
    private EngineFilter ruleEngineHandle;
    
    @Test
    public void test_man_age_less_than_25() {
        DecisionMatterReq req = new DecisionMatterReq();
        req.setTreeId(2110081902L);
        req.setUserId("test_user_001");
        
        Map<String, Object> valMap = new HashMap<>();
        valMap.put("gender", "man");
        valMap.put("age", 20);
        req.setValMap(valMap);
        
        EngineResult result = ruleEngineHandle.process(req);
        
        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals("100001", result.getNodeValue());
    }
}
```

---

## 🎨 设计模式总结

| 设计模式           | 应用位置              | 作用                               |
| ------------------ | --------------------- | ---------------------------------- |
| **策略模式**       | LogicFilter及其实现   | 支持多种用户属性过滤策略，易扩展   |
| **模板方法模式**   | BaseLogic, EngineBase | 定义算法骨架，子类实现具体步骤     |
| **责任链模式变体** | 决策树遍历            | 节点连续决策，直到找到果实节点     |
| **聚合根模式**     | TreeRuleRich          | 统一管理规则树数据，保证一致性     |
| **仓储模式**       | IRuleRepository       | 封装数据访问，返回领域对象         |
| **依赖注入**       | EngineConfig          | 自动注入所有策略实现，支持动态选择 |

---

## ⚠️ 注意事项

### 1. 设计模式应用原则

- **策略模式**: 新增用户属性时，只需新增LogicFilter实现类，无需修改现有代码
- **模板方法**: 不要在子类中重写模板方法，只实现抽象方法
- **单一职责**: 每个过滤器只负责一个用户属性的提取
- **开闭原则**: 对扩展开放，对修改关闭

### 2. 性能优化建议

- 规则树数据通过Redis缓存
- 决策结果可以缓存（用户ID+规则树ID为key）
- 避免深层决策树（建议不超过5层）

### 3. 异常处理

- 规则树不存在时抛出异常
- 决策失败时返回明确的错误码
- 记录完整的决策路径日志

---

## 📚 参考资料

- **设计模式**: 《设计模式：可复用面向对象软件的基础》
- **领域驱动设计**: 《领域驱动设计》Eric Evans
- **规则引擎**: Drools规则引擎原理

---

## 🚀 开始开发

请基于以上设计要求，完成规则引擎量化人群参与活动模块的开发。重点关注：

1. ✅ 正确应用设计模式
2. ✅ 代码结构清晰，职责分明
3. ✅ 高内聚、低耦合
4. ✅ 易扩展、易维护
5. ✅ 完善的异常处理和日志记录

开发完成后，请提供：

- 完整的源代码
- 单元测试用例
- 使用文档
- 规则树配置示例