/* SPDX-License-Identifier: GPL-2.0-or-later */
/* The VM command language shared by every host front end (see vm_commands.h).
   Lifted from the macOS runner (main_mac.m) so Linux, Android and macOS agree. */
#include "vm_commands.h"

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "mmu.h"
#include "share.h"
#include "winvm.h"

/* Simple ASCII typer using USB HID Keyboard usages */
void hv_vm_type_char(char ch) {
    uint8_t usage = 0;
    int shift = 0;
    if (ch >= 'a' && ch <= 'z') { usage = (uint8_t)(0x04 + (ch - 'a')); }
    else if (ch >= 'A' && ch <= 'Z') { usage = (uint8_t)(0x04 + (ch - 'A')); shift = 1; }
    else if (ch >= '1' && ch <= '9') { usage = (uint8_t)(0x1e + (ch - '1')); }
    else if (ch == '0') { usage = 0x27; }
    else {
        switch (ch) {
        case ' ': usage = 0x2c; break;
        case '\n': case '\r': usage = 0x28; break;
        case '\t': usage = 0x2b; break;
        case '-': usage = 0x2d; break;
        case '_': usage = 0x2d; shift = 1; break;
        case '=': usage = 0x2e; break;
        case '+': usage = 0x2e; shift = 1; break;
        case '[': usage = 0x2f; break;
        case '{': usage = 0x2f; shift = 1; break;
        case ']': usage = 0x30; break;
        case '}': usage = 0x30; shift = 1; break;
        case '\\': usage = 0x31; break;
        case '|': usage = 0x31; shift = 1; break;
        case ';': usage = 0x33; break;
        case ':': usage = 0x33; shift = 1; break;
        case '\'': usage = 0x34; break;
        case '"': usage = 0x34; shift = 1; break;
        case '`': usage = 0x35; break;
        case '~': usage = 0x35; shift = 1; break;
        case ',': usage = 0x36; break;
        case '<': usage = 0x36; shift = 1; break;
        case '.': usage = 0x37; break;
        case '>': usage = 0x37; shift = 1; break;
        case '/': usage = 0x38; break;
        case '?': usage = 0x38; shift = 1; break;
        case '!': usage = 0x1e; shift = 1; break;
        case '@': usage = 0x1f; shift = 1; break;
        case '#': usage = 0x20; shift = 1; break;
        case '$': usage = 0x21; shift = 1; break;
        case '%': usage = 0x22; shift = 1; break;
        case '^': usage = 0x23; shift = 1; break;
        case '&': usage = 0x24; shift = 1; break;
        case '*': usage = 0x25; shift = 1; break;
        case '(': usage = 0x26; shift = 1; break;
        case ')': usage = 0x27; shift = 1; break;
        default: return;
        }
    }
    if (shift) { hv_winvm_key_hid(0xe1, 1); usleep(20000); }
    hv_winvm_key_hid(usage, 1);
    usleep(35000);
    hv_winvm_key_hid(usage, 0);
    if (shift) { usleep(20000); hv_winvm_key_hid(0xe1, 0); }
    usleep(35000);
}

void hv_vm_type_text(const char *text) {
    for (const unsigned char *p = (const unsigned char *)text; *p; p++)
        if (*p < 0x80) hv_vm_type_char((char)*p);
}

