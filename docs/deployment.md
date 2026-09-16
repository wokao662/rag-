# 部署与重新上线手册

本文覆盖本项目的上线操作：代码合并后的重建部署、闸门开关核对、公网隧道恢复、冒烟验收与常见排障。

## 部署形态

- 三个容器（`compose.yaml`）：`rag-postgres`（PostgreSQL 17，含健康检查）、`qdrant`、`rag-app`（Spring Boot）。app 只绑定 `127.0.0.1:8080`，不直接对公网开放。
- 公网入口走 Cloudflare Tunnel：Windows 服务 `Cloudflared`，配置在 `%USERPROFILE%\.cloudflared\config.yml`，入口域名 `rag.xueyoufang.cn` → `http://127.0.0.1:8080`。免费隧道有 100 秒硬上限，推荐链路的快速失败（`readTimeout` 60 秒）就是按它定的。
- 数据都在 Docker 卷里（`postgres_data`、`qdrant_storage`），重建 app 不影响数据；Flyway 迁移在 app 启动时自动执行。

## 重新上线流程

1. **代码就绪**：确认目标分支已合并到 main，`git pull` 拉取最新代码。

2. **核对闸门开关（公网必须开）**：
   - `.env` 中设置 `GOVERNANCE_REVIEW_GATE=true`；
   - `compose.yaml` 的 `app.environment` 需含透传行（本仓库自 2026-09-16 起已内置）：
     `GOVERNANCE_REVIEW_GATE: ${GOVERNANCE_REVIEW_GATE:-false}`；
   - 少了透传行时 `.env` 的值不会进入容器，开关会静默保持 `false`（这正是修复前的缺口）。

3. **重建并启动**：
   ```powershell
   docker compose up -d --build
   docker ps    # 三个容器 Up，rag-postgres 显示 healthy
   ```

4. **启动公网隧道**（需管理员权限的终端）：
   ```powershell
   Start-Service Cloudflared
   Get-Service Cloudflared    # 确认 Running
   ```

5. **执行冒烟验收**（下节清单）。

## 冒烟验收清单

1. **入口**：浏览器打开 `https://rag.xueyoufang.cn`，用访问码兑换进入。访问码按需发放（写入即生效）：
   ```sql
   INSERT INTO access_codes (code, label, role) VALUES ('TEST-XXXX', '测试者A', 'tester');
   ```
   `role` 取 `tester` / `reviewer`（reviewer 额外解锁审核入口）。
2. **对话流**：输入一条真实的学习困惑 → 完成画像对话 → 触发推荐；推荐卡片应出现"研究依据"区块，DOI 链接可点开。
3. **服务端抽查**：
   ```powershell
   docker exec rag-postgres psql -U learning_app -d learning_app -c "SELECT created_at, status, error_message FROM model_call_logs WHERE task_type='recommend' ORDER BY created_at DESC LIMIT 10;"
   ```
   - `failed` 行应带明确 `error_message`；若含 `finish_reason=length`（截断诊断），考虑上调 `max_tokens`（当前 1600）；
   - 推荐正常时 `input_json` 的 `knowledgeDropped` 应为 0。
4. **闸门生效**：推荐结果中出现的策略应全部为已审核通过（11 条 approved；2 条背景知识已 rejected + paused，不应出现）。

## 常见排障

- **推荐 502 / "推荐生成服务暂时不可用"**：看 `model_call_logs` 最近 failed 行。服务端生成速率会抖动（实测 10–36 token/秒），慢到击穿 `readTimeout`（60 秒）时按设计快速失败走兜底——稍后重试即可，不是卡死。
- **隧道不通**：`Get-Service Cloudflared` 是否 Running；配置与凭据文件在 `%USERPROFILE%\.cloudflared\`。
- **端口被占**：app 固定绑 `127.0.0.1:8080`，本地 CLI 测试与容器互斥时先停容器或复用它。
- **刚改了知识语料（`data/chunks` 或 `data/strategies`）**：按顺序重跑 `StrategyImporter`（DB 镜像）与 `StrategyIndexer`（Qdrant），否则推荐仍用旧 chunk。

## 停服与回滚

- 只停公网入口：`Stop-Service Cloudflared`（容器继续运行）。
- 关闭闸门：`.env` 里改回 `GOVERNANCE_REVIEW_GATE=false` 后 `docker compose up -d app`。
- 全部停止：`docker compose down`（数据卷保留，不清库）。
