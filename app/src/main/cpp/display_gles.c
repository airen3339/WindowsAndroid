/* SPDX-License-Identifier: GPL-2.0-or-later */
/* Presents the guest framebuffer (ramfb, XRGB8888 in guest RAM) on an Android
   Surface with OpenGL ES 3 — the Android counterpart of devices/fb_metal.m.

   One render thread owns the EGL context. Each vsync it samples the guest
   framebuffer in 16-row bands, uploads only the bands that changed (plus a full
   refresh every 250 ms, for changes too small for the sampling to see), and
   draws one aspect-fitted quad. The BGRX -> RGBA swizzle happens in the shader,
   so the upload is a straight copy out of guest memory. When nothing changed
   it does not draw at all, which keeps an idle Windows desktop nearly free. */
#include "display_gles.h"

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <android/native_window.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "winvm.h"

#define TAG "winvm-display"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#define BAND 16
#define MAX_BANDS (8192 / BAND)

static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t cv = PTHREAD_COND_INITIALIZER;
static ANativeWindow *pending_win; /* what the UI handed us (acquired), or NULL */
static int win_changed;
static int thread_started;
static volatile int paused;
static float clear_rgb[3] = {0.0f, 0.0f, 0.0f};

static uint64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u;
}

/* ---- GL state, render thread only ---- */
static EGLDisplay dpy = EGL_NO_DISPLAY;
static EGLContext ctx = EGL_NO_CONTEXT;
static EGLSurface surf = EGL_NO_SURFACE;
static EGLConfig cfg;
static GLuint prog, tex, vao;
static GLint u_scale;
static uint32_t tex_w, tex_h;
static uint64_t band_hash[MAX_BANDS];
static ANativeWindow *cur_win;

static const char *VS =
    "#version 300 es\n"
    "uniform vec2 u_scale;\n"
    "out vec2 v_uv;\n"
    "void main() {\n"
    "  vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));\n"
    "  v_uv = vec2(p.x, 1.0 - p.y);\n"
    "  gl_Position = vec4((p * 2.0 - 1.0) * u_scale, 0.0, 1.0);\n"
    "}\n";
static const char *FS =
    "#version 300 es\n"
    "precision mediump float;\n"
    "uniform sampler2D u_tex;\n"
    "in vec2 v_uv;\n"
    "out vec4 o;\n"
    "void main() { o = vec4(texture(u_tex, v_uv).bgr, 1.0); }\n";

static GLuint compile(GLenum type, const char *src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(s, sizeof log, NULL, log);
        LOGW("shader: %s", log);
    }
    return s;
}

static int gl_init(void) {
    dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (dpy == EGL_NO_DISPLAY || !eglInitialize(dpy, NULL, NULL)) return -1;
    const EGLint attrs[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RED_SIZE, 8,
                            EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE};
    EGLint n = 0;
    if (!eglChooseConfig(dpy, attrs, &cfg, 1, &n) || n < 1) return -1;
    const EGLint cattrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, cattrs);
    return ctx == EGL_NO_CONTEXT ? -1 : 0;
}

static void gl_objects(void) {
    prog = glCreateProgram();
    glAttachShader(prog, compile(GL_VERTEX_SHADER, VS));
    glAttachShader(prog, compile(GL_FRAGMENT_SHADER, FS));
    glLinkProgram(prog);
    u_scale = glGetUniformLocation(prog, "u_scale");
    glGenVertexArrays(1, &vao);
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    tex_w = tex_h = 0;
}

static void drop_surface(void) {
    if (surf != EGL_NO_SURFACE) {
        eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(dpy, surf);
        surf = EGL_NO_SURFACE;
    }
}

/* Leaving a window for another one (the phone's screen for an external display): blank it
   first, or its last frame stays on screen under the trackpad. */
static void blank_and_drop_surface(void) {
    if (surf != EGL_NO_SURFACE) {
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        eglSwapBuffers(dpy, surf); /* fails harmlessly if the window is already going away */
    }
    drop_surface();
}

