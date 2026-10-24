/**
 * Videos recorded from frames as they are shown, and no GUI type: a {@link dev.vexelray.codec.record.Recorder} encodes
 * frames to AV1 on a thread of its own, through {@code ffmpeg-wrapper-record}, which holds the encoders and no
 * decoder.
 */
package dev.vexelray.codec.record;
