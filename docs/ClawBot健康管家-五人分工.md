# ClawBot 家庭健康管家 — 五人分工与减冲突约定

> **配套**：[项目脉络](./ClawBot健康管家-项目脉络.md) · [项目结构约定](./项目结构约定.md)  
> **原则**：按**包路径所有权**分工；共享文件归组长；先合契约再合功能，减少合并冲突。  
> **定位**：项目优先做成一个“日常实用型家庭健康管家”，重点覆盖吃药、喝水、运动、早睡、复查、建档、导诊、报告整理、挂号等高频健康小事。  
> **目标**：每个人的工作都要能落到“具体功能 + 具体目录 + 具体验收结果”，确保最后能拼成一条完整主流程。

---

## 0. 姓名登记（开会时填）

| 代号 | 姓名 | 微信号/学号（可选） | 负责分支 |
|------|------|---------------------|----------|
| 组长 | （你） | | `feature/health-core`（含 plan + common + booking） |
| 成员A | | | `feature/health-profile` |
| 成员B | | | `feature/health-triage` |
| 成员C | | | `feature/health-medication` |
| 成员D | | | `feature/health-report` |

---

## 1. 分工总览（一图看懂）

```
                    ┌────────────────────────────────────────────┐
                    │  组长：主流程编排 + 红线 + 契约 + 挂号闭环   │
                    │  common / plan / booking / 集成联调 / 合并   │
                    └──────────────────┬─────────────────────────┘
           ┌───────────┬───────────────┼───────────────┬───────────┐
           ▼           ▼               ▼               ▼
        成员A        成员B           成员C           成员D
        档案建档     分科导诊RAG     用药+日常提醒   体检PDF解析
      profile/     triage/        medication/     report/
                                  checkup/
```

| 角色 | 主题 | 目标 | 独占目录（别人默认只读） |
|------|------|------|---------------------------|
| **组长** | 编排、合规、契约、**日常提醒中心**、**沙箱挂号**、集成联调、合并 | 把“建档→提醒→导诊→挂号→回执”串起来 | `platform/agent/plan/`、`modules/health/common/`、`modules/health/booking/`、`modules/health/reminder/`（如新增） |
| **成员A** | 全家健康档案 | 把本人/家人的档案做成可写、可查、可确认 | `modules/health/profile/` |
| **成员B** | 症状导诊 + 分科目录 RAG | 把症状描述变成“科室建议 + 检查建议 + 免责声明” | `modules/health/triage/`、`resources/health/rag/` |
| **成员C** | 用药提醒 + OTC 提示 + 体检模板 | 把“吃药管理、健康打卡、日常提醒”做实用 | `modules/health/medication/`、`modules/health/checkup/`、对应 resources |
| **成员D** | 体检 PDF 解析 + 异常跟进 | 把报告变成“偏离项 + 复查建议” | `modules/health/report/` |

---

## 2. 组长职责（可多承担）

### 2.1 产品与协作

- 维护两份文档与验收清单；范围变更只通过组长同步
- 定分支策略、合并顺序、Code Review
- 组织联调 Demo 彩排；准备答辩主叙事
- 统一密钥示例（`application-local.yml.example` 增补说明），**不提交真实 Key**
- 维护全局工具名、共享 DTO、免责声明、编排状态机口径

### 2.2 代码任务（独占 / 主笔）

| 任务 | 路径建议 | 说明 |
|------|----------|------|
| Plan 编排骨架 | `platform/agent/plan/*` | `Plan`/`Step`、状态：RUNNING / WAIT_USER / DONE / FAILED |
| 等待用户恢复 | 同上 + 与入站衔接 | 用户回 `1`/`2`/`3`/`确认`/`已完成` 恢复对应 plan |
| 医学红线 | `modules/health/common/HealthDisclaimers` + chat 系统提示接入点 | 拒答确诊/开方；统一免责声明常量 |
| 共享契约 | `modules/health/common/*` | DTO：`PatientRef`、`TriageResult`、`SlotOption`、`BookingReceipt` |
| 挂号适配器接口 + 沙箱实现 | `modules/health/common/HospitalPlatformAdapter` + `modules/health/booking/**` | 接口与 Sandbox 实现均由组长完成 |
| 沙箱挂号 Tool | `modules/health/booking/api` | `health_booking_recommend` / `health_booking_confirm` |
| 工具名与 Schema 登记表 | 本文或 `common/HealthToolNames.java` | 防工具名冲突 |
| Flyway 版本号分配 | `db/migration/V2__...` 起 | 成员A 写 SQL，版本号由组长发号 |
| Capability 文案 | `CapabilityCatalog` 由组长统一维护 | 能力列表展示 |
| 集成胶水 | 少量改 `AgentOrchestrator` | 挂上 Plan + 挂号流程；禁止多人同时改此文件 |
| 联调分支 | `feature/health-integrate` 或直接往 `develop` | 串联“建档→提醒→导诊→挂号”主 Demo |

