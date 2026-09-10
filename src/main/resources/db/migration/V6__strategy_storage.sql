-- 策略存储层：投稿、审核、渐进投放与反馈升降权的地基。
-- 此前策略只存在于 data/strategies/*.json 与 Qdrant payload，无事务、无审计、
-- 无法承载审核队列与并发投稿，reviewStatus 也只是记录不是闸门。

-- reviewStatus 枚举在此统一。历史 JSON 用 draft、旧文档用 pending/approved/rejected，
-- 两套值零交集；这里取并集并补 archived，导入时把种子策略置为 approved。
CREATE TABLE IF NOT EXISTS strategies (
    strategy_id          VARCHAR(128) PRIMARY KEY,
    name                 VARCHAR(256) NOT NULL,
    aliases              JSONB NOT NULL DEFAULT '[]'::jsonb,
    summary              TEXT,
    steps                JSONB NOT NULL DEFAULT '[]'::jsonb,
    suitable_for         JSONB NOT NULL DEFAULT '[]'::jsonb,
    not_suitable_for     JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_ids           JSONB NOT NULL DEFAULT '[]'::jsonb,

    -- 六个推荐分字段，各自唯一写入者，避免互相覆盖：
    --   evidence_score      导入/审核者（文献证据），反馈消费不碰
    --   effectiveness_score 审核者或模型二，反馈消费不碰
    --   community_score     反馈消费服务（Wilson 下界），唯一写入者
    --   tried_count/helpful_count  反馈消费服务，唯一写入者
    --   overall_score       反馈消费服务合成的排序分，唯一写入者
    -- reviewer_score 单独存放审核者判断：它与合成分的差值是模型二的核心训练标签，
    -- 若共用 overall_score 会被互相覆盖而毁掉标签。
    evidence_score       DOUBLE PRECISION NOT NULL DEFAULT 0,
    community_score      DOUBLE PRECISION NOT NULL DEFAULT 0,
    effectiveness_score  DOUBLE PRECISION NOT NULL DEFAULT 0,
    overall_score        DOUBLE PRECISION NOT NULL DEFAULT 0,
    tried_count          INTEGER NOT NULL DEFAULT 0,
    helpful_count        INTEGER NOT NULL DEFAULT 0,
    reviewer_score       DOUBLE PRECISION,

    review_status        VARCHAR(24) NOT NULL DEFAULT 'draft',
    exposure_state       VARCHAR(24) NOT NULL DEFAULT 'seed',
    exposed_user_count   INTEGER NOT NULL DEFAULT 0,
    exposure_cap         INTEGER NOT NULL DEFAULT 50,
    pending_archive      BOOLEAN NOT NULL DEFAULT FALSE,
    archive_missing      BOOLEAN NOT NULL DEFAULT FALSE,

    submitted_by         UUID REFERENCES users(id) ON DELETE SET NULL,
    reviewed_by          VARCHAR(64),
    reviewed_at          TIMESTAMPTZ,
    review_note          TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT strategies_review_status_check
        CHECK (review_status IN ('draft', 'pending', 'approved', 'rejected', 'archived')),
    CONSTRAINT strategies_exposure_state_check
        CHECK (exposure_state IN ('seed', 'scaling', 'full', 'paused')),
    CONSTRAINT strategies_score_range CHECK (
        evidence_score BETWEEN 0 AND 1
        AND community_score BETWEEN 0 AND 1
        AND effectiveness_score BETWEEN 0 AND 1
        AND overall_score BETWEEN 0 AND 1
    ),
    CONSTRAINT strategies_cap_positive CHECK (exposure_cap > 0)
);

CREATE INDEX IF NOT EXISTS idx_strategies_review_status ON strategies (review_status);
CREATE INDEX IF NOT EXISTS idx_strategies_pending_archive ON strategies (pending_archive)
    WHERE pending_archive;

-- 来源。kind 区分文献与用户投稿：generate_strategy_chunks.py 硬校验 sourceIds 非空，
-- 用户投稿没有权威文献，需要 user_submission 类来源才能入库。
CREATE TABLE IF NOT EXISTS sources (
    source_id   VARCHAR(64) PRIMARY KEY,
    kind        VARCHAR(32) NOT NULL DEFAULT 'literature',
    title       TEXT,
    attribution TEXT,
    url         TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT sources_kind_check CHECK (kind IN ('literature', 'user_submission', 'web', 'other'))
);

-- 策略 chunk 的数据库镜像。chunk_id 由 uuid5(固定命名空间, "{strategyId}:{chunkType}:{index}")
-- 确定性推导并直接作主键：重复导入走 upsert 不产生重复行，也不产生 Qdrant 孤儿点。
CREATE TABLE IF NOT EXISTS strategy_chunks (
    chunk_id       UUID PRIMARY KEY,
    strategy_id    VARCHAR(128) NOT NULL REFERENCES strategies(strategy_id) ON DELETE CASCADE,
    chunk_type     VARCHAR(32) NOT NULL,
    step_number    INTEGER,
    total_steps    INTEGER,
    condition_label TEXT,
    text           TEXT NOT NULL,
    source_ids     JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT strategy_chunks_type_check
        CHECK (chunk_type IN ('definition', 'procedure', 'suitable_condition', 'unsuitable_condition'))
);

CREATE INDEX IF NOT EXISTS idx_strategy_chunks_strategy ON strategy_chunks (strategy_id);

-- 曝光记录：同一用户对同一策略只计一次曝光，exposed_user_count 由它去重得出。
-- 渐进投放的"人数上限"依赖这张表，没有去重就会被重复推荐刷爆上限。
CREATE TABLE IF NOT EXISTS strategy_exposures (
    strategy_id      VARCHAR(128) NOT NULL REFERENCES strategies(strategy_id) ON DELETE CASCADE,
    user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    first_exposed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (strategy_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_strategy_exposures_user ON strategy_exposures (user_id);
