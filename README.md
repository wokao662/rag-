# rag-
尝试用codex生成rag流程

## 递归文本分块 CLI（Python）
`chunk_text_cli.py` 会按“段落 → 换行 → 句子 → 标点 → 字符”的优先级递归切割，适合为 RAG 入库准备中文文本。

### 运行方式
```bash
python chunk_text_cli.py
```
交互模式下输入文本，输入 `END` 并回车结束，然后会输出切割后的文本块。

### 也支持命令行参数输入
```bash
python chunk_text_cli.py --text "这里是一段用于RAG测试的文本。它会被切割成多个块。" --chunk-size 20 --overlap 0
```

### 也支持管道输入
```bash
echo "第一句。第二句。第三句。" | python chunk_text_cli.py --chunk-size 10 --overlap 0
```

### 读取文件并生成 JSONL
```powershell
python chunk_text_cli.py --file .\data\raw\资料.txt --chunk-size 300 --overlap 0 --format jsonl --source-id source-001 --output .\data\chunks\source-001.jsonl
```

JSONL 中每行包含 `chunkId`、正文 `text` 和基础 `metadata`，后续可以补充学习策略、具体方法、适用条件和来源等元数据，再调用 Embedding 模型。

## 使用 Apache Tika 提取文档文本（Java）
项目通过 Maven 接入 Apache Tika，可从 PDF、Word、PowerPoint、HTML 等常见文档中提取文本。

```powershell
mvn compile exec:java "-Dexec.mainClass=DocumentExtractor" "-Dexec.args=资料文件.pdf data/raw/source-001.txt"
```

省略第二个参数时，默认输出到 `data/raw/<输入文件名>.txt`：

```powershell
mvn compile exec:java "-Dexec.mainClass=DocumentExtractor" "-Dexec.args=资料文件.pdf"
```

当前内嵌解析方式只应用于本地可信文档；开放用户上传后应改用隔离的 Tika 服务。

### 一条命令完成提取和自动清洗

```powershell
.\scripts\extract_and_clean.ps1 -InputFile .\data\documents\source-003.pdf -SourceId source-003
```

命令会分别生成：

- `data/extracted/source-003.txt`：Tika 原始提取结果，不做覆盖或修改。
- `data/cleaned/source-003.txt`：合并断行、清除重复页眉页脚和独立页码后的文本。
- `data/cleaned/source-003.report.json`：清洗统计报告。

自动清洗后仍应抽查复杂双栏、表格和扫描 PDF 的内容，再将 `cleaned` 文件交给递归切割脚本。

## 从策略档案生成最终 Chunk

`generate_strategy_chunks.py` 读取 `data/strategies/*.json`，将策略的定义、每个实施步骤、每个适用条件和每个不适用条件分别生成可检索的 JSONL Chunk。

```powershell
python .\generate_strategy_chunks.py
```

默认输出到 `data/chunks/strategy-*.jsonl`，并生成 `data/chunks/generation-report.json`。无效文件会被跳过并记录在报告中；需要在发现错误时返回失败状态可使用：

```powershell
python .\generate_strategy_chunks.py --strict
```

## 向量化、入库与搜索

当前配置为硅基流动 `Qwen/Qwen3-Embedding-4B`、1024 维稠密向量，以及 Qdrant `Cosine` 相似度。
搜索时会为用户问题添加检索任务指令，知识库 chunk 保持原文向量化。

首次使用时复制配置模板，并把占位值替换为自己的硅基流动 API Key：

```powershell
Copy-Item .env.example .env
```

`.env` 只保存在本机且已被 Git 忽略。Java 会优先读取系统环境变量，没有时读取项目根目录的 `.env`。
不要把真实 Key 发到 Git、聊天记录或截图中。

使用 Compose 启动统一配置的 Qdrant：

```powershell
docker compose up -d
```

如果本机已经有手动创建且名为 `qdrant` 的容器，可以继续使用 `docker start qdrant`；迁移到 Compose 前需要先停止并移除旧容器，但命名卷 `qdrant_storage` 可以保留并复用。

