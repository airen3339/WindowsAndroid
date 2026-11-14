/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * main_linux.c — headless runner for ARM64 Windows 11 on Linux / Android (`winvm`).
 *
 * The same engine the Android app embeds, as a plain executable: push it to a
 * device (or the emulator) with adb and run it from `adb shell`, with the
 * framebuffer saved as PNG screenshots and input fed through a command file.
 * Mirrors the macOS runner's --headless mode (host/main_mac.m).
 */
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "hv_os.h"
#include "vm_commands.h"
#include "winvm.h"

static volatile int g_sig_stop = 0;

static void on_sig(int sig) {
    (void)sig;
    g_sig_stop = 1;
    hv_winvm_stop();
}

typedef struct TimerArgs {
    const char *screenshot_path;
    int screenshot_after;
    int screenshot_every;
    int timeout_sec;
} TimerArgs;

static void shot(const TimerArgs *ta, int elapsed, const char *what) {
    char path[1024];
    if (ta->screenshot_every > 0) {
        /* shot.png -> shot-0030.png, so a boot can be followed frame by frame */
        const char *dot = strrchr(ta->screenshot_path, '.');
        int stem = dot ? (int)(dot - ta->screenshot_path) : (int)strlen(ta->screenshot_path);
        snprintf(path, sizeof path, "%.*s-%04d%s", stem, ta->screenshot_path, elapsed, dot ? dot : ".png");
    } else {
        snprintf(path, sizeof path, "%s", ta->screenshot_path);
    }
    int rc = hv_winvm_save_png(path);
    uint32_t fw = 0, fh = 0;
    hv_winvm_display_size(&fw, &fh);
    fprintf(stderr, "winvm: %s screenshot %s at %ds (%ux%u, rc=%d)\n", what, path, elapsed, fw, fh, rc);
}

static void *timer_thread(void *arg) {
    TimerArgs *ta = (TimerArgs *)arg;
    int elapsed = 0, shot_taken = 0;
    while (!g_sig_stop) {
        sleep(1);
        elapsed++;
        if (ta->screenshot_path && ta->screenshot_every > 0 && elapsed % ta->screenshot_every == 0) shot(ta, elapsed, "periodic");
        if (ta->screenshot_path && !shot_taken && ta->screenshot_after > 0 && elapsed >= ta->screenshot_after) {
            shot(ta, elapsed, "saved");
            shot_taken = 1;
        }
        if (ta->timeout_sec > 0 && elapsed >= ta->timeout_sec) {
            if (ta->screenshot_path && !shot_taken) shot(ta, elapsed, "final");
            fprintf(stderr, "winvm: timeout (%ds) reached, stopping VM.\n", ta->timeout_sec);
            g_sig_stop = 1;
            hv_winvm_stop();
            break;
        }
    }
    return NULL;
}

static void usage(const char *prog) {
    fprintf(stderr,
            "usage: %s --code <code.fd> --vars <vars.fd> [options]\n"
            "  --disk <img|hvsparse>     Primary NVMe disk image (read/write)\n"
            "  --disk1 <img>             Secondary/installer NVMe image (read-only)\n"
            "  --cpus <N>                Number of vCPUs (default: 4)\n"
            "  --ram <MB>                Guest RAM in MB (default: 4096)\n"
            "  --share <dir>             Host folder shared at http://10.0.2.4/\n"
            "  --resolution <WxH>        Screen size to boot at (needs scripts/build_firmware.sh firmware)\n"
            "  --dvd <iso>               ISO image in a USB DVD drive (twice: a second drive)\n"
            "  --dvd-boot-keys           Press Space while the first DVD boots (installing onto a blank disk)\n"
            "  --screenshot <png>        Save guest framebuffer PNG\n"
            "  --screenshot-after <sec>  Save screenshot after N seconds\n"
            "  --screenshot-every <sec>  Save numbered screenshots every N seconds\n"
            "  --timeout <sec>           Auto-stop VM after N seconds\n"
            "  --cmd-file <path>         Tail input commands from file\n"
            "  --no-net                  Disable SLIRP networking\n"
            "  --sound                   Enable audio\n"
            "  --stats <sec>             Print JIT statistics every N seconds\n"
            "  --log <level>             Log level (default: 0)\n"
            "  --headless                Accepted for compatibility (always headless)\n",
            prog);
    exit(2);
}