#### 组长验收目标

组长不是“只做接口”，而是要把全局链路跑通。第一阶段必须确认下面四件事：

1. 用户问健康相关问题时，红线能拦住“确诊/开药”请求
2. Plan 能在中途暂停，并根据用户回复继续
3. 挂号能基于成员B 的导诊结果给出 Top3
4. 成员A/D/C 的结果都能接入统一回复格式

#### 组长兼挂号：流程与验收

1. `recommend`：科室/时间/`PatientRef` → Top3 `SlotOption`
2. WAIT_USER：用户回 `1/2/3`
3. `confirm`：沙箱生成订单 → `BookingReceipt`（医院、科室、时间、订单号、链接）
4. 失败：原因 + 新的 Top3；一期不做真支付/人脸（预留步骤类型即可）
5. 沙箱至少 2 家假医院；可选 `static/health/booking-receipt.html`

验收：Top3 可选中；回执完整；与成员B 的 `TriageResult`、成员A 的 `PatientRef` 能串起来。

### 2.3 组长明确不做（避免抢活）

- 不写 profile / triage / report / medication / checkup 的业务细节（可 Review）
- 不独自改成员 resources 下的业务 JSON 内容（可先建空目录占位）
- 不让成员去碰 `AgentOrchestrator`、`ToolRegistry`、`application.yml` 主干逻辑
- 不把挂号做成“单点演示”，而是要串在主流程里

### 2.4 组长第一周交付物

1. `health/common` 空壳 + 接口 + DTO 编译通过
2. `plan` 最小可跑：两步假工具 + 中间 WAIT_USER（含 `1/2/3`）
3. 红线 Prompt 已接入主对话（可测拒答）
4. `booking` 沙箱骨架可本地返回假 Top3
5. 本文姓名表填完；开短会对齐工具名和验收标准

---

## 3. 成员A — 健康档案（profile）

### 3.1 目标

把“家庭健康档案”做成真正能用的功能：支持本人/家人建档；字段含身高体重、既往史、过敏、慢性病等；敏感信息**二次确认**后落库；按微信用户隔离；支持查询和复用给挂号、导诊、体检模板。

### 3.2 独占路径

```
modules/health/profile/
  api/          # health_profile_* Tool
  service/
  infra/        # 仓库实现（建议 JDBC，表结构与组长确认）
src/test/.../modules/health/profile/
```

可新增（经组长发号）：

```
resources/db/migration/V2__health_patient_profile.sql
```

### 3.3 必做功能

- 新建健康档案
- 查看健康档案
- 敏感信息确认后再写入
- 家庭成员关系管理（本人、妈妈、爸爸、孩子等）
- 输出 `PatientRef` 供组长挂号使用

### 3.4 接口约定（与 common 对齐）

- 输入：`wechatUserId`、就诊人关系（本人/妈妈…）、结构化字段
- 确认流：Tool 返回“待确认摘要” → 编排 WAIT_USER → 用户“确认” → 真正写入
- 输出：供挂号/导诊使用的 `PatientRef`（id、称呼、过敏摘要等）

### 3.5 验收标准

- [ ] “给妈妈建档…” → 确认 → 可查询
- [ ] 不同微信号数据隔离
- [ ] 单测：写入/确认/查询至少覆盖
- [ ] 能把档案读出来给组长挂号和成员C 体检模板使用

### 3.6 禁止改动

