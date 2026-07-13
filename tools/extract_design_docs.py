from __future__ import annotations

import json
import re
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET


W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
TEXT_TAGS = {f"{W}t", f"{W}delText", f"{W}instrText"}


def node_text(node: ET.Element) -> str:
    parts: list[str] = []
    for item in node.iter():
        if item.tag in TEXT_TAGS and item.text:
            parts.append(item.text)
        elif item.tag == f"{W}tab":
            parts.append("\t")
        elif item.tag in {f"{W}br", f"{W}cr"}:
            parts.append("\n")
    return "".join(parts).strip()


def paragraph_style(paragraph: ET.Element) -> str | None:
    style = paragraph.find(f"./{W}pPr/{W}pStyle")
    return style.get(f"{W}val") if style is not None else None


def render_paragraph(paragraph: ET.Element) -> str:
    text = node_text(paragraph)
    if not text:
        return ""
    style = (paragraph_style(paragraph) or "").lower()
    match = re.search(r"heading\s*([1-6])", style)
    if match:
        return f"{'#' * int(match.group(1))} {text}"
    if style.startswith("title"):
        return f"# {text}"
    if "subtitle" in style:
        return f"## {text}"
    if "list" in style:
        return f"- {text}"
    return text


def render_table(table: ET.Element) -> list[str]:
    rows: list[list[str]] = []
    for row in table.findall(f"./{W}tr"):
        cells = []
        for cell in row.findall(f"./{W}tc"):
            value = " / ".join(
                filter(None, (render_paragraph(p) for p in cell.findall(f".//{W}p")))
            )
            cells.append(value.replace("|", "\\|"))
        if cells:
            rows.append(cells)
    if not rows:
        return []
    width = max(len(row) for row in rows)
    normalized = [row + [""] * (width - len(row)) for row in rows]
    output = ["| " + " | ".join(normalized[0]) + " |"]
    output.append("| " + " | ".join(["---"] * width) + " |")
    output.extend("| " + " | ".join(row) + " |" for row in normalized[1:])
    return output


def render_part(xml_bytes: bytes) -> list[str]:
    root = ET.fromstring(xml_bytes)
    output: list[str] = []
    def visit(child: ET.Element) -> None:
        if child.tag == f"{W}p":
            line = render_paragraph(child)
            if line:
                output.append(line)
        elif child.tag == f"{W}tbl":
            output.extend(render_table(child))
        else:
            for nested in child:
                visit(nested)

    visit(root)
    return output


def render_document(path: Path) -> tuple[str, dict[str, object]]:
    sections: list[str] = [f"# Source: {path.name}"]
    part_names: list[str] = []
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        ordered = ["word/document.xml"]
        ordered.extend(sorted(name for name in names if re.fullmatch(r"word/header\d+\.xml", name)))
        ordered.extend(sorted(name for name in names if re.fullmatch(r"word/footer\d+\.xml", name)))
        ordered.extend(name for name in ("word/footnotes.xml", "word/endnotes.xml", "word/comments.xml") if name in names)
        for part_name in ordered:
            if part_name not in names:
                continue
            lines = render_part(archive.read(part_name))
            if not lines:
                continue
            part_names.append(part_name)
            sections.append(f"\n## OOXML part: {part_name}\n")
            sections.extend(lines)
    text = "\n\n".join(sections).strip() + "\n"
    words = re.findall(r"\b\w+\b", text, flags=re.UNICODE)
    return text, {"source": path.name, "parts": part_names, "characters": len(text), "words": len(words)}


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: extract_design_docs.py INPUT_DIR OUTPUT_DIR", file=sys.stderr)
        return 2
    input_dir = Path(sys.argv[1]).resolve()
    output_dir = Path(sys.argv[2]).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    manifest: list[dict[str, object]] = []
    for source in sorted(input_dir.glob("*.docx")):
        text, metadata = render_document(source)
        output = output_dir / f"{source.stem}.md"
        output.write_text(text, encoding="utf-8")
        metadata["extracted"] = output.name
        manifest.append(metadata)
    (output_dir / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps({"documents": len(manifest), "words": sum(int(item["words"]) for item in manifest), "output": str(output_dir)}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
