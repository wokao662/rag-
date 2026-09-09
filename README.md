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

### Web 聊天界面（开发版）

启动后端后直接在浏览器打开：

```
http://127.0.0.1:8080/
```

页面由 Spring Boot 直接托管（`src/main/resources/static`），无需额外构建。功能：

- 首次打开自动生成本地用户标识，无需手动输入；接入正式认证后无缝替换。
- 未开始对话时显示欢迎页和示例问题，点击示例即可直接发送。
- 聊天窗口发送消息后，画像不足时显示助手的追问（带“正在输入”状态），画像充足时自动展示推荐卡片（策略名称、具体步骤、推荐理由、注意事项和来源）。
- 右上角“我的画像”抽屉展示当前画像，每个字段附用户原话引用（画像只能由系统根据对话更新，不提供手动编辑）。
- 左上角可展开历史会话抽屉，会话以首条消息摘要为标题，点击可回看完整消息记录。
- 推荐卡片中的每个策略带“采纳 / 不感兴趣”反馈按钮，点击后写入 `recommendation_feedback` 表，同一推荐同一策略只保留最新一次反馈。反馈接口：

```powershell
$body = @{ strategyId = "strategy-keyword-mnemonic"; action = "adopted" } | ConvertTo-Json
Invoke-RestMethod `
  -Method Post `
  -ContentType "application/json; charset=utf-8" `
  -Uri "http://127.0.0.1:8080/api/v1/users/web-user-001/messages/<推荐消息ID>/feedback" `
  -Body $body
```

`action` 只能是 `adopted` 或 `dismissed`；消息必须属于该用户的会话，否则返回 404。推荐消息 ID 来自消息接口响应的 `assistantMessageId`，或消息历史接口的 `messageId`。

### 模型调用日志

每次大模型调用（画像抽取 `extract`、画像决策 `decide`、推荐生成 `recommend`）都会写入 `model_call_logs` 表：任务类型、模型版本、输入输出快照、耗时、`status`（`success` / `fallback` 走了本地兜底 / `failed`）和失败原因。日志写入失败不影响主流程。用于定位延迟瓶颈、评估推荐质量，以及为后续自研模型积累评测数据：

```powershell
docker exec rag-postgres psql -U learning_app -d learning_app -c "SELECT task_type, status, latency_ms, created_at FROM model_call_logs ORDER BY created_at DESC LIMIT 10;"
```

页面依赖的会话查询接口：

- `GET /api/v1/users/{externalId}/conversations`：列出该用户的会话（按最近更新排序，最多 100 条），`title` 为该会话首条用户消息的摘要。
- `GET /api/v1/users/{externalId}/conversations/{conversationId}/messages`：读取会话的完整消息（含推荐结果的 metadata）。