生成最终 chunk 后执行入库：

```powershell
python .\generate_strategy_chunks.py --strict
mvn compile exec:java "-Dexec.mainClass=com.example.rag.cli.StrategyIndexer"
```

程序会创建 `learning_strategies` Collection，并把 `data/chunks/*.jsonl` 全部写入。
`chunkId` 是稳定 UUID，因此重复运行会更新已有数据，不会生成重复记录。

执行一次检索：

```powershell
mvn compile exec:java "-Dexec.mainClass=com.example.rag.cli.StrategySearcher" "-Dexec.args=我总是考试前突击，学完很快忘记，应该怎么办"
```

测试完整的 RAG 推荐链路（检索后再调用聊天模型，返回 JSON）：

```powershell
mvn compile exec:java "-Dexec.mainClass=com.example.rag.cli.RagRecommendationTest" "-Dexec.args=我背单词很快忘，而且每天只能学习30分钟"
```

这是命令行测试版：单轮调用、不保存用户画像和历史对话。Prompt 已包含知识来源限定、稳定 chunkId 引用、信息不足澄清、无匹配兜底和基础注入防护。

Qdrant 默认地址是 `http://127.0.0.1:6333`。如果以后连接其他 Qdrant，可在 `.env` 修改：

```dotenv
QDRANT_URL=http://服务器地址:6333
```

## 团队协作约定

- Git 只保存代码、`data/sources` 和 `data/strategies` 等原始数据。
- `data/chunks`、`data/extracted`、`data/cleaned` 和 `target` 都是本地生成物，不提交。
- 每位开发者使用自己的 `.env` 或系统环境变量；仓库只提供不含秘密的 `.env.example`。
- Qdrant 运行数据保存在 Docker 命名卷 `qdrant_storage` 中，不提交到 Git。

## PostgreSQL 用户与对话数据库

PostgreSQL 用于保存用户、多轮会话、消息和用户画像；Qdrant 继续只负责策略向量检索。
本地数据保存在 Docker 命名卷 `rag_postgres_data`，不会写进仓库目录。

在本机 `.env` 中补充：

```dotenv
POSTGRES_DB=learning_app
POSTGRES_USER=learning_app
POSTGRES_PASSWORD=请设置本地开发密码
POSTGRES_PORT=5432
```

你已有手动创建的 `qdrant` 容器时，先只启动 PostgreSQL，避免 Compose 尝试创建同名 Qdrant：

```powershell
docker compose up -d postgres
docker compose ps postgres
docker exec rag-postgres pg_isready -U learning_app -d learning_app
```

第一次创建 `rag_postgres_data` 时，PostgreSQL 会自动执行 `database/init/001_schema.sql`，建立 `users`、`conversations`、`messages` 和 `user_profiles` 四张表。初始化脚本只在空数据卷首次启动时执行；未来结构升级应使用迁移脚本，不要删除数据卷重建。

查看数据表：

```powershell
docker exec rag-postgres psql -U learning_app -d learning_app -c "\dt"
```

验证 Java JDBC 连接和四张表的读写（测试数据会在事务中回滚）：

```powershell
mvn compile exec:java "-Dexec.mainClass=DatabaseConnectionTest"
```

运行持久化多轮用户画像测试（输入 `exit` 结束）：

```powershell
mvn compile exec:java "-Dexec.mainClass=UserProfileConversationTest"
```

不提供参数时，程序会为本次运行生成一个独立的测试用户，避免读取上一次测试遗留的画像。终端输入使用 JVM 当前默认编码；在中文 Windows 环境中通常为 GBK，避免强制按 UTF-8 读取造成乱码。

如果需要跨多次运行继续测试同一个用户，可以明确指定稳定的用户 ID：

```powershell
mvn compile exec:java "-Dexec.mainClass=UserProfileConversationTest" "-Dexec.args=profile-test-user-001"
```

