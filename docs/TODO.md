# TODO

Things noticed and not done, each with what was seen and where.

## Video at 60 fps is limited by `Video.next()`, not by decoding

Found while fixing 60 fps playback that showed no frames (2026-11-13). The fix (Player shows the newest made, asks
ahead by the decoder's own latency; Footage keeps the asked frame at once and seeks ahead when that is cheaper)
makes a slow decoder show fewer frames instead of none. It does not make it faster. On a loaded 20-core machine, a 1080p60
AV1 clip (testsrc2, SVT-AV1 preset 10, GOP 321) played at 2 to 38 fps a second, about 15 on average. A 2160p60 clip
showed 1 to 2 fps for its first four seconds, then 17 to 60. 1080p30 held 30.0.

What one frame costs, measured with a single-file bench against `sibarum.ffmpeg.Video`, 1920x1080 out:

| Step | Cost |
| --- | --- |
| `next()`: decode, scale to RGBA, copy 8 MB into a `byte[]` | 10.5 ms idle, 16 to 24 ms with the machine busy |
| a frame passed by an exact seek (decoded, dropped unscaled) | about 1.4 ms |
| `MipChain.of` (open question 7 of vexelray-gui's `device-feed.md`) | 6 to 8 ms |

So stepping forward through frames nobody will see costs nearly as much as showing them, and at 60 fps one `next()`
is about a frame period: a decoder that falls behind can only catch up by seeking, which re-decodes from the keyframe
(up to 450 ms on these clips). Ways out, all in ffmpeg-wrapper:

- **A skip without a seek:** `ffw_skip(us)` setting `skip_until_us` without `av_seek_frame`, so frames up to a time are
  decoded and dropped unscaled from where the decoder is. Footage would use it instead of `next()` for frames outside
  its window, and instead of `skips()`' seeks.
- **Scaling on demand:** decode and convert as two calls, so a frame is only scaled if it is kept.
- **Less copying:** hand the RGBA as a `MemorySegment` the mip chain reads, not a fresh 8 MB `byte[]` per frame.

## 2160p sources scale down on the decoder thread too

The demo opens every clip at a side of 1920 (`Player.SIDE`), so a 2160p clip is decoded at 4K and scaled to 1080p in
`next()`. Same cost model as above, larger. Not measured separately.

## `Footage.RUN_SECONDS` and the seek cost overlap

A target more than `RUN_SECONDS` ahead is always sought, and `Footage.skips` now also seeks when that's cheaper by its own
measured costs. The fixed 2 s rule may no longer be needed. Left as it was, since it bounds the worst case before any
seek has been measured.
