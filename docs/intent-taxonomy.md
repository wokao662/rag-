# 意图标签体系 v0（intent-taxonomy）

> 2026-10-04 定稿。用于 decide 层（ConversationalProfileAgent）每轮为用户消息标注意图。
> 第一阶段**只记录、不驱动行为**——先把数据攒起来，行为路由等标签在真实数据上稳定后再接。

## 设计原则

1. **标签 = 系统动作**：每个标签必须对应系统要做的事；没有动作对应的意图不建标签（防止标签表膨胀成语言学作业）。
2. **宽容解析**：intent 是观测字段。缺失、类型错误或不在白名单内一律记为 `unknown`，绝不因标注失败拖垮真实回答（与 action 等硬校验字段相反）。
3. **只记不用**：intent 只进 model_call_logs，不改变任何用户可见行为。判错零代价，这正是它敢在第一阶段就上的原因。

## 六个标签

| 标签 | 判据 | 真实例子（来自生产日志） | 系统动作：现状 → 未来 |
|---|---|---|---|
| `provide_info` | 提供关于自己的学习信息：首次求助、回答追问、补约束 | "专业概念看不懂，看完教材也不知道在讲什么"；"中学"；"应付考试" | 更新画像+就绪判断（不变） |
| `deepen` | 对**已推荐过**的方法要步骤/例子/落地安排 | "这两个方法我还是不太明白每天具体该怎么做，结合我背四级单词的情况举个例子" | 走 explain（不变，路由更稳） |
| `explore` | 要**当前推荐之外**的新方法/新方向 | "除了记单词，还有什么方法方式推荐"；"除了记单词还有什么方法可以锻炼我的记忆" | 扩展式检索（要改——旧故障病根在这） |
| `evaluate` | 以现状方案为参照问"够不够/行不行/还差什么" | "只靠背单词能行吗？"；"为了通过考试，我还应该做些什么" | 诊断+补强推荐 |
| `switch` | 引入与当前话题**无关**的新学习内容/目标 | "我数学上有困难"（英语账号会话里开出独立 episode） | 开新 episode（已有）→ 会话级路由（未来） |
| `chitchat` | 不含学习信息、无明确求助的寒暄/情绪 | 样本中暂未出现纯闲聊，待观察 | 轻回应+引回学习（不变） |

## 边界案例（prompt 中同步给出）

1. **explore vs evaluate**："除了记单词还有什么方法" = explore（直接要新的）；"只靠背单词能行吗" = evaluate（以现状为参照问够不够）。
2. **deepen vs explore**：要的对象**已被推荐过** = deepen；没推荐过 = explore。
3. **provide_info vs switch**：补充当前话题细节 = provide_info；引入无关新话题 = switch。
4. **chitchat**：无学习信息且无明确求助才算。

## 暂不纳入（记录在案，防止标签膨胀）

- **repeat_unsatisfied**（重发同一条消息 = 不满信号）：不交给模型判——这是"行为事实"而非"语言理解"，用规则检测（相同文本重复发送）在后处理分析中识别。
- **情绪强度**（挫败/焦虑）：不建标签——与既有安全原则一致（不推断心理属性）。

## 数据采集与查看

- 落点：`model_call_logs` 表（task_type='decide'）的 `output_json.intent`
- 分布统计：

```sql
SELECT output_json->>'intent' AS intent, count(*)
FROM model_call_logs WHERE task_type = 'decide'
GROUP BY 1 ORDER BY 2 DESC;
```

- `unknown` 占比是模型配合度指标：持续偏高说明 prompt 判据需修订或标签边界太模糊。

## 迭代约定

- v1 起**只改 ConversationalProfileAgent 的 prompt 文本**（不动代码）。
- 每 1~2 周翻一次日志：看分布、看 unknown 率、收集边界误判案例。
- 标签增删的前提：日志里出现明确的"套不进去"案例群，而不是设想中的情况。

## 相关代码（第 1 步落地清单）

| 文件 | 内容 |
|---|---|
| `profile/ConversationalProfileAgent.java` | SYSTEM_PROMPT：输出 JSON 的 intent 字段 + 意图标注判据段落 |
| `profile/ProfileDecisionValidator.java` | ALLOWED_INTENTS 白名单；Decision record 加 intent；宽容解析 |
| `profile/UserProfileService.java` | 两处 decide 日志 output 加 intent；两处兜底构造记 unknown |
| `cli/UserProfileConversationTest.java` | 兜底构造同步 |
| `test/.../ProfileDecisionValidatorTest.java` | 宽容策略四用例 |
