/**
 * The stack's asset file, and no GUI type. {@link dev.vexelray.codec.asset.Assets} writes and reads an
 * {@link dev.vexelray.codec.asset.Asset}: meshes, point clouds, volumes, textures, distance fields, compiled shadelet
 * compositions and looks, each kind a list, every array a {@link dev.vexelray.codec.asset.Packed}. Encoded with
 * intrabuf, every schema FULL. Blocking: callers run these on {@code Gui.offload()}.
 */
package dev.vexelray.codec.asset;
