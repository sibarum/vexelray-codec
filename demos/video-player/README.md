# video-player-demo

The smallest application that plays a video: one `VideoView` filling a window, looping, opened with
`Videos.open(path, 1920)` on `gui.offload()` once `ImageStarter`'s textures have the device. It is here to see
`vexelray-codec-video` on a real GPU and to drive it with `ottermate`, which the module's headless tests cannot do.

Not a module of `vexelray-codec`: installing the library never builds an application. Install the codec modules first
(`mvn install` in the parent), then:

```
mvn compile exec:exec "-Dapp.args=C:/clips/holiday.mp4"
```

| Key | Does |
| --- | --- |
| Space | pause, or play on |
| ← / → | five seconds back or on |
| Home | the start |

## Driving it

Two readouts, by landmark: `player.state` says what was opened and whether it plays (`H.264 video · 1920 × 1080 ·
1:30 · playing`), and `player.stats` how many distinct frames reached the plate in the last second and where the clock
is (`shown 59.9 fps · 0:40 / 1:30`). The timeline never goes quiet while a video plays, so `settle` times out: wait on
a readout with `await` instead.

```
# play.txt
await player.state playing
await player.stats · 0:05 /
shot C:/tmp/at-5s.png
key SPACE
await player.stats shown 0.0 fps
shot C:/tmp/paused.png
```

```
ottermate --script play.txt --launch mvn.cmd compile exec:exec -Dautomation=0 "-Dapp.args=C:/clips/holiday.mp4"
```

An `await` gives up after 30 s, so wait for a clock time at most that far ahead. `shown 0.0 fps` rather than
`0.0 fps`, which `30.0 fps` also contains.

The fps readout polls `VideoView.shownMicros` every millisecond, so it can miss a frame now and then. For an exact count
use the probe beside the log (`target/logs/video-player-demo-probe.csv`): each frame the window drew is a
`frame.present` row without a `draw skipped` row before it.
