/* SPDX-License-Identifier: GPL-2.0-or-later */
/* JNI surface of libwinandroid.so for com.windowsandroid.VmNative. Thin by
   design: the engine API
   (host/winvm.h) is already thread-safe, so most calls go straight through. */
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "audio.h"
#include "display_gles.h"
#include "hv_os.h"
#include "hvsparse.h"
#include "net_slirp.h"
#include "share.h"
#include "vm_commands.h"
#include "winvm.h"

#define TAG "winvm"
#define JNIFN(ret, name) JNIEXPORT ret JNICALL Java_com_windowsandroid_VmNative_##name

/* ---- stderr -> logcat + a log file the user (or adb) can read ---- */

static FILE *log_file;
static pthread_mutex_t log_mu = PTHREAD_MUTEX_INITIALIZER;

static void *log_pump(void *arg) {
    int fd = (int)(intptr_t)arg;
    char buf[4096];
    size_t have = 0;
    for (;;) {
        ssize_t n = read(fd, buf + have, sizeof buf - 1 - have);
        if (n <= 0) break;
        have += (size_t)n;
        buf[have] = 0;
        char *start = buf, *nl;
        while ((nl = memchr(start, '\n', have - (size_t)(start - buf)))) {
            *nl = 0;
            if (!strncmp(start, "s_gl", 4)) { start = nl + 1; continue; } /* the emulator's GLES driver chatter */
            __android_log_write(ANDROID_LOG_INFO, TAG, start);
            pthread_mutex_lock(&log_mu);
            if (log_file) {
                fputs(start, log_file);
                fputc('\n', log_file);
                fflush(log_file);
            }
            pthread_mutex_unlock(&log_mu);
            start = nl + 1;
        }
        have -= (size_t)(start - buf);
        memmove(buf, start, have);
        if (have == sizeof buf - 1) { /* a very long line: flush it as is */
            __android_log_write(ANDROID_LOG_INFO, TAG, buf);
            have = 0;
        }
    }
    return NULL;
}

JNIEXPORT void JNICALL Java_com_windowsandroid_VmNative_nativeInit(JNIEnv *env, jclass cls, jstring jlog_path) {
    (void)cls;
    static int done;
    if (done) return;
    done = 1;
    const char *path = (*env)->GetStringUTFChars(env, jlog_path, NULL);
    pthread_mutex_lock(&log_mu);
    log_file = fopen(path, "w");
    pthread_mutex_unlock(&log_mu);
    (*env)->ReleaseStringUTFChars(env, jlog_path, path);
    int p[2];
    if (pipe(p) == 0) {
        /* stderr only: the engine logs there; stdout belongs to whatever else is in the process
           (the emulator's GL stack prints to it). */
        setvbuf(stderr, NULL, _IONBF, 0);
        dup2(p[1], STDERR_FILENO);
        close(p[1]);
        pthread_t t;
        if (pthread_create(&t, NULL, log_pump, (void *)(intptr_t)p[0]) == 0) pthread_detach(t);
    }
    fprintf(stderr, "winvm: libwinandroid loaded; host counter %llu Hz, page size %zu\n",
            (unsigned long long)hv_host_freq(), hv_host_page_size());
}

/* ---- lifecycle ---- */

static _Atomic int vm_state; /* 0 idle, 1 created, 2 running, 3 exited */
static _Atomic int vm_exit_reason = -1;

static void on_exit_cb(int reason) {
    atomic_store(&vm_exit_reason, reason);
    atomic_store(&vm_state, 3);
    fprintf(stderr, "winvm: guest exited (%s)\n", hv_winvm_reason_name(reason));
    hv_winvm_flush_disks();
}

static char *dup_jstring(JNIEnv *env, jstring s) {
    if (!s) return NULL;
    const char *c = (*env)->GetStringUTFChars(env, s, NULL);
    char *r = c && *c ? strdup(c) : NULL;
    if (c) (*env)->ReleaseStringUTFChars(env, s, c);
    return r;
}

