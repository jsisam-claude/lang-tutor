# -*- coding: utf-8 -*-
"""SVG -> Android VectorDrawable for the picture-room icon set.

The SVGs are constrained by the art brief (only path/circle/rect/ellipse,
absolute coords, solid palette fills, plain strokes), so the conversion is a
faithful shape-by-shape translation, not a general SVG importer. Circles,
ellipses and rects become arc/rounded-rect path data; every paint attribute
maps 1:1 onto VectorDrawable's path attributes.
"""
import glob, os, re, xml.etree.ElementTree as ET

import pathlib
REPO = pathlib.Path(__file__).resolve().parent.parent
SVG_DIR = str(REPO / "art/picture-room")
OUT_DIR = str(REPO / "app/src/main/res/drawable")

def f2s(v):
    s = f"{float(v):.2f}".rstrip("0").rstrip(".")
    return s if s else "0"

def circle_path(cx, cy, r):
    return (f"M {f2s(cx - r)} {f2s(cy)} "
            f"A {f2s(r)} {f2s(r)} 0 1 0 {f2s(cx + r)} {f2s(cy)} "
            f"A {f2s(r)} {f2s(r)} 0 1 0 {f2s(cx - r)} {f2s(cy)} Z")

def ellipse_path(cx, cy, rx, ry):
    return (f"M {f2s(cx - rx)} {f2s(cy)} "
            f"A {f2s(rx)} {f2s(ry)} 0 1 0 {f2s(cx + rx)} {f2s(cy)} "
            f"A {f2s(rx)} {f2s(ry)} 0 1 0 {f2s(cx - rx)} {f2s(cy)} Z")

def rect_path(x, y, w, h, rx):
    if rx <= 0:
        return f"M {f2s(x)} {f2s(y)} h {f2s(w)} v {f2s(h)} h {f2s(-w)} Z"
    rx = min(rx, w / 2, h / 2)
    return (f"M {f2s(x + rx)} {f2s(y)} h {f2s(w - 2 * rx)} "
            f"A {f2s(rx)} {f2s(rx)} 0 0 1 {f2s(x + w)} {f2s(y + rx)} v {f2s(h - 2 * rx)} "
            f"A {f2s(rx)} {f2s(rx)} 0 0 1 {f2s(x + w - rx)} {f2s(y + h)} h {f2s(-(w - 2 * rx))} "
            f"A {f2s(rx)} {f2s(rx)} 0 0 1 {f2s(x)} {f2s(y + h - rx)} v {f2s(-(h - 2 * rx))} "
            f"A {f2s(rx)} {f2s(rx)} 0 0 1 {f2s(x + rx)} {f2s(y)} Z")

def paint_attrs(el):
    a = {}
    fill = el.get("fill", "#000000")
    if fill and fill.lower() != "none":
        a["android:fillColor"] = fill
    stroke = el.get("stroke")
    if stroke and stroke.lower() != "none":
        a["android:strokeColor"] = stroke
        a["android:strokeWidth"] = el.get("stroke-width", "1")
        join = el.get("stroke-linejoin")
        if join: a["android:strokeLineJoin"] = join
        cap = el.get("stroke-linecap")
        if cap: a["android:strokeLineCap"] = cap
    return a

converted = 0
for svg in sorted(glob.glob(f"{SVG_DIR}/*.svg")):
    word = os.path.basename(svg)[:-4]
    tree = ET.parse(svg)
    root = tree.getroot()
    assert root.get("viewBox") == "0 0 128 128", f"{word}: unexpected viewBox"
    paths = []
    for el in root:
        tag = el.tag.split("}")[-1]
        if tag == "path":
            data = re.sub(r"\s+", " ", el.get("d").strip())
        elif tag == "circle":
            data = circle_path(float(el.get("cx")), float(el.get("cy")), float(el.get("r")))
        elif tag == "ellipse":
            data = ellipse_path(float(el.get("cx")), float(el.get("cy")),
                                float(el.get("rx")), float(el.get("ry")))
        elif tag == "rect":
            data = rect_path(float(el.get("x")), float(el.get("y")),
                             float(el.get("width")), float(el.get("height")),
                             float(el.get("rx", 0)))
        else:
            raise SystemExit(f"{word}: unsupported element <{tag}>")
        attrs = paint_attrs(el)
        attr_str = "".join(f'\n        {k}="{v}"' for k, v in attrs.items())
        paths.append(f'    <path{attr_str}\n        android:pathData="{data}"/>')
    body = "\n".join(paths)
    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- Picture-room art (docs/picture-vocabulary.md): drawn as flat SVG by the\n"
        "     art queue, converted shape-for-shape by the batch converter. Regenerate\n"
        "     rather than hand-edit; the SVG is the source of truth. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="96dp"\n'
        '    android:height="96dp"\n'
        '    android:viewportWidth="128"\n'
        '    android:viewportHeight="128">\n'
        f"{body}\n"
        "</vector>\n"
    )
    with open(f"{OUT_DIR}/pic_{word}.xml", "w") as f:
        f.write(xml)
    converted += 1
print(f"converted {converted} drawables into {OUT_DIR}")
