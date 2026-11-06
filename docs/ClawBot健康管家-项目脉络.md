# ClawBot 家庭健康管家 — 项目脉络（全员必读）

> **读者**：五人协作全体成员  
> **基础代码**：本仓库现有 `bot`（微信 iLink Cloudbot）  
> **配套文档**：[五人分工与减冲突约定](./ClawBot健康管家-五人分工.md)  
> **结构约定**：[项目结构约定](./项目结构约定.md)（必须遵守）

本文说明：**项目做什么、不做什么、如何落到现有 Bot、一期做到哪、各模块怎么协作**。

---

## 1. 一句话定位

在现有 **微信 Cloudbot** 上，做一个面向家庭场景的 **日常实用型健康管家**。

它重点解决的是日常生活里最常见、最容易忘、最需要持续管理的健康小事：

- 吃药提醒
- 喝水提醒
- 运动提醒
- 早睡提醒
- 复查提醒
- 家庭健康档案管理
- 症状导诊
- 体检报告整理
- 挂号协助
- 健康习惯打卡

产品叙事可以概括成一句话：

> **ClawBot 健康管家是一个面向家庭场景的日常健康助手，帮助用户把建档、提醒、导诊、报告整理、挂号和复查这些高频健康事务串起来，让健康管理真正融入微信日常使用场景。**

**强调**：不是医生、不开方、不确诊、不替代问诊；目标是让健康管理更省心、更日常。

---

## 2. 合规红线（重中之重）

| 允许做 | 禁止做 |
|--------|--------|
| 健康科普、指标释义、档案管理 | 确诊、鉴别诊断、「你得了××病」 |
| 就医指引（建议挂哪一科） | 开具处方、给出治疗方案当医嘱 |
| 报告解析：标注「偏离参考范围」 | 对异常做临床结论 |
| 用药提醒、OTC 配伍**提示** | 改剂量、推荐处方药用法 |
| 资料整理成复诊可读摘要 | 替代执业医师看病 |

**统一落款（所有医疗相关回复必须带）**：

> 仅供参考，不能替代执业医师诊疗。如有不适请及时线下就医。

用户若明确要求「确诊 / 开药 / 治好我」，Agent **拒绝**并引导线下就医。

---

## 3. 和现有 Bot 的关系

### 3.1 我们不重写 Bot

基础能力继续用：

| 已有能力 | 健康管家如何用到 |
|----------|------------------|
| 微信 iLink 收发 | 用户主入口 |
| `AgentOrchestrator` + `ToolRegistry` | 新健康工具以 `AgentTool` 注册 |
| DeepSeek 多轮对话 | 意图理解 + 润色（加医学护栏 Prompt） |
| `modules/file` 文档解析 | PDF 体检报告可复用/扩展 |
| `modules/task` 提醒 | 用药定时提醒可复用调度思路 |
| JDBC / 文件持久化 | 健康档案按微信用户隔离存储 |
| 语音 ASR | 用户可用语音描述症状（已有则直接受益） |

### 3.2 运行时主链路（不变）

```
微信
 → platform.channel.wechat
 → platform.agent（合并消息 + 编排 + ToolRegistry）
 → modules.*.api（AgentTool）
 → modules.*.service → infra
 → platform.channel 回发
```

健康能力 = **新增 `modules/health/**` 下的多个子模块 + 少量 platform 编排扩展**，而不是另起一个项目。

### 3.3 技术栈（保持）

- Java 21 · Spring Boot 3.3.4 · Maven  
- 微信 iLink SDK（依赖，非本仓源码）  
- 启动：仓库根 `mvn clean install -DskipTests`，再进入 `bot/` 执行 `mvn spring-boot:run`  
- **必须用微信小号扫码**（封号风险）

---

## 4. 目标架构（对齐 OpenClaw 思路，落在本仓库）

资料中的 OpenClaw 分层，在本项目中的映射：

| OpenClaw | 本仓库落点 | 一期 |
|----------|------------|------|
| Claw-Adapter | `platform.channel.wechat` + 文件/语音入站 | 已有微信；PDF 走文件通道 |
| Claw-Core | `platform.agent` + **Plan/等待用户恢复** | **新建编排能力** |
| Claw-Tools | `modules/health/*` 各 Tool | 新建 |
| Claw-Storage | 健康档案表/JSON，按 `wechatUserId` 隔离 | 新建；跨端 Sync **不做** |
| Claw-LLM | DeepSeek + 医学护栏 Prompt | 改 Prompt / 系统策略 |
| Claw-Sync | — | **一期不做** |

