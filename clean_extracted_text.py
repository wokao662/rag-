#!/usr/bin/env python3
"""Clean layout artifacts from text extracted from PDF/Office documents."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Iterable, List


SECTION_HEADING = re.compile(
    r"^(?:[一二三四五六七八九十百]+[、．.]|第[一二三四五六七八九十百\d]+[章节篇]|"
    r"\d{1,2}(?:\.\d+)*[、．.])\s*\S+"
)
PAGE_NUMBER = re.compile(r"^(?:第?\s*\d+\s*页?|[-—·.\s]*\d+[-—·.\s]*)$")
SENTENCE_END = re.compile(r"[。！？!?；;：:]$|[.!?][\]\)）】》”’\"']*$")
LIST_ITEM = re.compile(r"^(?:[-•●▪◦]|\(?\d{1,2}[)）.]|[（(][一二三四五六七八九十\d]{1,3}[)）])\s*")
BLOCK_PREFIX = re.compile(r"^(?:摘要|关键词|中图分类号|基金项目|作者简介)[：:]")


def normalize_line(line: str) -> str:
    line = line.replace("\u3000", " ").replace("\u00a0", " ")
    line = re.sub(r"[\u200b-\u200d\ufeff]", "", line)
    return re.sub(r"[\t ]+", " ", line).strip()


def repeated_layout_lines(lines: Iterable[str]) -> set[str]:
    """Find short lines repeated across pages, which are often headers/footers."""
    counts = Counter(line for line in lines if 2 <= len(line) <= 80)
    return {
        line
        for line, count in counts.items()
        if count >= 2 and not SECTION_HEADING.match(line) and not SENTENCE_END.search(line)
    }


def is_heading(line: str) -> bool:
    if SECTION_HEADING.match(line):
        return True
    if len(line) <= 30 and not SENTENCE_END.search(line):
        return bool(re.search(r"(?:摘要|关键词|参考文献|结论|引言|附录)$", line))
    return False


def needs_space(left: str, right: str) -> bool:
    if not left or not right:
        return False
    return bool(re.match(r"[A-Za-z0-9]", right) and re.search(r"[A-Za-z0-9]$", left))


def join_wrapped_lines(lines: Iterable[str]) -> List[str]:
    paragraphs: List[str] = []
    current = ""

    def flush() -> None:
        nonlocal current
        if current:
            paragraphs.append(current.strip())
            current = ""

    for index, line in enumerate(lines):
        if index == 0 or line.startswith("□") or is_heading(line) or LIST_ITEM.match(line):
            flush()
            paragraphs.append(line)
            continue

        if BLOCK_PREFIX.match(line):
            flush()
            current = line
            if SENTENCE_END.search(line):
                flush()
            continue

        if current.endswith("-") and re.match(r"^[A-Za-z]", line):
            current = current[:-1] + line
        else:
            current += (" " if needs_space(current, line) else "") + line

        if SENTENCE_END.search(line):
            flush()

    flush()
    return paragraphs


def clean_text(text: str) -> tuple[str, dict[str, int]]:
    original_lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    normalized = [normalize_line(line) for line in original_lines]
    nonempty = [line for line in normalized if line]
    repeated = repeated_layout_lines(nonempty)

    kept: List[str] = []
    removed_page_numbers = 0
    removed_repeated = 0
    first_content_line = nonempty[0] if nonempty else ""
    for line in nonempty:
        if PAGE_NUMBER.match(line):
            removed_page_numbers += 1
            continue
        # Preserve the document title when it is also reused as a page header,
        # but remove other short repeated layout lines from every page.
        if line in repeated and line != first_content_line:
            removed_repeated += 1
            continue
        if line.startswith("网络首发时间：") and "网络首发地址：" in line:
            removed_repeated += 1
            continue
        kept.append(line)

    paragraphs = join_wrapped_lines(kept)
    cleaned = "\n\n".join(paragraphs).strip() + "\n"
    report = {
        "originalCharacters": len(text),
        "cleanedCharacters": len(cleaned),
        "originalLines": len(original_lines),
        "outputParagraphs": len(paragraphs),
        "removedBlankLines": len(normalized) - len(nonempty),
        "removedPageNumberLines": removed_page_numbers,
        "removedRepeatedHeaderFooterLines": removed_repeated,
    }
    return cleaned, report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="清洗 Tika 提取文本中的 PDF 排版噪声。")
    parser.add_argument("--input", type=Path, required=True, help="Tika 原始提取文本")
    parser.add_argument("--output", type=Path, required=True, help="清洗后的 UTF-8 文本")
    parser.add_argument("--report", type=Path, help="可选的 JSON 清洗报告")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    try:
        text = args.input.read_text(encoding="utf-8")
        if not text.strip():
            raise ValueError("输入文件为空")
        cleaned, report = clean_text(text)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(cleaned, encoding="utf-8")
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(
                json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
            )
        print(f"清洗完成: {args.output}")
        print(json.dumps(report, ensure_ascii=False))
    except (OSError, ValueError) as error:
        print(f"清洗失败: {error}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