static int attach_surface(ANativeWindow *w) {
    drop_surface();
    surf = eglCreateWindowSurface(dpy, cfg, w, NULL);
    if (surf == EGL_NO_SURFACE || !eglMakeCurrent(dpy, surf, surf, ctx)) {
        LOGW("eglCreateWindowSurface/MakeCurrent failed: 0x%x", eglGetError());
        drop_surface();
        return -1;
    }
    eglSwapInterval(dpy, 1);
    static int objects;
    if (!objects) {
        gl_objects();
        objects = 1;
    }
    memset(band_hash, 0, sizeof band_hash);
    tex_w = tex_h = 0;
    return 0;
}

static uint64_t hash_band(const uint8_t *px, uint32_t w, uint32_t stride, uint32_t y0, uint32_t y1) {
    uint64_t h = 0x9e3779b97f4a7c15ull ^ y0;
    uint32_t qwords = w / 2; /* two pixels per qword */
    for (uint32_t y = y0; y < y1; y += 4) {
        const uint64_t *row = (const uint64_t *)(px + (size_t)y * stride);
        for (uint32_t x = (y >> 2) & 1; x < qwords; x += 2) h = (h ^ row[x]) * 0x100000001b3ull;
    }
    return h | 1;
}

void wa_display_fit(uint32_t fb_w, uint32_t fb_h, uint32_t view_w, uint32_t view_h, float *sx, float *sy) {
    *sx = *sy = 1.0f;
    if (!fb_w || !fb_h || !view_w || !view_h) return;
    float dst = (float)view_w / (float)view_h, src = (float)fb_w / (float)fb_h;
    if (dst > src) *sx = src / dst;
    else *sy = dst / src;
}

/* Present. A lost surface (window going away under us, or broken for good) is dropped so the
   loop waits for a new window instead of spinning on a swap that fails at once. */
static int swap(void) {
    if (eglSwapBuffers(dpy, surf)) return 1;
    EGLint e = eglGetError();
    LOGW("eglSwapBuffers failed: 0x%x", e);
    if (e == EGL_BAD_SURFACE || e == EGL_BAD_NATIVE_WINDOW || e == EGL_CONTEXT_LOST) drop_surface();
    return 0;
}

