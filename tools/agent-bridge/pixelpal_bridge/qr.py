"""Terminal QR rendering for the pairing URI.

The encoder itself is vendored in `_qrcode.py` (Project Nayuki, MIT) so the
bridge keeps its "pure stdlib, no pip" promise: a fresh `install.sh` machine
must always be able to show a scannable code.

Two module rows are drawn per text row with half blocks, because terminal
characters are about twice as tall as they are wide; that keeps the code
square and scannable instead of stretched.
"""

from __future__ import annotations

from ._qrcode import QrCode

#: Quiet zone (in modules) required on every side by the QR spec.
QUIET_ZONE = 2


def matrix(uri: str) -> list[list[bool]]:
    """Return the QR module grid for `uri` (True = dark)."""
    code = QrCode.encode_text(uri, QrCode.Ecc.MEDIUM)
    size = code.get_size()
    return [[code.get_module(x, y) for x in range(size)] for y in range(size)]


def _dark(rows: list[list[bool]], x: int, y: int) -> bool:
    """Module lookup that treats everything outside the grid as light."""
    if 0 <= y < len(rows) and 0 <= x < len(rows[0]):
        return rows[y][x]
    return False


def render(uri: str) -> list[str]:
    """Render `uri` as rows of half-block characters."""
    rows = matrix(uri)
    size = len(rows)
    low, high = -QUIET_ZONE, size + QUIET_ZONE
    lines: list[str] = []
    for y in range(low, high, 2):
        cells: list[str] = []
        for x in range(low, high):
            top = _dark(rows, x, y)
            bottom = _dark(rows, x, y + 1)
            if top and bottom:
                cells.append("█")
            elif top:
                cells.append("▀")
            elif bottom:
                cells.append("▄")
            else:
                cells.append(" ")
        lines.append("".join(cells))
    return lines
