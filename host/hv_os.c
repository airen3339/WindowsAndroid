/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "hv_os.h"

#include <errno.h>
#include <string.h>
#include <unistd.h>

#if HV_OS_DARWIN
#include <mach/mach_time.h>
#include <pthread/qos.h>
#else
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#endif

static uint64_t host_freq;

uint64_t hv_host_freq(void) {
    uint64_t f = __atomic_load_n(&host_freq, __ATOMIC_RELAXED);
    if (f) return f;
#if HV_OS_DARWIN
    mach_timebase_info_data_t tb;
    mach_timebase_info(&tb);
    /* mach_absolute_time ticks are numer/denom ns: 125/3 on Apple silicon = 24 MHz. */
    f = tb.numer ? (uint64_t)1000000000ull * tb.denom / tb.numer : 24000000ull;
#else
    __asm__ volatile("mrs %0, cntfrq_el0" : "=r"(f));
    if (!f) f = 19200000ull; /* never seen, but a zero here would divide by zero everywhere */
#endif
    __atomic_store_n(&host_freq, f, __ATOMIC_RELAXED);
    return f;
}

int hv_cond_wait_ns(pthread_cond_t *cv, pthread_mutex_t *mu, uint64_t ns) {
#if HV_OS_DARWIN
    struct timespec rel = {(time_t)(ns / 1000000000ull), (long)(ns % 1000000000ull)};
    return pthread_cond_timedwait_relative_np(cv, mu, &rel);
#else
    /* CLOCK_MONOTONIC, so a wall-clock change never stretches a WFI. */
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    uint64_t nsec = (uint64_t)ts.tv_nsec + ns % 1000000000ull;
    ts.tv_sec += (time_t)(ns / 1000000000ull + nsec / 1000000000ull);
    ts.tv_nsec = (long)(nsec % 1000000000ull);
    return pthread_cond_clockwait(cv, mu, CLOCK_MONOTONIC, &ts);
#endif
}

double hv_thread_cpu_seconds(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts) != 0) return 0;
    return (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
}

void hv_thread_set_prio(int prio) {
#if HV_OS_DARWIN
    qos_class_t c;
    switch (prio) {
    case HV_PRIO_VCPU_UI: c = QOS_CLASS_USER_INTERACTIVE; break;
    case HV_PRIO_VCPU: c = QOS_CLASS_USER_INTERACTIVE; break;
    case HV_PRIO_DEVICE: c = QOS_CLASS_USER_INITIATED; break;
    default: c = QOS_CLASS_UTILITY; break;
    }
    pthread_set_qos_class_self_np(c, 0);
#else
    /* Android: nice values map onto the framework's thread priorities
       (THREAD_PRIORITY_DISPLAY = -4, URGENT_DISPLAY = -8, AUDIO = -16). A process
       may lower its own nice value down to the RLIMIT_NICE the zygote grants apps;
       failures are harmless. The vCPUs sit at DISPLAY priority, just below the
       render thread, which is what keeps them on the big cores under EAS. */
    int nice_v;
    switch (prio) {
    case HV_PRIO_VCPU_UI: nice_v = -8; break;
    case HV_PRIO_VCPU: nice_v = -4; break;
    case HV_PRIO_DEVICE: nice_v = -2; break;
    default: nice_v = 10; break;
    }
    setpriority(PRIO_PROCESS, (id_t)syscall(SYS_gettid), nice_v);
#endif
}

void hv_thread_set_name(const char *name) {
#if HV_OS_DARWIN
    pthread_setname_np(name);
#else
    char buf[16];
    strncpy(buf, name, sizeof buf - 1);
    buf[sizeof buf - 1] = 0;
    pthread_setname_np(pthread_self(), buf);
#endif
}

size_t hv_host_page_size(void) {
    long p = sysconf(_SC_PAGESIZE);
    return p > 0 ? (size_t)p : 4096;
}
