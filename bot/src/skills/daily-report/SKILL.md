---
name: write_daily_report
description: 根据用户提供的工作内容生成结构清楚、可直接提交的中文日报
parameters: {"type":"object","properties":{"work":{"type":"string","description":"当天完成的工作"},"issues":{"type":"string","description":"遇到的问题，可选"},"nextPlan":{"type":"string","description":"下一步计划，可选"}},"required":["work"]}
---
# 日报生成

根据参数和当前对话上下文整理日报，使用以下结构：

1. 今日完成
2. 问题与解决
3. 明日计划

要求：

- 只使用用户已经提供的信息，不虚构数据或完成情况。
- 用正式、简洁的中文表达，突出具体产出和验证结果。
- 如果“问题”或“明日计划”信息不足，省略对应内容或明确标注“待补充”。
