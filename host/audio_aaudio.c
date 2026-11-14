/* SPDX-License-Identifier: GPL-2.0-or-later */
/* Android host audio for the guest's Intel HD Audio controller (devices/hda.c):
   AAudio low-latency streams, the counterpart of CoreAudio in audio_mac.c.

   Speaker: an output stream whose data callback pulls S16LE frames straight out
   of the HDA playback ring (hv_hda_pull_playback). Microphone: an input stream
   whose callback pushes into the capture ring (hv_hda_push_capture). Both are
   opened when Windows starts the matching HDA stream (backend start_out/start_in)
   at the rate/channel count Windows programmed, and AAudio resamples if the
   device runs at something else. The microphone stays closed until the app has
   RECORD_AUDIO (hv_audio_set_mic_allowed). */
#include "audio.h"

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>

#define TAG "winvm-audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER;
static HvHda *g_hda;
static AAudioStream *out_stream, *in_stream;
static unsigned out_ch, out_rate, in_ch, in_rate;
static int in_wanted;            /* Windows has a capture stream running */
static int mic_allowed = 0;      /* RECORD_AUDIO granted */

static aaudio_data_callback_result_t out_cb(AAudioStream *s, void *user, void *data, int32_t frames) {
    (void)user;
    int32_t ch = AAudioStream_getChannelCount(s);
    uint32_t bytes = (uint32_t)frames * (uint32_t)ch * 2u;
    uint32_t got = g_hda ? hv_hda_pull_playback(g_hda, data, bytes) : 0;
    if (got < bytes) memset((uint8_t *)data + got, 0, bytes - got); /* underrun: silence, not a stall */
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static aaudio_data_callback_result_t in_cb(AAudioStream *s, void *user, void *data, int32_t frames) {
    (void)user;
    int32_t ch = AAudioStream_getChannelCount(s);
    if (g_hda) hv_hda_push_capture(g_hda, data, (uint32_t)frames * (uint32_t)ch * 2u);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void err_cb(AAudioStream *s, void *user, aaudio_result_t error);

static AAudioStream *open_stream(aaudio_direction_t dir, unsigned ch, unsigned rate) {
    AAudioStreamBuilder *b = NULL;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return NULL;
    AAudioStreamBuilder_setDirection(b, dir);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(b, (int32_t)ch);
    AAudioStreamBuilder_setSampleRate(b, (int32_t)rate);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    if (dir == AAUDIO_DIRECTION_OUTPUT) {
        AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
        AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
        AAudioStreamBuilder_setDataCallback(b, out_cb, NULL);
    } else {
        AAudioStreamBuilder_setInputPreset(b, AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION);
        AAudioStreamBuilder_setDataCallback(b, in_cb, NULL);
    }
    AAudioStreamBuilder_setErrorCallback(b, err_cb, NULL);
    AAudioStream *s = NULL;
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &s);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) {
        LOGW("open %s %u ch @ %u Hz failed: %s", dir == AAUDIO_DIRECTION_OUTPUT ? "output" : "input", ch, rate,
             AAudio_convertResultToText(r));
        return NULL;
    }
    /* Twice the burst: low latency without underruns when a vCPU is late refilling the ring. */
    AAudioStream_setBufferSizeInFrames(s, AAudioStream_getFramesPerBurst(s) * 2);
    if (AAudioStream_requestStart(s) != AAUDIO_OK) {
        AAudioStream_close(s);
        return NULL;
    }
    LOGI("%s stream: %d ch @ %d Hz (asked %u @ %u), burst %d", dir == AAUDIO_DIRECTION_OUTPUT ? "speaker" : "microphone",
         AAudioStream_getChannelCount(s), AAudioStream_getSampleRate(s), ch, rate, AAudioStream_getFramesPerBurst(s));
    return s;
}

static void close_stream(AAudioStream **s) {
    if (!*s) return;
    AAudioStream_requestStop(*s);
    AAudioStream_close(*s);
    *s = NULL;
}

