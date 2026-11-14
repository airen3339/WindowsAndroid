/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef HV_AUDIO_H
#define HV_AUDIO_H

#include "hda.h"
#include "virtio_snd.h"

/* Host audio for the guest's Intel HD Audio duplex controller (speaker + microphone).
   Darwin: CoreAudio (host/audio_mac.c). Android: AAudio (host/audio_aaudio.c). */
void hv_audio_attach_hda(HvHda *hda);
void hv_audio_shutdown(void);

/* Legacy virtio-snd attachment retained for compatibility. */
void hv_audio_attach(HvVirtioSnd *d);

/* Microphone gate: the host only opens its input once the user allows it (Android
   RECORD_AUDIO). Default on, where the host has no such gate. */
void hv_audio_set_mic_allowed(int allowed);

#endif
