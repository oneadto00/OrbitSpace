"""Build a read-only text index in the resource-limited parser process."""
from __future__ import annotations

import json
import math
import re

import pymupdf

ACTIVE = re.compile(r"/(?:JavaScript|JS|Launch|OpenAction|AA|EmbeddedFiles?|RichMedia|XFA|SubmitForm|ImportData|GoToR|GoToE)\b")


def index_pdf(raw: bytes, limit: int) -> bytes:
    if not raw.startswith(b"%PDF-") or b"%%EOF" not in raw[-1024:]:
        raise ValueError("INVALID_PDF")
    with pymupdf.open(stream=raw, filetype="pdf") as doc:
        if doc.needs_pass or doc.is_repaired or not 1 <= doc.page_count <= 256 or doc.xref_length() > 30000:
            raise ValueError("PDF_LIMIT")
        decoded = 0
        for xref in range(1, doc.xref_length()):
            descriptor = doc.xref_object(xref, compressed=True)
            if ACTIVE.search(descriptor):
                raise ValueError("ACTIVE_PDF_CONTENT")
            if doc.xref_is_stream(xref):
                packed = len(doc.xref_stream_raw(xref) or b"")
                size = len(doc.xref_stream(xref) or b"")
                decoded += size
                if size > 16 * 1024**2 or size > max(65536, packed * 100) or decoded > min(96 * 1024**2, len(raw)*100):
                    raise ValueError("PDF_EXPANSION_LIMIT")
        pages = []
        budget = 0
        for page in doc:
            # Text extraction uses the unrotated crop box with a top-left origin.
            bounds = pymupdf.Rect(0, 0, page.cropbox.width, page.cropbox.height)
            if (not all(math.isfinite(x) for x in bounds) or bounds.width <= 0 or bounds.height <= 0
                    or bounds.width * bounds.height > 20000000 or len(page.get_contents()) > 1024):
                raise ValueError("PAGE_LIMIT")
            if len(page.get_bboxlog()) > 20000:
                raise ValueError("PAGE_COMPLEXITY")
            spans = []
            lines = []
            words = page.get_text("words", sort=True)
            if len(words) > 20000:
                raise ValueError("PAGE_WORD_LIMIT")
            line_id = None
            for x0,y0,x1,y1,text,block,line,_ in words:
                rect = pymupdf.Rect(x0,y0,x1,y1) & bounds
                if rect.is_empty or not all(math.isfinite(v) for v in rect):
                    continue
                if (block,line) != line_id:
                    lines.append([])
                    line_id = (block,line)
                lines[-1].append(text)
                spans.append(dict(text=text,x=(rect.x0-bounds.x0)/bounds.width,y=(rect.y0-bounds.y0)/bounds.height,
                                  width=rect.width/bounds.width,height=rect.height/bounds.height))
            entry = dict(page=page.number+1,text="\n".join(" ".join(line) for line in lines),spans=spans)
            budget += len(json.dumps(entry,ensure_ascii=False).encode())
            if budget > limit:
                raise ValueError("PDF_INDEX_LIMIT")
            pages.append(entry)
        result = json.dumps({"pages":pages},ensure_ascii=False,separators=(",",":")).encode()
        if len(result)>limit:
            raise ValueError("PDF_INDEX_LIMIT")
        return result
