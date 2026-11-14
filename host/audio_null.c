/* SPDX-License-Identifier: GPL-2.0-or-later */
/* No audio backend (plain Linux test builds): Windows still sees its HD Audio
   device, but no host stream is ever opened behind it. */
#include "audio.h"

void hv_audio_attach_hda(HvHda *hda) {
    (void)hda;
}
void hv_audio_shutdown(void) {}
void hv_audio_attach(HvVirtioSnd *d) {
    (void)d;
}
void hv_audio_set_mic_allowed(int allowed) {
    (void)allowed;
}
