-- 情境化画像（episode）切片 1：给会话打上"目标情境"归属。
--
-- 背景：user_profiles 是一人一份 profile_json，所有对话共用同一份画像，
-- 导致用户换个学习目标（上一场聊数学、下一场聊英语）仍被困在同一份画像里。
-- 目标形态：画像 = 共享层(shared) + 若干目标情境(episodes)，会话绑定到某个情境。
--
-- 本迁移是零风险的第一步：只给 conversations 增加一个可空的 episode_id 列，
-- 不改动任何现有行。profile_json 的结构升级（shared/episodes）与向后兼容读
-- 全部放在应用层处理，避免在 SQL 里批量重写已有 9 份真实画像 JSON。
--
-- episode_id 语义：指向该用户 profile_json.episodes[].id（形如 'ep_xxxxxxxx'）。
-- 它不是外键——episodes 存在 JSON blob 内部，没有独立表。
-- 允许为空：历史会话（本迁移之前创建的）episode_id 为 NULL，
-- 应用层读到 NULL 时按"默认情境"处理，保证老数据不崩。

ALTER TABLE conversations
    ADD COLUMN IF NOT EXISTS episode_id VARCHAR(64);

-- 按用户 + 情境查会话（例如"这个情境下都有哪些聊天"），以及活跃会话归属判断。
CREATE INDEX IF NOT EXISTS idx_conversations_user_episode
    ON conversations (user_id, episode_id);

COMMENT ON COLUMN conversations.episode_id IS
    '目标情境 id，指向 user_profiles.profile_json.episodes[].id；NULL=历史会话按默认情境处理';