指定相同 ID 会有意复用该用户在 PostgreSQL 中的历史画像；需要测试全新画像时不要传入 ID，或者改用新的 ID。

在 `feature/conversational-profile-agent` 分支中，这个测试会在每轮画像抽取后再次调用聊天模型：

- 根据当前画像和最近对话动态决定继续追问（`ask`）还是进入推荐（`recommend`）。
- 每次只追问一个最关键的问题，并避免重复询问已经回答的内容。
- 模型只能检查信息是否一致，不能判定用户是否说谎，也不得进行疾病、智力或人格诊断。
- Agent 输出必须通过 Java 结构校验；接口失败或输出不合规时自动使用 `ProfileReadinessPolicy` 本地规则兜底。
- 这个阶段仍只显示 `ready=true`，尚未自动调用 Qdrant 推荐。

测试程序将每轮消息和通过 Java 校验的画像保存到 PostgreSQL。当前只接受用户明确表达的信息；画像具备主要困难，或同时具备学习目标与学习内容时，会显示 `ready=true`。这一阶段只验证画像抽取、校验、合并、追问和持久化，暂不进入 Qdrant 推荐。

停止服务但保留数据：

```powershell
docker compose stop postgres
```

不要执行 `docker compose down -v` 或 `docker volume rm rag_postgres_data`，这些命令会删除数据库数据。

## Spring Boot 后端

正式后端入口为 `com.example.rag.RagApplication`。旧的命令行测试类暂时保留，用于验证模型和 RAG 链路，但 WebApp 后续只调用 Spring Boot HTTP API。

先启动 PostgreSQL：

```powershell
docker compose up -d postgres
```

再启动后端：

```powershell
mvn spring-boot:run
```

默认监听 `http://127.0.0.1:8080`。验证应用接口：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/status
```

验证包含数据库连接状态的健康检查：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
```

数据库结构现在由 `src/main/resources/db/migration` 下的 Flyway 迁移管理。已有数据库首次启动时会建立 Flyway 基线；新数据库会执行 `V1__initial_schema.sql`。后续改表应新增 `V2__...sql`、`V3__...sql`，不要修改已经在共享环境执行过的旧迁移。

构建并运行自动化测试：

```powershell
mvn test
```

生成可部署 JAR：

```powershell
mvn clean package
java -jar target/rag-demo-1.0-SNAPSHOT.jar
```

### 用户画像 API（开发版）

创建或识别用户并开始新会话：

```powershell
$conversation = Invoke-RestMethod `
  -Method Post `
  -Uri http://127.0.0.1:8080/api/v1/users/web-user-001/conversations
$conversation
```

发送一条消息，其中 `$conversation.conversationId` 来自上一步：

```powershell
$body = @{ content = "我背英语单词很快忘，每天可以学习30分钟" } | ConvertTo-Json
Invoke-RestMethod `
  -Method Post `
  -ContentType "application/json; charset=utf-8" `
  -Uri "http://127.0.0.1:8080/api/v1/users/web-user-001/conversations/$($conversation.conversationId)/messages" `
  -Body $body
```

查询当前画像：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/users/web-user-001/profile
```

当前 URL 中的 `externalId` 只是开发阶段用于串联数据的稳定标识，不是登录凭证。正式对外部署前必须接入认证，由服务端从已验证的 Token 中取得用户身份，不能信任前端任意传入的用户 ID。

### 推荐 API（开发版）

对话中画像充足（`ready=true`）时会自动触发一次推荐：系统把画像整理成检索文本，经 Embedding 向量化后在 Qdrant 召回候选策略 chunk，并按策略补齐该策略的全部 chunk（定义、实施步骤、适用条件等），再由聊天模型基于完整资料生成结构化推荐。推荐结果作为 assistant 消息（`messageType=recommendation`）存入会话，同时包含在消息接口的响应中。

也可以对已有画像主动获取或刷新推荐：

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://127.0.0.1:8080/api/v1/users/web-user-001/recommendations
```

