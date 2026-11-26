package com.reau.app.dash.video

/**
 * Which AVC encoder makes the video for the dash (the rider's experimental choice, 26/11/2026): [PHONE], the one the phone's own list puts first
 * ([EncoderOrder.names]: the hardware encoder, which is what the app has always used), or [SOFTWARE], the software encoder Android ships with.
 *
 * Why it exists: the two pilots whose dash restarts a minute into the transmission both have the MediaTek hardware encoder, which does not take
 * constant bitrate and makes keyframes 4 to 10 times bigger than the Qualcomm one; the dash decodes H.264 in software, so a bigger frame is more
 * work for it. Asking the same phone for Android's software encoder (which should keep a constant bitrate and tends to make smaller keyframes; whether it does is
 * what the test will show: review of 26/11/2026, S7) tells whether the encoder matters at all.
 * Read when the video opens, so a change counts from the next stream.
 */
enum class VideoEncoder { PHONE, SOFTWARE }