/* Returns NULL on success or an error message. Paths stay owned by the engine for the process lifetime. */
JNIEXPORT jstring JNICALL Java_com_windowsandroid_VmNative_nativeCreate(JNIEnv *env, jclass cls, jstring code, jstring vars,
                                                                       jstring disk0, jstring disk1, jstring share_dir,
                                                                       jstring share_sock, jint ncpus, jlong ram_mb, jboolean net,
                                                                       jboolean sound, jint stats_interval,
                                                                       jint display_w, jint display_h,
                                                                       jstring dvd0, jstring dvd1, jboolean dvd_boot_keys) {
    (void)cls;
    if (atomic_load(&vm_state) != 0) return (*env)->NewStringUTF(env, "A VM already ran in this process.");
    HvWinVmConfig cfg;
    memset(&cfg, 0, sizeof cfg);
    cfg.code_fd = dup_jstring(env, code);
    cfg.vars_fd = dup_jstring(env, vars);
    cfg.disk0 = dup_jstring(env, disk0);
    cfg.disk1 = dup_jstring(env, disk1);
    cfg.share_dir = dup_jstring(env, share_dir);
    /* The WebDAV server's Unix socket lives in internal storage: shared storage is FUSE,
       which has no sockets (and sun_path is only 108 bytes). */
    cfg.share_sock = dup_jstring(env, share_sock);
    cfg.ncpus = ncpus;
    cfg.ram_mb = (uint64_t)ram_mb;
    cfg.no_net = !net;
    cfg.enable_sound = sound;
    cfg.stats_interval = stats_interval;
    if (display_w > 0 && display_h > 0) {
        cfg.display_width = (uint32_t)display_w;
        cfg.display_height = (uint32_t)display_h;
    }
    cfg.dvd[0] = dup_jstring(env, dvd0);
    cfg.dvd[1] = dup_jstring(env, dvd1);
    cfg.dvd_boot_keys = dvd_boot_keys;
    char err[256] = {0};
    if (hv_winvm_create(&cfg, err, sizeof err) != 0) return (*env)->NewStringUTF(env, err[0] ? err : "VM creation failed.");
    atomic_store(&vm_state, 1);
    return NULL;
}

JNIFN(void, nativeSetBootResolution)(JNIEnv *env, jclass cls, jint w, jint h) {
    (void)env;
    (void)cls;
    if (atomic_load(&vm_state) >= 1) hv_winvm_set_boot_resolution(w > 0 ? (uint32_t)w : 0, h > 0 ? (uint32_t)h : 0);
}

JNIFN(void, nativeStart)(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    int expected = 1;
    if (!atomic_compare_exchange_strong(&vm_state, &expected, 2)) return;
    hv_winvm_start(1, on_exit_cb);
}

JNIFN(jint, nativeState)(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return atomic_load(&vm_state);
}

JNIFN(jint, nativeExitReason)(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    int r = atomic_load(&vm_exit_reason);
    return r;
}

JNIFN(void, nativeStop)(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    if (atomic_load(&vm_state) >= 1) hv_winvm_stop();
}

JNIFN(void, nativeFlushDisks)(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    if (atomic_load(&vm_state) >= 1) hv_winvm_flush_disks();
}

JNIFN(void, nativePowerButton)(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    if (atomic_load(&vm_state) == 2) hv_winvm_power_button();
}

/* ---- display ---- */

JNIFN(void, nativeSetSurface)(JNIEnv *env, jclass cls, jobject surface) {
    (void)cls;
    ANativeWindow *w = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    wa_display_set_window(w);
    if (w) ANativeWindow_release(w); /* the presenter holds its own reference */
}

JNIFN(void, nativeSetDisplayPaused)(JNIEnv *env, jclass cls, jboolean paused) {
    (void)env;
    (void)cls;
    wa_display_set_paused(paused);
}

