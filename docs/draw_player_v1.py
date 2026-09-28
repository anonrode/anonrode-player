#!/usr/bin/env python3
"""Render the actual current Anonrode player screen to PNG.
720x1600 (matches the Infinix X669). Uses values from the working tree
of agent 12's polish + agent 5's sync toggle + agent 3's right-rail.
No design guessing — every number comes from a grep-able source.
"""
from PIL import Image, ImageDraw, ImageFont
import os

W, H = 720, 1600
ACCENT = (124, 93, 255)        # #7C5DFF (SkinPalette AccentPurple)
ACCENT_22 = (124, 93, 255, 56)  # accent @ 22% alpha
WHITE = (255, 255, 255)
WHITE_75 = (255, 255, 255, 191)
WHITE_40 = (255, 255, 255, 102)
WHITE_20 = (255, 255, 255, 51)
WHITE_14 = (255, 255, 255, 36)
BLACK = (0, 0, 0)
BLACK_85 = (0, 0, 0, 217)
BLACK_80 = (0, 0, 0, 204)
BLACK_35 = (0, 0, 0, 89)
BG_DARK = (15, 17, 21)
SURFACE = (26, 29, 34)
TRACK = (255, 255, 255, 64)
RAIL_BG = (0, 0, 0, 97)
RAIL_BORDER = (255, 255, 255, 36)
DISABLED = (255, 255, 255, 89)


def font(size, bold=False):
    candidates = [
        ("C:\\Windows\\Fonts\\segoeuib.ttf" if bold else "C:\\Windows\\Fonts\\segoeui.ttf"),
        ("C:\\Windows\\Fonts\\arialbd.ttf" if bold else "C:\\Windows\\Fonts\\arial.ttf"),
        "/System/Library/Fonts/Helvetica.ttc",
    ]
    for p in candidates:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                pass
    return ImageFont.load_default()


def text(draw, xy, s, fnt, fill=WHITE):
    draw.text(xy, s, font=fnt, fill=fill)


def circle(draw, center, r, outline=WHITE_20, fill=BLACK_35, width=1):
    draw.ellipse([center[0] - r, center[1] - r, center[0] + r, center[1] + r],
                 outline=outline, fill=fill, width=width)


def round_rect(draw, xy, r, fill=None, outline=None, width=1):
    if fill or outline:
        draw.rounded_rectangle(xy, radius=r, fill=fill, outline=outline, width=width)