```
用户（微信）
    ↓
意图：建档 | 用药 | 症状导诊 | 体检解析 | 挂号 | 健康记录 | 其它已有工具
    ↓
Claw-Core 风格编排（可暂停：等用户回 1 / 确认 / 已完成）
    ↓
Health Tools + 分科 RAG（只读知识，不诊断）
    ↓
回微信：摘要 / 清单 / 提醒 / Top3 号源 / 演示链接
```

---

## 5. 一期功能范围（已评审锁定）

### 5.1 必做（P0）

1. **医学红线 Prompt + 拒答**
2. **通用 Plan 编排**：多步工具 + `wait_user`（用户回 `1`/`确认`/`已完成` 可恢复）
3. **个人/全家电子档案**：对话建档；敏感字段二次确认；按微信用户隔离
4. **症状科普导诊**：只基于 **公立医院分科目录 RAG** → 建议科室 + 常见检查名称（不诊断）
5. **沙箱挂号**：Top3 → 用户选序号 → 沙箱成功回执（文字摘要 + H5/演示链接）
6. **体检 PDF 上传解析**：标偏离参考范围 + 建议与医生复核（不下诊断）

### 5.2 一期应做（P1）

7. **用药管理提醒**：药名/频次/疗程 + 定时推送（复用/对接 task 调度）
8. **OTC 配伍小样本表**：命中则提示冲突风险（非医嘱）
9. **体检方案模板**：青年 / 中老年 / 孕期套餐清单
10. **日常健康记录**：喝水、运动、睡眠、体重等轻量记录

### 5.3 可后置（P2）

11. 病历/检验「复诊一页纸」深度整理  
12. 真医院/市平台适配器实装（接口一期先定）  
13. 完整药品说明书库、跨设备 Sync、本地大模型、硬件手环  

### 5.4 一期 RAG 最小集

**仅：公立医院分科诊疗目录**（支撑「挂哪科」）。  
指标释义全文库、慢病大全、完整 OTC 说明书库 — 后续再加。

### 5.5 挂号交互（已定）

| 项 | 约定 |
|----|------|
| 选号 | Bot 给 Top3，用户回 `1/2/3` 再提交 |
| 一期平台 | **沙箱**（假号源，可演示多院） |
| 设计抽象 | `HospitalPlatformAdapter` 可插拔 |
| 支付 | 真挂时 Bot 暂停，微信催用户去付 |
| 登录/人脸 | 真挂时发链接，用户完成后再继续 |
| 约满 | 失败原因 + 新的替代 Top3（不做放号监控） |
| 身份 | 本人 + 家人代挂 |

---

## 6. 建议代码落位（所有人按此建包，减少冲突）

在现有结构下新增（名称以分工文档为准，勿擅自改名）：

```
bot/src/main/java/com/github/wechat/cloudbot/
├── platform/agent/
│   ├── plan/                    # 【组长】Plan、Step、等待用户恢复
│   └── …现有 AgentOrchestrator / ToolRegistry
├── modules/health/
│   ├── common/                  # 【组长】共享 DTO、红线文案常量、Adapter 接口
│   ├── profile/                 # 【成员A】档案 api/service/infra
│   ├── triage/                  # 【成员B】症状导诊 + 分科 RAG
│   ├── booking/                 # 【组长】挂号 Tool + Sandbox Adapter 实现
│   ├── report/                  # 【成员D】PDF 体检解析
│   ├── medication/              # 【成员C】用药提醒 + OTC 小表 + 日常提醒
│   └── checkup/                 # 【成员C】体检套餐模板
bot/src/main/resources/
├── health/
│   ├── rag/departments.json     # 【成员B】分科目录
│   ├── otc/interactions-sample.json  # 【成员C】OTC 小样本
│   └── checkup/templates.json   # 【成员C】体检模板
└── db/migration/
    └── V2__health_*.sql         # 【组长定编号；成员A写表结构 PR】
```

**依赖方向（强制）**：

- `modules/health/*` → `platform`、`modules/health/common`（允许）
- `booking`（组长）可依赖 `triage` 经 common 的 `TriageResult`
- **禁止** `profile` 去改 `booking` 内部；**禁止** 业务模块互改对方 `infra`
- **禁止** 非组长大面积改 `AgentOrchestrator` / `ToolRegistry` / `CloudbotProperties` / `application.yml` 主干键

新增工具步骤（与现网一致）：

1. `XxxTool implements AgentTool` + `@Component`  
2. `ToolDefinition` 名称全局唯一（建议前缀 `health_`）  
3. 业务在 `service/`，IO 在 `infra/`  
4. 单测放 `bot/src/test/java/.../modules/health/...`

建议工具名（勿占用他组名字）：

