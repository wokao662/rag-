# 问题表：任务 ③ 审核预填发现的问题汇总

> 与 13 份预填表配套。**只列问题与建议，不改任何数据**。最终处置由维护者决定。

| 编号 | 严重度 | 问题 | 涉及对象 | 建议处置 |
|---|---|---|---|---|
| Q-SCORE | 🔴 高 | `evidenceScore` / `effectivenessScore` 为 null，导入按 0 处理导致合成分垫底 | strategy-spaced-learning | 预填表已给建议值（0.9 / 0.8）与依据；**走审核决定接口落库**（分数与"谁定的、何时定的"绑定），任务 ② 文献清单落地后复核 |
| Q-NOSTEP | 🔴 高 | 两条策略 `steps` 为空（stepCount=0），推中时产生空 `methodSteps` | strategy-learning-motivation-types、strategy-learning-strategy-classification | 预填表参谋意见均为**选项①（背景知识，移出推荐库）**，等你拍板后由维护者定性 |
| Q-SRC-002 | 🟡 中 | source-002 是孤儿来源：没有任何策略引用它，且其 URL 是间隔练习教学资源，本可支撑 spaced-learning / distributed-practice | source-002 | 维护者要求二选一定性：**漏引 vs 该废弃**。建议：先核实 URL 有效性——内容真实则属漏引（补 verification 后关联），链接失效则废弃 |
| Q-SRC | 🟡 中 | 4/5 个来源 verificationStatus = pending，来源真实性未走完审核流程 | source-003 ~ source-006 | 随审核流程补 verification；#1 步核对的"编号存在+内容对口"只能证明自洽，不能替代来源核验 |
| Q-DUP | 🟡 中 | spaced-learning 与 distributed-practice 是同一效应（间隔效应）的两条策略，内容、来源主题、适合人群大面积重叠 | 两条策略 | 建议维护者决定：合并为一条（保留 distributed-practice 的更具体步骤）或差异化定位（如 spaced 面向备考排期、distributed 面向知识保持） |
| Q-CHUNK | 🟡 中 | 「中学生学习策略分析」被切成 suitable_condition chunk，但它是研究对象描述，不是"使用策略的条件"，语义错位 | strategy-learning-strategy-classification 的 chunk | 若 Q-NOSTEP 定性为背景知识则随整组处置；若保留则需重切 chunk（不在本任务范围） |
| Q-LABEL | 🟢 低 | 个别标签出处悬空或暂无法被画像命中："听觉通道接收信息时"（imagery）无出处标注；"认知负荷已较高"（self-explanation）对应画像无此字段 | 两条策略的标签 | 标签先保留，出处待核；"认知负荷"类标签标记为 future——现阶段推荐引擎用不上 |
| Q-DEPTH | 🟢 低 | 全部 13 条 chunk 平均仅 31–46 字，步骤笼统（"确定内容/首次学习"级别），缺操作示例 | 全部策略 | **不在本任务修复**（补内容=语料填厚阶段的工作）；本预填只做如实记录 |
| Q-SRC-001 | 🟢 低 | 来源编号从 002 开始，无 source-001（data/sources/ 里也没有）；但 data/raw/ 下有 source-001.txt | 编号体系 | 无策略引用即无实际影响；建议维护者知悉编号断档原因即可 |

## 附：交叉核对结论（第 1、2 步）

- ✅ 13 条策略的 sourceIds 全部指向存在的文件，无"引用缺失"硬错误
- ✅ 107 块 chunk 与入库记录一致；无重复、无截断、无过短碎片
- ⚠ source-002 孤儿（见 Q-SRC-002）；#11/#12 无步骤 chunk（见 Q-NOSTEP）

> 勘误：早期草稿曾记"3 个策略文件名与 strategyId 不一致"（Q-FILENAME），经 review 确认为误报——`data/strategies/` 下 13 个文件名均与 strategyId 一致，`source-003/004/005.json` 位于 `data/sources/`，是来源文件而非策略档案。该条已撤销。
