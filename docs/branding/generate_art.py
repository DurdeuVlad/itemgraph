#!/usr/bin/env python3
"""
ItemGraph branding art generator.

Everything here is hand-placed pixel art: flat colours, hard edges, a small
fixed palette, nearest-neighbour upscaling only. No gradients, no blur, no
anti-aliasing, no glow. That is deliberate - it matches Minecraft's own
texture style and keeps the assets reproducible/editable by a human.

Brand constraints (docs/BRANDING.md):
  - precise, calm, evidence-driven, infrastructure-oriented, trustworthy
  - NOT punitive / theatrical / surveillance-themed / police-themed
  - solid amber node + solid edge      = OBSERVED
  - dimmed slate node + dashed edge    = INFERRED

How OBSERVED vs INFERRED is encoded, and why it is NOT a distinct shape
----------------------------------------------------------------------
An earlier revision drew the inferred hop as a hollow 3x3 outline reached by a
short dashed stem. At 32x32 that stem+loop pair aliased into a blocky letter
"P" floating beside the trace - a random glyph rather than a graph element.

The fix follows the transit-map "line under construction" convention
(https://transitmap.net/under-construction/): keep the *shape* of the element
identical to the operating one and differentiate it only by dashing the line
and lowering its value. Graphviz encodes uncertainty the same way - `dashed`
and `dotted` are styles applied to the existing node/edge, not extra marker
shapes (https://graphviz.org/docs/attr-types/style/). Applied Energistics 2's
own network logo likewise renders its whole routing motif as uniform-width
traces separated purely by brightness, with no terminal symbols at all.

So every node here is the same 3x3 block. Four identical blocks on one rising
line cannot resolve into a letterform, because there is no stem, no loop and
no enclosed counter anywhere in the composition.

Usage:  python3 docs/branding/generate_art.py
"""

from PIL import Image

# ---------------------------------------------------------------------------
# tiny pixel canvas
# ---------------------------------------------------------------------------


