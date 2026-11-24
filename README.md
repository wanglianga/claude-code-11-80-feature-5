# 防汛平台状态迁移与规则升级服务

某市防汛平台在汛期中途把固定阈值风险规则升级为多因子规则（同时考虑**上游水位趋势、泵站启停滞后、传感器可信度**）。
旧作业保存的是**网格窗口状态**，新规则需要**泵站—河段—道路三层中间状态**。本服务提供状态迁移与规则升级的全流程治理能力：

- **流定义登记表**：输入主题、事件时间字段、水位基准、状态模式、规则包版本、分区→道路/泵站/河段映射；
- **检查点清单**：每个分区的消费位点、窗口水印、状态快照、输出批次，并提供检查点比对（位点/水印滞后与就绪度）；
- **状态语义证明器**：比较旧新字段、聚合粒度与依赖关系，给出旧状态确实可转换的守恒条件；
- **迁移计划器**：按分区选择 `ADAPT`（经证明的状态适配）/ `REPLAY`（限定时间窗回放）/ `MANUAL`（人工裁定），支持混合路线；
- **双轨校验器**：旧新规则在同一输入截点并行计算并量化风险差异；
- **切换闸门**：仅当关键分区满足证据要求且覆盖率达标时发布新输出，否则维持旧输出并标记待验证；
- **回退控制器**：保留旧规则只读快照，撤回指定范围内的新输出；
- **事件证据账**：每次风险结论记录输入、规则与状态版本，区分 `OLD_OUTPUT` / `NEW_CANDIDATE` / `NEW_OUTPUT` / `RETRACTED`。
- **预警结果账**：为每个道路—窗口冻结当时的规则版本、状态水印、输入可信度、风险输出与实际派单干预；规则升级跨越同一积水事件时按输出时刻拆分暴露；
- **标签可观察性分类器**：区分已核验水深、受处置影响的观测、视频/人工延迟标签、完全未观察；
- **时间对齐器**：把现场水深、封路、泵站动作与预警时间连接，处置之后的下降水位不得反证原先风险；
- **校准评估器**：按流域、道路类型与观测状态计算等级命中、预警提前量、校准区间（Wilson）与右删失范围；
- **上线门禁**：只允许把通过最低覆盖与安全下限的规则扩大到更多道路，并标记覆盖选择偏差。

## 技术栈

Java 25 · Spring Boot 4.0 · Spring Data JPA · PostgreSQL 18 · springdoc-openapi（Swagger UI）· Maven

## 快速开始

### 方式一：Docker Compose 一键启动（应用 + PostgreSQL）

```bash
docker compose up --build
```

启动后访问 http://localhost:8080/swagger-ui.html 。

### 方式二：本地开发

前置：JDK 25、Maven 3.9+、PostgreSQL（库/用户/密码均为 `ccdev`，见 `src/main/resources/application.yml`，可用 `DB_URL/DB_USER/DB_PASSWORD` 覆盖）。

```bash
# 运行测试（使用 ccdev_test 库，首次需创建：CREATE DATABASE ccdev_test OWNER ccdev;）
mvn test

# 打包并启动（首次启动自动建表并写入演示数据）
mvn package -DskipTests
java -jar target/state-migration-service-1.0.0.jar
```

### 测试账号（HTTP Basic）

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| `operator` | `operator123` | OPERATOR |
| `admin` | `admin123` | ADMIN, OPERATOR |

Swagger UI（`/swagger-ui.html`）与 OpenAPI 文档（`/v3/api-docs`）无需登录；`/api/**` 需上述账号。

## 接口一览

| 模块 | 方法与路径 | 说明 |
| --- | --- | --- |
| 流定义 | `POST /api/streams`、`GET /api/streams[/{id}]` | 登记/查询流定义 |
| 规则包 | `POST /api/streams/{id}/rule-packages`、`GET .../rule-packages` | 登记/二次修订；多因子修订使相关比较与证明过期并重新绑定目标版本 |
| 流事件 | `POST /api/streams/{id}/events`、`GET .../events` | 事件写入（位点按分区自增）与查询 |
| 检查点 | `POST /api/streams/{id}/checkpoints`、`GET .../checkpoints` | 登记/查询检查点清单 |
| 检查点比对 | `GET /api/streams/{id}/checkpoint-compare` | 位点/水印滞后、快照与迁移就绪度比对 |
| 证明器 | `POST /api/streams/{id}/proofs`、`GET /api/proofs/{id}` | 生成状态语义证明（守恒条件） |
| 迁移编排 | `POST /api/streams/{id}/migration-plans`、`POST .../execute-all`、`POST /api/migration-plans/{id}/execute`、`POST /api/migration-plans/{id}/adjudicate` | 计划生成/执行/人工裁定 |
| 双轨校验 | `POST /api/streams/{id}/dual-track-runs`、`GET /api/dual-track-runs/{id}/diffs` | 同截点并行计算与差异量化 |
| 切换闸门 | `POST /api/streams/{id}/gate/evaluate`、`POST .../gate/release`、`GET .../gate` | 评估/发布/状态 |
| 回退控制 | `POST /api/streams/{id}/rollbacks`、`GET .../rollbacks` | 撤回输出范围、旧快照只读 |
| 证据账 | `GET /api/streams/{id}/evidence?partitionNo=&kind=` | 按分区/类别查询证据 |
| 预警结果账 | `POST/GET /api/streams/{id}/outcome-ledger`、`GET .../outcome-ledger/{entryId}`、`GET .../outcome-ledger/exposure` | 冻结条目；暴露拆分汇总 |
| 观测/处置 | `POST/GET /api/streams/{id}/observations`、`POST/GET .../interventions` | 现场水深/视频/人工标签；泵站抽排/道路封闭 |
| 结果覆盖 | `GET /api/streams/{id}/outcome-coverage` | 标签状态分类统计、不可识别占比、覆盖选择偏差 |
| 校准评估 | `POST/GET /api/streams/{id}/evaluations`、`GET .../{runId}` | 按流域/道路类型/观测状态：等级命中、提前量、校准区间、右删失范围 |
| 上线门禁 | `POST/GET /api/streams/{id}/release-gates`、`GET .../latest` | 最低覆盖+安全下限判定；允许扩大的道路范围 |