- `AgentOrchestrator`、`ToolRegistry` 核心逻辑
- `triage` / `booking` / `report` / `medication` / `checkup` 包
- 擅自改 `application.yml` 大段（配置项用 `HealthProfileProperties`，前缀 `cloudbot.health.profile`，由组长在 yml 留一行开关亦可）

---

## 4. 成员B — 症状导诊 + 分科 RAG（triage）

### 4.1 目标

把“症状 → 科室建议”做得像日常使用的健康助手：用户描述不适 → 检索分科目录 → 输出建议科室 + 常见检查项目名称 + 免责声明；**不出现确诊病名口吻**。

### 4.2 独占路径

```
modules/health/triage/
  api/          # health_triage_department
  service/      # 检索与话术拼装
  infra/        # 加载 departments.json
resources/health/rag/departments.json
src/test/.../modules/health/triage/
```

### 4.3 必做功能

- 症状关键词匹配
- 科室推荐排序
- 常见检查项目返回
- 免责声明拼接
- 避免输出病名、诊断、处方建议

### 4.4 数据文件要求

`departments.json` 建议结构（可微调，改了要通知组长挂号侧）：

```json
[
  {
    "department": "呼吸内科",
    "aliases": ["呼吸科", "肺科"],
    "symptomKeywords": ["咳嗽", "咳痰", "气短", "低烧"],
    "commonExams": ["血常规", "胸部X线", "C反应蛋白"],
    "notes": "科普指引，非诊断"
  }
]
```

至少覆盖 Demo 用的 **8～15 个常见科室**。

### 4.5 输出契约（给组长挂号用）

必须填充 `common.TriageResult`：

- `suggestedDepartments: List<String>`（有序）
- `commonExams: List<String>`
- `disclaimer: String`（用 common 常量）
- **禁止**字段：`diagnosis` / `diseaseName`

### 4.6 验收标准

- [ ] “咳嗽三天低烧” → 出现呼吸内科/全科类建议
- [ ] 回复含免责声明
- [ ] 单测：关键词命中科室
- [ ] 返回结果能直接给组长挂号模块消费

### 4.7 禁止改动

- 不实现挂号下单（挂号归组长）
- 不改 PDF / 用药模块
- RAG 一期不要擅自加药品说明书大库（范围外）

---

## 5. 成员C — 用药提醒 + OTC + 体检模板 + 日常健康管理

### 5.1 目标

把“健康管家最常用、最日常”的部分做实：用药录入（药名/频次/疗程）+ 定时微信提醒；OTC 小样本配伍表；按年龄/病史生成体检套餐模板；支持喝水、运动、早睡等轻量提醒/打卡能力（如一期来不及，可先做预留接口）。

### 5.2 独占路径

```
modules/health/medication/
  api/          # health_med_add / health_med_list
  service/
  infra/
modules/health/checkup/
  api/          # health_checkup_plan
  service/
  infra/
resources/health/otc/interactions-sample.json
resources/health/checkup/templates.json
src/test/.../modules/health/medication|checkup/
```

### 5.3 必做功能

- 用药记录新增/查看
- 用药定时提醒接入
- OTC 冲突提示（非医嘱）
- 体检套餐模板生成
- 轻量健康提醒入口（喝水/运动/早睡等）如能一期落地则更好

### 5.4 与现有模块协作

- 用药提醒：优先调用/扩展 `modules/task` 的提醒 API；若需改 task 公共签名，**开小 PR 给组长审**
- 可读成员A 档案中的年龄/慢病史（经 common / profile api），用于体检模板；A 未就绪时用入参 Mock

### 5.5 OTC 小样本

`interactions-sample.json` 至少 3～5 组，例如：

```json
[
  {"a": "阿司匹林", "b": "布洛芬", "risk": "可能增加出血风险，请问药师或医生", "level": "warn"}
]
```

话术必须带「非医嘱」。

### 5.6 验收标准

- [ ] 用药可录入并至少证明提醒已注册/可触发
- [ ] OTC 冲突可演示一组
- [ ] 体检模板三类人群可生成清单
- [ ] 至少有一个“日常提醒”能力能演示出来（喝水/运动/早睡任选其一）
- [ ] 能和成员A 的档案或组长的主流程打通一个场景

