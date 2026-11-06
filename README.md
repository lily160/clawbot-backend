# Cloudbot

Cloudbot 是一个基于微信 iLink SDK 的智能助手项目，既能做通用聊天、搜索、天气、图片/视频理解、语音回复和小工具，也能承接一整套「健康管家」能力，包括建档、导诊、挂号、体检报告解析、用药提醒、健康打卡和体检模板。

> **安全提醒：请使用微信【小号】扫码登录。Cloudbot 存在封号风险，切勿使用主号。**

## 当前能力

### 通用助手能力

| 能力 | 说明 | 主要依赖 |
|------|------|----------|
| 多轮对话 | DeepSeek 负责意图分流与回复润色 | `DEEPSEEK_API_KEY` |
| 天气查询 | 高德天气查询后再润色为微信口吻 | `AMAP_API_KEY` |
| 联网搜索 | 博查搜索实时信息后再润色回复 | `BOCHA_API_KEY` |
| 识图 / 识视频 | 通义千问视觉理解 + DeepSeek 优化表达 | `DASHSCOPE_API_KEY` |
| 文生图 / 文生视频 | 火山方舟生图、生视频 | `ARK_API_KEY` |
| 语音 | ASR 转写、TTS 回复、音色切换、声音复刻 | `DASHSCOPE_API_KEY` |
| 人格切换 | 发送「变身：xxx」切换语气与 TTS 风格 | — |
| 娱乐互动 | 猜数字、笑话、谜语、名言、掷骰子、抽签、运势 | — |
| 待办提醒 | 添加 / 查看 / 删除提醒，支持一次性与重复任务 | — |
| 灵感笔记 | 保存笔记到数据库，并可同步到 GitHub Gist | `GITHUB_GIST_TOKEN` |
| 文件收发 | 读取 txt/md/docx/xlsx/zip/pdf，支持导出文件 | — |
| 数学计算 | 表达式求值（括号、幂、四则运算） | — |
| 翻译 | 文字翻译 | — |
| 语义记忆 | 长期记忆存储与语义搜索 | — |
| 插件 SPI | 外部 JAR 热加载扩展工具 | — |

### 健康管家能力

| 能力 | 说明 | 主要工具 |
|------|------|----------|
| 健康档案 | 录入、查询本人或家人的健康档案 | `health_profile_upsert` / `health_profile_get` |
| 症状导诊 | 根据症状建议科室与常见检查，支持追问 | `health_triage_department` |
| 挂号推荐 | 输出医院候选号源、导诊建议与预约入口 | `health_booking_recommend` / `health_booking_confirm` |
| 体检报告解析 | 解析 PDF，标注偏离项，支持趋势对比与复查建议 | `health_report_parse` |
| 用药管理 | 记录、查看、修改、删除用药，并联动提醒 | `health_med_*` |
| 健康打卡 | 喝水、运动、早睡等打卡统计 | `health_checkin_add` / `health_checkin_today` |
| 日常提醒 | 设置喝水、运动、早睡、复查提醒 | `health_daily_reminder` |
| 体检模板 | 输出青年、中老年、孕期等体检套餐建议 | `health_checkup_plan` |

## 架构概览

本仓库采用「外层按能力模块、模块内按 `api` / `service` / `infra`」的结构：

```text
cloudbot/
├── bot/
│   ├── bootstrap/          # Spring Boot 启动入口
│   ├── platform/           # 微信通道、Agent Loop、共享模型、持久化
│   └── modules/            # chat/search/weather/health/... 各能力模块
├── plugins/                # 外部插件 JAR 目录
└── docs/                   # 项目文档
```

消息主链路如下：

```text
微信
 → platform.channel.wechat
 → platform.agent（消息合并、意图路由、ToolRegistry、Agent Loop）
 → modules.*.api（AgentTool / Gateway）
 → modules.*.service → modules.*.infra
 → platform.channel 回发
```

健康主流程由 `modules/health/common` 的共享契约收口，核心阶段包括建档、导诊、挂号、报告、提醒、打卡和体检模板。

## 运行环境

- Java 21
- Spring Boot 3.3.4
- Maven 多模块
- MySQL、Redis（按配置启用）
- 微信 iLink SDK 2.3.3

## 快速启动

### 1. 编译

在仓库根目录执行：

```powershell
cd <本仓库根目录>
mvn clean install -DskipTests
```

### 2. 配置环境变量或本地配置

先在 `bot/src/main/resources/` 准备本地 `application.yml`，配置 MySQL 数据源及所需的运行参数。项目会从本地配置或环境变量读取密钥。使用本地配置时，可以复制密钥模板：

```powershell
Copy-Item bot/src/main/resources/application-local.yml.example bot/src/main/resources/application-local.yml
```

或者直接设置环境变量：

```powershell
$env:DEEPSEEK_API_KEY="sk-xxx"
$env:DASHSCOPE_API_KEY="sk-xxx"
$env:AMAP_API_KEY="xxx"
$env:BOCHA_API_KEY="xxx"
$env:ARK_API_KEY="xxx"
$env:GITHUB_GIST_TOKEN="ghp_xxx"
$env:ALIYUN_OCR_APPCODE="xxx"
# 或使用 AccessKey 方案
$env:ALIYUN_OCR_ACCESS_KEY_ID="xxx"
$env:ALIYUN_OCR_ACCESS_KEY_SECRET="xxx"
```

### 3. 启动 Bot

```powershell
cd bot
mvn spring-boot:run
```

### 4. 扫码登录

1. 启动后根据日志找到二维码图片，默认保存在 `bot/data/login-qrcode.png`
2. 使用微信小号扫码登录
3. 登录成功后即可通过微信直接对话

## 主要配置文件

| 文件 | 用途 |
|------|------|
| `bot/src/main/resources/application.yml` | 本地运行配置（Git 忽略） |
| `bot/src/main/resources/application-local.yml` | 本地密钥与个人配置（不要提交） |
| `bot/src/main/resources/application-local.yml.example` | 本地配置模板 |

## 关键环境变量

| 变量 | 用途 |
|------|------|
| `DEEPSEEK_API_KEY` | 主对话与回复润色 |
| `AMAP_API_KEY` | 天气、地理编码、POI 查询 |
| `BOCHA_API_KEY` | 联网搜索 |
| `DASHSCOPE_API_KEY` | 视觉理解、语音能力 |
| `ARK_API_KEY` | 生图 / 生视频 |
| `GITHUB_GIST_TOKEN` | 灵感笔记同步 |
| `ALIYUN_OCR_APPCODE` / `ALIYUN_OCR_ACCESS_KEY_ID` / `ALIYUN_OCR_ACCESS_KEY_SECRET` | 体检报告 OCR |

## 文档索引

| 文档 | 说明 |
|------|------|
| `docs/项目结构约定.md` | 目录结构、依赖边界、协作规则 |
| `docs/健康管家-功能说明.md` | 健康管家完整功能说明 |
| `docs/健康管家-API定义.md` | 健康管家工具 / API 定义 |
| `docs/健康报告模块-功能说明.md` | 体检报告解析模块细节 |
| `bot/README.md` | bot 模块的启动与职责说明 |

## 说明

- 运行数据、日志、二维码、auth 信息都会落到 `bot/data/` 或 `bot/logs/` 下。
- 插件 JAR 放在仓库根目录的 `plugins/` 目录即可热加载。
- 健康相关回复统一带有免责声明，不做诊断或处方建议。
