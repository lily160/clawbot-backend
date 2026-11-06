# Bot 模块

微信 **Agent** 应用。折中结构：外层按能力模块，模块内 `api` / `service` / `infra`。

详见 [项目结构约定](../docs/项目结构约定.md)。

## 启动

```powershell
cd <本仓库根目录>
mvn clean install -DskipTests

cd bot
mvn spring-boot:run
```

入口类：`com.github.wechat.cloudbot.bootstrap.WeChatBotApplication`  
（`scanBasePackages = com.github.wechat.cloudbot`）

完整说明见根目录 [README.md](../README.md)。

## 包职责

| 区域 | 包 | 职责 |
|------|-----|------|
| 启动 | `bootstrap` | Spring Boot 入口 |
| 平台 | `platform.channel` / `agent` / `persistence` / `model` | 通道、编排、持久、共享模型 |
| 能力 | `modules.chat` 等 | 各能力 api/service/infra |

## 体检报告解析

体检报告能力位于 `modules.health.report`：

- `api/health_report_parse`：Function Calling 工具入口
- `service/`：报告编排、指标解析、安全输出
- `infra/`：PDF 提取、OCR 兜底、讯飞签名与接口调用