int main(int argc, char **argv) {
    HvWinVmConfig cfg;
    memset(&cfg, 0, sizeof cfg);
    cfg.ncpus = 4;
    cfg.ram_mb = 4096;

    TimerArgs targs = {NULL, 0, 0, 0};
    const char *cmd_file = getenv("HV_INPUT_FILE");
    char share_sock_buf[512] = {0};

    int ndvd = 0;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--headless")) continue;
        if (!strcmp(argv[i], "--no-net")) { cfg.no_net = 1; continue; }
        if (!strcmp(argv[i], "--sound")) { cfg.enable_sound = 1; continue; }
        if (!strcmp(argv[i], "--help") || !strcmp(argv[i], "-h")) usage(argv[0]);
        if (i + 1 >= argc) usage(argv[0]);

        if (!strcmp(argv[i], "--code")) cfg.code_fd = argv[++i];
        else if (!strcmp(argv[i], "--vars")) cfg.vars_fd = argv[++i];
        else if (!strcmp(argv[i], "--disk")) cfg.disk0 = argv[++i];
        else if (!strcmp(argv[i], "--disk1")) cfg.disk1 = argv[++i];
        else if (!strcmp(argv[i], "--cpus")) cfg.ncpus = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--ram")) cfg.ram_mb = strtoull(argv[++i], NULL, 0);
        else if (!strcmp(argv[i], "--share")) cfg.share_dir = argv[++i];
        else if (!strcmp(argv[i], "--dvd")) {
            if (ndvd < 2) cfg.dvd[ndvd++] = argv[++i];
            else usage(argv[0]);
        }
        else if (!strcmp(argv[i], "--dvd-boot-keys")) cfg.dvd_boot_keys = 1;
        else if (!strcmp(argv[i], "--resolution")) {
            if (sscanf(argv[++i], "%ux%u", &cfg.display_width, &cfg.display_height) != 2) usage(argv[0]);
        }
        else if (!strcmp(argv[i], "--screenshot")) targs.screenshot_path = argv[++i];
        else if (!strcmp(argv[i], "--screenshot-after")) targs.screenshot_after = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--screenshot-every")) targs.screenshot_every = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--timeout")) targs.timeout_sec = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--cmd-file")) cmd_file = argv[++i];
        else if (!strcmp(argv[i], "--stats")) cfg.stats_interval = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--log")) cfg.log = atoi(argv[++i]);
        else usage(argv[0]);
    }
    if (!cfg.code_fd || !cfg.vars_fd) usage(argv[0]);
    setvbuf(stderr, NULL, _IONBF, 0);

    if (cfg.share_dir && *cfg.share_dir) {
        snprintf(share_sock_buf, sizeof share_sock_buf, "%s/.winvm_webdav.sock", cfg.share_dir);
        cfg.share_sock = share_sock_buf;
    }

    /* Android has no /etc/resolv.conf for libslirp to find the host's DNS servers in: unless one
       is named in SLIRP_RESOLV_CONF, use public resolvers. */
    if (!getenv("SLIRP_RESOLV_CONF") && access("/etc/resolv.conf", R_OK) != 0) {
        FILE *rc = fopen("resolv.conf", "w");
        if (rc) {
            fputs("nameserver 1.1.1.1\nnameserver 8.8.8.8\n", rc);
            fclose(rc);
            char cwd[512];
            if (getcwd(cwd, sizeof cwd - 16)) {
                strcat(cwd, "/resolv.conf");
                setenv("SLIRP_RESOLV_CONF", cwd, 1);
            }
        }
    }
    fprintf(stderr, "winvm: host counter %llu Hz, page size %zu\n", (unsigned long long)hv_host_freq(), hv_host_page_size());
    char err[256] = {0};
    if (hv_winvm_create(&cfg, err, sizeof err) != 0) {
        fprintf(stderr, "winvm: failed to create VM: %s\n", err);
        return 1;
    }

    signal(SIGINT, on_sig);
    signal(SIGTERM, on_sig);

    if (targs.screenshot_path || targs.timeout_sec > 0) {
        pthread_t tt;
        pthread_create(&tt, NULL, timer_thread, &targs);
        pthread_detach(tt);
    }
    if (cmd_file && *cmd_file) hv_vm_command_file_start(cmd_file, &g_sig_stop);

    fprintf(stderr, "winvm: starting headless (%d vCPUs, %llu MB RAM)...\n", cfg.ncpus, (unsigned long long)cfg.ram_mb);
    hv_winvm_start(0, NULL);
    int r = hv_winvm_run_cpu0();
    fprintf(stderr, "\nwinvm: stopped (%s)\n", g_sig_stop ? "requested" : hv_winvm_reason_name(r));
    if (targs.screenshot_path && targs.screenshot_after == 0 && targs.screenshot_every == 0) {
        int rc = hv_winvm_save_png(targs.screenshot_path);
        fprintf(stderr, "winvm: saved screenshot %s (rc=%d)\n", targs.screenshot_path, rc);
    }
    hv_winvm_flush_disks();
    return 0;
}
