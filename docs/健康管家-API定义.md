# 健康管家 API 定义

> 本文档面向开发、联调与后续扩展，按当前代码中的 Tool / Gateway / Port 契约整理。

## 1. 定义范围

健康管家在当前项目中主要通过以下几类接口对外提供能力：

- `AgentTool`：供 LLM Function Calling 调用的工具
- `*Gateway`：供编排层或服务层调用的能力边界
- `*Port`：模块对外稳定契约
- 领域服务：健康流程内的业务编排、解析、推荐、统计逻辑

---

## 2. 工具总览

### 2.1 健康档案

| 工具名 | 作用 | 主要位置 |
|------|------|----------|
| `health_profile_upsert` | 新建或更新健康档案 | `modules/health/profile/api/HealthProfileUpsertTool` |
| `health_profile_get` | 查询当前用户健康档案 | `modules/health/profile/api/HealthProfileGetTool` |

### 2.2 症状导诊

| 工具名 | 作用 | 主要位置 |
|------|------|----------|
| `health_triage_department` | 症状导诊、追问、输出科室和检查建议 | `modules/health/triage/api/TriageDepartmentTool` |

### 2.3 挂号

| 工具名 | 作用 | 主要位置 |
|------|------|----------|
| `health_booking_recommend` | 生成候选号源与医院推荐 | `modules/health/booking/...` |
| `health_booking_confirm` | 确认预约或返回预约入口 | `modules/health/booking/...` |
| `health_booking_followup` | 预约后的后续流程处理 | `modules/health/common` 相关编排中使用 |
| `health_booking_list` | 挂号结果或候选号源列表 | `modules/health/booking/...` |

> 说明：当前仓库中挂号能力的核心契约已经落在 `BookingServicePort` 与 `BookingService`，具体工具层可由编排层或后续补充的 `AgentTool` 继续承接。

### 2.4 体检报告

| 工具名 | 作用 | 主要位置 |
|------|------|----------|
| `health_report_parse` | 解析 PDF 体检报告，返回结构化结果 | `modules/health/report/api/HealthReportParseTool` |

### 2.5 用药管理

| 工具名 | 作用 | 主要位置 |
|------|------|----------|
| `health_med_add` | 记录用药信息 | `modules/health/medication/api/HealthMedAddTool` |
| `health_med_list` | 查询用药清单 | `modules/health/medication/api/HealthMedListTool` |
| `health_med_update` | 修改用药记录 | `modules/health/medication/api/HealthMedEditTool` |
| `health_med_delete` | 删除用药记录 | `modules/health/medication/api/HealthMedDeleteTool` |
| `health_med_stats` | 用药统计与汇总 | `modules/health/medication/api/HealthMedStatsTool` |

### 2.6 健康打卡

| 工具名 | 作用 | 主要位置 |
|------|------|----------|
| `health_checkin_add` | 新增健康打卡 | `modules/health/checkin/api/HealthCheckinAddTool` |
| `health_checkin_today` | 查询今日或最近几天打卡 | `modules/health/checkin/api/HealthCheckinTodayTool` |

### 2.7 日常提醒与模板

| 工具名 | 作用 | 主要位置 |
|------|------|----------|
| `health_daily_reminder` | 设置喝水、运动、早睡、复查提醒 | `modules/health/medication/api/HealthDailyReminderTool` |
| `health_checkup_plan` | 输出体检套餐建议 | `modules/health/checkup/api/HealthCheckupPlanTool` |

---

## 3. 核心接口

### 3.1 `AgentTool`

健康模块里大部分工具都实现了 `AgentTool`。

```java
public interface AgentTool {
    ToolDefinition definition();
    ToolResult execute(ToolCall call);
}
```

#### `ToolDefinition`

```java
public record ToolDefinition(String name, String description, String parametersJsonSchema) {}
```

- `name`：全局唯一工具名
- `description`：给模型看的工具用途说明
- `parametersJsonSchema`：JSON Schema 参数定义

#### `ToolResult`

```java
public record ToolResult(String toolCallId, String name, String content, boolean success) {}
```

- `ok(...)` 表示成功
- `fail(...)` 表示失败
- `content` 用于回灌模型或直接回复用户

