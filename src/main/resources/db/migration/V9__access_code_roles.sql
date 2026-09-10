-- 审核者角色。
--
-- strategies 表从 V6 起就有 reviewed_by / reviewed_at / review_note / reviewer_score 四列，
-- 但至今没有任何代码写入它们：审核的存储层早就就绪，缺的是“谁有权审”。这一列补的是身份，
-- 它是人工审核 13 个策略与开启 GOVERNANCE_REVIEW_GATE 的前置条件，也是 reviewer_score
-- （模型二的核心训练标签）能否开始积累的前置条件。
--
-- 不另建管理员凭证体系：访问码的发放流程已经在用，加一列就能复用，两人团队不值得维护两套。
-- 默认 tester 让现存访问码全部保持原有权限，不会因为迁移而突然失去访问。
ALTER TABLE access_codes ADD COLUMN IF NOT EXISTS role VARCHAR(16) NOT NULL DEFAULT 'tester';

-- 约束单独加而不在 ADD COLUMN 里内联：内联的 CHECK 在列已存在时（迁移重跑）不会被补上，
-- 且约束名由 PostgreSQL 自动生成。先 DROP IF EXISTS 再 ADD，既幂等又让约束名固定可引用。
ALTER TABLE access_codes DROP CONSTRAINT IF EXISTS access_codes_role_check;
ALTER TABLE access_codes ADD CONSTRAINT access_codes_role_check CHECK (role IN ('tester', 'reviewer'));

COMMENT ON COLUMN access_codes.role IS
    'tester 是默认角色，只能访问自己的用户空间；reviewer 额外具备审核策略的资格。'
    '角色属于访问码而不属于用户：同一个人可以持有测试与审核两个码，互不干扰，'
    '这样审核身份被泄露时可以单独停用而不影响他继续使用产品。';