| 工具名 | 模块 |
|--------|------|
| `health_profile_upsert` / `health_profile_get` | profile |
| `health_triage_department` | triage |
| `health_booking_recommend` / `health_booking_confirm` | booking（**组长**） |
| `health_report_parse` | report（成员D） |
| `health_med_add` / `health_med_list` | medication（成员C） |
| `health_checkup_plan` | checkup（成员C） |

---

## 7. 用户主流程（Demo 剧本）

### 7.1 建档 → 导诊 → 挂号（主演示）

```
用户：给妈妈建档，高血压，青霉素过敏
Bot：展示将保存的字段 → 请回「确认」
用户：确认
Bot：已保存（免责声明）

用户：妈妈咳嗽三天有点低烧
Bot：根据分科目录，建议考虑：呼吸内科 / 全科 …
     常见检查名称：血常规、胸片等
     【非诊断】需要的话可以帮你看号源 Top3

用户：帮我挂号
Bot：Top3：①… ②… ③… 请回 1/2/3
用户：1
Bot：【沙箱】预约成功 + 摘要 + 链接
```

### 7.2 PDF 报告

```
用户：[发送体检 PDF]
Bot：已解析。偏离参考：…。建议与医生复核。不能据此确诊。
```

### 7.3 用药 / 日常提醒

```
用户：记下阿司匹林肠溶片每天一次
Bot：已记录，将按时提醒。若与已录药物命中 OTC 样本冲突表，会另发提醒（非医嘱）。
```

```
用户：每天早上 8 点提醒我喝水
Bot：已设置日常提醒。
```

---

## 8. 里程碑（建议 4 周，可按课表压缩）

| 里程碑 | 目标 | 可演示 |
|--------|------|--------|
| **M1** | 红线 Prompt + Plan 等待用户 + 档案建档确认流 | 建档二次确认 |
| **M2** | 分科 RAG 导诊 + 沙箱挂号 Top3 闭环 | 导诊→选号→回执 |
| **M3** | PDF 解析标异 + 用药提醒 + OTC 小表 + 日常提醒 | 发 PDF / 设提醒 |
| **M4** | 体检模板 + 话术打磨 + 五人合并联调 | 完整 Demo 彩排 |

真平台适配、Sync、基层/科研线：**不进本阶段验收**。

---

## 9. 分支与合并总原则（细节见分工文档）

1. 从最新 `master`（或组长指定的 `develop`）拉自己的 `feature/health-*`  
2. **每人只改自己的包路径**；共享文件走组长或「契约 PR」  
3. 合并顺序建议：`common/plan/booking(组长)` → `profile` ∥ `triage` ∥ `report` ∥ `medication/checkup` → 组长串主 Demo  
4. 每天至少一次 `git fetch` + rebase/merge 主干，避免期末大爆炸  
5. PR 必须包含：改动说明、如何微信测、是否触及红线文案  

---

## 10. 环境与密钥

- 最少：`DEEPSEEK_API_KEY`（对话）  
- PDF/本地解析：优先复用 `modules/file`；若需云 OCR 再另申请 Key（**成员D** 提出，组长统一配 `application-local.yml` 示例）  
- 挂号一期 **无第三方医院 Key**（沙箱，**组长**负责）  
- 本地配置：`application-local.yml` **禁止提交**；密钥用环境变量或本地文件  

---

## 11. 验收标准（一期 Demo）

- [ ] 任意「请确诊/开药」类请求被拒并带免责声明  
- [ ] 能为本人或家人建档，敏感信息需确认  
- [ ] 症状描述 → 只输出科室/检查指引，无病名确诊表述  
- [ ] 沙箱挂号：Top3 → 选择 → 摘要+链接  
- [ ] 上传体检 PDF → 列出偏离项 + 免责声明  
- [ ] 用药可录入并至少触发一次提醒（或可证明调度已注册）  
- [ ] OTC 样本表至少 3～5 组冲突可演示  
- [ ] 日常提醒（喝水/运动/早睡任选其一）至少能演示一项  
- [ ] 五人代码合入同一分支后 `mvn -pl bot test`（或约定子集）可通过  

---

## 12. 文档索引

| 文档 | 用途 |
|------|------|
| 本文 | 产品与技术脉络 |
| [ClawBot健康管家-五人分工.md](./ClawBot健康管家-五人分工.md) | 谁做什么、改哪些目录、怎么减冲突 |
| [项目结构约定.md](./项目结构约定.md) | 包依赖与模块分层硬规则 |
| 仓库根 [README.md](../README.md) | 启动、扫码、已有能力 |

---

**变更记录**：初稿根据组内需求评审整理；已修订为与当前仓库一致的健康管家主线：**日常提醒、档案、导诊、沙箱挂号、报告解析、用药管理、体检模板**。