---

## 4. 健康档案 API

### 4.1 `ProfileServicePort`

位置：`modules/health/profile/api/ProfileServicePort`

```java
public interface ProfileServicePort {
    PatientRef upsert(PatientRef patientRef);
    Optional<PatientRef> findById(String patientId);
}
```

#### 语义

- `upsert`：新增或更新档案
- `findById`：按患者 ID 查询单条档案

### 4.2 `health_profile_upsert`

位置：`modules/health/profile/api/HealthProfileUpsertTool`

#### 主要参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `relation` | string | 是 | 关系，如本人、妈妈、爸爸 |
| `name` | string | 否 | 姓名或称呼 |
| `gender` | string | 否 | 性别 |
| `birthYear` | integer | 否 | 出生年份 |
| `allergies` | array[string] | 否 | 过敏史 |
| `chronicDiseases` | array[string] | 否 | 慢病史 |
| `isConfirm` | boolean | 是 | 是否确认保存 |

#### 返回

- 成功：保存成功提示、待确认提示或补全提示
- 失败：参数不完整或服务异常

### 4.3 `health_profile_get`

位置：`modules/health/profile/api/HealthProfileGetTool`

#### 参数

无显式参数，用户身份由上下文注入。

#### 返回

返回当前微信用户下的已生效档案列表，采用适合微信展示的文本格式。

---

## 5. 症状导诊 API

### 5.1 `health_triage_department`

位置：`modules/health/triage/api/TriageDepartmentTool`

#### 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `symptoms` | string | 是 | 当前这次症状描述 |
| `followUpRound` | integer | 否 | 追问轮次 |
| `sameAsBeforeConfirmed` | boolean | 否 | 用户确认与上次相同 |

#### 返回 JSON

返回内容通常是一个 JSON 文本，关键字段包括：

- `needsMoreInfo`：是否需要追问
- `formattedResponse`：建议直接回复用户的排版文本
- `followUpRound`：下一轮追问轮次
- `followUpQuestions`：追问问题列表

#### 约束

- 只做导诊，不做诊断
- 只传当前症状，不要把历史症状随便拼接进去
- 复诊场景需要尊重“和上次一样吗”的流程

---

## 6. 挂号 API

### 6.1 `BookingServicePort`

位置：`modules/health/booking/api/BookingServicePort`

```java
public interface BookingServicePort {
    List<SlotOption> recommend(String symptomText, PatientRef patientRef, TriageResult triageResult);
    Optional<BookingReceipt> confirm(String chosenSlotId, SlotOption slotOption, PatientRef patientRef);
}
```

#### 语义

- `recommend`：根据症状、档案和导诊结果，生成候选号源
- `confirm`：确认预约

### 6.2 `BookingQueryClient`

位置：`modules/health/booking/infra/BookingQueryClient`

```java
public interface BookingQueryClient {
    List<SlotOption> recommend(PatientRef patientRef, String preferredDepartment, int limit);
    BookingSubmitResult confirm(PatientRef patientRef, SlotOption chosenSlot);
}
```

#### 语义

- 面向真实医院平台的远程调用边界
- 默认实现可以返回沙箱数据，但不会伪造成真实成功回执

### 6.3 `BookingService`

位置：`modules/health/booking/service/BookingService`

主要职责：

- 根据导诊结果选择推荐科室
- 使用真实平台适配器或兜底号源生成候选列表
- 处理预约确认和预约入口拼接

#### 重要行为

- 如果没有真实平台适配器，不会伪造成功预约
- 兜底号源仅用于演示和引导

---

## 7. 体检报告 API

### 7.1 `health_report_parse`

位置：`modules/health/report/api/HealthReportParseTool`

#### 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `filePath` | string | 是 | 本地 PDF 文件路径 |
| `originalFilename` | string | 否 | 原始文件名 |

#### 返回字段

返回值通常是 JSON 文本，常见字段：

- `message`：面向用户的结构化摘要
- `result`：结构化解析结果
- `trends`：历史趋势
- `followUpSuggestions`：复查建议
- `followUpPlans`：异常跟进计划
- `hasHistory`：是否存在历史报告可对比

