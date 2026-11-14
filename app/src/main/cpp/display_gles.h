/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef WA_DISPLAY_GLES_H
#define WA_DISPLAY_GLES_H

#include <android/native_window.h>
#include <stdint.h>

/* Present the guest framebuffer on this window (NULL: stop presenting). Any thread.
   A window that is set again (same object) is taken as resized and redrawn. */
void wa_display_set_window(ANativeWindow *w);
/* Stop drawing while the activity is in the background. */
void wa_display_set_paused(int paused);
/* Letterbox colour around the guest display. */
void wa_display_set_background(float r, float g, float b);
/* The aspect fit the presenter uses: the guest image covers sx * view_w by sy * view_h, centred.
   The UI uses the same numbers to map touches to guest coordinates. */
void wa_display_fit(uint32_t fb_w, uint32_t fb_h, uint32_t view_w, uint32_t view_h, float *sx, float *sy);

#endif