## 迁移路线选择语义

- **状态适配（ADAPT）**：旧聚合拆解/提升为三层状态，延迟小。仅当守恒条件满足时选用：
  - `PUMP_ON_OFF_CONSERVATION`：由启停事件推导的窗口内泵运行时长须与快照累计量一致（容差 1 分钟）；
  - `WINDOW_GRANULARITY_MATCH`：旧窗口须为约两小时，与新规则累计粒度一致。
  快照缺失启停事件时**适配器必须拒绝该快照，禁止以零值填充缺失启停区间**；执行时若发现累计量不守恒，计划器自动**转为限定时间窗回放**。
- **限定窗口回放（REPLAY）**：从冻结位点对受影响分区回放原始事件重算三层状态，语义最直接；回放窗口受事件保留范围约束，保留不足时降级为**人工裁定（MANUAL）**。
- 计划器依据状态可逆性（证明结论）、事件保留范围与汛情时效（关键分区优先适配）为各分区选择或混合两条路线。

## 关键业务规则

1. 新规则把某道路由低风险改为高风险、而旧作业尚未完成该分区检查点时，闸门**维持旧输出**并把该分区标记为**待验证**（`pendingVerification`），补齐检查点后方可放行。
2. 双轨期间规则二次修订（如可信度规则 v2.0.0→v2.1.0）：相关双轨比较与适配证明**过期**，引用旧证明的计划置为 `STALE`，目标版本**重新绑定**到新规则包，须重新证明与双轨后才能发布。
3. 回放分区越过切换水印时，证据账区分：已发布新输出（越水印部分**撤回**为 `RETRACTED`）、本次回放结果仅作**新候选**（`NEW_CANDIDATE`）、旧输出（`OLD_OUTPUT`）保持不变。
4. 回退保留旧规则**只读快照**，撤回 `[fromOffset, toOffset]` 范围内的新输出，闸门回到 `HOLD`，流恢复旧规则服务。

## 评估与上线门禁语义

不能把「大屏等级」或「派单次数下降」直接当作模型改善：道路封闭会让视频标签缺失、抽排会改变后续水位、
人工核验与现场水深记录常迟到数日。为此本服务把**预警结果**与**事后观测**分开治理：

- **结果账冻结**：`POST /outcome-ledger` 冻结当时的规则版本、状态水印、输入可信度、风险输出与派单干预，
  之后到达的标签/新规则不改变已冻结条目。`stage` 区分 `OLD_PUBLISHED` / `NEW_PUBLISHED` / `SHADOW_SCREENING`；
  **影子运行（`SHADOW_SCREENING`）只作筛查，不计入真实减灾收益，也不参与放行**。
- **暴露拆分**：规则升级跨越同一积水事件时，以 `exposureGroup` 归组，按 `outputTime` 相对 `ruleChangeBoundary`
  拆为 `BEFORE_RULE_CHANGE` / `AFTER_RULE_CHANGE`（`GET /outcome-ledger/exposure` 汇总跨事件拆分）。
- **标签可观察性分类**（`ObservabilityClass`）：
  - `VERIFIED_DEPTH` 已核验水深（独立核验，可进入严格评估集）；
  - `INTERVENTION_AFFECTED` 受处置影响的观测——**红色预警后抽排、水位未再上升，不得计作假阳性**，也不计漏报；
  - `DELAYED_VIDEO` / `DELAYED_MANUAL` 视频/人工延迟标签（按事件时间纳入）；
  - `UNOBSERVED` 完全未观察（如道路封闭导致无标签）。
- **时间对齐**：现场水深、封路、泵站动作按事件时间与预警连接；处置之后的下降水位仅记录、`usableForDisproof=false`，
  **不反证原先风险**。
