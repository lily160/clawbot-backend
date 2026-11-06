# 修复与变更记录

## 1. Flyway 迁移脚本兼容 MySQL 8.3

**问题：** MySQL 8.3 不支持 `CREATE INDEX IF NOT EXISTS` 语法，Flyway 10.10 + outOfOrder 模式下重复执行迁移时报 `Duplicate key name` 错误。

**文件：**
- `bot/src/main/resources/db/migration/V2_2__normalize_reminders.sql`
- `bot/src/main/resources/db/migration/V5_1__health_report_history.sql`

**修复：** 将 `CREATE INDEX` 包装在 MySQL 存储过程中，通过查询 `information_schema.statistics` 判断索引是否已存在，避免重复创建。

```sql
CREATE PROCEDURE IF NOT EXISTS _create_idx_reminders()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'reminders'
          AND index_name = 'idx_reminders_user_enabled_time'
    ) THEN
        CREATE INDEX idx_reminders_user_enabled_time ON reminders(user_id, enabled, trigger_time);
    END IF;
END;
CALL _create_idx_reminders();
DROP PROCEDURE IF EXISTS _create_idx_reminders;
```

V5_1 中 4 个索引同理，拆为两个存储过程 `_create_hrr_indexes` 和 `_create_hih_indexes`。

---

## 2. 修复天气查询配置 Key 不匹配

**问题：** 天气查询返回"未配置"，`QueryWeatherTool` 无法正常工作。

**根因：** `application-local.yml` 中配置了 `amap.weather.key`，但 `AmapProperties` 的 `@ConfigurationProperties(prefix = "amap")` 绑定字段为 `apiKey`，导致 key 值为空。

**文件：** `bot/src/main/resources/application-local.yml`

**修复：** 将 `amap.weather.key` 改为 `amap.api-key`（Spring Boot relaxed binding 自动映射到 `apiKey`）。

```yaml
# 修复前
amap:
  weather:
    key: ${AMAP_API_KEY}

# 修复后
amap:
  api-key: ${AMAP_API_KEY}
```

---

## 3. CapabilityCatalog 能力列表优化

**问题：** 用户问 bot 有什么功能时，回复只显示"健康管家将逐步开放…"一行，且动态列出了 39 个 Agent 工具名，用户体验差。

**文件：** `bot/src/main/java/com/github/wechat/cloudbot/platform/agent/CapabilityCatalog.java`

**修改：**
- `userFacingSummary()` 完整列出 7 项健康能力：建档 / 导诊 / 挂号 / 报告解析 / 用药提醒 / 体检计划 / 每日打卡
- 删除了"当前已启用的 Agent 工具（39个）"动态工具列表块
- `userFacingSummary(Collection<String>)` 不再追加工具名列表（参数保留兼容）
- 优化了示例问题和排版

---

## 4. 死代码清理

### 4.1 删除讯飞 OCR 相关类（已切换为阿里云 OCR）

**删除的主代码文件：**
- `bot/src/main/java/.../modules/health/report/infra/IflytekOcrClient.java`
- `bot/src/main/java/.../modules/health/report/infra/IflytekOcrProperties.java`
- `bot/src/main/java/.../modules/health/report/infra/IflytekSignatureFactory.java`

**删除的测试文件：**
- `bot/src/test/java/.../modules/health/report/infra/IflytekSignatureFactoryTest.java`
- `bot/src/test/java/.../modules/health/report/infra/IflytekOcrClientTest.java`

### 4.2 删除无引用的接口和类

- `bot/src/main/java/.../modules/health/booking/infra/HospitalPlatformAdapter.java` — 未使用的接口，`BookingQueryClient` 已替代
- `bot/src/main/java/.../modules/health/common/HealthFlowRoadmap.java` — 零外部引用，仅含一段硬编码说明文字

---

## 5. 综合冒烟测试适配阿里云 OCR

**问题：** 删除讯飞 OCR 类后 `ComprehensiveApiSmokeMain.java` 编译失败，因为其引用了已删除的 `IflytekOcrClient` / `IflytekOcrProperties` / `IflytekSignatureFactory`。

**文件：** `bot/src/test/java/.../bootstrap/ComprehensiveApiSmokeMain.java`

**修复：** 将 `testIflytekOcr()` 重写为 `testAliyunOcr()`，使用 `AliyunOcrClient` + `AliyunOcrProperties`，从环境变量读取 `ALIYUN_OCR_ACCESS_KEY_ID` / `ALIYUN_OCR_ACCESS_KEY_SECRET`。

---

## 6. 补全各功能模块单元测试

**背景：** 多个功能模块缺少测试覆盖。

**新增 11 个测试类（67 个测试），全部通过：**

| 模块 | 测试类 | 测试数 |
|------|--------|--------|
| Health Profile | `HealthProfileGetToolTest` | 6 |
| Health Profile | `HealthProfileUpsertToolTest` | 6 |
| Weather | `QueryWeatherToolTest` | 7 |
| Media | `GenerateImageToolTest` | 7 |
| Media | `GenerateVideoToolTest` | 6 |
| Location | `QueryPoiToolTest` | 6 |
| Location | `GeocodeToolTest` | 6 |
| Browse | `BrowseUrlToolTest` | 5 |
| Translate | `TranslateToolTest` | 7 |
| File | `ExportDocumentToolTest` | 6 |
| File | `SendFileToolTest` | 5 |

**测试模式：** 使用 Mockito mock 各模块的 Gateway/Service 层，聚焦 AgentTool 层的参数解析、边界条件、异常处理、服务不可用等路径。

---

## 7. 修复 CapabilityCatalogTest

**问题：** `CapabilityCatalogTest.summaryAppendsEnabledTools` 断言了已删除的"当前已启用的 Agent 工具"动态列表。

**文件：** `bot/src/test/java/.../platform/agent/CapabilityCatalogTest.java`

**修复：**
- 替换为 `summaryWithEnabledToolsReturnsSameContent`：验证传 null 和传工具列表返回一致（不再追加工具名）
- 新增 `summaryMentionsHealthCapabilities`：验证 6 项健康能力（建档/导诊/报告解析/用药提醒/体检计划/每日打卡）均出现在摘要中

---

## 已知遗留问题（未修复）

1. **挂号模块**：`BookingService` 完整但 4 个 AgentTool 未实现，`RemoteBookingQueryClient` 为骨架（用户决定暂不开发）
2. **PDF OCR 性能**：`PdfReportExtractor.renderPage()` 每页重复 `PDDocument.load`，30 页加载 30 次（用户决定暂不修复）
3. **ProfileServiceTest**：2 个测试失败（`testHandleUpsert_Draft_Success`、`testHandleUpsert_Confirm_Success`）
4. **TriageServiceTest**：1 个测试失败（`triage_normalKeywordsOnly_needThreeForConfidence`）
5. **TriagePipelineIntegrationTest**：1 个测试失败（`pipeline_fullChain_triageToBookingReceipt`）
6. **JdbcChatMemoryStoreTest**：5 个测试报错（H2 上下文初始化问题）