JNIFN(jlong, nativeDisplaySize)(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    uint32_t w = 0, h = 0;
    if (hv_winvm_display_size(&w, &h) != 0) return 0;
    return (jlong)((uint64_t)w << 32 | h);
}

JNIFN(jboolean, nativeSaveScreenshot)(JNIEnv *env, jclass cls, jstring jpath) {
    (void)cls;
    const char *p = (*env)->GetStringUTFChars(env, jpath, NULL);
    int rc = hv_winvm_save_png(p);
    (*env)->ReleaseStringUTFChars(env, jpath, p);
    return rc == 0;
}

/* ---- input ---- */

JNIFN(void, nativeKeyHid)(JNIEnv *env, jclass cls, jint usage, jboolean down) {
    (void)env;
    (void)cls;
    hv_winvm_key_hid((uint8_t)usage, down);
}

JNIFN(void, nativeKeyLinux)(JNIEnv *env, jclass cls, jint code, jboolean down) {
    (void)env;
    (void)cls;
    hv_winvm_key_linux((uint16_t)code, down);
}

JNIFN(void, nativeTablet)(JNIEnv *env, jclass cls, jdouble x, jdouble y, jint buttons, jint wheel) {
    (void)env;
    (void)cls;
    if (wheel > 127) wheel = 127;
    if (wheel < -127) wheel = -127;
    hv_winvm_tablet(x, y, (uint8_t)buttons, (int8_t)wheel);
}

JNIFN(void, nativeCtrlAltDel)(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    hv_winvm_send_ctrl_alt_del();
}

JNIFN(jboolean, nativeCommand)(JNIEnv *env, jclass cls, jstring jline) {
    (void)cls;
    const char *l = (*env)->GetStringUTFChars(env, jline, NULL);
    int quit = hv_vm_command(l);
    (*env)->ReleaseStringUTFChars(env, jline, l);
    return quit != 0;
}

static volatile int cmd_file_stop;

JNIFN(void, nativeStartCommandFile)(JNIEnv *env, jclass cls, jstring jpath) {
    (void)cls;
    const char *p = (*env)->GetStringUTFChars(env, jpath, NULL);
    hv_vm_command_file_follow(p, &cmd_file_stop); /* never replay a script left from a previous run */
    (*env)->ReleaseStringUTFChars(env, jpath, p);
}

/* ---- status ---- */

/* [frames, jitUsed, jitCap, jitFlushes, wfiTicks, kbdReady, tabReady, usbDelivered, netTx, netRx, counterHz] */
JNIEXPORT jlongArray JNICALL Java_com_windowsandroid_VmNative_nativeStats(JNIEnv *env, jclass cls) {
    (void)cls;
    jlong v[11] = {0};
    if (atomic_load(&vm_state) >= 1) {
        uint64_t used, cap, flushes, wfi;
        hv_winvm_engine_stats(&used, &cap, &flushes, &wfi);
        int kbd, tab;
        uint64_t delivered;
        hv_winvm_usb_stats(&kbd, &tab, &delivered);
        v[0] = (jlong)hv_winvm_display_frames();
        v[1] = (jlong)used;
        v[2] = (jlong)cap;
        v[3] = (jlong)flushes;
        v[4] = (jlong)wfi;
        v[5] = kbd;
        v[6] = tab;
        v[7] = (jlong)delivered;
        v[8] = (jlong)hv_net_tx_frames;
        v[9] = (jlong)hv_net_rx_frames;
    }
    v[10] = (jlong)hv_host_freq();
    jlongArray a = (*env)->NewLongArray(env, 11);
    (*env)->SetLongArrayRegion(env, a, 0, 11, v);
    return a;
}

/* ---- host integration: links / OAuth, clipboard, microphone ---- */

/* Text from and to the guest crosses as real UTF-8 bytes: NewStringUTF/GetStringUTFChars speak
   "modified" UTF-8, which mangles emoji and aborts on malformed input under CheckJNI. */
