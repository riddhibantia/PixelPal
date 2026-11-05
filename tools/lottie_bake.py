#!/usr/bin/env python3
"""Bake one Lottie frame into a flat SVG (and Android VectorDrawable paths).

PixelPal's pet Lottie files are simple: shape layers (ellipses, rounded
rects, fills) with animated root/ear transforms. This evaluates the property
graph at a given frame, composes the layer/group affine transforms, and emits
absolute-coordinate paths. No masks, gradients or shear transforms in this
art, so this is exact.
"""
import json
import math
import sys

W = 400.0
H = 400.0


def eval_prop(p, ft):
    if p is None:
        return None
    if isinstance(p, (int, float, list)):
        return p
    if p.get("s") and "x" in p:  # split position
        return [eval_prop(p["x"], ft)[0], eval_prop(p["y"], ft)[0]]
    if p.get("a", 0) == 0:
        return p.get("k")
    ks = p.get("k") or []
    if not ks:
        return None
    chosen = ks[0]
    for kf in ks:
        if kf.get("t", 0) <= ft:
            chosen = kf
    val = chosen.get("s", chosen.get("e"))
    return val


def mat(identity=False):
    return [1.0, 0.0, 0.0, 0.0, 1.0, 0.0]


def mul(A, B):
    a1, b1, c1, d1, e1, f1 = A
    a2, b2, c2, d2, e2, f2 = B
    return [
        a1 * a2 + b1 * d2,
        a1 * b2 + b1 * e2,
        a1 * c2 + b1 * f2 + c1,
        d1 * a2 + e1 * d2,
        d1 * b2 + e1 * e2,
        d1 * c2 + e1 * f2 + f1,
    ]


def apply(M, pt):
    a, b, c, d, e, f = M
    x, y = pt
    return (a * x + b * y + c, d * x + e * y + f)


def scalar(v, default=0.0):
    """Rotation/opacity keyframes store single-element lists (s:[0]); unwrap."""
    if v is None:
        return default
    if isinstance(v, (int, float)):
        return float(v)
    if isinstance(v, list):
        return float(v[0]) if v else default
    return default


def pair(v, default=(0.0, 0.0)):
    if v is None:
        return list(default)
    if isinstance(v, (int, float)):
        return [float(v), float(v)]
    if isinstance(v, list):
        if len(v) == 1:
            return [float(v[0]), float(v[0])]
        return [float(v[0]), float(v[1])]
    return list(default)


def transform(p, s, r, a):
    p = pair(p, (0, 0))
    s = pair(s, (100, 100))
    r = scalar(r, 0.0)
    a = pair(a, (0, 0))
    th = math.radians(r)
    cos_t, sin_t = math.cos(th), math.sin(th)
    S = [s[0] / 100.0, 0, 0, 0, s[1] / 100.0, 0]
    R = [cos_t, -sin_t, 0, sin_t, cos_t, 0]
    M = mul([1, 0, p[0], 0, 1, p[1]], mul(R, mul(S, [1, 0, -a[0], 0, 1, -a[1]])))
    return M


def layer_matrix(layer, by_ind, ft):
    ks = layer.get("ks", {})
    M = transform(
        eval_prop(ks.get("p"), ft),
        eval_prop(ks.get("s"), ft),
        eval_prop(ks.get("r"), ft),
        eval_prop(ks.get("a"), ft),
    )
    parent = layer.get("parent")
    if parent is not None and parent in by_ind:
        M = mul(layer_matrix(by_ind[parent], by_ind, ft), M)
    return M


def is_axis_aligned(M):
    a, b, c, d, e, f = M
    return abs(b) < 1e-6 and abs(d) < 1e-6 and a > 0 and e > 0


def ellipse_path(M, w, h):
    # Degenerate (zero-scale sparkles at frame 0) produce a single point; skip.
    ax = math.hypot(M[0] * w, M[3] * w)
    ay = math.hypot(M[1] * h, M[4] * h)
    if ax < 1e-3 or ay < 1e-3:
        return None
    if is_axis_aligned(M):
        cx, cy = apply(M, (0, 0))
        rx, ry = abs(M[0]) * w / 2.0, abs(M[4]) * h / 2.0
        if rx < 1e-6 or ry < 1e-6:
            return None
        return (
            f"M {cx - rx:.3f},{cy:.3f} "
            f"a {rx:.3f},{ry:.3f} 0 1,0 {2 * rx:.3f},0 "
            f"a {rx:.3f},{ry:.3f} 0 1,0 {-2 * rx:.3f},0 Z"
        )
    pts = []
    steps = 72
    for i in range(steps):
        th = 2 * math.pi * i / steps
        pts.append(apply(M, (math.cos(th) * w / 2.0, math.sin(th) * h / 2.0)))
    return "M " + " L ".join(f"{x:.3f},{y:.3f}" for x, y in pts) + " Z"


