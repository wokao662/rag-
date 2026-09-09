CREATE TABLE IF NOT EXISTS model_call_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    conversation_id UUID REFERENCES conversations(id) ON DELETE SET NULL,
    task_type VARCHAR(32) NOT NULL CHECK (task_type IN ('extract', 'decide', 'recommend')),
    model VARCHAR(128) NOT NULL,
    input_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    output_json JSONB,
    latency_ms INTEGER CHECK (latency_ms >= 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('success', 'fallback', 'failed')),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_model_call_logs_created
    ON model_call_logs (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_model_call_logs_user
    ON model_call_logs (user_id, created_at DESC);
