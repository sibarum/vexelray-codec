# vexelray-codec

Media files on a VexelRay GUI. A wrapper does the decoding; this is the VexelRay-specific half of each codec: getting
what it decoded onto the GPU and into a tree.

An **optional component**. Applications, demos and the suite apps depend on it. Nothing in `vexelray-framework` or
`vexelray-gui` does, so a binary that shows no pictures links no decoder.

```
mvn install        # build, test, and put it in the local Maven repository
```

Needs the VexelRay stack (through `vexelray-framework`) and `imagelib-wrapper` installed to the local repository first.
The image tests decode for real, so they fail rather than skip when imagelib's native library is missing.

## Modules

```
vexelray-codec-image     pictures, through imagelib-wrapper: PNG, JPEG, GIF, WebP, BMP, ICO, TIFF, TGA, PNM, QOI,
                         SVG; animated where the file is.
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

## Platforms

Every module here is portable Java. Native code belongs to the wrappers: `imagelib-wrapper` bundles its library per
platform under `natives/<os>-<arch>/` and loads the host's. If a codec ever needs something per OS that its wrapper
cannot carry, it goes in a `vexelray-codec-<kind>-<os>` module per OS (Windows, Linux and macOS from day one, the
unbuilt ones stubs), selected by an OS-activated profile in the *application*, as `vexelray/docs/native-bindings.md`
§3.1 describes, never in this parent.

### In a native image

The decoder's FFM metadata comes with `imagelib-wrapper`'s jar. Linking imagelib in, so the executable is the only
file, is still the application's: see `imagelib-wrapper`'s README, *In a native image*, and `Pix/pom.xml`.