def rounded_rect_path(M, w, h, r):
    hw, hh = w / 2.0, h / 2.0
    r = max(0.0, min(r, hw, hh))
    if is_axis_aligned(M):
        cx, cy = apply(M, (0, 0))
        sx, sy = abs(M[0]), abs(M[4])
        x0, y0 = cx - hw * sx, cy - hh * sy
        x1, y1 = cx + hw * sx, cy + hh * sy
        rx, ry = r * sx, r * sy
        return (
            f"M {x0 + rx:.3f},{y0:.3f} H {x1 - rx:.3f} "
            f"A {rx:.3f},{ry:.3f} 0 0,1 {x1:.3f},{y0 + ry:.3f} "
            f"V {y1 - ry:.3f} A {rx:.3f},{ry:.3f} 0 0,1 {x1 - rx:.3f},{y1:.3f} "
            f"H {x0 + rx:.3f} A {rx:.3f},{ry:.3f} 0 0,1 {x0:.3f},{y1 - ry:.3f} "
            f"V {y0 + ry:.3f} A {rx:.3f},{ry:.3f} 0 0,1 {x0 + rx:.3f},{y0:.3f} Z"
        )
    # rotated: sample the outline
    pts = []
    corners = [
        (hw - r, hh - r, 1),
        (-hw + r, hh - r, 2),
        (-hw + r, -hh + r, 3),
        (hw - r, -hh + r, 4),
    ]
    for i in range(4):
        px, py = corners[i][0], corners[i][1]
        # corner arc sweep around the corner centre
        cx = hw if i in (0, 3) else -hw
        cy = hh if i in (0, 1) else -hh
        start = math.atan2(py - cy, px - cx)
        for k in range(12):
            th = start + (math.pi / 2) * k / 11
            pts.append(apply(M, (cx + r * math.cos(th), cy + r * math.sin(th))))
    return "M " + " L ".join(f"{x:.3f},{y:.3f}" for x, y in pts) + " Z"


def color(c):
    r, g, b = c[0], c[1], c[2]
    return (
        f"#{int(round(r * 255)):02X}{int(round(g * 255)):02X}{int(round(b * 255)):02X}"
    )


def walk_group(items, M, opacity, ft, out):
    fill = None
    for it in items:
        if it.get("ty") == "fl":
            fill = it
    for it in items:
        ty = it.get("ty")
        if ty == "gr":
            tr = None
            for child in it.get("it", []):
                if child.get("ty") == "tr":
                    tr = child
            Mg = M
            if tr:
                Mg = mul(
                    M,
                    transform(
                        eval_prop(tr.get("p"), ft),
                        eval_prop(tr.get("s"), ft),
                        eval_prop(tr.get("r"), ft),
                        eval_prop(tr.get("a"), ft),
                    ),
                )
            walk_group(it.get("it", []), Mg, opacity, ft, out)
        elif ty in ("el", "rc") and fill is not None:
            fp = fill.get("c")
            col = color(eval_prop(fp.get("k"), ft) if fp else [1, 1, 1, 1])
            fo = eval_prop(fill.get("o"), ft)
            alpha = opacity * ((scalar(fo, 100.0)) / 100.0)
            p = pair(eval_prop(it.get("p"), ft), (0, 0))
            s = pair(eval_prop(it.get("s"), ft), (0, 0))
            shapeM = mul(M, [1, 0, p[0], 0, 1, p[1]])
            if ty == "el":
                d = ellipse_path(shapeM, s[0], s[1])
            else:
                d = rounded_rect_path(shapeM, s[0], s[1], scalar(eval_prop(it.get("r"), ft), 0.0))
            if d:
                out.append({"d": d, "fill": col, "alpha": alpha})


def bake(path, frame, pre=None):
    d = json.load(open(path))
    by_ind = {l.get("ind"): l for l in d["layers"]}
    out = []
    # Lottie layers: index 0 is the FRONT-most layer; paint back-to-front.
    for layer in reversed(d["layers"]):
        if layer.get("ty") not in (3, 4):
            continue
        M = layer_matrix(layer, by_ind, frame)
        if pre is not None:
            M = mul(pre, M)
        lo = eval_prop(layer.get("ks", {}).get("o"), frame)
        opacity = scalar(lo, 100.0) / 100.0
        walk_group(layer.get("shapes", []), M, opacity, frame, out)
    return out


def to_vector(paths, size):
    body = "\n".join(
        f'    <path\n'
        f'        android:fillColor="{p["fill"]}"\n'
        f'        android:fillAlpha="{min(1.0, max(0.0, p["alpha"])):.3f}"\n'
        f'        android:pathData="{p["d"]}" />'
        for p in paths
        if p["alpha"] > 0.004
    )
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{size}dp"\n'
        f'    android:height="{size}dp"\n'
        f'    android:viewportWidth="{size}"\n'
        f'    android:viewportHeight="{size}">\n'
        f'{body}\n'
        '</vector>\n'
    )


def to_svg(paths, w, h, bg=None):
    body = "".join(
        f'<path d="{p["d"]}" fill="{p["fill"]}" fill-opacity="{p["alpha"]:.3f}"/>'
        for p in paths
    )
    bgrect = f'<rect width="{w}" height="{h}" fill="{bg}"/>' if bg else ""
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
        f'viewBox="0 0 {w} {h}">{bgrect}{body}</svg>'
    )


if __name__ == "__main__":
    args = sys.argv[1:]
    fit = None
    size = None
    art_h = None
    frame = 0.0
    pos = []
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--fit":
            fit = [float(v) for v in args[i + 1].split(",")]
            i += 2
        elif a == "--size":
            size = float(args[i + 1])
            i += 2
        elif a == "--art-height":
            art_h = float(args[i + 1])
            i += 2
        elif a == "--frame":
            frame = float(args[i + 1])
            i += 2
        else:
            pos.append(a)
            i += 1
    src, dst = pos[0], pos[1]
    pre = None
    if fit:
        x0, y0, x1, y1 = fit
        s = art_h / (y1 - y0)
        cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
        pre = [s, 0.0, size / 2.0 - s * cx, 0.0, s, size / 2.0 - s * cy]
    paths = bake(src, frame, pre)
    if dst.endswith(".svg"):
        svg = to_svg(paths, size or W, size or H)
        open(dst, "w").write(svg)
        print(f"{len(paths)} paths -> {dst}")
    elif size:
        xml = to_vector(paths, size)
        open(dst, "w").write(xml)
        print(f"{len(paths)} paths -> {dst} (vector {size:g}dp)")
    else:
        svg = to_svg(paths, W, H)
        open(dst, "w").write(svg)
        print(f"{len(paths)} paths -> {dst}")
