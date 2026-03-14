#!/usr/bin/env python3
"""Simple text chunking CLI for a RAG pipeline."""

from __future__ import annotations

import argparse
import re
import sys
from typing import List


def split_sentences(text: str) -> List[str]:
    """Split text into sentences while keeping punctuation."""
    text = re.sub(r"\s+", " ", text.strip())
    if not text:
        return []
    parts = re.split(r"(?<=[。！？.!?；;])\s+", text)
    return [p.strip() for p in parts if p.strip()]


def chunk_text(text: str, chunk_size: int, overlap: int) -> List[str]:
    """Chunk text using sentence-aware packing with overlap."""
    if chunk_size <= 0:
        raise ValueError("chunk_size must be > 0")
    if overlap < 0:
        raise ValueError("overlap must be >= 0")
    if overlap >= chunk_size:
        raise ValueError("overlap must be smaller than chunk_size")

    sentences = split_sentences(text)
    if not sentences:
        return []

    chunks: List[str] = []
    current = ""

    for sentence in sentences:
        if len(sentence) > chunk_size:
            # Fallback for super long sentence: hard split it.
            start = 0
            while start < len(sentence):
                end = min(start + chunk_size, len(sentence))
                part = sentence[start:end]
                if current:
                    chunks.append(current)
                    current = ""
                chunks.append(part)
                start = end - overlap if end < len(sentence) else end
            continue

        candidate = f"{current} {sentence}".strip()
        if len(candidate) <= chunk_size:
            current = candidate
        else:
            if current:
                chunks.append(current)
                tail = current[-overlap:] if overlap > 0 else ""
                current = f"{tail}{sentence}".strip()
            else:
                current = sentence

    if current:
        chunks.append(current)

    return chunks


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
    parser = argparse.ArgumentParser(
        description="将文本按 RAG 场景分块，并在终端输出分块结果。"
    )
    parser.add_argument(
        "--text",
        type=str,
        help="直接传入文本；不传时可通过标准输入或交互方式输入。",
    )
    parser.add_argument(
        "--chunk-size",
        type=int,
        default=300,
        help="每块最大字符数（默认: 300）",
    )
    parser.add_argument(
        "--overlap",
        type=int,
        default=50,
        help="相邻块重叠字符数（默认: 50）",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()

    if args.text:
        text = args.text.strip()
    elif not sys.stdin.isatty():
        text = sys.stdin.read().strip()
    else:
        text = read_interactive_text()

    if not text:
        print("未检测到文本输入，请通过 --text、管道输入或交互输入提供文本。")
        sys.exit(1)

    chunks = chunk_text(text, args.chunk_size, args.overlap)

    print(f"\n共切割出 {len(chunks)} 个文本块：")
    for idx, chunk in enumerate(chunks, start=1):
        print(f"\n--- Chunk {idx} (len={len(chunk)}) ---")
        print(chunk)


if __name__ == "__main__":
    main()
