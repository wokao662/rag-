CREATE TABLE IF NOT EXISTS recommendation_feedback (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    strategy_id VARCHAR(128) NOT NULL,
    action VARCHAR(20) NOT NULL CHECK (action IN ('adopted', 'dismissed')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (message_id, strategy_id)
);

CREATE INDEX IF NOT EXISTS idx_recommendation_feedback_user
    ON recommendation_feedback (user_id, created_at DESC);

DROP TRIGGER IF EXISTS recommendation_feedback_set_updated_at ON recommendation_feedback;
CREATE TRIGGER recommendation_feedback_set_updated_at
BEFORE UPDATE ON recommendation_feedback
FOR EACH ROW EXECUTE FUNCTION set_updated_at();
