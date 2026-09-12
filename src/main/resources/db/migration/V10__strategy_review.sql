-- 人工审核落库所需的三处结构调整。
--
-- V6 建表时就留了 reviewed_by / reviewed_at / review_note / reviewer_score 四列，V9 补了
-- access_codes.role，但到这里为止没有任何代码写入过它们：13 个策略全停在 draft，
-- GOVERNANCE_REVIEW_GATE 只能关着（开了会把推荐过滤成全空），reviewer_score 一条都没攒到，
-- 而它与合成分的差值正是"适合人群预测"模型的核心训练标签。审核端点要落库，先得把这三处补齐。

-- reviewed_by 记的是审核者的人名（access_codes.label），不是访问码本身：访问码是凭据，
-- 把它抄进第二张表就等于让凭据出现在审核界面和接口响应里，泄露面凭空多一处。
-- label 是 VARCHAR(128)，而这一列建表时是 VARCHAR(64)（与 code 同宽，看着像给码留的），
-- 不加宽会在长 label 上抛 "value too long"——而那是一次已经做出、只差落库的审核决定。
-- 加宽 varchar 不触发表重写。
ALTER TABLE strategies ALTER COLUMN reviewed_by TYPE VARCHAR(128);

COMMENT ON COLUMN strategies.reviewed_by IS
    '审核者人名，取自 access_codes.label。刻意不存访问码：码是凭据，'
    '而这一列要在审核界面与接口响应里展示。';

-- reviewer_score 是审核者对策略质量的独立判断，与 overall_score 分开存放：二者的差值才是
-- 模型二的训练标签，共用一列会被反馈消费服务的重算覆盖掉，标签当场就毁。
-- V6 的 strategies_score_range 只约束了四个非空分列，这一列可空所以漏在约束外面，
-- 一个 7.5 分的"审核者判断"能被写进来并永久污染训练集。
ALTER TABLE strategies DROP CONSTRAINT IF EXISTS strategies_reviewer_score_range;
ALTER TABLE strategies ADD CONSTRAINT strategies_reviewer_score_range
    CHECK (reviewer_score IS NULL OR reviewer_score BETWEEN 0 AND 1);

-- 审核者码必须有人名。reviewed_by 要写 label，而 label 在 V4 里是可空的：空 label 的
-- reviewer 码会让"谁审的"落成 NULL，审计链断在这一行，而事后无法从库里还原当时是谁签的字。
-- 用数据库约束而不是应用层判空来兜：约束挡得住所有写入路径，包括将来手工发码的人
-- （也就是我们自己），应用层只挡走接口的那一条。
ALTER TABLE access_codes DROP CONSTRAINT IF EXISTS access_codes_reviewer_label_check;
ALTER TABLE access_codes ADD CONSTRAINT access_codes_reviewer_label_check
    CHECK (role <> 'reviewer' OR label IS NOT NULL);

COMMENT ON COLUMN strategies.reviewer_score IS
    '审核者独立打分，0 到 1，NULL 表示尚未审核。驳回时可为空，通过时必填：'
    '通过与合成分的差值是模型二的训练标签，缺一条就少一条，且事后补不回来。';
