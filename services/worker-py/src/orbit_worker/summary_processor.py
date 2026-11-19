"""Local extractive summarization. All text is data; only the input manifest can supply citations."""
from __future__ import annotations

from collections import Counter
import json
import math
import re

TOKENS = re.compile(r"[\u3400-\u9fff]|[^\W_\u3400-\u9fff]+", re.UNICODE)
KINDS = {"DOCUMENT_VERSION", "PDF_VERSION", "GIT_SNAPSHOT", "CHAT_MESSAGE"}


def summarize(manifest: dict, limit: int) -> bytes:
    sources = manifest["sources"]
    if not 1 <= len(sources) <= 64:
        raise ValueError("INVALID_MANIFEST")
    points = []
    for source in sources:
        if source["kind"] not in KINDS or not all(isinstance(source[k], str) and source[k]
                                                   for k in ("resourceId", "versionId", "text")):
            raise ValueError("INVALID_SOURCE")
        # Centroid scoring favours sentences covering terms recurring across this source.
        sentences = [s.strip()[:2048] for s in re.split(r"(?<=[。！？.!?])\s*|[\r\n]+", source["text"]) if s.strip()]
        if not sentences:
            continue
        terms = [Counter(TOKENS.findall(sentence.casefold())) for sentence in sentences]
        centroid = Counter()
        for term_counts in terms:
            centroid.update(term_counts.keys())
        def score(index):
            counts = terms[index]
            return sum(math.log1p(centroid[t]) * min(n, 3) for t, n in counts.items()) / math.sqrt(max(1,sum(counts.values())))
        best = sorted(range(len(sentences)), key=score, reverse=True)[:2]
        text = " ".join(sentences[i] for i in sorted(best))
        points.append({"text": text, "sources": [{k: source[k] for k in ("kind", "resourceId", "versionId")}]})
    result = json.dumps({"points": points}, ensure_ascii=False).encode()
    if not points or len(result) > limit:
        raise ValueError("SUMMARY_LIMIT")
    return result
