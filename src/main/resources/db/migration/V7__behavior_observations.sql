-- 行为观测：由确定性代码从本库已有数据算出的聚合信号，为用户自述提供客观对照。
--
-- 采集与使用解耦（2026-09-10 隐私决定）：这张表的内容不进入任何模型输入，也不写入
-- model_call_logs。现在采集只是为了将来能评估"失真觉察"到底有没有用；真要接入模型一时，
-- 也只给这里已经聚合好的偏差信号（例如"自述每天 60 分钟 vs 近 7 天实际活跃 1 天"），
-- 不给原始行为日志。用户对隐私敏感，采集范围必须小于使用范围时才敢长期留着。
--
-- window_days = 0 表示不设窗口（全量历史）。指标名与窗口一起构成主键，
-- 重算走 upsert，不会产生历史堆积。
CREATE TABLE IF NOT EXISTS behavior_observations (
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    metric       VARCHAR(48) NOT NULL,
    window_days  INTEGER NOT NULL CHECK (window_days >= 0),
    metric_value DOUBLE PRECISION NOT NULL,
    computed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, metric, window_days)
);

CREATE INDEX IF NOT EXISTS idx_behavior_observations_user
    ON behavior_observations (user_id);

COMMENT ON TABLE behavior_observations IS
    '行为观测聚合值。仅供离线分析与将来评估失真觉察使用，禁止直接进入模型输入或 model_call_logs。';

COMMENT ON COLUMN behavior_observations.metric IS
    'user_messages / active_days / conversation_span_days / recommendations_received / '
    'recommended_strategies / trial_feedback_given / methods_tried / '
    'trial_follow_through_rate / helpful_rate。'
    '两个 _rate 的分子分母量级必须一致（都数方法而非消息），否则率会超过 1。';
