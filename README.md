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

先启动 Qdrant，并在当前 VS Code PowerShell 会话中设置硅基流动 API Key：

```powershell
docker start qdrant
$env:SILICONFLOW_API_KEY="你的硅基流动 API Key"
```

生成最终 chunk 后执行入库：

```powershell
python .\generate_strategy_chunks.py --strict
mvn compile exec:java "-Dexec.mainClass=StrategyIndexer"
```

程序会创建 `learning_strategies` Collection，并把 `data/chunks/*.jsonl` 全部写入。
`chunkId` 是稳定 UUID，因此重复运行会更新已有数据，不会生成重复记录。

执行一次检索：

```powershell
mvn compile exec:java "-Dexec.mainClass=StrategySearcher" "-Dexec.args=我总是考试前突击，学完很快忘记，应该怎么办"
```

测试完整的 RAG 推荐链路（检索后再调用聊天模型，返回 JSON）：

```powershell
mvn compile exec:java "-Dexec.mainClass=RagRecommendationTest" "-Dexec.args=我背单词很快忘，而且每天只能学习30分钟"
```

这是命令行测试版：单轮调用、不保存用户画像和历史对话。Prompt 已包含知识来源限定、稳定 chunkId 引用、信息不足澄清、无匹配兜底和基础注入防护。

Qdrant 默认地址是 `http://127.0.0.1:6333`。如果以后连接其他 Qdrant，可设置：

```powershell
$env:QDRANT_URL="http://服务器地址:6333"
```
