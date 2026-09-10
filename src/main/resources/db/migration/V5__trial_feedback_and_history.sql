-- 卡片上的反馈简化为单向点赞：👍 只用于向投稿者展示鼓励数，不参与推荐度计算。
-- 原先的 adopted / dismissed 双向语义已取消。原因是「看完就点」测的是愿不愿意试，
-- 不是试了有没有用；而负面信号在卡片上随手一点，会让好方法仅因为被推给了不合适的人
-- 而挨踩降权。负面判断改由下面的 method_trial_feedback 承载：用户在历史页主动填写，
-- 有上下文，质量远高于随手点踩。
UPDATE recommendation_feedback SET action = 'liked' WHERE action = 'adopted';
DELETE FROM recommendation_feedback WHERE action = 'dismissed';

ALTER TABLE recommendation_feedback DROP CONSTRAINT IF EXISTS recommendation_feedback_action_check;
ALTER TABLE recommendation_feedback ADD CONSTRAINT recommendation_feedback_action_check
    CHECK (action = 'liked');

-- 按策略统计点赞数时需要这个索引：投稿者页面要显示「你的方法已被 N 人点赞」。
CREATE INDEX IF NOT EXISTS idx_recommendation_feedback_strategy
    ON recommendation_feedback (strategy_id);

-- 尝试后反馈：communityScore / triedCount / helpfulCount 的唯一写入来源，
-- 也是渐进投放升降权的唯一依据。一个用户对一个方法只有一条记录，可修改。
CREATE TABLE IF NOT EXISTS method_trial_feedback (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    strategy_id VARCHAR(128) NOT NULL,
    tried BOOLEAN NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    note TEXT,
    source_message_id UUID REFERENCES messages(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT method_trial_feedback_outcome_check CHECK (outcome IN
        ('helpful', 'partial', 'not_helpful', 'not_suitable', 'no_time')),
    -- tried 与 outcome 必须自洽：没试过的人不能评价有没有用。
    -- not_suitable 是「方法本身没问题但不适合我的情况」，属于适合人群预测模型的负样本，
    -- 不参与降权；no_time 是纯粹的未曝光，两者都不应计入 triedCount。
    CONSTRAINT method_trial_feedback_outcome_matches_tried CHECK (
        (tried AND outcome IN ('helpful', 'partial', 'not_helpful'))
        OR (NOT tried AND outcome IN ('not_suitable', 'no_time'))
    ),
    CONSTRAINT method_trial_feedback_note_length
        CHECK (note IS NULL OR length(trim(note)) <= 2000),
    UNIQUE (user_id, strategy_id)
);

-- 按策略聚合 triedCount / helpfulCount 时的主查询路径。
CREATE INDEX IF NOT EXISTS idx_method_trial_feedback_strategy
    ON method_trial_feedback (strategy_id, created_at DESC);