### 5.7 禁止改动

- 不实现挂号、不改 triage/report
- 不提交真实用药清单到 git（只用演示数据）
- `pom` 新增依赖须组长 Approve

---

## 6. 成员D — 体检 PDF 解析（report）

### 6.1 目标

把体检报告处理成用户看得懂的结果：微信接收体检 PDF → 解析文本 → 列出偏离参考范围项，并附免责声明；**不下诊断、不写病名结论**。

### 6.2 独占路径

```
modules/health/report/
  api/          # health_report_parse
  service/
  infra/
src/test/.../modules/health/report/
```

可自备脱敏示例：`bot/src/test/resources/health/sample-checkup.pdf`（禁止真实病患资料入库）。

### 6.3 必做功能

- PDF/文本提取
- 指标名、测得值、参考范围、是否偏离的结构化输出
- 偏离项标注
- 免责声明拼接
- 简单的复查建议提示（只做“建议复核/咨询医生”，不写治疗方案）

### 6.4 与现有模块协作

- **优先复用** `modules/file` 的文档提取能力：只经其 **api** 调用，**禁止改 file 的 infra**
- 若缺 PDF 能力：在 `health/report/infra` 增加依赖（如 PDFBox），**pom 变更必须组长 Review 锁版本**

### 6.5 输出要求

- 结构化：指标名、测得值、参考范围、是否偏离
- 固定免责声明（用 `common` 常量）
- 禁止输出“确诊××”“建议服用××药”类句子

### 6.6 周切片建议

| 周 | 交付 |
|----|------|
| W1 | 打通收文件 → 抽出纯文本 |
| W2 | 规则/简单解析标偏离 + 微信回复模板 |
| W3～W4 | 样例 PDF 稳定演示 + 单测 |

### 6.7 验收标准

- [ ] 示例 PDF（脱敏人造）可解析出至少 1 个“偏离”演示项
- [ ] 回复含免责声明、无确诊表述
- [ ] 相关单测通过
- [ ] 结果能和成员A/C 的健康档案或体检计划形成闭环

### 6.8 禁止改动

- 不实现挂号 / 用药 / 导诊
- 不往 RAG 塞诊断话术
- 不提交真实用户体检 PDF 到 git

---

## 7. 减冲突铁律（合并前必读）

### 7.1 目录所有权

| 路径 | 所有者 | 他人 |
|------|--------|------|
| `platform/agent/plan/**` | 组长 | 只读 |
| `modules/health/common/**` | 组长 | 只提 Issue/PR 建议，不直接推 |
| `modules/health/booking/**` | **组长** | 只读 |
| `modules/health/profile/**` | 成员A | 只读 |
| `modules/health/triage/**`、`resources/health/rag/**` | 成员B | 只读 |
| `modules/health/medication/**`、`checkup/**`、对应 resources | 成员C | 只读 |
| `modules/health/report/**` | 成员D | 只读 |
| `AgentOrchestrator.java` | **仅组长** | 提需求给组长 |
| `ToolRegistry.java` | 尽量不改；新工具靠 `@Component` 自动注册 | — |
| `CloudbotProperties.java` / `application.yml` | 组长统筹 | 成员用独立 `*Properties` + 小 PR |
| `CapabilityCatalog.java` | 组长 | 成员把文案发组长 |
| `pom.xml`（依赖） | 任何新增依赖 = **必须组长 Approve** | — |

### 7.2 分支命名

```
feature/health-core          # 组长（含 plan + common + booking）
feature/health-profile       # A
feature/health-triage        # B
feature/health-medication    # C（用药 + OTC + 体检模板）
feature/health-report        # D（PDF）
feature/health-integrate     # 组长联调（可选）
```

### 7.3 推荐合并顺序（降低返工）

```
① 组长 common + plan + 红线 + booking 骨架  → 合入 develop/master
② 成员A profile ∥ 成员B triage ∥ 成员D report ∥ 成员C medication/checkup
③ 组长把 triage/profile 接到 booking，串主 Demo；改 CapabilityCatalog、修编译
```

说明：挂号已在组长侧，不再单独等“成员C booking”；成员B 的 `TriageResult` 字段尽早与组长对齐，B 可用 Mock 自测。

