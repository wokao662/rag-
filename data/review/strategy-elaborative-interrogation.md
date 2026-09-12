# 审核预填：strategy-elaborative-interrogation（精细加工）

> 本文档是审核预填**建议**，最终审核决定由维护者通过审核端点落库。本文不改任何策略 JSON、不写数据库。

## 1. 来源核对

| 检查项 | 结果 |
|---|---|
| sourceIds → 文件存在 | ✅ source-006 存在 |
| 来源身份 | Dunlosky, Rawson, Marsh, Nathan & Willingham (2013), *Psychological Science in the Public Interest*，DOI 可解析 |
| 内容支撑性 | ✅ 该综述评估了「精细化提问」（elaborative interrogation），评为中等实用性（moderate utility），与策略定义、步骤、适用人群一致 |
| verification 状态 | ⚠ pending（与 4/5 来源一样，待审核流程核验证） |

结论：**来源真实且对口，可支撑本策略**。

## 2. Chunk 抽查

- 共 10 块（定义 1、步骤 5、适合 2、不适合 2），与档案 steps/suitableFor/notSuitableFor 数量一致
- 结构：✅ 无重复、无截断；各块单独可读、自包含
- 深度：⚠ 与库内整体水平一致，步骤表述偏笼统（缺"问什么问题、怎么自问自答"的示例），属语料填厚阶段处理

## 3. 人群标签初稿

**保留（档案已有，合理）**：
- 适合：需要深度理解概念关系的学习；已有一定领域基础知识的学习者
- 不适合：完全没有领域知识的新手；长文本中追问频率过低的情况

**【建议新增】**：
- 适合：备考需要"知其所以然"的理科/社科概念学习 —— 理由：精细加工的核心收益是理解性记忆，对考试中的迁移题型帮助最大
- 不适合：纯粹机械记忆类任务（如背单词表）—— 理由：该技术强在概念关系解释，对无意义材料的增益有限

**【拿不准】**：
- 低龄学生（小学生）是否适合？档案未提。Dunlosky 的实验对象多为中学生以上，低龄学生自问自答的元认知能力可能不足 —— **建议审核者判断**。

## 4. 评分核对（只记录，不改）

| 字段 | 现值 | 合理性 |
|---|---|---|
| evidenceScore | 0.7 | Dunlosky 评为 moderate utility，0.7 偏上限但可接受 |
| effectivenessScore | 0.5 | 与 moderate 评级一致，合理 |

## 5. 关联问题

- 来源 verification 待办 → 见问题表 Q-SRC
- 内容深度偏薄 → 见问题表 Q-DEPTH