/* One frame. Returns 1 if it drew (-1 if presenting failed) (and swapped, which waited for vsync). */
static int frame(int force) {
    HvFbDisplay *fb = hv_winvm_fb();
    const uint8_t *px = NULL;
    uint32_t w = 0, h = 0, stride = 0;
    /* The window's size, not eglQuerySurface's: after a rotation EGL reports the old size until
       the next swap, and the first frame would be laid out for the wrong orientation. */
    EGLint sw = cur_win ? ANativeWindow_getWidth(cur_win) : 0, sh = cur_win ? ANativeWindow_getHeight(cur_win) : 0;
    if (sw <= 0 || sh <= 0) {
        eglQuerySurface(dpy, surf, EGL_WIDTH, &sw);
        eglQuerySurface(dpy, surf, EGL_HEIGHT, &sh);
    }
    int have = fb && hv_fb_display_get_frame(fb, &px, &w, &h, &stride) == 0 && h <= 8192;
    if (!have) {
        if (!force) return 0;
        glViewport(0, 0, sw, sh);
        glClearColor(clear_rgb[0], clear_rgb[1], clear_rgb[2], 1);
        glClear(GL_COLOR_BUFFER_BIT);
        return swap() ? 1 : -1;
    }

    int resized = w != tex_w || h != tex_h;
    if (resized) {
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, (GLsizei)w, (GLsizei)h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
        tex_w = w;
        tex_h = h;
        memset(band_hash, 0, sizeof band_hash);
        force = 1;
    }

    /* Dirty bands -> one contiguous row range to upload. */
    uint32_t nb = (h + BAND - 1) / BAND, lo = nb, hi = 0;
    for (uint32_t b = 0; b < nb; b++) {
        uint32_t y0 = b * BAND, y1 = y0 + BAND < h ? y0 + BAND : h;
        uint64_t v = hash_band(px, w, stride, y0, y1);
        if (force || v != band_hash[b]) {
            band_hash[b] = v;
            if (b < lo) lo = b;
            hi = b + 1;
        }
    }
    if (lo >= hi && !force) return 0;

    glBindTexture(GL_TEXTURE_2D, tex);
    if (lo < hi) {
        uint32_t y0 = lo * BAND, y1 = hi * BAND < h ? hi * BAND : h;
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, (GLint)(stride / 4));
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, (GLint)y0, (GLsizei)w, (GLsizei)(y1 - y0), GL_RGBA, GL_UNSIGNED_BYTE,
                        px + (size_t)y0 * stride);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    }

    float sx, sy;
    wa_display_fit(w, h, (uint32_t)sw, (uint32_t)sh, &sx, &sy);
    glViewport(0, 0, sw, sh);
    glClearColor(clear_rgb[0], clear_rgb[1], clear_rgb[2], 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(prog);
    glUniform2f(u_scale, sx, sy);
    glBindVertexArray(vao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (!swap()) return -1;
    hv_fb_count_presented(fb);
    return 1;
}

static int dropped_gen, drop_req_gen; /* set_window(NULL) waits until the thread let go of the window */

static void *render_thread(void *unused) {
    (void)unused;
    pthread_setname_np(pthread_self(), "winvm-display");
    if (gl_init() != 0) {
        LOGW("EGL init failed: 0x%x", eglGetError());
        return NULL;
    }
    ANativeWindow *cur = NULL;
    uint64_t last_full = 0, last_change = 0;
    for (;;) {
        pthread_mutex_lock(&mu);
        while (!win_changed && (!cur || paused)) pthread_cond_wait(&cv, &mu);
        if (win_changed) {
            win_changed = 0;
            int fresh = cur != pending_win;
            if (fresh) {
                blank_and_drop_surface();
                if (cur) ANativeWindow_release(cur);
                cur = pending_win;
                if (cur) ANativeWindow_acquire(cur);
                cur_win = cur;
            }
            dropped_gen = drop_req_gen; /* whatever the UI took away, we no longer touch */
            pthread_cond_broadcast(&cv);
            pthread_mutex_unlock(&mu);
            if (cur && (fresh || surf == EGL_NO_SURFACE)) {
                if (attach_surface(cur) == 0)
                    LOGI("presenting on %dx%d window", ANativeWindow_getWidth(cur), ANativeWindow_getHeight(cur));
            }
            if (surf != EGL_NO_SURFACE) frame(1); /* new window, or the same one resized */
            continue;
        }
        pthread_mutex_unlock(&mu);
        if (surf == EGL_NO_SURFACE) { /* attach failed or the surface was lost: retry now and then */
            usleep(100000);
            if (cur && attach_surface(cur) == 0) frame(1);
            continue;
        }
        uint64_t t = now_ms();
        int full = t - last_full >= 250;
        if (full) last_full = t;
        int r = frame(full);
        if (r > 0 && !full) last_change = t;
        if (r <= 0) {
            /* Nothing new. Look again soon while things are moving; an idle desktop is polled
               at ~60 Hz instead, which keeps a still Windows screen nearly free. */
            usleep(t - last_change < 1000 ? 4000 : 16000);
        }
    }
    return NULL;
}

void wa_display_set_window(ANativeWindow *w) {
    pthread_mutex_lock(&mu);
    if (pending_win) ANativeWindow_release(pending_win);
    pending_win = w;
    if (w) ANativeWindow_acquire(w);
    win_changed = 1;
    int gen = ++drop_req_gen;
    if (!thread_started) {
        pthread_t t;
        if (pthread_create(&t, NULL, render_thread, NULL) == 0) {
            pthread_detach(t);
            thread_started = 1;
        }
    }
    pthread_cond_broadcast(&cv);
    /* surfaceDestroyed: the platform may tear the window down as soon as we return, so wait
       (briefly) until the render thread has stopped using it. */
    if (!w && thread_started) {
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_sec += 1;
        while (dropped_gen - gen < 0 && pthread_cond_timedwait(&cv, &mu, &ts) == 0) {}
    }
    pthread_mutex_unlock(&mu);
}

void wa_display_set_paused(int p) {
    pthread_mutex_lock(&mu);
    paused = p;
    pthread_cond_broadcast(&cv);
    pthread_mutex_unlock(&mu);
}

void wa_display_set_background(float r, float g, float b) {
    clear_rgb[0] = r;
    clear_rgb[1] = g;
    clear_rgb[2] = b;
}
