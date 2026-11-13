# TODO

Things noticed and not done, each with what was seen and where.

## Video frames: what is left for the GPU (device feed)

Since 2026-11-13 the decoder converts only the frames it shows. Every frame is decoded (`Video.decode`), and only the
ones the window wants are scaled to RGBA, in one threaded swscale pass straight into a chain's first level
(`Video.convert`). Their mips are made by `OpaqueMips`, and chains are reused. A shown frame costs the decoder thread
about 2 ms at 1080p and 4 ms from a 2160p source, and both 60 fps test clips play at 60. What is still on the CPU,
and belongs to vexelray-gui's device feed (`vexelray-gui/docs/plans/device-feed.md`, steps 2 and 3, open question 7):

- **Conversion to RGBA.** The decoded planes are ready for it: `Video.planes()` gives the held frame as the decoder
  made it, as `Planes`. That is the pixel format by FFmpeg's name (`yuv420p`, `nv12`, `yuv420p10le`), the coded size,
  each plane's native memory, stride and rows, the matrix (601, 709 or 2020) and whether the range is full. Step 3
  uploads those planes and converts in the shader. The planes are valid only until the next `decode`, `seek` or
  `close`, so Footage would have to upload them, or copy them out, before it decodes on. Today it converts at that
  same point.
- **Mips.** `OpaqueMips` makes them on the CPU, multi-threaded, about a third of a shown frame's cost. On the GPU
  they'd be a blit chain or a compute pass after the upload.
- **The copy into the feed's slot.** `Player.once` copies a kept chain into a feed slot (about 11 MB at 1080p, on the
  player's thread). Footage makes frames ahead into its own cache, so stepping back and looping cost nothing, and so
  it can't write into a slot it doesn't have yet. With the device feed the slot would be a GPU image, and the copy
  becomes the upload.
- **Formats beyond 8-bit 4:2:0.** `convert` handles any format swscale knows. A shader path needs a converter per
  layout: at least `yuv420p`, `nv12` and the 10-bit `p010`/`yuv420p10le`. Anything else can fall back to `convert`.

## A Rasterware video profile

Rasterware converts media into formats chosen for size and cheap decoding. For video played through this library, the
profile should specify:

- **A keyframe interval of about 1 s.** A seek is exact: it decodes from the keyframe before the target and drops what
  it passes. With the SVT-AV1 default of about 5.3 s at 60 fps, a seek costs up to roughly 320 decoded frames; at 1 s
  it's at most 60. Footage measures what seeks cost and seeks ahead when that's cheaper than decoding on, so shorter
  GOPs make it seek more readily and recover from a stall sooner.
- **Storage at the size it will be shown.** The demo plays at a long side of 1920 (`Player.SIDE`). A 2160p source is
  decoded at 4K and scaled on every shown frame: twice what a 1080p source costs per shown frame (4 ms against 2 ms),
  and three times the decoding of every frame, shown or not (3.1 ms against 1.1 ms). Storing at the shown size removes
  both.
- **8-bit 4:2:0 (`yuv420p`)** unless the content needs more, so the GPU path's first converter covers it.

## `Footage.RUN_SECONDS` and the seek cost overlap

A target more than `RUN_SECONDS` ahead is always sought, and `Footage.skips` also seeks when that's cheaper by its own
measured costs. The fixed 2 s rule may no longer be needed. Left as it was, since it bounds the worst case before any
seek has been measured.

## Unevenly spaced frames that stand for later numbers

Footage converts a frame only if it's wanted when it is decoded. A frame of a variable-rate stream that turns out to
stand for later numbers too (the next frame comes more than one number on) has no chain for them, so they stay
missing and the decoder seeks back for them. That's correct, but slow for such streams. If it matters, ffwrap could
keep the previous decoded frame referenced, so it can still be converted once its span is known.