### 7.2 `HealthReportParseService`

位置：`modules/health/report/service/HealthReportParseService`

#### 处理流程

1. PDF 文本提取
2. 对扫描页进行 OCR 兜底
3. 指标解析
4. 生成 `HealthReportParseResult`

### 7.3 `ReportTrendAnalyzer`

位置：`modules/health/report/service/ReportTrendAnalyzer`

#### 能力

- 对比当前与上一次报告指标变化
- 生成趋势项
- 输出复查建议和异常跟进计划

---

## 8. 用药 API

### 8.1 `health_med_add`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `drug_name` | string | 是 | 药名 |
| `dosage` | string | 是 | 每次用量 |
| `frequency` | string | 是 | 频次 |
| `course_days` | integer | 否 | 疗程天数 |
| `notes` | string | 否 | 备注 |
| `user_id` | string | 否 | 上下文注入 |

### 8.2 `health_med_list`

无业务参数，返回当前用户已登记的用药清单。

### 8.3 `health_med_update`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `record_id` | string | 是 | 记录 ID |
| `dosage` | string | 否 | 新用量 |
| `frequency` | string | 否 | 新频次 |
| `course_days` | integer | 否 | 新疗程天数 |
| `notes` | string | 否 | 新备注 |

### 8.4 `health_med_delete`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `record_id` | string | 是 | 记录 ID |

### 8.5 `health_daily_reminder`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `kind` | string | 是 | `drink_water` / `exercise` / `sleep_early` / `followup` / `custom` |
| `time` | string | 是 | 每日时间，格式 `HH:mm` |
| `note` | string | 否 | 自定义提醒内容 |
| `user_id` | string | 否 | 上下文注入 |

---

## 9. 健康打卡 API

### 9.1 `health_checkin_add`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `kind` | string | 是 | `drink_water` / `exercise` / `sleep_early` |
| `date` | string | 否 | `YYYY-MM-DD`，默认今天 |
| `count` | integer | 否 | 次数或数量 |
| `remark` | string | 否 | 备注 |
| `user_id` | string | 否 | 上下文注入 |

### 9.2 `health_checkin_today`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `days` | integer | 否 | 查看最近几天，默认 1 |
| `user_id` | string | 否 | 上下文注入 |

---

## 10. 体检模板 API

### 10.1 `health_checkup_plan`

位置：`modules/health/checkup/api/HealthCheckupPlanTool`

#### 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `age` | integer | 否 | 年龄 |
| `gender` | string | 否 | 性别 |
| `chronic_conditions` | array[string] | 否 | 慢病史 |
| `pregnancy_stage` | string | 否 | `none` / `prep` / `pregnant` |
| `bmi` | number | 否 | BMI |
| `smoker` | boolean | 否 | 是否吸烟 |
| `drinker` | boolean | 否 | 是否饮酒 |
| `family_history` | array[string] | 否 | 家族史 |
| `lifestyle` | array[string] | 否 | 生活方式标签 |
| `relation` | string | 否 | 档案关系 |
| `export` | boolean | 否 | 是否导出文件 |
| `notes` | string | 否 | 额外说明 |
| `user_id` | string | 否 | 上下文注入 |

#### 返回

返回体检建议文本；当 `export=true` 时还可生成发送文件。

---

## 11. 统一返回约定

### 11.1 成功返回

成功时通常使用：

```java
ToolResult.ok(call.id(), call.name(), content)
```

### 11.2 失败返回

失败时通常使用：

```java
ToolResult.fail(call.id(), call.name(), message)
```

### 11.3 约定

- `content` 应尽量是适合微信展示的文本
- 对于面向模型的工具，可返回结构化 JSON 文本
- 对于直接展示给用户的工具，优先返回排版清晰的自然语言文本

---

## 12. 扩展建议

新增健康能力时，建议遵循以下规则：

1. 工具名统一使用 `health_` 前缀
2. 工具只做参数校验和流程入口，核心逻辑放 `service`
3. IO、HTTP、文件、SDK 放 `infra`
4. 跨模块协作优先走 `common` 的共享 DTO
5. 所有医疗回复默认带免责声明
6. 不伪造预约成功、不输出诊断结论
