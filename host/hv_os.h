/* SPDX-License-Identifier: GPL-2.0-or-later */
/* What the engine needs from the host OS, in one place, so the same core runs on
   Darwin (macOS) and Linux (Android, and plain Linux for tests).

   The one thing that matters most is time. The guest's generic-timer counter is
   the host's ARM generic-timer counter, read directly by translated code (see
   translate_counter). Apple silicon runs it at 24 MHz; Android phones do not
   (Qualcomm Snapdragon: 19.2 MHz; the Android emulator on Apple silicon: 24 MHz;
   others vary), so every "ticks" quantity goes through hv_host_freq(). */
#ifndef HV_OS_H
#define HV_OS_H

#include <pthread.h>
#include <stdint.h>
#include <time.h>

#if defined(__APPLE__)
#include <TargetConditionals.h>
/* Declared, not included: <mach/mach_time.h> drags in PAGE_MASK and friends. */
uint64_t mach_absolute_time(void);
#define HV_OS_DARWIN 1
#else
#define HV_OS_DARWIN 0
#define TARGET_OS_OSX 0
#endif

#if defined(__ANDROID__)
#define HV_OS_ANDROID 1
#else
#define HV_OS_ANDROID 0
#endif

/* The host counter, in the same units as the guest's CNTVCT_EL0. */
static inline uint64_t hv_host_ticks(void) {
#if HV_OS_DARWIN
    return mach_absolute_time();
#else
    uint64_t v;
    __asm__ volatile("mrs %0, cntvct_el0" : "=r"(v)::"memory");
    return v;
#endif
}

/* Its frequency in Hz (CNTFRQ_EL0 on Linux, 24 MHz on Apple silicon). Nonzero. */
uint64_t hv_host_freq(void);

static inline uint64_t hv_us_to_ticks(uint64_t us) {
    return us * hv_host_freq() / 1000000ull;
}
static inline uint64_t hv_ticks_to_ns(uint64_t ticks) {
    uint64_t f = hv_host_freq();
    return ticks / f * 1000000000ull + ticks % f * 1000000000ull / f;
}
static inline double hv_ticks_to_s(uint64_t ticks) {
    return (double)ticks / (double)hv_host_freq();
}

/* Wait on cv for at most ns nanoseconds (spurious wakeups allowed). mu is held. */
int hv_cond_wait_ns(pthread_cond_t *cv, pthread_mutex_t *mu, uint64_t ns);

/* CPU time this thread has used, in seconds. */
double hv_thread_cpu_seconds(void);

/* Scheduling class for the calling thread. */
enum {
    HV_PRIO_VCPU,       /* guest vCPU: as fast as possible, but below the UI thread */
    HV_PRIO_VCPU_UI,    /* guest vCPU at UI priority (HV_QOS=ui) */
    HV_PRIO_DEVICE,     /* device / audio feeder threads */
    HV_PRIO_BACKGROUND, /* prefault, profiling, housekeeping */
};
void hv_thread_set_prio(int prio);

/* Name the calling thread (shows in top, perfetto, crash dumps). */
void hv_thread_set_name(const char *name);

/* Make freshly written instructions visible to instruction fetch. */
static inline void hv_icache_flush(void *start, size_t bytes) {
    __builtin___clear_cache((char *)start, (char *)start + bytes);
}

/* The host's VM page size (4 KB or 16 KB on Android, 16 KB on Apple silicon). */
size_t hv_host_page_size(void);

#endif
