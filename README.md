# vexelray-codec

Media files on a VexelRay GUI. A wrapper does the decoding; this is the VexelRay-specific half of each codec: getting
what it decoded onto the GPU and into a tree.

An **optional component**. Applications, demos and the suite apps depend on it. Nothing in `vexelray-framework` or
`vexelray-gui` does, so a binary that shows no pictures links no decoder.

```
mvn install        # build, test, and put it in the local Maven repository
```

Needs the VexelRay stack (through `vexelray-framework`), `imagelib-wrapper` and `ffmpeg-wrapper` installed to the local
repository first. The image and video tests decode for real, so they fail rather than skip when a wrapper's native
library is missing.

## Modules

```
vexelray-codec-image     pictures, through imagelib-wrapper: PNG, JPEG, GIF, WebP, BMP, ICO, TIFF, TGA, PNM, QOI,
                         SVG; animated where the file is.
vexelray-codec-video     videos, through ffmpeg-wrapper: H.264, HEVC, VP9, AV1 and the rest, in MP4, MKV, WebM, MOV
                         and the rest; played on a node, a frame uploaded as each falls due. Video only, no sound yet.
```

### vexelray-codec-image

```
dev.vexelray.codec.image        pixels, and no GUI type. Blocking: run on Gui.offload().
  Pictures                      a file, a class-path resource or bytes in hand, at each size asked for, from one
                                decode. A failure is a reason, never an exception.
  Decoded                       what Pictures returns: what the source is, and a Picture per size, or why not.
  Picture                       every frame packed into one RGBA8 sheet, scaled down and never up.
  Original                      a still at its own full size, any part of which is made at any size: a raster
                                resampled, a vector rasterised again. One at a time: a photograph's is large.
  MaskField                     an SVG mask rasterised finely and made into a signed distance field by vexelray-canvas's
                                DistanceField. One small texture is the shape at any size (Canvas.field).
  Resample                      any ImageFilter (vexelray-canvas), exact at any scale, in linear light, premultiplied.
  Stamp                         which version of a file: the key for anything cached from it.
  Lru                           a cache bounded by weight, least recently used out first.

dev.vexelray.codec.image.gui    on vexelray-gui.
  Textures                      pictures on the GPU: uploaded once, recent ones kept, the rest to GuiApp.release.
  ImageView                     one picture on a node: fitted, animated, sliding in from the side it came from,
                                over a backdrop (dark, light, checker, magenta, green) for its transparent pixels,
                                and posed by its caller: zoomed, moved and turned (Pose), as visual transforms.
  ImageStarter                  for a framework application: Textures, bound to the window and unbound at shutdown.
```

In an application:

```java
@VexelApp(name = "gallery", title = "Gallery", starters = ImageStarter.class)
final class GalleryApp { }

@Configuration
final class Recipes {
    @Provides
    ImageView picture(Gui gui, KronoGui krono, Textures textures) {
        ImageView view = new ImageView(gui, krono, textures, Length.ZERO, Length.ZERO);
        gui.offload().execute(() -> {
            Decoded d = Pictures.resource(Recipes.class, "/splash.svg", 2048);
            if (d.image()) {
                view.show(d.picture());
            }
        });
        return view;
    }
}
```

An application that wants another budget writes its own `@Provides Textures`, and the starter's backs off.

### vexelray-codec-video

```
dev.vexelray.codec.video        frames, and no GUI type. Videos blocks: run it on Gui.offload().
  Videos                        a file opened at the size it will be shown, or its poster frame as a Picture (a
                                Decoded, as Pictures gives), a tenth of the way in. A failure is a reason.
  Opened                        what Videos.open returns: the Clip, or why not.
  Clip                          an open file and its decoder, frames scaled down to a side and never up.
  Reel                          a Clip decoded a few frames ahead on a thread of its own. The frame due at a time,
                                the late ones dropped; a seek replaces the queue. Owns and closes the clip.

dev.vexelray.codec.video.gui    on vexelray-gui.
  VideoView                     one clip on a node, fitted and enlarged to its stage, paced by a krono shred: play,
                                pause, resume, exact seek, loop, onEnd. A texture per frame (Textures.upload), the one
                                before given back (Textures.release), one upload in flight at a time.
```

Video uses the image module's `Textures`, so an application that plays videos names `ImageStarter` and no starter of
its own:

```java
@Provides
VideoView player(Gui gui, KronoGui krono, Textures textures) {
    VideoView view = new VideoView(gui, krono, textures, Length.ZERO, Length.ZERO);
    gui.offload().execute(() -> {
        Opened o = Videos.open(Path.of("clip.mp4"), 1920);
        if (o.video()) {
            view.play(o.clip());       // the view owns the clip now
        }
    });
    return view;
}
```

A texture is never rewritten (`GuiApp.texture`), so a frame on screen is a texture made for it and given back after
the next one lands. At 1080p and 30 fps that is about 250 MB a second through staging. That works, but it is the cost
to watch: open a clip at the side it is shown at, not at its own size. A GPU-side YUV path, uploading planes and
converting in the shader, would cut it by more than half and needs nothing new from the wrapper.

Not yet: **sound** (the stack has no audio output, and the wrapper only reports that a file has some), the display
matrix's **rotation** (phone video shot upright plays on its side), and a **native image** (FFmpeg is shared libraries,
so they would ship beside the executable). See `ffmpeg-wrapper`'s README.

## Platforms

Every module here is portable Java. Native code belongs to the wrappers: `imagelib-wrapper` and `ffmpeg-wrapper` each
bundle their libraries per platform under `natives/<os>-<arch>/` and load the host's. `ffmpeg-wrapper` has Windows
built, Linux scripted and macOS not yet. If a codec ever needs something per OS that its wrapper
cannot carry, it goes in a `vexelray-codec-<kind>-<os>` module per OS (Windows, Linux and macOS from day one, the
unbuilt ones stubs), selected by an OS-activated profile in the *application*, as `vexelray/docs/native-bindings.md`
§3.1 describes, never in this parent.

### In a native image

The decoder's FFM metadata comes with `imagelib-wrapper`'s jar. Linking imagelib in, so the executable is the only
file, is still the application's: see `imagelib-wrapper`'s README, *In a native image*, and `Pix/pom.xml`.
