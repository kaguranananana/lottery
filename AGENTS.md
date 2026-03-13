# Lottery System Agent Guide

A Spring Boot lottery (prize draw) platform using **DDD + Dubbo + Sharding** architecture.

## Architecture Overview

**6-Module Maven Structure** (parent: `pom.xml`):
- **lottery-rpc**: Interface definitions (Dubbo services) - pure interfaces, no implementation
- **lottery-common**: Shared utilities (Result.java, Constants.java, PageRequest.java)
- **lottery-domain**: Business logic (DDD domain services, repositories, state machines)
- **lottery-infrastructure**: Data persistence (MyBatis DAOs, repositories, Redis utilities)
- **lottery-application**: Orchestration (process coordinators, MQ handlers, scheduled jobs)
- **lottery-interfaces**: Entry points (Spring Boot app, Dubbo facades, DTO assemblers)

**Key Deployment**: `lottery-interfaces` is the only WAR/executable module; others are compiled into it.

## Critical Architecture Patterns

### 1. **DDD Layering**
- **Domain**: Services/repositories define interfaces (I* pattern)
- **Infrastructure**: Implementations (@Repository) that adapt to DAO layer
- **Application**: Process coordinators (I*Process) orchestrate domain services
- **Interfaces**: Facades (@Service on Dubbo) coordinate application processes

Example: Award distribution flow
```
ILotteryActivityBooth.drawLottery()
  → IActivityDrawProcess.doProcess()
    → IActivity/IStrategy/IRule domain services
      → ActivityRepository (infra) → IActivityDao (DAO)
```

### 2. **Database Sharding Pattern**
- **Tool**: `cn.bugstack.middleware:db-router-spring-boot-starter` v1.0.4
- **Config**: `application.yml` → `mini-db-router.jdbc.datasource`
  - 2 databases (db00/default, db01, db02), 4 tables per shard
  - Router key: `uId` (user ID for sharding)
  - Route annotation: Use on DAOs for automatic routing
- **Critical**: Each activity/strategy record must include `uId` for correct shard selection

### 3. **Dubbo RPC Integration**
- **Registry**: Nacos (localhost:8848)
- **Facade Pattern**: `LotteryActivityBooth`, `LotteryActivityDeploy` annotated with `@Service` (Dubbo)
- **Service Interfaces**: In lottery-rpc module (ILotteryActivityBooth.java)
- **DTO Mapping**: ActivityMapping, AwardMapping in assembler package
- **Config**: `dubbo.scan.base-packages: com.anontokyo.lottery.rpc` in application.yml

### 4. **External Integrations**
- **Redis** (localhost:6379): Caching, distributed locks
  - Utility class: `RedisUtil.java` in infrastructure
- **Kafka** (localhost:9092): Async event/order processing
  - KafkaProducer.java in application.mq.producer