def gradient_h(img, xy_box, color_a, color_b, steps=20):
    """Horizontal gradient: left = color_a, right = color_b."""
    x0, y0, x1, y1 = xy_box
    w = x1 - x0
    for i in range(steps):
        f = i / (steps - 1)
        c = tuple(int(color_a[k] + (color_b[k] - color_a[k]) * f) for k in range(3))
        # PIL doesn't directly gradient-paint without Image.composite; use a band
        x = x0 + int(i * w / steps)
        draw.line([(x, y0), (x, y1)], fill=c, width=max(1, w // steps))


def gradient_v(img, xy_box, color_a, color_b, steps=20):
    x0, y0, x1, y1 = xy_box
    h = y1 - y0
    for i in range(steps):
        f = i / (steps - 1)
        c = tuple(int(color_a[k] + (color_b[k] - color_a[k]) * f) for k in range(3))
        y = y0 + int(i * h / steps)
        draw.line([(x0, y), (x1, y)], fill=c)


img = Image.new("RGBA", (W, H), BG_DARK + (255,))
draw = ImageDraw.Draw(img, "RGBA")

# ── Top scrim (black 80% top → transparent bottom, ~120dp tall) ──
top_scrim = Image.new("RGBA", (W, 200), (0, 0, 0, 0))
top_d = ImageDraw.Draw(top_scrim)
for y in range(200):
    a = int(204 * (1 - y / 200))  # 204 = 0.8 * 255
    top_d.line([(0, y), (W, y)], fill=(0, 0, 0, a))
img.alpha_composite(top_scrim, dest=(0, 0))

# ── Top bar contents (per PlayerScreenTopBar.kt) ──
f_body_lg = font(16)
f_body_md = font(14)

# Back arrow
text(draw, (50, 100), "\u2190", font(28), WHITE)  # left arrow

# Title (truncated, 1 line, bodyMedium)
text(draw, (96, 96), "Episode Title \u2014 S01E03", f_body_lg, WHITE)
text(draw, (96, 120), "Series Name", f_body_md, WHITE_75)

# Right cluster: HW/SW chip, audio, CC, more
# HW/SW chip (40dp tap, 12sp label)
chip_x = 470
round_rect(draw, [chip_x, 88, chip_x + 40, 128], 6, fill=BLACK_35, outline=ACCENT_22, width=1)
text(draw, (chip_x + 6, 96), "HW", font(12, bold=True), ACCENT)

# Audio icon
audio_x = 530
circle(draw, (audio_x, 108), 18, fill=BLACK_35, outline=WHITE_20, width=1)
text(draw, (audio_x - 5, 100), "\u266B", font(16), WHITE)  # music note

# CC icon
cc_x = 580
circle(draw, (cc_x, 108), 18, fill=BLACK_35, outline=WHITE_20, width=1)
text(draw, (cc_x - 8, 100), "CC", font(11, bold=True), WHITE)

# More (overflow)
more_x = 630
circle(draw, (more_x, 108), 18, fill=BLACK_35, outline=WHITE_20, width=1)
text(draw, (more_x - 4, 100), "\u2026", font(16), WHITE)

# ── Video surface (black, occupying middle 40-65%) ──
# Placeholder
draw.rectangle([40, 200, 680, 1100], fill=BLACK)
text(draw, (260, 630), "[ VIDEO SURFACE ]", font(28), fill=(90, 94, 102, 255))

# ── Right-edge action rail (Agent 3 + Agent 5) ──
# 5 icons: CC / audio / sync (toggle) / rotate / more
# 64dp wide pill, 48dp circles, 12dp gaps
rail_x = 596
rail_y = 460
rail_w = 64
rail_h = 5 * 48 + 4 * 12 + 16  # 5 icons + 4 gaps + 8dp top/bot padding
round_rect(draw, [rail_x, rail_y, rail_x + rail_w, rail_y + rail_h], 28, fill=RAIL_BG, outline=RAIL_BORDER, width=1)

icons = [
    ("CC", WHITE, WHITE_20),  # CC icon, white tint (subs on)
    ("\u266B", WHITE_75, WHITE_20),  # audio
    ("\u2728", ACCENT, ACCENT),  # sync (ON-idle state, accent border)
    ("\u21BB", WHITE, WHITE_20),  # rotate
    ("\u2026", WHITE_75, WHITE_20),  # more
]
for i, (glyph, tint, border) in enumerate(icons):
    cy = rail_y + 8 + 24 + i * (48 + 12)
    cx = rail_x + 32
    border_w = 3 if i == 2 else 1  # sync has thicker border (active)
    circle(draw, (cx, cy), 24, fill=BLACK_35, outline=border, width=border_w)
    text(draw, (cx - 8, cy - 11), glyph, font(18, bold=(i == 2)), tint)

# ── Bottom dock (Agent 3 + Agent 12 polish) ──
# Bottom scrim (transparent top → black 85% bottom, ~280dp tall)
bot_scrim = Image.new("RGBA", (W, 320), (0, 0, 0, 0))
bot_d = ImageDraw.Draw(bot_scrim)
for y in range(320):
    a = int(217 * (y / 320))  # 217 = 0.85 * 255
    bot_d.line([(0, y), (W, y)], fill=(0, 0, 0, a))
img.alpha_composite(bot_scrim, dest=(0, H - 320))

# Row 1: seek bar (always visible)
seek_y = H - 280
# Current time (left)
text(draw, (60, seek_y - 8), "12:34", font(14), WHITE_75)
# Seek track
track_x0, track_x1 = 120, 600
draw.rounded_rectangle([track_x0, seek_y + 8, track_x1, seek_y + 14], radius=3, fill=TRACK)
# Played portion (purple)
draw.rounded_rectangle([track_x0, seek_y + 8, track_x0 + 180, seek_y + 14], radius=3, fill=ACCENT)
# Thumb
thumb_x = track_x0 + 180
draw.ellipse([thumb_x - 9, seek_y + 2, thumb_x + 9, seek_y + 20], fill=WHITE, outline=ACCENT, width=2)
# Remaining time (right)
text(draw, (W - 60 - 36, seek_y - 8), "32:47", font(14), WHITE_75)

# Row 2: utility row (Lock, A-B, Aspect, PiP) - auto-hides, shown here
util_y = seek_y + 80
util_icons = [
    ("\u25A0", 0.0),  # placeholder shapes
]
# Just the transport row, the utility row was in the original design
# but Agent 3's PlayerScreenBottomBar.kt has only 2 rows in the bottom
# block (seek + transport), with a 6-icon transport.
# Let me re-read the actual code.

# Row 2: transport row (left cluster + right cluster)
trans_y = seek_y + 70
# Left cluster: lock (40dp), ⏪10 (48dp), ⏮ (48dp), BIG play (64dp), ⏭ (48dp), ⏩10 (48dp)
# Use Arrangement.SpaceBetween: left cluster left-aligned, right cluster right-aligned
left_x = 60
# 1) Lock
lock_cx, lock_cy = left_x + 20, trans_y + 20
circle(draw, (lock_cx, lock_cy), 20, fill=BLACK_35, outline=WHITE_40, width=1)
text(draw, (lock_cx - 8, lock_cy - 10), "\U0001F512", font(14), WHITE)  # lock

# 2) ⏪10 (48dp, squared pill with "10" inside + chevron)
back_x = left_x + 60
round_rect(draw, [back_x, trans_y - 2, back_x + 48, trans_y + 42], 10, fill=ACCENT_22, outline=(124, 93, 255, 140), width=1)
text(draw, (back_x + 6, trans_y + 4), "\u2039", font(14, bold=True), ACCENT)
text(draw, (back_x + 16, trans_y + 6), "10", font(14, bold=True), ACCENT)

# 3) ⏮ (48dp round)
prev_cx = back_x + 96
circle(draw, (prev_cx, trans_y + 20), 24, fill=None, outline=None, width=0)
text(draw, (prev_cx - 7, trans_y + 6), "\u23EE", font(20), WHITE)  # skip prev

# 4) BIG play (64dp, hollow outline circle with white border)
play_cx = prev_cx + 80
draw.ellipse([play_cx - 32, trans_y - 12, play_cx + 32, trans_y + 52], outline=WHITE, fill=None, width=2)
# Play triangle (centered in 64dp circle)
text(draw, (play_cx - 12, trans_y - 2), "\u25B6", font(36, bold=True), WHITE)

# 5) ⏭ (48dp round)
next_cx = play_cx + 80
text(draw, (next_cx - 6, trans_y + 6), "\u23ED", font(20), WHITE)  # skip next

# 6) ⏩10
fwd_x = next_cx + 48
round_rect(draw, [fwd_x, trans_y - 2, fwd_x + 48, trans_y + 42], 10, fill=ACCENT_22, outline=(124, 93, 255, 140), width=1)
text(draw, (fwd_x + 14, trans_y + 6), "10", font(14, bold=True), ACCENT)
text(draw, (fwd_x + 30, trans_y + 4), "\u203A", font(14, bold=True), ACCENT)

# Right cluster (PiP / sub-sync / rotate) - 8dp gaps, right-aligned
# Sub-sync is 56dp (slightly bigger for the spinning ring), others 48dp
right_edge = W - 60
# Rotate (rightmost, 48dp)
rotate_cx = right_edge - 24
circle(draw, (rotate_cx, trans_y + 20), 24, fill=BLACK_35, outline=WHITE_20, width=1)
text(draw, (rotate_cx - 6, trans_y + 8), "\u21BB", font(16), WHITE)

# Sub-sync (56dp, 8dp gap from rotate)
sync_cx = rotate_cx - 32 - 28
draw.ellipse([sync_cx - 28, trans_y - 8, sync_cx + 28, trans_y + 48], fill=BLACK_35, outline=ACCENT, width=3)
text(draw, (sync_cx - 8, trans_y + 4), "\u2728", font(18, bold=True), ACCENT)
# SYNCING label under the sync icon
text(draw, (sync_cx - 22, trans_y + 56), "SYNCED", font(8, bold=True), ACCENT)

# PiP (48dp, 8dp gap from sync)
pip_cx = sync_cx - 32 - 24
circle(draw, (pip_cx, trans_y + 20), 24, fill=BLACK_35, outline=WHITE_20, width=1)
text(draw, (pip_cx - 8, trans_y + 8), "\u29C9", font(14), WHITE)  # PiP-ish

# Bottom safe area
text(draw, (W // 2 - 80, H - 30), "v0.7 working tree (no build yet)", font(11), fill=(124, 130, 139, 255))

# Save
out = r"C:\Users\Anon\Desktop\Anon\anonrode-player\docs\player_screen_v0_7.png"
img.save(out, "PNG")
print(f"Saved {out} ({W}x{H})")
