-- 给 model_call_logs 补上 token 用量。
--
-- 这三列是诊断延迟的唯一依据。此前只知道一次调用花了 89 秒，却无法判断是输入太大还是输出太长，
-- 而两者的处理办法完全相反：输入大要裁知识负载，输出大要压 max_tokens。
--
-- 三列都可空。failed 与 fallback 的行没有真正调用模型，NULL 表示“未知”，与“消耗 0 token”
-- 不是一回事；把未知记成 0 会拉低平均用量，让真实单价看不出来。CHECK (>= 0) 在 SQL 的
-- 三值逻辑下对 NULL 求值为 unknown，不会挡住这些行。
ALTER TABLE model_call_logs ADD COLUMN IF NOT EXISTS prompt_tokens INTEGER CHECK (prompt_tokens >= 0);
ALTER TABLE model_call_logs ADD COLUMN IF NOT EXISTS completion_tokens INTEGER CHECK (completion_tokens >= 0);
ALTER TABLE model_call_logs ADD COLUMN IF NOT EXISTS total_tokens INTEGER CHECK (total_tokens >= 0);

-- 用量分析必然按任务类型分组：三类调用的 max_tokens 分别是 1000 / 800 / 1400，延迟量级也差一个
-- 数量级，混在一起的平均值没有意义。日志按每轮对话最多三行的速度增长并保留一年，没有这个索引
-- 就得全表扫。已有的两个索引都以 created_at 或 user_id 打头，覆盖不了 task_type 上的分组。
CREATE INDEX IF NOT EXISTS idx_model_call_logs_task_created
    ON model_call_logs (task_type, created_at DESC);

COMMENT ON COLUMN model_call_logs.prompt_tokens IS
    '输入 token 数。NULL 表示未知（failed / fallback 行没有调用模型），不是 0。';

COMMENT ON COLUMN model_call_logs.completion_tokens IS
    '输出 token 数。延迟主要由它决定：三类调用实测生成速率一致（约 17-23 token/s），'
    '所以耗时差异来自输出长度，而不是输入长度。';

COMMENT ON COLUMN model_call_logs.total_tokens IS
    'API 报告的总 token 数。缺失时不用 prompt 加 completion 补算，'
    '否则“API 没报”与“API 报了这个数”在库里无法区分，换带思维链的模型后就会与计费值偏离。';
