#!/usr/bin/env python3
"""Generate index-ready JSONL chunks from structured learning strategies."""

from __future__ import annotations

import argparse
import json
import sys
import uuid
from pathlib import Path
from typing import Any, Iterable


CHUNK_NAMESPACE = uuid.UUID("b88ce934-624f-4a8f-8e56-33bea21af3b3")


class StrategyError(ValueError):
    """Raised when a strategy file does not match the expected structure."""


def require_string(data: dict[str, Any], field: str, file: Path) -> str:
    value = data.get(field)
    if not isinstance(value, str) or not value.strip():
        raise StrategyError(f"{file.name}: {field} 必须是非空字符串")
    return value.strip()


def string_list(value: Any, field: str, file: Path) -> list[str]:
    if value is None:
        return []
    if not isinstance(value, list) or not all(isinstance(item, str) and item.strip() for item in value):
        raise StrategyError(f"{file.name}: {field} 必须是字符串数组")
    return [item.strip() for item in value]


def stable_chunk_id(strategy_id: str, chunk_type: str, index: int) -> str:
    return str(uuid.uuid5(CHUNK_NAMESPACE, f"{strategy_id}:{chunk_type}:{index}"))


def resolve_sources(item: dict[str, Any], strategy_sources: list[str]) -> list[str]:
    value = item.get("sourceIds", strategy_sources)
    if not isinstance(value, list):
        return strategy_sources
    return [source for source in value if isinstance(source, str) and source.strip()]


def base_metadata(
    strategy: dict[str, Any], strategy_id: str, name: str, chunk_type: str, source_ids: list[str]
) -> dict[str, Any]:
    recommendation = strategy.get("recommendation")
    recommendation_snapshot = recommendation if isinstance(recommendation, dict) else {}
    return {
        "strategyId": strategy_id,
        "strategyName": name,
        "chunkType": chunk_type,
        "sourceIds": source_ids,
        "reviewStatus": strategy.get("reviewStatus", "draft"),
        "recommendationSnapshot": recommendation_snapshot,
    }


def make_chunk(
    strategy: dict[str, Any],
    strategy_id: str,
    name: str,
    chunk_type: str,
    index: int,
    text: str,
    source_ids: list[str],
    extra_metadata: dict[str, Any] | None = None,
) -> dict[str, Any]:
    metadata = base_metadata(strategy, strategy_id, name, chunk_type, source_ids)
    if extra_metadata:
        metadata.update(extra_metadata)
    return {
        "chunkId": stable_chunk_id(strategy_id, chunk_type, index),
        "text": text.strip(),
        "metadata": metadata,
    }


def condition_chunks(
    strategy: dict[str, Any],
    strategy_id: str,
    name: str,
    field: str,
    chunk_type: str,
    sentence_prefix: str,
    strategy_sources: list[str],
) -> Iterable[dict[str, Any]]:
    conditions = strategy.get(field, [])
    if not isinstance(conditions, list):
        raise StrategyError(f"{strategy_id}: {field} 必须是数组")

    for index, item in enumerate(conditions, start=1):
        if not isinstance(item, dict):
            raise StrategyError(f"{strategy_id}: {field}[{index}] 必须是对象")
        label = item.get("label")
        reason = item.get("reason")
        if not isinstance(label, str) or not label.strip():
            raise StrategyError(f"{strategy_id}: {field}[{index}].label 必须是非空字符串")
        if not isinstance(reason, str) or not reason.strip():
            raise StrategyError(f"{strategy_id}: {field}[{index}].reason 必须是非空字符串")
        text = f"{name}{sentence_prefix}{label.strip()}，原因是：{reason.strip()}"
        yield make_chunk(
            strategy,
            strategy_id,
            name,
            chunk_type,
            index,
            text,
            resolve_sources(item, strategy_sources),
            {"conditionLabel": label.strip()},
        )


def generate_chunks(strategy: dict[str, Any], file: Path) -> list[dict[str, Any]]:
    strategy_id = require_string(strategy, "strategyId", file)
    name = require_string(strategy, "name", file)
    summary = require_string(strategy, "summary", file)
    steps = string_list(strategy.get("steps"), "steps", file)
    source_ids = string_list(strategy.get("sourceIds"), "sourceIds", file)
    if not source_ids:
        raise StrategyError(f"{file.name}: sourceIds 不能为空")

    chunks = [
        make_chunk(
            strategy,
            strategy_id,
            name,
            "definition",
            1,
            f"{name}：{summary}",
            source_ids,
        )
    ]

    for index, step in enumerate(steps, start=1):
        chunks.append(
            make_chunk(
                strategy,
                strategy_id,
                name,
                "procedure",
                index,
                f"{name}的第 {index} 步：{step}。",
                source_ids,
                {"stepNumber": index, "totalSteps": len(steps)},
            )
        )

    chunks.extend(condition_chunks(
        strategy, strategy_id, name, "suitableFor", "suitable_condition", "适合", source_ids
    ))
    chunks.extend(condition_chunks(
        strategy, strategy_id, name, "notSuitableFor", "unsuitable_condition", "不适合", source_ids
    ))
    return chunks


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="从学习策略 JSON 自动生成最终检索 Chunk。")
    parser.add_argument("--input-dir", type=Path, default=Path("data/strategies"))
    parser.add_argument("--output-dir", type=Path, default=Path("data/chunks"))
    parser.add_argument("--report", type=Path, help="生成报告路径，默认位于输出目录")
    parser.add_argument("--strict", action="store_true", help="存在无效文件时返回失败状态")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    report_path = args.report or args.output_dir / "generation-report.json"
    args.output_dir.mkdir(parents=True, exist_ok=True)

    generated: list[dict[str, Any]] = []
    errors: list[dict[str, str]] = []
    seen_ids: dict[str, str] = {}

    for file in sorted(args.input_dir.glob("*.json")):
        try:
            strategy = json.loads(file.read_text(encoding="utf-8"))
            if not isinstance(strategy, dict):
                raise StrategyError(f"{file.name}: 顶层必须是 JSON 对象")
            strategy_id = require_string(strategy, "strategyId", file)
            if strategy_id in seen_ids:
                raise StrategyError(f"{file.name}: strategyId 与 {seen_ids[strategy_id]} 重复")
            chunks = generate_chunks(strategy, file)
            output = args.output_dir / f"{strategy_id}.jsonl"
            output.write_text(
                "\n".join(json.dumps(chunk, ensure_ascii=False) for chunk in chunks) + "\n",
                encoding="utf-8",
            )
            seen_ids[strategy_id] = file.name
            generated.append({"file": file.name, "output": output.name, "chunks": len(chunks)})
            print(f"已生成 {len(chunks):2d} 个 Chunk: {file.name} -> {output.name}")
        except (OSError, json.JSONDecodeError, StrategyError) as error:
            errors.append({"file": file.name, "error": str(error)})
            print(f"跳过无效文件: {file.name}: {error}", file=sys.stderr)

    suspicious = [file.name for file in args.input_dir.iterdir() if file.is_file() and file.suffix != ".json"]
    for name in suspicious:
        errors.append({"file": name, "error": "扩展名不是 .json，未处理"})
        print(f"跳过非 JSON 文件: {name}", file=sys.stderr)

    report = {
        "generatedStrategies": len(generated),
        "generatedChunks": sum(item["chunks"] for item in generated),
        "generated": generated,
        "errors": errors,
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"生成完成: {report['generatedStrategies']} 个策略，{report['generatedChunks']} 个 Chunk")
    print(f"报告: {report_path}")

    if args.strict and errors:
        sys.exit(1)


if __name__ == "__main__":
    main()
