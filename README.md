# rag-
尝试用codex生成rag流程

## 文本分块 CLI（Python）
新增了一个可直接在终端使用的文本分块脚本：`chunk_text_cli.py`。

### 运行方式
```bash
python3.14 chunk_text_cli.py
```
交互模式下输入文本，输入 `END` 并回车结束，然后会输出切割后的文本块。

### 也支持命令行参数输入
```bash
python3.14 chunk_text_cli.py --text "这里是一段用于RAG测试的文本。它会被切割成多个块。" --chunk-size 20 --overlap 5
```

### 也支持管道输入
```bash
echo "第一句。第二句。第三句。" | python3.14 chunk_text_cli.py --chunk-size 10 --overlap 2
```
