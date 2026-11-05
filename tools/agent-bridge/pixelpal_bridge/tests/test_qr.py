"""The pairing QR must exist everywhere, with no pip dependency."""

from __future__ import annotations

from pixelpal_bridge import qr

URI = "pixelpal://pair?host=10.0.0.5&port=8787&key=" + "k" * 32


def test_matrix_is_square_and_module_aligned():
    rows = qr.matrix(URI)
    assert len(rows) >= 21
    assert all(len(row) == len(rows) for row in rows)


def test_matrix_has_three_finder_patterns():
    """A QR is unreadable without its corner finders: 7x7 dark ring + core."""
    rows = qr.matrix(URI)
    size = len(rows)
    for ox, oy in ((0, 0), (size - 7, 0), (0, size - 7)):
        assert rows[oy][ox], "finder outer corner must be dark"
        assert rows[oy][ox + 6], "finder ring must be 7 modules wide"
        assert not rows[oy + 1][ox + 1], "finder has a light gap inside the ring"
        assert rows[oy + 3][ox + 3], "finder has a dark 3x3 core"


def test_render_keeps_a_quiet_zone():
    lines = qr.render(URI)
    border = len(lines[0]) - len(qr.matrix(URI))
    assert border == 2 * qr.QUIET_ZONE
    assert lines[0].strip() == "", "top row of the quiet zone must be blank"
    assert lines[-1].strip() == "", "bottom row of the quiet zone must be blank"


def test_render_is_deterministic():
    assert qr.render(URI) == qr.render(URI)


def test_render_uses_half_blocks_so_the_code_stays_square():
    lines = qr.render(URI)
    glyphs = set("".join(lines))
    assert glyphs - {" "} <= {"█", "▀", "▄"}