static void open_in_locked(void) {
    if (in_stream || !in_wanted || !mic_allowed) return;
    in_stream = open_stream(AAUDIO_DIRECTION_INPUT, in_ch ? in_ch : 2, in_rate ? in_rate : 48000);
}

/* A device change (headphones, Bluetooth, DeX dock) disconnects the stream; reopen it
   off the callback thread, which is where AAudio wants it done. */
static void *reopen_thread(void *arg) {
    AAudioStream *dead = arg;
    pthread_mutex_lock(&mu);
    if (dead == out_stream) {
        close_stream(&out_stream);
        if (out_ch) out_stream = open_stream(AAUDIO_DIRECTION_OUTPUT, out_ch, out_rate);
    } else if (dead == in_stream) {
        close_stream(&in_stream);
        open_in_locked();
    }
    pthread_mutex_unlock(&mu);
    return NULL;
}

static void err_cb(AAudioStream *s, void *user, aaudio_result_t error) {
    (void)user;
    LOGW("stream error %s, reopening", AAudio_convertResultToText(error));
    pthread_t t;
    if (pthread_create(&t, NULL, reopen_thread, s) == 0) pthread_detach(t);
}

static int hda_start_out(void *opaque, unsigned channels, unsigned rate_hz) {
    (void)opaque;
    if (channels < 1 || channels > 2) channels = 2;
    if (rate_hz < 8000 || rate_hz > 192000) rate_hz = 48000;
    pthread_mutex_lock(&mu);
    if (out_stream && (channels != out_ch || rate_hz != out_rate)) close_stream(&out_stream);
    out_ch = channels;
    out_rate = rate_hz;
    if (!out_stream) out_stream = open_stream(AAUDIO_DIRECTION_OUTPUT, channels, rate_hz);
    int ok = out_stream != NULL;
    pthread_mutex_unlock(&mu);
    return ok ? 0 : -1;
}

static void hda_stop_out(void *opaque) {
    (void)opaque;
    pthread_mutex_lock(&mu);
    close_stream(&out_stream);
    out_ch = 0;
    pthread_mutex_unlock(&mu);
}

static int hda_start_in(void *opaque, unsigned channels, unsigned rate_hz) {
    (void)opaque;
    if (channels < 1 || channels > 2) channels = 2;
    if (rate_hz < 8000 || rate_hz > 192000) rate_hz = 48000;
    pthread_mutex_lock(&mu);
    if (in_stream && (channels != in_ch || rate_hz != in_rate)) close_stream(&in_stream);
    in_ch = channels;
    in_rate = rate_hz;
    in_wanted = 1;
    open_in_locked();
    pthread_mutex_unlock(&mu);
    return 0; /* without the permission Windows simply records silence */
}

static void hda_stop_in(void *opaque) {
    (void)opaque;
    pthread_mutex_lock(&mu);
    in_wanted = 0;
    close_stream(&in_stream);
    pthread_mutex_unlock(&mu);
}

void hv_audio_attach_hda(HvHda *hda) {
    pthread_mutex_lock(&mu);
    g_hda = hda;
    hda->backend.opaque = NULL;
    hda->backend.start_out = hda_start_out;
    hda->backend.stop_out = hda_stop_out;
    hda->backend.start_in = hda_start_in;
    hda->backend.stop_in = hda_stop_in;
    pthread_mutex_unlock(&mu);
}

void hv_audio_set_mic_allowed(int allowed) {
    pthread_mutex_lock(&mu);
    mic_allowed = allowed != 0;
    if (mic_allowed) open_in_locked();
    else close_stream(&in_stream);
    pthread_mutex_unlock(&mu);
}

void hv_audio_shutdown(void) {
    pthread_mutex_lock(&mu);
    close_stream(&out_stream);
    close_stream(&in_stream);
    out_ch = 0;
    in_wanted = 0;
    pthread_mutex_unlock(&mu);
}

void hv_audio_attach(HvVirtioSnd *d) {
    (void)d; /* the HDA controller is the Windows path; virtio-snd has no Windows driver */
}