class Canvas:
    def __init__(self, w, h, bg):
        self.w = w
        self.h = h
        self.px = [[bg for _ in range(w)] for _ in range(h)]

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = c

    def rect(self, x, y, w, h, c):
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                self.set(xx, yy, c)

    def frame(self, x, y, w, h, c):
        for xx in range(x, x + w):
            self.set(xx, y, c)
            self.set(xx, y + h - 1, c)
        for yy in range(y, y + h):
            self.set(x, yy, c)
            self.set(x + w - 1, yy, c)

    def line(self, x0, y0, x1, y1, c, dash=0):
        """Integer Bresenham. dash=n draws n on / n off along the run."""
        dx = abs(x1 - x0)
        dy = -abs(y1 - y0)
        sx = 1 if x0 < x1 else -1
        sy = 1 if y0 < y1 else -1
        err = dx + dy
        i = 0
        while True:
            if not dash or (i // dash) % 2 == 0:
                self.set(x0, y0, c)
            if x0 == x1 and y0 == y1:
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy
            i += 1

    def elbow(self, x0, y0, x1, y1, c, dash=0):
        """Right-angle route: horizontal first, then vertical.

        Orthogonal rather than diagonal on purpose. A 1px diagonal run is a
        staircase of corner-touching pixels, which already reads as a dotted
        line - so a *deliberately* dashed diagonal carries no extra meaning.
        Axis-aligned runs are genuinely solid, which is what makes the
        solid/dashed distinction legible at 32x32. Same reason Applied
        Energistics draws its network logo as right-angled circuit traces.

        dash=n gives n on / n off, phase-continuous across the corner.
        """
        pts = []
        sx = 1 if x1 >= x0 else -1
        for x in range(x0, x1 + sx, sx):
            pts.append((x, y0))
        sy = 1 if y1 >= y0 else -1
        for y in range(y0 + sy, y1 + sy, sy):
            pts.append((x1, y))
        for i, (x, y) in enumerate(pts):
            if not dash or (i // dash) % 2 == 0:
                self.set(x, y, c)

    def image(self, scale=1):
        img = Image.new("RGBA", (self.w, self.h))
        img.putdata([c for row in self.px for c in row])
        if scale != 1:
            img = img.resize((self.w * scale, self.h * scale), Image.NEAREST)
        return img

    def blit(self, other, ox, oy, scale=1, skip=None):
        for y in range(other.h):
            for x in range(other.w):
                c = other.px[y][x]
                if skip is not None and c == skip:
                    continue
                for sy in range(scale):
                    for sx in range(scale):
                        self.set(ox + x * scale + sx, oy + y * scale + sy, c)


def rgb(h):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)


TRANSPARENT = (0, 0, 0, 0)

# ---------------------------------------------------------------------------
# 32x32 graph tile icon
# ---------------------------------------------------------------------------

# Trace layout, shared by every variant so the three icons stay siblings:
# one motif, three palettes. (x, y) is the top-left of a 3x3 node; the centre
# is (x+1, y+1). The main run ascends left-to-right = movement through time.
#
# The last hop is the INFERRED one: the trace is observed as far as NODES[-2],
# then continues to a node we did not directly witness. That is exactly the
# worked example in docs/BRANDING.md (two OBSERVED rows, then one INFERRED
# edge), and it keeps the inferred element on the trace rather than floating
# beside it - nothing is left disconnected in empty space.
NODES = [(5, 24), (12, 18), (19, 12), (25, 5)]
INFERRED_FROM = -2                                 # last edge + last node are inferred
DASH = 1                                           # 1px on / 1px off along the run

# A sparse, hand-chosen speckle. Regular enough to look placed by a person,
# irregular enough not to look like a generated pattern.
SPECKLE = [
    (3, 4), (7, 2), (12, 9), (20, 3), (27, 6), (29, 14), (2, 12), (6, 27),
    (13, 28), (21, 26), (28, 22), (9, 21), (23, 16), (18, 8), (5, 17),
    (26, 29), (11, 3), (30, 9),
]


def node(c, x, y, base, hi, lo):
    c.rect(x, y, 3, 3, base)
    c.set(x, y, hi)
    c.set(x + 2, y + 2, lo)


def graph_tile(pal):
    c = Canvas(32, 32, pal["bg"])

    for x, y in SPECKLE:
        c.set(x, y, pal["speck"])

    # flat panel edges: dark outer ring, optional second frame ring,
    # then a single bevel highlight on top/left (Minecraft UI panel look)
    c.frame(0, 0, 32, 32, pal["border"])
    if "frame" in pal:
        c.frame(1, 1, 30, 30, pal["frame"])
        c.frame(2, 2, 28, 28, pal["frame"])
    else:
        for i in range(1, 31):
            c.set(i, 1, pal["bevel"])
            c.set(1, i, pal["bevel"])

    # edges: every hop but the last is solid; the last is the same line, dashed
    observed = NODES[:INFERRED_FROM + 1]
    for a, b in zip(observed, observed[1:]):
        c.elbow(a[0] + 1, a[1] + 1, b[0] + 1, b[1] + 1, pal["edge"])

    a, b = NODES[INFERRED_FROM], NODES[INFERRED_FROM + 1]
    c.elbow(a[0] + 1, a[1] + 1, b[0] + 1, b[1] + 1, pal["edge"], dash=DASH)

    # nodes: identical 3x3 block throughout, only the palette changes
    for x, y in observed:
        node(c, x, y, pal["node"], pal["node_hi"], pal["node_lo"])
    x, y = NODES[INFERRED_FROM + 1]
    node(c, x, y, pal["node_inf"], pal["node_inf_hi"], pal["node_inf_lo"])
    return c


SLATE = {  # v2 - dark slate tile, amber trace
    "bg": rgb("#2b303a"), "speck": rgb("#333945"), "border": rgb("#191c23"),
    "bevel": rgb("#3c4351"),
    "edge": rgb("#8b95a5"), "edge_dim": rgb("#4d5565"),
    "node": rgb("#d8a441"), "node_hi": rgb("#efc87a"), "node_lo": rgb("#a87a26"),
    # INFERRED: same block, amber drained out of it. Still high-contrast
    # against the slate ground, so it reads as a node and not as a smudge.
    "node_inf": rgb("#8b95a5"), "node_inf_hi": rgb("#aeb7c4"),
    "node_inf_lo": rgb("#646d7c"),
}

STONE = {  # v3 - light stone tile, slate trace
    "bg": rgb("#aeaaa1"), "speck": rgb("#b9b5ac"), "border": rgb("#6b675f"),
    "bevel": rgb("#c2beb5"),
    "edge": rgb("#5d5a53"), "edge_dim": rgb("#8b877f"),
    "node": rgb("#35506b"), "node_hi": rgb("#4e6c8a"), "node_lo": rgb("#23364a"),
    "node_inf": rgb("#6b7783"), "node_inf_hi": rgb("#848f9a"),
    "node_inf_lo": rgb("#4f5962"),
}

# ---------------------------------------------------------------------------
# 16x16 map-item icon (v1)
# ---------------------------------------------------------------------------


def map_item():
    edge = rgb("#4a3a24")
    frame = rgb("#a7895a")
    field = rgb("#d9caa4")
    field2 = rgb("#cdbd95")
    ink = rgb("#6b5334")
    ink_dim = rgb("#9c8355")
    nd = rgb("#2f6273")
    nd_hi = rgb("#4a8496")
    nd_inf = rgb("#8a9499")
    nd_inf_hi = rgb("#a5adb1")

    c = Canvas(16, 16, field)
    for x, y in [(4, 3), (9, 5), (3, 8), (12, 11), (6, 13), (11, 2)]:
        c.set(x, y, field2)
    c.frame(0, 0, 16, 16, edge)
    c.frame(1, 1, 14, 14, frame)

    # Same grammar as graph_tile: orthogonal run, observed hops solid, final
    # hop dotted, final node drained of colour. No separate marker shape.
    # Only three nodes here - 16x16 native cannot carry four and stay legible.
    pts = [(3, 11), (7, 8), (11, 4)]

    for a, b in zip(pts[:-1], pts[1:-1]):
        c.elbow(a[0], a[1], b[0], b[1], ink)
    c.elbow(pts[-2][0], pts[-2][1], pts[-1][0], pts[-1][1], ink_dim, dash=1)

    for x, y in pts[:-1]:
        c.rect(x, y, 2, 2, nd)
        c.set(x, y, nd_hi)
    x, y = pts[-1]
    c.rect(x, y, 2, 2, nd_inf)
    c.set(x, y, nd_inf_hi)
    return c


# ---------------------------------------------------------------------------
# 5x7 blocky bitmap font (Minecraft-UI flavoured, variable width)
# ---------------------------------------------------------------------------

FONT = {
    "I": ["###", ".#.", ".#.", ".#.", ".#.", ".#.", "###", "..."],
    "T": ["#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#..", "....."],
    "G": [".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".###.", "....."],
    "a": ["....", "....", ".##.", "...#", ".###", "#..#", ".###", "...."],
    "c": ["....", "....", ".##.", "#..#", "#...", "#..#", ".##.", "...."],
    "e": ["....", "....", ".##.", "#..#", "####", "#...", ".##.", "...."],
    "g": ["....", "....", ".##.", "#..#", "#..#", ".###", "...#", ".##."],
    "h": ["#...", "#...", "###.", "#..#", "#..#", "#..#", "#..#", "...."],
    "i": ["#", ".", "#", "#", "#", "#", "#", "."],
    "m": [".....", ".....", "##.##", "#.#.#", "#.#.#", "#.#.#", "#.#.#", "....."],
    "n": ["....", "....", "###.", "#..#", "#..#", "#..#", "#..#", "...."],
    "o": ["....", "....", ".##.", "#..#", "#..#", "#..#", ".##.", "...."],
    "p": ["....", "....", "###.", "#..#", "#..#", "###.", "#...", "#..."],
    "r": ["...", "...", "###", "#..", "#..", "#..", "#..", "..."],
    "t": [".#.", ".#.", "###", ".#.", ".#.", ".#.", "..#", "..."],
    "u": ["....", "....", "#..#", "#..#", "#..#", "#..#", ".###", "...."],
    "v": ["#####", "#####", "#...#", "#...#", "#...#", ".#.#.", "..#..", "....."],
    ".": [".", ".", ".", ".", ".", ".", "#", "."],
    " ": ["..", "..", "..", "..", "..", "..", "..", ".."],
}
# 'v' has no ascender - blank its top two rows
FONT["v"][0] = "....."
FONT["v"][1] = "....."


def text_width(s, scale=1, tracking=1):
    w = 0
    for ch in s:
        w += (len(FONT[ch][0]) + tracking) * scale
    return w - tracking * scale


def draw_text(c, s, ox, oy, colour, scale=1, tracking=1, shadow=None):
    """Minecraft-style text: optional hard drop shadow one font-pixel down-right."""
    if shadow is not None:
        draw_text(c, s, ox + scale, oy + scale, shadow, scale, tracking, None)
    x = ox
    for ch in s:
        glyph = FONT[ch]
        for gy, row in enumerate(glyph):
            for gx, p in enumerate(row):
                if p != "#":
                    continue
                for sy in range(scale):
                    for sx in range(scale):
                        c.set(x + gx * scale + sx, oy + gy * scale + sy, colour)
        x += (len(glyph[0]) + tracking) * scale


# ---------------------------------------------------------------------------
# banner (native 512x128, upscaled x2 -> 1024x256)
# ---------------------------------------------------------------------------

BG = rgb("#22262e")
BG_LINE = rgb("#2a2f39")
BG_NODE = rgb("#323845")
TITLE = rgb("#e8e4da")
TITLE_SH = rgb("#3b3931")
TAG = rgb("#98a1ad")
TAG_SH = rgb("#2a2e36")
RULE = rgb("#d8a441")
RULE_DIM = rgb("#6d5624")
BORDER = rgb("#181b21")


def banner():
    c = Canvas(512, 128, BG)

    # faint background trace: same motif as the icon, far in the back
    bgpts = [(-10, 96), (58, 70), (132, 104), (206, 52), (288, 86),
             (366, 40), (448, 74), (528, 30)]
    for a, b in zip(bgpts, bgpts[1:]):
        c.line(a[0], a[1], b[0], b[1], BG_LINE)
    for x, y in bgpts:
        c.rect(x - 2, y - 2, 5, 5, BG_NODE)

    bg2 = [(-10, 30), (74, 22), (150, 44), (240, 18), (330, 36), (420, 14), (520, 34)]
    for a, b in zip(bg2, bg2[1:]):
        c.line(a[0], a[1], b[0], b[1], BG_LINE)
    for x, y in bg2:
        c.rect(x - 1, y - 1, 3, 3, BG_NODE)

    # icon block, 32x32 at 3x
    icon = graph_tile(SLATE)
    c.blit(icon, 24, 16, scale=3)

    tx = 148
    draw_text(c, "ItemGraph", tx, 30, TITLE, scale=4, tracking=1, shadow=TITLE_SH)

    # a short amber rule that reads as one more edge of the trace
    ruley = 70
    rulew = text_width("ItemGraph", 4)
    c.rect(tx, ruley, rulew, 2, RULE_DIM)
    c.rect(tx, ruley, 26, 2, RULE)
    c.rect(tx + rulew - 4, ruley - 2, 6, 6, RULE)
    c.set(tx + rulew - 4, ruley - 2, rgb("#efc87a"))

    draw_text(c, "Trace item movement through time.", tx, 84, TAG,
              scale=2, tracking=1, shadow=TAG_SH)

    c.frame(0, 0, 512, 128, BORDER)
    return c


# ---------------------------------------------------------------------------

def main():
    import os

    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.dirname(os.path.dirname(here))
    res = os.path.join(repo, "src", "main", "resources")

    # Archived alternates - kept for the record, not shipped. v1 reads as an
    # adventure/treasure map and v3's light stone reads softer than the brand
    # wants; v2 slate is the one that actually looks like infrastructure.
    map_item().image(scale=16).save(os.path.join(here, "itemgraph_icon_v1_map.png"))
    graph_tile(STONE).image(scale=8).save(os.path.join(here, "itemgraph_icon_v3_stone.png"))

    # FINAL: dark slate is the shipped icon. Written to both places so the
    # branding folder and the mod resource can never drift apart.
    final_256 = graph_tile(SLATE).image(scale=8)
    final_256.save(os.path.join(here, "itemgraph_icon_v2_slate.png"))
    final_256.save(os.path.join(res, "itemgraph_icon.png"))

    # High-resolution CurseForge avatar exports (512x512 and 1024x1024)
    final_512 = graph_tile(SLATE).image(scale=16)
    final_512.save(os.path.join(here, "curseforge_avatar_512.png"))

    final_1024 = graph_tile(SLATE).image(scale=32)
    final_1024.save(os.path.join(here, "curseforge_avatar_1024.png"))

    banner().image(scale=2).save(os.path.join(here, "curseforge_banner.png"))
    print("wrote icons (256x256, 512x512, 1024x1024) and banner (1024x256)")


if __name__ == "__main__":
    main()
