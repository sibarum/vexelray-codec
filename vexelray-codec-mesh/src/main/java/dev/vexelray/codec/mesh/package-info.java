/**
 * Geometry, and nothing else: no GUI type. {@link dev.vexelray.codec.mesh.Meshes} turns a file, a resource or bytes in
 * hand, whatever its format, into a {@link dev.vexelray.codec.mesh.Loaded}, whose
 * {@link dev.vexelray.codec.mesh.Mesh} is its vertices, faces and edges and the
 * {@link dev.vexelray.codec.mesh.Attribute}s on them. Blocking: callers run these in a {@code @Job}.
 */
package dev.vexelray.codec.mesh;