- **XXL-JOB** (http://localhost:7397): Scheduled tasks
  - LotteryXxlJob.java with @XxlJob annotations
  - Handles activity state transitions, order exports

## Critical Developer Workflows

### Build & Test
```bash
# Full Maven build (creates lottery-interfaces WAR)
mvn clean package

# Run tests (Spring Boot integration tests in lottery-interfaces)
mvn test

# Only compile (skip tests, faster)
mvn clean compile -DskipTests
```

### Database Setup
1. Create databases: `lottery`, `lottery_01`, `lottery_02`
2. Run schema migration:
   ```bash
   mysql -u root -p lottery < doc/sql/lottery.sql
   mysql -u root -p lottery_01 < doc/sql/lottery_01.sql
   mysql -u root -p lottery_02 < doc/sql/lottery_02.sql
   ```
3. Load test data:
   ```bash
   mysql -u root -p lottery < doc/sql/lottery_test_data.sql
   ```

### Local Service Dependencies
Before running the app locally, ensure these are running:
- MySQL: port 13306 (sharded setup)
- Redis: port 6379 (auth: `060214.yjx` per application.yml)
- Kafka: port 9092 (topics auto-created)
- Nacos: port 8848 (service registry)
- XXL-JOB Admin: port 7397 (scheduler UI)

## Code Conventions

### Constants & Enums
All in `lottery-common/Constants.java`:
- Response codes: ResponseCode enum (SUCCESS, LOSING_DRAW, OUT_OF_STOCK, etc.)
- Strategy modes: StrategyMode.SINGLE vs ENTIRETY
- Draw states: DrawState (FAIL, SUCCESS, Cover)
- Use constants instead of magic strings

### DTO Object Mapping
- **Mapping Interface**: IMapping\<S,T\> generic interface
- **Implementations**: ActivityMapping, AwardMapping in assembler
- **Pattern**: `sourceToTarget(source)` and `targetToSource(target)` methods
- **Always use** in facades when converting between domain/RPC DTOs

### Result Wrapping
All responses use `Result` class:
```java
Result.buildSuccessResult()  // {code: "0000", info: "成功"}
Result.buildFailResult(code, msg)  // Custom error
```
Response codes in Constants.ResponseCode enum—never hardcode.

### Repository Pattern
- **Domain Interface**: `IActivityRepository` (in domain module)
- **Infra Implementation**: `ActivityRepository` (in infrastructure module)
  - Injects DAOs: IActivityDao, IStrategyDao, etc.
  - Handles object conversion (PO → VO)
  - Manages Redis cache (redisUtil)

### Facade Pattern (RPC Entry Points)
- Class name: `Lottery*` prefix (e.g., LotteryActivityBooth)
- Decorator: `@Service` (Dubbo, not Spring @Service)
- Responsibilities:
  1. Log request/response
  2. Validate input
  3. Call application process
  4. Map VO ↔ DTO via assembler
  5. Wrap in Result and return

## Project-Specific Idioms

### Process Coordinators
- Interface: `I*Process` (IActivityDrawProcess, IActivityDeployProcess)
- Responsibility: Orchestrate multiple domain services in correct order
- Do not put business logic here—only flow control
- Located in: lottery-application/process/* directories

### State Machines
- Config: StateConfig.java (activity status transitions)
- Handler: IStateHandler interface for state-specific behavior
- Prevents invalid activity transitions (draft → live → closed)

### Sharding Awareness
- Every user-related query must respect sharding by `uId`
- Test data: Use multiple user IDs to verify sharding works across db01/db02
- When adding new tables, include `uId` for proper routing

### MQ (Kafka) Async Patterns
- Producer only in application layer (KafkaProducer)
- Kafka topics: Likely named after entity (activity, order, etc.)
- Manual commit: `ack-mode: manual_immediate` in consumer config
- Error handling: Retry logic in application.yml (producer retries: 1)

### XXL-JOB Scheduled Tasks
- Decorated with `@XxlJob("jobName")`
- Use `XxlJobHelper.log()` for executor logging
- Common tasks: Activity state transitions, export user order history
- Config in application.yml (logpath, access token, admin address)

## Key Files Reference

| Purpose | Path |
|---------|------|
| Domain service interfaces | `lottery-domain/domain/**/*.java` (activity, strategy, rule, award) |
| Database repos | `lottery-infrastructure/repository/**Repository.java` |
| RPC interfaces | `lottery-rpc/rpc/**/*.java` (booth, deploy) |
| Facades | `lottery-interfaces/interfaces/facade/**Booth.java` |
| Mappers | `lottery-interfaces/interfaces/assembler/**Mapping.java` |
| Constants | `lottery-common/common/Constants.java` |
| App processes | `lottery-application/application/process/**/*.java` |
| MyBatis XMLs | `lottery-interfaces/src/main/resources/mybatis/mapper/*.xml` |
| SQL schemas | `doc/sql/lottery*.sql` |
| Config | `lottery-interfaces/src/main/resources/application.yml` |

## Common Modification Patterns

### Add New RPC Service
1. Define interface in lottery-rpc (ILottery*.java)
2. Implement facade in lottery-interfaces (@Service Dubbo)
3. Assemble DTOs with mapping class
4. Call application process coordinators

### Add New Domain Service
1. Define repository interface in lottery-domain
2. Implement with DAOs in lottery-infrastructure
3. Create domain service that uses repository
4. Call from application process

### Add New Scheduled Job
1. Add method in LotteryXxlJob.java with @XxlJob annotation
2. Register job name in XXL-JOB UI (port 7397)
3. Handle sharding with `XxlJobHelper.shardIndex/shardTotal`
4. Use `dbRouter` to iterate across shards

### Debug Sharding Issues
- Check `application.yml` mini-db-router.list (which DBs are active)
- Verify test data has varied `uId` values across shards
- Log SQL: Enable `log-impl: org.apache.ibatis.logging.stdout.StdOutImpl`
- Use db-router debugger: Add breakpoints in routing logic

## Testing Guidance
- Unit tests: Test domain services in isolation (mock repos)
- Integration tests: Use @SpringBootTest on lottery-interfaces module
- External services: Mock Kafka, Redis in tests or use test containers
- Sharding tests: Create records with different uIds, verify correct DB


## Error Handling
- Always check ResponseCode enum before adding custom error codes
- Log errors with context: userId, activityId, operationName
- Never expose internal exceptions to clients—wrap in Result
- Use try-catch in facades; let domain throw DomainException

---

**Documentation**:
- **AGENTS.md**: This file - architecture & agent patterns
- **doc/高并发测试指南.md**: Complete concurrency testing methodology  
- **doc/JMeter测试指南.md**: JMeter setup & configuration guide
- **doc/快速启动指南.md**: Quick start for testing (recommended reading)
- **doc/高并发测试验证脚本.sql**: SQL validation scripts after testing

**Last Updated**: 2026-03-12  
**Java Version**: 8  
**Build Tool**: Maven 3.6+  
**Spring Boot**: 2.3.5.RELEASE
