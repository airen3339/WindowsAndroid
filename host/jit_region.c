#include "jit_region.h"

#include "hv_os.h"

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#if HV_OS_DARWIN
#include <libkern/OSCacheControl.h>
#include <mach/mach.h>
#else
#include <fcntl.h>
#include <sys/syscall.h>
#endif

#if HV_OS_DARWIN
/* The split shape on a host that can make it itself: plain RW memory, an
   alias, and the original turned R-X. Works for macOS binaries without the
   hardened runtime. */
static int open_split(HvJitRegion *region, size_t bytes) {
    void *rx = mmap(NULL, bytes, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
    if (rx == MAP_FAILED) return -1;
    vm_address_t rw = 0;
    vm_prot_t cur, max;
    if (vm_remap(mach_task_self(), &rw, bytes, 0, VM_FLAGS_ANYWHERE, mach_task_self(), (vm_address_t)rx, FALSE, &cur, &max,
                 VM_INHERIT_NONE) != KERN_SUCCESS ||
        mprotect(rx, bytes, PROT_READ | PROT_EXEC) != 0) {
        munmap(rx, bytes);
        return -1;
    }
    if (hv_jit_region_attach(region, rx, (void *)rw, bytes) != 0) return -1;
    region->owned = 1;
    return 0;
}
#else
/* Linux / Android: one shared-memory object mapped twice, R-X where code runs
   and RW- where the translator writes it. This is what ART's own JIT does
   inside every app process, so SELinux allows it for untrusted_app (anonymous
   RWX memory needs execmem, which is not something to count on). */
static int open_split(HvJitRegion *region, size_t want) {
    /* Whole host pages (4 KB or 16 KB); a smaller request is honoured as a limit. */
    size_t pg = hv_host_page_size(), bytes = (want + pg - 1) & ~(pg - 1);
    int fd = (int)syscall(SYS_memfd_create, "winvm-jit", 1u /* MFD_CLOEXEC */);
    if (fd < 0) return -1;
    if (ftruncate(fd, (off_t)bytes) != 0) {
        close(fd);
        return -1;
    }
    void *rx = mmap(NULL, bytes, PROT_READ | PROT_EXEC, MAP_SHARED, fd, 0);
    void *rw = rx == MAP_FAILED ? MAP_FAILED : mmap(NULL, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (rx == MAP_FAILED || rw == MAP_FAILED) {
        if (rx != MAP_FAILED) munmap(rx, bytes);
        return -1;
    }
    if (hv_jit_region_attach(region, rx, rw, bytes) != 0) return -1;
    region->owned = 1;
    if (want < bytes) region->limit = want / sizeof(uint32_t);
    return 0;
}

/* Fallback: one RWX mapping (rw_diff 0). Fine on plain Linux and on devices
   whose policy still grants execmem. */
static int open_rwx(HvJitRegion *region, size_t bytes) {
    void *p = mmap(NULL, bytes, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
    if (p == MAP_FAILED) return -1;
    region->base = p;
    region->capacity = bytes / sizeof(uint32_t);
    region->owned = 1;
    return 0;
}
#endif

int hv_jit_region_open(HvJitRegion *region, size_t bytes) {
    memset(region, 0, sizeof *region);
#if HV_OS_DARWIN
    if (getenv("HV_JIT_SPLIT")) return open_split(region, bytes);
#if TARGET_OS_OSX
    void *p = mmap(NULL, bytes, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANON | MAP_JIT, -1, 0);
    if (p == MAP_FAILED) return -1;
    region->base = p;
    region->capacity = bytes / sizeof(uint32_t);
    region->owned = 1;
    return 0;
#else
    return open_split(region, bytes); /* works only where nothing enforces code signing (tests on a jailbroken device) */
#endif
#else
    if (getenv("HV_JIT_RWX")) return open_rwx(region, bytes);
    if (open_split(region, bytes) == 0) return 0;
    fprintf(stderr, "winvm: dual-mapped JIT refused, trying an RWX mapping\n");
    return open_rwx(region, bytes);
#endif
}

int hv_jit_region_attach(HvJitRegion *region, void *rx, void *rw, size_t bytes) {
    memset(region, 0, sizeof *region);
    if (!rx || !rw || bytes < 4096) return -1;
    region->base = rx;
    region->rw_diff = (intptr_t)((uint8_t *)rw - (uint8_t *)rx);
    region->capacity = bytes / sizeof(uint32_t);
    return 0;
}

void hv_jit_region_close(HvJitRegion *region) {
    if (region->owned && region->base) {
        size_t bytes = region->capacity * sizeof(uint32_t);
        if (region->rw_diff) munmap((uint8_t *)region->base + region->rw_diff, bytes);
        munmap(region->base, bytes);
    }
    memset(region, 0, sizeof *region);
}

void hv_jit_write(HvJitRegion *region, uint32_t *start, const void *src, size_t bytes) {
    /* One instruction may be rewritten while other CPUs run it (chaining a branch).
       memcpy may copy a word in pieces, and a CPU fetching in between takes half of
       the old branch and half of the new one: a jump to nowhere. */
    if (bytes == 4) {
        uint32_t word;
        memcpy(&word, src, 4);
#if TARGET_OS_OSX
        if (!region->rw_diff) pthread_jit_write_protect_np(0);
#endif
        __atomic_store_n((uint32_t *)((uint8_t *)start + region->rw_diff), word, __ATOMIC_RELEASE);
        return;
    }
    if (region->rw_diff) {
        memcpy((uint8_t *)start + region->rw_diff, src, bytes);
        return;
    }
#if TARGET_OS_OSX
    pthread_jit_write_protect_np(0); /* MAP_JIT: writable on this thread only, until hv_jit_finish */
#endif
    memcpy(start, src, bytes);
}

void hv_jit_finish(HvJitRegion *region, uint32_t *start, size_t words) {
    (void)region;
#if TARGET_OS_OSX
    if (!region->rw_diff) pthread_jit_write_protect_np(1);
#endif
    if (!start || !words) return;
#if HV_OS_DARWIN
    sys_icache_invalidate(start, words * sizeof(uint32_t));
#else
    /* Two aliases: clean the data cache by the address that was written, then
       invalidate the instruction cache by the address that runs. With a PIPT
       I-cache and IDC/DIC (every core this targets) the first half is cheap;
       __builtin___clear_cache on the RX alias does both for the 1:1 case. */
    if (region->rw_diff) {
        static uintptr_t line;
        static int idc;
        if (!line) {
            uint64_t ctr;
            __asm__ volatile("mrs %0, ctr_el0" : "=r"(ctr));
            idc = (ctr >> 28) & 1; /* data-to-instruction coherence: no clean needed */
            line = (uintptr_t)4 << ((ctr >> 16) & 0xf);
        }
        if (idc) goto icache;
        uintptr_t a = (uintptr_t)start + (uintptr_t)region->rw_diff, end = a + words * sizeof(uint32_t);
        for (uintptr_t p = a & ~(line - 1); p < end; p += line) __asm__ volatile("dc cvau, %0" ::"r"(p) : "memory");
        __asm__ volatile("dsb ish" ::: "memory");
    }
icache:
    hv_icache_flush(start, words * sizeof(uint32_t));
#endif
}

/* Any thread: an even number of words (blocks stay 8-byte aligned), taken atomically. */
uint32_t *hv_jit_reserve(HvJitRegion *region, size_t words) {
    words = (words + 1) & ~(size_t)1;
    size_t at = __atomic_fetch_add(&region->used, words, __ATOMIC_RELAXED);
    size_t cap = region->limit && region->limit < region->capacity ? region->limit : region->capacity;
    if (at + words > cap) return NULL; /* full: the flush resets used */
    return region->base + at;
}

void hv_jit_reset(HvJitRegion *region) {
    region->used = 0;
}