int hv_vm_command(const char *line_in) {
    char line[512];
    snprintf(line, sizeof line, "%s", line_in);
    line[strcspn(line, "\r\n")] = 0;
    if (!line[0] || line[0] == '#') return 0;
    double xn = 0, yn = 0;
    int code = 0;
    if (sscanf(line, "click %lf %lf", &xn, &yn) == 2) {
        hv_winvm_tablet(xn, yn, 0, 0);
        usleep(40000);
        hv_winvm_tablet(xn, yn, 1, 0);
        usleep(80000);
        hv_winvm_tablet(xn, yn, 0, 0);
    } else if (sscanf(line, "rclick %lf %lf", &xn, &yn) == 2) {
        hv_winvm_tablet(xn, yn, 0, 0);
        usleep(40000);
        hv_winvm_tablet(xn, yn, 2, 0);
        usleep(80000);
        hv_winvm_tablet(xn, yn, 0, 0);
    } else if (sscanf(line, "move %lf %lf", &xn, &yn) == 2) {
        hv_winvm_tablet(xn, yn, 0, 0);
    } else if (sscanf(line, "hidkey %i", &code) == 1) {
        hv_winvm_key_hid((uint8_t)code, 1);
        usleep(50000);
        hv_winvm_key_hid((uint8_t)code, 0);
    } else if (!strcmp(line, "win")) {
        hv_winvm_key_hid(0xe3, 1);
        usleep(60000);
        hv_winvm_key_hid(0xe3, 0);
    } else if (!strcmp(line, "winr")) {
        hv_winvm_key_hid(0xe3, 1);
        usleep(30000);
        hv_winvm_key_hid(0x15, 1); /* 'r' */
        usleep(50000);
        hv_winvm_key_hid(0x15, 0);
        usleep(30000);
        hv_winvm_key_hid(0xe3, 0);
    } else if (!strcmp(line, "enter")) {
        hv_winvm_key_hid(0x28, 1);
        usleep(50000);
        hv_winvm_key_hid(0x28, 0);
    } else if (!strcmp(line, "cad")) {
        hv_winvm_send_ctrl_alt_del();
    } else if (!strcmp(line, "power")) {
        hv_winvm_power_button();
    } else if (!strncmp(line, "type ", 5)) {
        for (const char *p = line + 5; *p; p++) hv_vm_type_char(*p);
    } else if (!strncmp(line, "screenshot ", 11)) {
        int rc = hv_winvm_save_png(line + 11);
        fprintf(stderr, "winvm: screenshot -> %s (rc=%d)\n", line + 11, rc);
    } else if (!strcmp(line, "cpus")) {
        HvMachine *m = hv_winvm_machine();
        for (int i = 0; i < m->ncpus; i++)
            if (m->cpus[i]) __atomic_store_n(&m->cpus[i]->exit_req, 1, __ATOMIC_SEQ_CST);
        usleep(4000);
        for (int i = 0; i < m->ncpus; i++) {
            HvCpu *c = m->cpus[i];
            if (!c) continue;
            uint64_t pc = c->pc;
            fprintf(stderr, "CPU%d: pc=%llx el=%u exit=%u x0=%llx x1=%llx x2=%llx x3=%llx x8=%llx x16=%llx lr=%llx\n",
                    i, (unsigned long long)pc, c->el, c->exit_reason,
                    (unsigned long long)c->x[0], (unsigned long long)c->x[1],
                    (unsigned long long)c->x[2], (unsigned long long)c->x[3],
                    (unsigned long long)c->x[8], (unsigned long long)c->x[16],
                    (unsigned long long)c->x[30]);
            if (pc >= 0x1000) {
                fprintf(stderr, "  insns:");
                for (int off = -32; off <= 32; off += 4) {
                    uint64_t va = (pc & ~3ull) + (int64_t)off;
                    HvWalk w = {0};
                    hv_mmu_walk(c, va, HV_ACC_READ, c->el, &w);
                    uint32_t word = 0;
                    if (w.fsc == 0) hv_phys_read(m, w.pa, &word, 4);
                    fprintf(stderr, " %s%08x", off == 0 ? "*" : "", word);
                }
                fprintf(stderr, "\n");
            }
        }
        fflush(stderr);
    } else if (sscanf(line, "oauthmode %d", &code) == 1) {
        hv_share_set_url_handler_enabled(code);
        fprintf(stderr, "winvm: oauthmode=%d\n", code);
    } else if (!strcmp(line, "oauthstatus")) {
        char *u = hv_share_take_open_url();
        int done = hv_share_take_oauth_done();
        fprintf(stderr, "OAUTH_STATUS url=%s done=%d\n", u ? u : "(none)", done);
        free(u);
    } else if (sscanf(line, "wait %d", &code) == 1 && code > 0) {
        usleep((useconds_t)code * 1000u);
    } else if (!strcmp(line, "quit")) {
        hv_winvm_stop();
        fprintf(stderr, "winvm: cmd: %s\n", line);
        return 1;
    }
    fprintf(stderr, "winvm: cmd: %s\n", line);
    return 0;
}

typedef struct CmdFile {
    char *path;
    volatile int *stop;
    int from_end;
} CmdFile;

static void *cmd_file_thread(void *arg) {
    CmdFile *cf = arg;
    FILE *f = fopen(cf->path, "a+");
    if (!f) {
        free(cf->path);
        free(cf);
        return NULL;
    }
    long pos = 0;
    struct stat st;
    ino_t ino = fstat(fileno(f), &st) == 0 ? st.st_ino : 0;
    if (cf->from_end && fseek(f, 0, SEEK_END) == 0) pos = ftell(f);
    while (!cf->stop || !*cf->stop) {
        char line[512];
        /* Replaced rather than appended to (adb push, an editor's save): a new script, from the top. */
        if (stat(cf->path, &st) == 0 && st.st_ino != ino) {
            FILE *g = fopen(cf->path, "a+");
            if (g) {
                fclose(f);
                f = g;
                ino = st.st_ino;
                pos = 0;
            }
        }
        fseek(f, pos, SEEK_SET);
        if (!fgets(line, sizeof line, f) || !strchr(line, '\n')) {
            clearerr(f);
            usleep(150000);
            continue;
        }
        pos = ftell(f);
        if (hv_vm_command(line)) break;
    }
    fclose(f);
    free(cf->path);
    free(cf);
    return NULL;
}

static void start_reader(const char *path, volatile int *stop, int from_end) {
    CmdFile *cf = calloc(1, sizeof *cf);
    if (!cf) return;
    cf->path = strdup(path);
    cf->stop = stop;
    cf->from_end = from_end;
    pthread_t t;
    if (pthread_create(&t, NULL, cmd_file_thread, cf) == 0) pthread_detach(t);
}

void hv_vm_command_file_start(const char *path, volatile int *stop) {
    start_reader(path, stop, 0);
}

void hv_vm_command_file_follow(const char *path, volatile int *stop) {
    start_reader(path, stop, 1);
}