返回固定 JSON：`status`（`answer`、`clarify`、`no_match`）、给用户看的 `answer`、检索条件 `queryText`、识别到的 `userConstraints`、最多三条 `recommendations`（含方法步骤、理由、来源和 chunkId 引用）以及 `followUpQuestions`。模型输出会经过 Java 校验：状态与内容必须一致，引用必须指向真实检索到的 chunk，校验失败返回 502。

用户还没有画像时调用推荐接口返回 404；Qdrant、Embedding 或聊天模型不可用时返回 502，画像对话流程不受影响。

### 推荐历史与尝试后反馈 API（开发版）

反馈分两条通道，职责互不重叠：

| 通道 | 写入表 | 作用 | 是否影响推荐度 |
| --- | --- | --- | --- |
| 卡片上的 👍 | `recommendation_feedback` | 只汇总给方法投稿者看，告诉他“有人觉得不错” | 否 |
| 历史页的尝试后反馈 | `method_trial_feedback` | 驱动 `triedCount` / `helpfulCount` / `communityScore` 与渐进投放的升降权 | 是（唯一依据） |

取消卡片上的“不感兴趣”是有意的：随手点踩会让好方法仅因为被推给了不合适的人而降权。负面判断改由历史页的 `not_suitable` 承载，那是用户在有上下文时主动填写的，质量高得多，同时也是“适合人群预测”最有价值的负样本。

读取该用户历史上收到过的全部推荐（按时间倒序，跨会话）：

```powershell
Invoke-RestMethod `
  -Uri http://127.0.0.1:8080/api/v1/users/web-user-001/recommendation-history
```

历史内容取自推荐当时序列化进 `messages.metadata_json` 的快照，因此即使某个方法后来被降权归档，历史页仍能显示当初推荐了什么。每条方法额外带 `liked` 与 `trial` 两个当前状态字段，用于刷新页面后恢复界面。

提交或修改对某个方法的尝试后反馈：

```powershell
$body = @{ tried = $true; outcome = "helpful"; note = "分散到三天后确实记住了" } | ConvertTo-Json
Invoke-RestMethod `
  -Method Post `
  -ContentType "application/json; charset=utf-8" `
  -Uri "http://127.0.0.1:8080/api/v1/users/web-user-001/strategies/strategy-keyword-mnemonic/trial-feedback" `
  -Body $body
