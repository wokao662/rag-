#!/usr/bin/env python3
"""Chinese-friendly recursive text splitter for a RAG pipeline."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import List, Sequence


DEFAULT_SEPARATORS = ("\n\n", "\n", "。！？；.!?;", "，,、：:", " ", "")


def normalize_text(text: str) -> str:
    """Normalize line endings and redundant horizontal whitespace."""
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"[\t\f\v ]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def split_keep_separator(text: str, separator: str) -> List[str]:
    """Split text while retaining separators at the end of each piece."""
    if separator == "":
        return list(text)
    if separator in {"\n\n", "\n", " "}:
        raw_parts = text.split(separator)
        return [
            part + (separator if index < len(raw_parts) - 1 else "")
            for index, part in enumerate(raw_parts)
            if part
        ]
    pattern = rf"(?<=[{re.escape(separator)}])"
    return [part for part in re.split(pattern, text) if part]


def recursive_split(
    text: str,
    chunk_size: int,
    separators: Sequence[str] = DEFAULT_SEPARATORS,
) -> List[str]:
    """Recursively split text until every atomic piece fits chunk_size."""
    text = text.strip()
    if not text:
        return []
    if len(text) <= chunk_size:
        return [text]
    if not separators:
        return [text[index : index + chunk_size] for index in range(0, len(text), chunk_size)]

    separator = separators[0]
    if separator and not any(char in text for char in separator):
        return recursive_split(text, chunk_size, separators[1:])

    pieces = split_keep_separator(text, separator)
    if len(pieces) == 1:
        return recursive_split(text, chunk_size, separators[1:])

    result: List[str] = []
    for piece in pieces:
        piece = piece.strip()
        if not piece:
            continue
        if len(piece) <= chunk_size:
            result.append(piece)
        else:
            result.extend(recursive_split(piece, chunk_size, separators[1:]))
    return result


def merge_pieces(pieces: Sequence[str], chunk_size: int, overlap: int) -> List[str]:
    """Pack pieces and overlap only complete semantic pieces when possible."""
    chunks: List[str] = []
    current: List[str] = []

    for piece in pieces:
        candidate = " ".join([*current, piece]).strip()
        if current and len(candidate) > chunk_size:
            chunks.append(" ".join(current).strip())
            overlap_pieces: List[str] = []
            for previous in reversed(current):
                overlap_candidate = " ".join([previous, *overlap_pieces])
                if len(overlap_candidate) > overlap:
                    break
                overlap_pieces.insert(0, previous)
            current = overlap_pieces

        candidate = " ".join([*current, piece]).strip()
        if current and len(candidate) > chunk_size:
            current = []
        current.append(piece)

    if current:
        chunks.append(" ".join(current).strip())
    return chunks


def chunk_text(text: str, chunk_size: int, overlap: int) -> List[str]:
    """Recursively split and merge text into semantic chunks."""
    if chunk_size <= 0:
        raise ValueError("chunk_size 必须大于 0")
    if overlap < 0:
        raise ValueError("overlap 不能小于 0")
    if overlap >= chunk_size:
        raise ValueError("overlap 必须小于 chunk_size")
    pieces = recursive_split(normalize_text(text), chunk_size)
    return merge_pieces(pieces, chunk_size, overlap)


def read_interactive_text() -> str:
    print("请输入要切割的文本，输入 END 后回车结束：")
    lines: List[str] = []
    while True:
        try:
            line = input()
        except EOFError:
            break
        if line.strip().upper() == "END":
            break
        lines.append(line)
    return "\n".join(lines).strip()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="递归切割中文资料，为 RAG 入库做准备。")
    input_group = parser.add_mutually_exclusive_group()
    input_group.add_argument("--text", help="直接传入文本")
    input_group.add_argument("--file", type=Path, help="读取 UTF-8 文本文件")
    parser.add_argument("--chunk-size", type=int, default=300, help="每块最大字符数（默认: 300）")
    parser.add_argument("--overlap", type=int, default=0, help="只重叠完整语义单元（默认: 0）")
    parser.add_argument("--format", choices=("text", "jsonl"), default="text")
    parser.add_argument("--source-id", help="JSONL 中记录的资料来源 ID")
    parser.add_argument("--output", type=Path, help="输出到文件；不传则打印到终端")
    return parser.parse_args()


def load_text(args: argparse.Namespace) -> tuple[str, str]:
    if args.text is not None:
        return args.text, args.source_id or "command-line"
    if args.file is not None:
        return args.file.read_text(encoding="utf-8"), args.source_id or args.file.name
    if not sys.stdin.isatty():
        return sys.stdin.read(), args.source_id or "stdin"
    return read_interactive_text(), args.source_id or "interactive"


def render_chunks(chunks: Sequence[str], output_format: str, source_id: str) -> str:
    if output_format == "jsonl":
        records = []
        for index, chunk in enumerate(chunks, start=1):
            digest = hashlib.sha256(f"{source_id}:{index}:{chunk}".encode("utf-8")).hexdigest()[:16]
            records.append(json.dumps({
                "chunkId": f"chunk-{digest}",
                "text": chunk,
                "metadata": {
                    "sourceId": source_id,
                    "chunkIndex": index,
                    "charCount": len(chunk),
                },
            }, ensure_ascii=False))
        return "\n".join(records)

    sections = [f"共切割出 {len(chunks)} 个文本块："]
    for index, chunk in enumerate(chunks, start=1):
        sections.append(f"--- Chunk {index} (len={len(chunk)}) ---\n{chunk}")
    return "\n\n".join(sections)


def main() -> None:
    args = parse_args()
    try:
        text, source_id = load_text(args)
        if not text.strip():
            raise ValueError("未检测到文本输入")
        chunks = chunk_text(text, args.chunk_size, args.overlap)
        rendered = render_chunks(chunks, args.format, source_id)
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(rendered + "\n", encoding="utf-8")
            print(f"已输出 {len(chunks)} 个文本块到 {args.output}")
        else:
            print(rendered)
    except (OSError, ValueError) as error:
        print(f"错误：{error}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
