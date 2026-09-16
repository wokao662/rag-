-- Path 2：把 evidence（研究结论 + 可点开 DOI）接成真正的检索 chunk 类型。
-- 此前 evidence[] 是死字段：生成器忽略、导入器不入库、strategies 表也不存。
-- 填厚后每条策略的 evidence[] 带 claim + url(DOI)，需作为独立 chunk 进 strategy_chunks，
-- 才能被 expandByStrategy 拉回、随推荐展示出处。V6 的 CHECK 只允许 4 种 chunk_type，这里放开加 'evidence'。
ALTER TABLE strategy_chunks DROP CONSTRAINT IF EXISTS strategy_chunks_type_check;
ALTER TABLE strategy_chunks ADD CONSTRAINT strategy_chunks_type_check
    CHECK (chunk_type IN ('definition', 'procedure', 'suitable_condition', 'unsuitable_condition', 'evidence'));