- **评估路线**：
  - `STRICT_VERIFIED` 仅用独立核验水深（偏差小、解释清晰，但样本少、覆盖慢）；
  - `CENSORED_INTERVENTION` 纳入受处置影响/延迟标签，带删失与干预标记（覆盖更广，但须报告不可识别部分与敏感性范围）。
  评估按 `asOf` 判定右删失：**视频在峰值后补传时，当时按右删失处理；到达后重评按事件时间纳入，
  并可用 `replayOfRunId` 追溯当时右删失版本**。结果按流域、道路类型、观测状态分层给出等级命中、
  预警提前量（输出→积水事件时间）、Wilson 校准区间与右删失范围，另给敏感性上下界。
- **上线门禁**：只允许把通过**最低覆盖**（严格核验覆盖的道路比例）与**安全下限**（核验样本漏报率上限）的规则
  扩大到更多道路；某流域仅低风险道路获得核验时标记**覆盖选择偏差**并阻止全流域校准结论。

## 演示数据（首次启动自动写入）

流 `FY-MAIN`（4 个分区，p0/p1/p2 为关键分区）：

| 分区 | 道路 | 初始状态 | 预期路线 |
| --- | --- | --- | --- |
| 0 | R-001 | 检查点完成，启停守恒满足 | ADAPT |
| 1 | R-002 | 快照缺失启停事件 | REPLAY（禁止零值填充） |
| 2 | R-003 | 旧作业检查点未完成；新规则 LOW→HIGH | REPLAY + 闸门待验证 |
| 3 | R-004 | 检查点完成，空启停守恒满足 | ADAPT |

演示数据同时预置评估示例（结果账 + 观测 + 处置），覆盖四个关键场景：

| 道路 | 流域/类型 | 场景 | 预期分类/结论 |
| --- | --- | --- | --- |
| R-001 | B-NORTH/ARTERIAL | 红色预警后抽排、水位未再上升 | `INTERVENTION_AFFECTED`，不计假阳性 |
| R-002 | B-NORTH/COLLECTOR | 视频峰值后 3 天才补传（4 天前输出） | `DELAYED_VIDEO`，按事件时间纳入，可回溯当时右删失 |
| R-003 | B-SOUTH/ARTERIAL | 同一积水事件跨越 v1.0.0→v2.0.0 升级 | 暴露拆分为 `BEFORE`/`AFTER_RULE_CHANGE` |
| R-004 | B-SOUTH/LOCAL | 仅低风险道路得到人工核验 | 门禁标记覆盖选择偏差、阻止全流域结论 |

## 验证方式（本仓库交付前实际执行过的验证）

```bash
# 1) 自动化测试：25 个用例全部通过（证明器/计划器/适配器回退/双轨/闸门/规则修订/回退/证据账/接口鉴权，
#    以及结果账/标签分类/时间对齐/校准评估/上线门禁 8 个新增用例）
mvn test

# 2) 端到端冒烟（应用已启动、演示数据为初始状态）：17 步完整流程，输出 SMOKE_OK
scripts/smoke.sh

# 3) 重复冒烟前先重置演示数据（清空业务表并重启应用重新播种）
scripts/reset-db.sh && scripts/smoke.sh
```

冒烟流程：证明 → 迁移编排（p0/p3 适配、p1/p2 回放）→ 执行 → 双轨（p2 低→高升级）→ 闸门待验证 →
补齐 p2 检查点 → 规则二次修订 v2.1.0（证明/比较过期、计划 STALE）→ 重新绑定 → 发布新输出 →
p1 回放越过切换水印（已发布输出撤回、结果仅作候选）→ 回退（快照只读、范围撤回）→ 证据账分类统计 →
结果账与暴露拆分 → 结果覆盖与标签状态 → 校准评估（删失干预路线）→ 严格评估 →
迟到视频的当时右删失版本与到达后重评溯源 → 上线门禁（覆盖选择偏差阻止全流域结论）。

> 说明：本开发容器无 Docker 守护进程，`docker compose up --build` 未在本环境实际执行；
> Dockerfile/Compose 使用标准基础镜像（`maven:3.9-eclipse-temurin-25`、`eclipse-temurin:25-jre`、`postgres:18`），
> 应用本体已通过上述 `mvn test` 与 `scripts/smoke.sh` 在相同 jar 上验证。

## 目录结构

```
src/main/java/com/flood/statemigrate/
├── domain/      # 实体与枚举（流定义/规则包/检查点/证明/计划/双轨/闸门/回退/证据/事件/结果账/观测/处置/评估/门禁）
├── repo/        # Spring Data JPA 仓库
├── service/     # 证明器、计划器（适配+回放）、双轨、闸门、回退、证据账、聚合与规则引擎；
│                # 结果账、标签可观察性分类器、时间对齐器、校准评估器、上线门禁
├── web/         # REST 控制器与请求 DTO
├── config/      # 安全、OpenAPI、演示数据种子
└── error/       # 统一异常处理
scripts/         # smoke.sh 端到端冒烟、reset-db.sh 演示数据重置
docker/          # Maven 镜像仓库配置（构建加速）
Dockerfile · docker-compose.yml
```
