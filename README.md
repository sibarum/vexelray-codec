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
                         and the rest; played on a node, a frame uploaded as each falls due, or as a picture that
                         plays, with its sound.
vexelray-codec-mesh      meshes, in plain Java: OBJ, PLY (ASCII and binary), STL (ASCII and binary) and OFF, read
                         into one Mesh. Geometry only: no GUI type yet.
vexelray-codec-asset     the stack's own asset file, .vxa: meshes, point clouds, volumes, 2D and 3D textures,
                         distance fields, compiled shadelet compositions and looks, in one intrabuf payload.
```

### vexelray-codec-image

```
dev.vexelray.codec.image        pixels, and no GUI type. Blocking: run on Gui.offload().
  Pictures                      a file, a class-path resource or bytes in hand, at each size asked for, from one
                                decode. A failure is a reason, never an exception.
  Decoded                       what Pictures returns: what the source is, and a Picture per size, or why not.
  Picture                       every frame packed into one RGBA8 sheet, scaled down and never up; or, for a file
                                that plays, its first frame and its Motion.
  Motion                        that a picture is the first frame of a file that plays: its frames, length and loops,
                                and the Source that opens it (a Movie by default; a video's Footage); and whether,
                                fitted, it fills its stage beyond its own pixels (a video does; an animation never).
  Playable                      frames by number, each with its time, made ahead on a thread of their own: what a
                                Playback plays. Movie is one; vexelray-codec-video's Footage is another. One may keep
                                a Clock of its own (a video's sound), which Playback then keeps time by.
  Movie                         a file that plays, made a frame at a time on its own thread, each scaled and given
                                its mip chain there; the frames made kept within a budget, so seeking back is cheap.
  Original                      a still at its own full size, any part of which is made at any size: a raster
                                resampled, a vector rasterised again. One at a time: a photograph's is large.
  MaskField                     an SVG mask rasterised finely and made into a signed distance field by vexelray-canvas's
                                DistanceField. One small texture is the shape at any size (Canvas.field).
  Resample                      any ImageFilter (vexelray-canvas), exact at any scale, in linear light, premultiplied.
  Stamp                         which version of a file: the key for anything cached from it.
  Lru                           a cache bounded by weight, least recently used out first.

dev.vexelray.codec.image.gui    on vexelray-gui.
  Textures                      pictures on the GPU: uploaded once, recent ones kept, the rest to GuiApp.release.
  ImageView                     one picture on a node: fitted (never larger than its own pixels, unless it is a
                                vector or a video, which fill the stage: Picture.enlarges), animated, sliding in from
                                the side it came from, over a backdrop (dark, light, checker, magenta, green) for its
                                transparent pixels, and posed by its caller: zoomed, moved and turned (Pose), as
                                visual transforms.
  Playback                      how a picture that plays is played: play, pause, seek, step, loop; paced by krono,
                                its frames streamed through a GuiApp.stream, and held while the window is unfocused.
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

**A file that plays is a movie, not a sheet.** `Pictures.of(path, ...)` decodes only the first frame of an animated GIF,
WebP or APNG and gives it a `Motion`; `ImageView` shows that frame, opens a `Movie` from the file, and plays it
through a `StreamTexture`, a few slots made once and written in turn, so a frame costs a copy and no texture. Its length
and size are the file's business: one canvas is decoded, a few frames are made ahead, and the frames already made are
kept up to `Movie.BUDGET`. `ImageView.playback()` is the transport. Bytes in hand and resources, having no file to play
from, are still packed whole into a sheet. What plays is whatever the motion's `Source` opens, a `Playable`, so a video
plays through the same `Playback` (below).

### vexelray-codec-video

```
dev.vexelray.codec.video        frames, and no GUI type. Videos blocks: run it on Gui.offload().
  Videos                        a file opened at the size it will be shown, or its poster frame as a Picture (a
                                Decoded, as Pictures gives), a tenth of the way in; or, asked for one that plays, its
                                first frame with a Motion that opens a Footage. A failure is a reason.
  Opened                        what Videos.open returns: the Clip, or why not.
  Clip                          an open file and its decoder, frames scaled down to a side and never up.
  Reel                          a Clip decoded a few frames ahead on a thread of its own. The frame due at a time,
                                the late ones dropped; a seek replaces the queue. Owns and closes the clip.
  Footage                       a Clip as a Playable: frames by number at the stream's rate, each given its mip chain
                                on its own thread, a few ahead and the rest kept within a budget; forward by decoding,
                                anywhere else by an exact seek. What plays a video on an ImageView. Opens the
                                file's sound too, when there is one and a speaker, and gives it as its Clock.
  Sound                         (package-private) the sound as that Clock: the time being heard, and by the wall
                                past the sound's end.

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

**Or as a picture that plays.** `Videos.poster(path, side, true)` is the video's first frame carrying a `Motion` whose
source is `Footage.SOURCE`, so an `ImageView` plays it exactly as it plays an animated GIF: `Playback`'s transport,
stepping a frame at a time, looping, holding while the window is unfocused, and posed, zoomed and turned with the
picture. One difference: fitted, a video fills its stage, as a player shows it, where a still or a GIF smaller than
the stage is shown at its own size; the motion says so (`Motion.fills`, read through `Picture.enlarges`), so the
viewer needs nothing from this module to tell them apart. The frames go through a `StreamTexture` rather than a
texture each. A viewer that shows pictures and videos side by side (Pix) wants this; a player with nothing but video
can keep `VideoView`.

**With its sound.** A picture that plays a video plays its sound too: `Footage.open` opens the file's sound on the
speakers (`ffmpeg-wrapper`'s `Audio`), and gives it to `Playback` as the playable's own clock (`Playable.clock`). The
frames then follow the sound: the shred shows the frame the sound being heard has reached, rather than counting time
itself, because a speaker plays at its own pace and a picture can always be shown a little early or late. The sound
starts with the first frame, stops with every pause, hold, step and end, and goes wherever a seek or a loop sends the
pictures. Where the sound ends before the pictures, or the device is lost, the clock runs on by the wall. A file with no
sound, or a machine with no sound device, plays silently on `Playback`'s own clock, as before. The system property
`vexelray.sound` says where sound goes: `speakers` (the default), `nowhere` (played in real time to no device, the
clock and all: what the build's tests use, so a test run is quiet), or `off`. `VideoView` plays pictures only.

Not yet: the display matrix's **rotation** (phone video shot upright plays on its side), and a **native image**
(FFmpeg is shared libraries, so they would ship beside the executable). See `ffmpeg-wrapper`'s README.

### vexelray-codec-mesh

```
dev.vexelray.codec.mesh         geometry, and no GUI type. Blocking: run on Gui.offload().
  Meshes                        a file, a class-path resource or bytes in hand, its format told from the bytes. A
                                failure is a reason, never an exception: "malformed OBJ: line 12: ...".
  Loaded                        what Meshes returns: what the source is, and its Mesh, or why not.
  Mesh                          vertices, faces and edges, indexed, as the file has them: polygons of any size, not
                                triangulated, welded or reordered. edges() are the ones the file lists; wireframe()
                                is every distinct edge, faces' sides included, once.
  Attribute                     data on the mesh: a name, a scope, and a fixed number of floats each. Position,
                                normal, uv and color by those names whatever the format calls them; anything else
                                (a PLY property "confidence") by the file's own name.
```

```java
Loaded l = Meshes.of(Path.of("bunny.ply"));
if (l.geometry()) {
    Mesh m = l.mesh();
    float[] xyz = m.positions();                       // three a vertex
    for (int f = 0; f < m.faceCount(); f++) {
        int[] corners = m.face(f);                     // in order around the face
    }
    Attribute normal = m.attribute(Attribute.NORMAL);  // per vertex, or per corner where a vertex has several
}
```

**An attribute has a scope.** `VERTEX`, `CORNER`, `FACE` or `EDGE`: one value per vertex, per face corner (in
`Mesh.corners()` order), per face or per listed edge. OBJ indexes uvs and normals apart from positions, so a seam gives
one vertex two uvs; those land on the corners and the vertex stays one vertex, its edges still shared. Where every face
agrees, they are on the vertex. A colour is in `[0,1]` whatever the file stored; a value the file did not give is
`NaN`.

**As the file says it.** No units, axes or handedness are assumed or converted, since none of these formats records
them. The one change is STL's: it has no shared vertices, so corners that are the same point bit for bit are made one.

Not yet: OBJ groups, objects and materials (read past), PLY list properties other than faces' indices and
`texcoord`, glTF, and a GUI half that draws a `Mesh` through `vexelray-technique-mesh`.

### vexelray-codec-asset

```
dev.vexelray.codec.asset        the asset file, and no GUI type. Blocking: run on Gui.offload().
  Assets                        an Asset written to a file (whole or not at all) or bytes, and read back from a file,
                                bytes in hand or a resource, binary or text. A failure is a reason, never an exception.
  Unpacked                      what Assets.read returns: what the file is, and its Asset, or why not.
  Asset                         the file's root: a name, properties, and a list of each kind of item below.
  MeshData                      a Mesh (vexelray-codec-mesh): indices, and the data on vertices, corners, faces, edges.
  PointCloud                    points and the channels on them.
  Volume                        a regular 3D grid placed in space, and its channels: densities, a signed distance.
  Texture                       2D or 3D, a named texel format, level 0 and any mips.
  DistanceField                 a canvas distance field, one channel kept of the four the GPU gets (or three, or four,
                                for MSDF and MTSDF), packed.
  Shader                        a compiled shadelet composition: SPIR-V per host and stage (ShaderStage), and its
                                manifest (ShadeletInfo, StyleField), against which a look is checked.
  Look                          how an item is drawn: a source, modifiers and painters (ShadeletUse), with their floats.
  Channel                       a named array of numbers of a named type, n to an element.
  Packed                        every array's bytes: raw, deflated, or regrouped by byte and deflated.
```

```java
Asset asset = Asset.builder("ship")
        .property("units", "metres")
        .mesh(MeshData.of("hull", Meshes.of(Path.of("hull.ply")).mesh()))
        .field(DistanceField.ofRgba("badge", field.rgba(), field.inner(), field.spread()))
        .build();
Assets.write(asset, Path.of("ship.vxa"));

Unpacked u = Assets.read(Path.of("ship.vxa"));
if (u.readable()) {
    Mesh hull = u.asset().meshes().getFirst().toMesh();
}
```

**On disk**, a file is eight bytes of signature (`89 56 58 41 0D 0A 1A 0A`: `VXA` between the bytes that catch a
7-bit channel and a line-ending rewrite, as PNG's do) and one intrabuf payload of `Asset`. `Assets.toText` writes the
same asset in intrabuf's text format, for reading and diffing, and `Assets.read` takes either.

**Files outlive the build that wrote them, both ways.** Every schema is intrabuf FULL, so a newer reader opens an older
file (the fields it predates empty) and an older reader opens a newer one (skipping fields it does not know, nested
ones by length). That holds only while schemas grow at the end: a new field or a new kind of item goes last, with
`@Field(since = n)` and the minor bumped, and nothing is removed, reordered or retyped. The module builds release
payloads even at a snapshot version (`-Aintrabuf.snapshot=false`), since a snapshot payload is refused by any build
whose schema differs, which is right for messages and wrong for files. `CompatibilityTest` holds both directions: a
later schema stood in for by `FutureAsset`, and `golden/sampler-1.0.vxa`, the first version's file, which is never
rewritten.

**Vocabularies are names, not enums**: a `Packed` codec, a `Channel` type, a `Texture` format, a `DistanceField` kind.
An enum ordinal a reader does not know fails the whole decode; a name it does not know fails one item, whose `known()`
says so, and the rest of the file reads.

**Packing.** `Packed.of` keeps whichever is smallest of the raw bytes, their deflate, and (for 2-, 4- or 8-byte
elements) their bytes regrouped by position and then deflated, which compresses floats far better than deflate alone.

Not yet: streaming (a file is read whole, so 2 GB at most), sparse volumes, cube-map and array textures, materials and
scenes, and vastir source for shadelets (only compiled compositions and looks).

## Platforms

Every module here is portable Java, and the mesh and asset modules have no native code at all. Native code belongs to the wrappers: `imagelib-wrapper` and `ffmpeg-wrapper` each
bundle their libraries per platform under `natives/<os>-<arch>/` and load the host's. `ffmpeg-wrapper` has Windows
built, Linux scripted and macOS not yet. If a codec ever needs something per OS that its wrapper
cannot carry, it goes in a `vexelray-codec-<kind>-<os>` module per OS (Windows, Linux and macOS from day one, the
unbuilt ones stubs), selected by an OS-activated profile in the *application*, as `vexelray/docs/native-bindings.md`
§3.1 describes, never in this parent.

### In a native image

The decoder's FFM metadata comes with `imagelib-wrapper`'s jar. Linking imagelib in, so the executable is the only
file, is still the application's: see `imagelib-wrapper`'s README, *In a native image*, and `Pix/pom.xml`.