```

`tried` 与 `outcome` 必须自洽，数据库和 Java 层有同样的约束：

- `tried=true`（试过了）：`helpful`（很有用）、`partial`（有点用）、`not_helpful`（没什么用）
- `tried=false`（没试）：`not_suitable`（不适合我的情况）、`no_time`（还没时间试）

`note` 选填，最长 2000 字。同一用户对同一方法只保留一条记录（`UNIQUE (user_id, strategy_id)`），重复提交视为修改自己之前的反馈，`updated_at` 随之刷新。反馈绑定 `(user, strategy)` 而不是 `(message, strategy)`，因为一个人对一个方法只有一段真实体验，不该因为被推荐三次就填三次。

对系统没有推荐过的方法提交反馈返回 404；`tried` 与 `outcome` 矛盾、`outcome` 未知或缺字段返回 400。少了这道校验，任何人都能凭空对任意 `strategyId` 提交反馈，直接污染驱动升降权的统计数据。

### 策略存储层

`data/strategies/*.json` 是策略档案的权威来源，但 JSON 文件承载不了审核队列、并发投稿和事务，`reviewStatus` 写在里面也只是记录、不是闸门。V6 迁移把它们镜像进 PostgreSQL：

| 表 | 内容 |
| --- | --- |
| `strategies` | 策略档案 + 推荐分字段 + 审核与投放状态 |
| `sources` | 来源。`kind` 区分 `literature` / `user_submission` / `web` / `other`：`generate_strategy_chunks.py` 硬校验 `sourceIds` 非空，用户投稿没有权威文献，需要 `user_submission` 类来源才能入库 |
| `strategy_chunks` | Qdrant 中 chunk 的数据库镜像，`chunk_id` 与 JSONL 里的 uuid5 一致，重复导入走 upsert |
| `strategy_exposures` | 曝光去重表。同一用户对同一策略只计一次，否则重复推荐会把人数上限刷爆 |

导入是幂等的，改完档案可以直接重跑：

```powershell
mvn compile exec:java "-Dexec.mainClass=com.example.rag.cli.StrategyImporter" "-Dexec.classpathScope=runtime"
```

导入器只写内容与两个输入分（`evidence_score` / `effectiveness_score`），而且只在 `review_status = 'draft'` 时覆盖它们——人工审核过后档案不再是评分的权威，重新导入不会把审核结果冲掉。档案里的 `communityScore` 一律不导入：`triedCount = 0` 时那个数字没有任何数据支撑。

`community_score` / `overall_score` / `tried_count` / `helpful_count` / `exposed_user_count` / `exposure_state` / `pending_archive` 全是派生值，唯一写入者是 `StrategyGovernanceService`，应用启动时从 `method_trial_feedback` 与 `strategy_exposures` 整体重建一次。公式只存在这一处，导入器和人工改分都不自己算，避免同一个公式被复制成两份而逐渐走偏。

`reviewer_score` 单独存放审核者的判断：它与合成分的差值是模型二的核心训练标签，共用 `overall_score` 会被互相覆盖而毁掉标签。

### 推荐闸门与渐进投放

闸门位于召回之后、按策略补齐 chunk 之前：被挡下的策略连它的其他 chunk 也不会被送给模型。

`app.governance.review-gate`（环境变量 `GOVERNANCE_REVIEW_GATE`，默认 `false`）决定审核相关的两条规则是否生效：

| 拦截条件 | `false` | `true` |
| --- | --- | --- |
| `archive_missing`：在 Qdrant 对外服务却没有源档案，无溯源可查 | 生效 | 生效 |
| `pending_archive`：足够多真实用户试过且有用率过低 | 生效 | 生效 |
| `exposure_state = 'paused'`：人工暂停 | 生效 | 生效 |
| `review_status != 'approved'` | 不生效 | 生效 |
| 曝光人数达到当前档位上限 | 不生效 | 生效 |

默认关闭是因为现存 13 个策略档案全是 `draft`，直接开启会把推荐过滤成全空。但前三条拦截与审核无关，所以飞轮在人工审核完成之前就开始转：无溯源的和已被真实反馈证伪的照样挡下，曝光计数、社区分与合成总分照常累计。审核完成、把通过的策略置为 `approved` 之后改成 `true`。

闸门查询失败时保守处理：挡下全部候选（宁可这次不推荐，也不能把未审核内容当作已审核推给用户）。曝光记录失败则不阻断推荐——推荐已经生成，不能因为记账写不进去就把它丢掉，计数偏差下一次推荐就会补上。两条路径都会打完整异常栈：外层包装只有一句“数据库操作失败”，真正的 SQL 错误在 `cause` 里。

排查“推荐突然变空”时看 `model_call_logs.input_json` 里的 `gateFilteredOut`，它记录本次被闸门挡下的候选数。

### 升降权规则

只用确定性规则，不引入学习型排序模型，因此可解释、可审计：

- 社区分 = 有用率的 Wilson 95% 置信**下界**，不是点估计。1 个人说有用点估计是 100%，下界会把它拉回保守值（约 0.21）。
- 少于 10 条尝试反馈不动社区分：小样本抖动太大，会误杀或误捧一个方法。
- 只有 `helpful` 计入有用，`partial` 不算，保持保守。
- 合成总分 = 0.3 × 文献证据 + 0.5 × 社区反馈 + 0.2 × 有效性。
- 投放档位 `seed`（20 人）→ `scaling`（`exposure_cap`，默认 50）→ `full`。升档要同时满足人数达标与社区分 ≥ 0.5，否则留在原档继续收集反馈。
- 社区分 < 0.3 且样本足够时标记 `pending_archive`。**只标记不删除**，归档须人审确认。

### Web 聊天界面（开发版）

启动后端后直接在浏览器打开：

```
http://127.0.0.1:8080/
```

页面由 Spring Boot 直接托管（`src/main/resources/static`），无需额外构建。功能：

- 发放访问码后，页面首次使用要求输入访问码才能进入；未发放任何访问码时为本地开发模式，不启用拦截。
- 未开始对话时显示欢迎页和示例问题，点击示例即可直接发送。
- 聊天窗口发送消息后，画像不足时显示助手的追问（带“正在输入”状态），画像充足时自动展示推荐卡片（策略名称、具体步骤、推荐理由、注意事项和来源）。
- 右上角“我的画像”抽屉展示当前画像，每个字段附用户原话引用（画像只能由系统根据对话更新，不提供手动编辑）。
- 左上角可展开历史会话抽屉，会话以首条消息摘要为标题，点击可回看完整消息记录。
- 推荐卡片中的每个策略带一个 👍 按钮和右侧的“试过这个方法了？告诉我们效果 →”入口。点赞只能给不能取消，已点赞的按钮置灰；点击入口跳转到“我试过的”页面并自动定位到该方法。点赞接口：

```powershell
$body = @{ strategyId = "strategy-keyword-mnemonic"; action = "liked" } | ConvertTo-Json
Invoke-RestMethod `
  -Method Post `
  -ContentType "application/json; charset=utf-8" `
  -Uri "http://127.0.0.1:8080/api/v1/users/web-user-001/messages/<推荐消息ID>/feedback" `
  -Body $body
```

`action` 只能是 `liked`（`adopted` / `dismissed` 已退役，旧客户端提交返回 400）；消息必须属于该用户的会话，否则返回 404。推荐消息 ID 来自消息接口响应的 `assistantMessageId`，或消息历史接口的 `messageId`。

- 顶部“我试过的”打开推荐历史页，提供两种视图供用户自选：**按方法**（同一方法的多次推荐聚合成一张卡片，显示被推荐次数与最近一次时间）和**按时间**（一条推荐一张卡片，还原当时的推荐组合）。切换视图不重新请求，两个视图共用同一份接口数据。每张卡片内嵌尝试后反馈表单：先问试没试，再按选择给出互斥的效果选项，切换“试没试”会清空已选效果，避免留下不自洽的组合。

### 访问码（测试期）

给测试人员发放访问码（直接在数据库插入）：

```powershell
docker exec rag-postgres psql -U learning_app -d learning_app -c "INSERT INTO access_codes (code, label) VALUES ('TEST-8F3K2', '发给张三');"
```

一个访问码对应一个独立的用户空间（访问码即用户标识，首次使用自动创建用户）。收回权限：`UPDATE access_codes SET revoked = TRUE WHERE code = 'TEST-8F3K2';`。

启用条件：`access_codes` 表非空。启用后所有 `/api/v1/users/**` 接口要求请求头 `X-Access-Code`，且访问码必须与路径中的用户标识一致，否则返回 401。发放访问码后如还需使用旧的开发用户（如 `web-user-001`），把它的标识也插入 `access_codes` 即可。表为空时是本地开发模式，请求直接放行。

#### 角色

每个访问码带一个 `role`，默认 `tester`（只能访问自己的用户空间）；审核者发放 `reviewer`：

```powershell
docker exec rag-postgres psql -U learning_app -d learning_app -c "INSERT INTO access_codes (code, label, role) VALUES ('REVIEW-01', '发给审核人', 'reviewer');"
```

角色属于访问码而不属于用户：同一个人可以同时持有 `tester` 与 `reviewer` 两个码，审核身份泄露时单独停用那一个即可，不影响他继续使用产品。取值受 `access_codes_role_check` 约束，插入其它角色会被数据库拒绝。

兑换接口返回角色，前端据此决定是否展示审核入口：

```
POST /api/v1/auth/redeem
{"code": "REVIEW-01"}
-> 200 {"externalId": "REVIEW-01", "role": "reviewer"}
```

码不存在或已 `revoked` 返回 401（`revoked` 的 `reviewer` 码同样被拒，角色不绕过停用）；兑换成功会创建用户并更新 `last_used_at`。

这是 `role` 目前唯一的消费点：**审核类端点尚未实现，所以 `reviewer` 只告知身份、不拦截任何请求**。等审核端点落地时，强制点应放在端点上（校验兑换者的角色），而不是塞进 `AccessCodeFilter`——那个过滤器只判断 `X-Access-Code` 与路径中的用户标识是否一致，与角色无关，混进去会让两件事都变难查。

### 模型调用日志

每次大模型调用（画像抽取 `extract`、画像决策 `decide`、推荐生成 `recommend`）都会写入 `model_call_logs` 表：任务类型、模型版本、输入输出快照、耗时、`status`（`success` / `fallback` 走了本地兜底 / `failed`）、失败原因，以及三个 token 计数（`prompt_tokens` / `completion_tokens` / `total_tokens`）。日志写入失败不影响主流程。用于定位延迟瓶颈、评估推荐质量，以及为后续自研模型积累评测数据：

```powershell
docker exec rag-postgres psql -U learning_app -d learning_app -c "SELECT task_type, status, latency_ms, prompt_tokens, completion_tokens, created_at FROM model_call_logs ORDER BY created_at DESC LIMIT 10;"
```

#### token 用量与延迟归因

三个计数都可空：`failed` 与 `fallback` 行没有真正调用模型，NULL 表示“未知”而不是 0，否则平均用量会被一堆没花钱的调用拉低。`total_tokens` 缺失时不用前两个补算，这样“API 没报”与“API 报了这个数”在库里仍可区分——换成带思维链的模型后，真实 total 会包含 reasoning token，补算值就会与计费值偏离。

拆开存输入与输出是为了定位延迟。2026-09-10 的首批实测数据（同一次会话的三次调用）：

| 任务 | prompt | completion | 耗时 | 生成速率 |
| --- | --- | --- | --- | --- |
| `extract` | 479 | 204 | 6.0 秒 | 33.7 token/秒 |
| `decide` | 809 | 129 | 3.8 秒 | 34.4 token/秒 |
| `recommend` | 3427 | 845 | 23.2 秒 | 36.4 token/秒 |

三者速率一致，而 `decide` 的输入比 `extract` 大 69% 却更快，差别只在输出长度：**耗时几乎全由 completion 决定，数千 token 的输入贡献不到一秒**。这条数据推翻了此前“语料填厚就会稳定超时”的归因——削减知识负载治不了超时，它治的是成本与截断。

第二天（2026-09-11）重跑同一条链路，拿到了比上面更硬的对照：

| 日期 | prompt | completion | 耗时 | 生成速率 |
| --- | --- | --- | --- | --- |
| 09-10 | 3427 | 845 | 23.2 秒 | 36.4 token/秒 |
| 09-11 | 3410 | 893 | 83.9 秒 | 10.6 token/秒 |

输入差 0.5%、输出差 5.7%，而耗时差 3.6 倍——**变量只剩服务端生成速率**，这不再需要从“`decide` 比 `extract` 快”间接推断。同批的 `extract`（207 token / 19.9 秒）与 `decide`（101 token / 10.2 秒）也都是约 10 token/秒，说明是服务端整体变慢而不是单次抖动。速率实测区间因此从 16–36 扩到 **10–36 token/秒**，而 83.9 秒距 `readTimeout` 的 90 秒只剩 6 秒：超时不是理论风险，是已经踩到的线。真正的风险是**输出上限乘以速率波动**（`max_tokens=1400` ÷ 10 token/秒 = 140 秒，远超阈值），而这两个量都不在本项目控制范围内。

#### 写入前脱敏

这张表是全项目最大的隐私暴露面：`extract` 任务的 `input_json.currentUserMessage` 存的是用户逐字说过的原话。脱敏放在写入的唯一入口 `ModelCallLogger`，不靠调用方自觉——只要有人新增一处日志调用，忘了脱敏就是真的泄露。

替换成占位符而不是直接丢弃，因为句子结构对训练有价值，而直接标识符本来就是噪声，脱敏与训练质量是同向的。按顺序匹配（长的先匹，否则 18 位身份证会被当成卡号或长数字串）：

| 类型 | 占位符 |
| --- | --- |
| 18 位身份证（含末位 X） | `[证件号]` |
| 16–19 位银行卡号 | `[卡号]` |
| 11 位手机号 | `[手机号]` |
| 邮箱 | `[邮箱]` |
| 8 位以上连续数字（学号、工号、QQ 号） | `[数字串]` |

学习相关的短数字（每天 60 分钟、一周 3 次、背了 3000 个单词、坚持 21 天）必须完整保留，它们正是画像要抽的字段值，误伤就等于毁掉训练数据。脱敏返回深拷贝，不改入参：调用方记完日志还要继续用原对象做校验与合并，原地修改会把占位符带进画像。`messages.content` 也不受影响，用户自己的消息历史保留原文。

姓名、学校、住址这类没有固定格式的信息正则处理不了，**没有假装已解决**，只能靠下面的保留期兜住。

#### 保留期

```dotenv
LOG_RETENTION_DAYS=365
```

`app.logs.retention-days` 默认 365 天，设为 0 或负数关闭自动清理。启动时清一次，之后按 `app.logs.purge-cron`（默认每天 03:30）定时清。不按 `status` 区分保留期：`fallback` 与 `failed` 是最有价值的难样本，提前删掉等于专挑有用的数据丢。`user_id` 外键是 `ON DELETE SET NULL`，删用户会自动把它的日志匿名化。

### 行为观测（只采集，不喂模型）

`behavior_observations` 存的是由确定性代码从本库已有数据算出的聚合信号，为用户自述提供客观对照：自述“我每天都在学”对上近 7 天实际活跃天数，自述“我试过那个方法”对上真的提交过尝试后反馈的方法数。无意识失真在对话内部往往完全自洽，只有与行为对照才显形。

指标：`user_messages`（全量 / 7 天 / 30 天）、`active_days`（7 / 30 天）、`conversation_span_days`、`recommendations_received`、`recommended_strategies`、`trial_feedback_given`、`methods_tried`，以及两个比率 `trial_follow_through_rate` 与 `helpful_rate`。`window_days = 0` 表示不设窗口；指标名与窗口一起构成主键，重算走 upsert，不堆历史。

两个比率的分子分母量级必须一致：`method_trial_feedback` 有 `UNIQUE (user_id, strategy_id)`，反馈数天然是去重后的**方法数**，所以跟进率的分母用 `recommended_strategies`（被推荐过的不同方法数）而不是推荐**消息**数——一条消息里通常有好几个方法，拿消息数当分母会让“率”超过 1。

重算时机是写入行为的事务提交之后（发完一条消息、提交完一次尝试后反馈），否则读到的是提交前的旧数据，观测值会永远慢一轮。采集失败一律只打印不抛出，任何情况下不能让对话或反馈提交失败。

**隐私边界（2026-09-10 定）**：这张表的内容不进入任何模型输入，也不写入 `model_call_logs`。v1 的模型一只看对话相关内容（当前消息、历史画像、过往询问策略及结果）。采集与使用解耦：现在照采照存，目的只是将来评估“自述失真觉察”到底做不做得起来时手上有对照面，而不是等到那天才开始攒数据。真要接入模型一时，也只给这里已经聚合好的偏差信号，不给原始行为日志。用户对隐私敏感，采集范围必须小于使用范围时才敢长期留着。

页面依赖的会话查询接口：

- `GET /api/v1/users/{externalId}/conversations`：列出该用户的会话（按最近更新排序，最多 100 条），`title` 为该会话首条用户消息的摘要。
- `GET /api/v1/users/{externalId}/conversations/{conversationId}/messages`：读取会话的完整消息（含推荐结果的 metadata）。