static jbyteArray bytes_of(JNIEnv *env, char *s) {
    if (!s) return NULL;
    size_t n = strlen(s);
    jbyteArray a = (*env)->NewByteArray(env, (jsize)n);
    if (a) (*env)->SetByteArrayRegion(env, a, 0, (jsize)n, (const jbyte *)s);
    free(s);
    return a;
}

JNIFN(jbyteArray, nativeTakeOpenUrl)(JNIEnv *env, jclass cls) {
    (void)cls;
    return bytes_of(env, hv_share_take_open_url());
}

JNIFN(jboolean, nativeTakeSetupDone)(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return hv_share_take_setup_done() != 0;
}

JNIFN(jlong, nativeDvdBytesRead)(JNIEnv *env, jclass cls, jint index) {
    (void)env;
    (void)cls;
    return atomic_load(&vm_state) >= 1 ? (jlong)hv_winvm_dvd_bytes_read(index) : 0;
}

JNIFN(jboolean, nativeTakeOAuthDone)(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return hv_share_take_oauth_done() != 0;
}

JNIFN(void, nativeSetUrlHandlerEnabled)(JNIEnv *env, jclass cls, jboolean on) {
    (void)env;
    (void)cls;
    hv_share_set_url_handler_enabled(on);
}

JNIFN(void, nativeSetClipboard)(JNIEnv *env, jclass cls, jbyteArray utf8) {
    (void)cls;
    jsize n = (*env)->GetArrayLength(env, utf8);
    char *t = malloc((size_t)n + 1);
    if (!t) return;
    (*env)->GetByteArrayRegion(env, utf8, 0, n, (jbyte *)t);
    t[n] = 0;
    hv_share_set_clipboard(t);
    free(t);
}

JNIFN(jbyteArray, nativeTakeClipboard)(JNIEnv *env, jclass cls) {
    (void)cls;
    return bytes_of(env, hv_share_take_clipboard());
}

JNIFN(void, nativeSetMicAllowed)(JNIEnv *env, jclass cls, jboolean allowed) {
    (void)env;
    (void)cls;
    hv_audio_set_mic_allowed(allowed);
}

/* ---- disk images ---- */

static _Atomic uint64_t unpack_done, unpack_total;

static void unpack_progress(uint64_t done, uint64_t total, void *opaque) {
    (void)opaque;
    atomic_store(&unpack_done, done);
    atomic_store(&unpack_total, total);
}

/* Blocking: call from a background thread. */
JNIFN(jboolean, nativeUnpackSparse)(JNIEnv *env, jclass cls, jstring jsrc, jstring jdst) {
    (void)cls;
    const char *s = (*env)->GetStringUTFChars(env, jsrc, NULL);
    const char *d = (*env)->GetStringUTFChars(env, jdst, NULL);
    atomic_store(&unpack_done, 0);
    atomic_store(&unpack_total, 0);
    int rc = hv_sparse_unpack(s, d, unpack_progress, NULL);
    (*env)->ReleaseStringUTFChars(env, jsrc, s);
    (*env)->ReleaseStringUTFChars(env, jdst, d);
    return rc == 0;
}

JNIFN(jdouble, nativeUnpackProgress)(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    uint64_t t = atomic_load(&unpack_total);
    return t ? (jdouble)atomic_load(&unpack_done) / (jdouble)t : 0.0;
}

/* ---- environment (engine knobs, SLIRP_RESOLV_CONF) ---- */

JNIFN(void, nativeSetEnv)(JNIEnv *env, jclass cls, jstring jname, jstring jvalue) {
    (void)cls;
    const char *n = (*env)->GetStringUTFChars(env, jname, NULL);
    const char *v = (*env)->GetStringUTFChars(env, jvalue, NULL);
    setenv(n, v, 1);
    (*env)->ReleaseStringUTFChars(env, jname, n);
    (*env)->ReleaseStringUTFChars(env, jvalue, v);
}