### 7.4 提交与 PR 规范

- 每次 PR 只做**一个模块主题**（禁止“顺便重构全平台”）
- Commit message 示例：`feat(health-profile): add confirm flow for family archive`
- PR 描述必填：
  - 改动路径列表
  - 微信测试步骤
  - 是否含免责声明
  - 是否改了共享文件（若改了，@组长）

### 7.5 本地同步节奏

- 每天开工前：`git fetch` + 变基/合并主干
- 共享契约（common）若有变更：组长在群里通知，**全员当天内合入**
- 冲突文件若是自己的包：自己解决；若是 `Orchestrator`/yml：停手找组长

### 7.6 工具名防撞

由组长维护一张表（可放 `HealthToolNames`）：

| 工具名 | 负责人 |
|--------|--------|
| `health_profile_upsert` | A |
| `health_profile_get` | A |
| `health_triage_department` | B |
| `health_booking_recommend` | **组长** |
| `health_booking_confirm` | **组长** |
| `health_med_add` | C |
| `health_med_list` | C |
| `health_checkup_plan` | C |
| `health_report_parse` | D |

新增名字必须先在群里占用，再写代码。

---

## 8. 联调与接口对接清单

| 上游 | 下游 | 对接物 | 何时对齐 |
|------|------|--------|----------|
| 组长 common | 全员 | DTO / Adapter / 免责声明常量 | M1 第 1～2 天 |
| A profile | 组长 booking | `PatientRef` | M1 末 |
| B triage | 组长 booking | `TriageResult.suggestedDepartments` | M2 初 |
| 组长 plan | A / 组长 booking | WAIT_USER（确认 / 1/2/3） | M1 |
| file api | D report | 文本提取 | M2 |
| task api | C medication | 提醒调度 | M3 |
| A profile（年龄/病史） | C checkup | 模板入参 | M3 |
| D report | A profile / C checkup | 体检结果与健康计划输入 | M4 |

**对接会议**：建议 M1、M2 各开 30 分钟“契约会”，只对字段，不写代码。

---

## 9. 测试与质量门禁

| 角色 | 最低测试要求 |
|------|----------------|
| 全员 | 自己模块至少 1 个 JUnit；禁止提交无法编译的代码 |
| 组长 | Plan 恢复 + 红线拒答 + 沙箱挂号单测 |
| 合入前 | `cd bot && mvn -q -DskipTests compile` 必须过；有条件跑相关 test |

健康相关回复抽查：组长或任意成员用“请直接告诉我得了什么病”回归拒答。

---

## 10. 风险与升级路径

| 风险 | 应对 |
|------|------|
| 多人改 `AgentOrchestrator` | **禁止**；需求提给组长排队 |
| PDF 依赖冲突 | 依赖只加一次，组长锁版本 |
| 导诊话术像诊断 | 用 common 模板句；Code Review 盯“确诊”用词 |
| 期末合并爆炸 | 遵守每天同步 + 本文合并顺序 |
| 真挂号合规/风控 | 一期只沙箱；真平台另开阶段并由组长评估 |

---

## 11. 周会检查表（复制到群公告）

**本周每人只需回答：**

1. 我的分支名 / 是否已从最新主干更新？
2. 我独占目录下新增了哪些类？
3. 有没有碰共享文件？碰了是否已 @组长？
4. Demo 路径上卡在哪一步？
5. 需要别人提供的字段/Mock 是什么？
6. 我的功能是否已经进入“可演示、可验收、可串主流程”的状态？

---

## 12. 签字确认（可选）

| 角色 | 已阅读脉络文档 | 已接受目录所有权 | 日期 |
|------|----------------|------------------|------|
| 组长 | ☐ | ☐ | |
| 成员A | ☐ | ☐ | |
| 成员B | ☐ | ☐ | |
| 成员C | ☐ | ☐ | |
| 成员D | ☐ | ☐ | |

---

**使用方式建议**：第一次开会一起过“脉络” 30 分钟 +“分工” 30 分钟，填姓名表，组长当天合出 `common` 空壳，四人再并行开工。每周按“验收标准”逐项过一遍，没进入可演示状态的功能，优先补齐，不继续扩新功能。
