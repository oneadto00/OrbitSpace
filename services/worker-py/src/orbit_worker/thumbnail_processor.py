from __future__ import annotations

import io
from typing import Set

from PIL import Image, ImageOps

ALLOWED_FORMATS: Set[str] = {"PNG", "JPEG", "WEBP", "GIF"}
MAX_DIMENSION = 16384


class ThumbnailProcessingError(ValueError):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


def generate_safe_thumbnail(
    raw_bytes: bytes,
    max_image_pixels: int,
    max_dim: int,
    max_output_bytes: int,
) -> bytes:
    if not raw_bytes:
        raise ThumbnailProcessingError("EMPTY_IMAGE")

    Image.MAX_IMAGE_PIXELS = max_image_pixels

    try:
        with Image.open(io.BytesIO(raw_bytes)) as probe:
            fmt = (probe.format or "").upper()
            if fmt not in ALLOWED_FORMATS:
                raise ThumbnailProcessingError("UNSUPPORTED_IMAGE_FORMAT")
            width, height = probe.size
            if width <= 0 or height <= 0 or width > MAX_DIMENSION or height > MAX_DIMENSION:
                raise ThumbnailProcessingError("IMAGE_DIMENSIONS_EXCEEDED")
            if width * height > max_image_pixels:
                raise ThumbnailProcessingError("IMAGE_PIXELS_EXCEEDED")
            probe.verify()

        with Image.open(io.BytesIO(raw_bytes)) as src:
            src.load()
            if fmt == "GIF" and getattr(src, "is_animated", False):
                src.seek(0)
            oriented = ImageOps.exif_transpose(src)
            target_mode = "RGBA" if "A" in oriented.getbands() else "RGB"
            converted = oriented.convert(target_mode)
            converted.thumbnail((max_dim, max_dim), Image.Resampling.LANCZOS)
            # Copy pixel data into a clean image instance so no EXIF, XMP, ICC, or comment chunks survive.
            clean = Image.new(target_mode, converted.size)
            clean.paste(converted)

            out_buf = io.BytesIO()
            clean.save(out_buf, format="PNG", optimize=True)
            result = out_buf.getvalue()
            if len(result) > max_output_bytes:
                raise ThumbnailProcessingError("THUMBNAIL_OUTPUT_TOO_LARGE")
            return result
    except ThumbnailProcessingError:
        raise
    except Image.DecompressionBombError as exc:
        raise ThumbnailProcessingError("DECOMPRESSION_BOMB") from exc
    except Exception as exc:
        raise ThumbnailProcessingError("INVALID_IMAGE_DATA") from exc
