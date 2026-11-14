/* SPDX-License-Identifier: GPL-2.0-or-later */
/* USB DVD drive: Bulk-Only Transport and the SCSI / MMC commands UEFI and Windows send a
   read-only optical drive. See usb_msc.h. */
#include "usb_msc.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CBW_SIG 0x43425355u
#define CSW_SIG 0x53425355u

enum { SK_NONE = 0, SK_NOT_READY = 2, SK_ILLEGAL = 5, SK_UNIT_ATTENTION = 6, SK_DATA_PROTECT = 7 };

static uint32_t be32(const uint8_t *p) { return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3]; }
static uint16_t be16(const uint8_t *p) { return (uint16_t)(p[0] << 8 | p[1]); }
static void put32(uint8_t *p, uint32_t v) { p[0] = (uint8_t)(v >> 24), p[1] = (uint8_t)(v >> 16), p[2] = (uint8_t)(v >> 8), p[3] = (uint8_t)v; }
static void put16(uint8_t *p, uint16_t v) { p[0] = (uint8_t)(v >> 8), p[1] = (uint8_t)v; }
static void le32(uint8_t *p, uint32_t v) { p[0] = (uint8_t)v, p[1] = (uint8_t)(v >> 8), p[2] = (uint8_t)(v >> 16), p[3] = (uint8_t)(v >> 24); }

static int mlog(void) {
    static int on = -1;
    if (on < 0) on = getenv("HV_MSC_LOG") != NULL;
    return on;
}

HvUsbMsc *hv_usb_msc_open(const char *path) {
    HvUsbMsc *d = calloc(1, sizeof *d);
    if (!d) return NULL;
    if (hv_disk_open(&d->disk, path, 0, 1) != 0) {
        free(d);
        return NULL;
    }
    d->blocks = d->disk.size / HV_MSC_BLOCK;
    d->new_media = 1;
    hv_usb_msc_reset(d);
    return d;
}

void hv_usb_msc_close(HvUsbMsc *d) {
    if (!d) return;
    hv_disk_close(&d->disk);
    free(d);
}

void hv_usb_msc_reset(HvUsbMsc *d) {
    d->state = HV_MSC_CBW;
    d->cbw_len = 0;
    d->actual_left = 0;
    d->from_disc = 0;
}

static void sense(HvUsbMsc *d, uint8_t key, uint8_t asc, uint8_t ascq) {
    d->sense_key = key, d->asc = asc, d->ascq = ascq;
    d->status = key ? 1 : 0;
}

static void reply(HvUsbMsc *d, const void *p, uint32_t len) {
    if (len > sizeof d->reply) len = sizeof d->reply;
    memcpy(d->reply, p, len);
    d->reply_len = len;
}

/* MSF address of a logical block (CD addressing starts 2 seconds in). */
static void msf(uint8_t *p, uint32_t lba) {
    lba += 150;
    p[0] = 0;
    p[1] = (uint8_t)(lba / (60 * 75));
    p[2] = (uint8_t)(lba / 75 % 60);
    p[3] = (uint8_t)(lba % 75);
}

static void inquiry(HvUsbMsc *d, const uint8_t *cb) {
    uint8_t r[64] = {0};
    if (cb[1] & 1) { /* vital product data */
        r[0] = 0x05;
        r[1] = cb[2];
        if (cb[2] == 0x00) {
            r[3] = 3, r[4] = 0x00, r[5] = 0x80, r[6] = 0x83;
            reply(d, r, 7);
        } else if (cb[2] == 0x80) {
            static const char sn[] = "WINANDROID01";
            r[3] = sizeof sn - 1;
            memcpy(r + 4, sn, sizeof sn - 1);
            reply(d, r, 4 + sizeof sn - 1);
        } else if (cb[2] == 0x83) {
            static const char id[] = "WinAndr USB DVD-ROM";
            r[4] = 0x02, r[5] = 0x01, r[7] = sizeof id - 1; /* ASCII, T10 vendor ID based */
            memcpy(r + 8, id, sizeof id - 1);
            r[3] = (uint8_t)(4 + sizeof id - 1);
            reply(d, r, 8 + sizeof id - 1);
        } else {
            sense(d, SK_ILLEGAL, 0x24, 0);
        }
        return;
    }
    r[0] = 0x05; /* CD/DVD */
    r[1] = 0x80; /* removable */
    r[2] = 0x05; /* SPC-3 */
    r[3] = 0x02;
    r[4] = 31;
    memcpy(r + 8, "WinAndr ", 8);
    memcpy(r + 16, "USB DVD-ROM     ", 16);
    memcpy(r + 32, "1.00", 4);
    reply(d, r, 36);
}

static uint32_t page_2a(uint8_t *p) {
    /* MM capabilities and mechanical status: reads CD-ROM and DVD-ROM, tray, lockable. */
    memset(p, 0, 22);
    p[0] = 0x2a, p[1] = 20;
    p[2] = 0x3b, p[3] = 0x00;
    p[4] = 0x71, p[5] = 0xff;
    p[6] = 0x29; /* tray, eject, lock */
    put16(p + 8, 0x2c2);  /* 4x */
    put16(p + 10, 2);
    put16(p + 12, 512);
    put16(p + 14, 0x2c2);
    return 22;
}

static void mode_sense(HvUsbMsc *d, const uint8_t *cb, int ten) {
    uint8_t r[64] = {0};
    int page = cb[2] & 0x3f;
    uint32_t hdr = ten ? 8 : 4, n = hdr;
    if (page == 0x2a || page == 0x3f) n += page_2a(r + hdr);
    else {
        sense(d, SK_ILLEGAL, 0x24, 0);
        return;
    }
    if (ten) put16(r, (uint16_t)(n - 2));
    else r[0] = (uint8_t)(n - 1);
    reply(d, r, n);
}

static void read_toc(HvUsbMsc *d, const uint8_t *cb) {
    uint8_t r[32] = {0};
    int use_msf = cb[1] & 2, format = cb[2] & 0xf, track = cb[6];
    if (!format && cb[9] >> 6) format = cb[9] >> 6; /* older drives: the format in the control byte */
    uint32_t n;
    if (format == 0) {
        if (track > 1 && track != 0xaa) {
            sense(d, SK_ILLEGAL, 0x24, 0);
            return;
        }
        r[2] = 1, r[3] = 1;
        n = 4;
        if (track <= 1) {
            r[n + 1] = 0x14, r[n + 2] = 1;
            if (use_msf) msf(r + n + 4, 0);
            n += 8;
        }
        r[n + 1] = 0x14, r[n + 2] = 0xaa;
        if (use_msf) msf(r + n + 4, (uint32_t)d->blocks);
        else put32(r + n + 4, (uint32_t)d->blocks);
        n += 8;
    } else if (format == 1) { /* session info: one session, starting at track 1 */
        r[2] = 1, r[3] = 1;
        r[5] = 0x14, r[6] = 1;
        if (use_msf) msf(r + 8, 0);
        n = 12;
    } else {
        sense(d, SK_ILLEGAL, 0x24, 0);
        return;
    }
    put16(r, (uint16_t)(n - 2));
    reply(d, r, n);
}

static void get_configuration(HvUsbMsc *d, const uint8_t *cb) {
    uint8_t r[128] = {0};
    int rt = cb[1] & 3;
    uint16_t start = be16(cb + 2);
    uint16_t profile = d->blocks * HV_MSC_BLOCK > 900ull << 20 ? 0x0010 : 0x0008; /* DVD-ROM / CD-ROM */
    uint32_t n = 8;
    put16(r + 6, profile);
#define FEATURE(code, flags, ...) do { \
        static const uint8_t body_[] = {__VA_ARGS__}; \
        if ((rt == 2 && start == (code)) || (rt != 2 && (code) >= start)) { \
            put16(r + n, (code)); r[n + 2] = (flags); r[n + 3] = sizeof body_; \
            memcpy(r + n + 4, body_, sizeof body_); n += 4 + sizeof body_; \
        } } while (0)
    if ((rt == 2 && start == 0) || (rt != 2 && start == 0)) { /* profile list */
        put16(r + n, 0); r[n + 2] = 0x03; r[n + 3] = 4;
        put16(r + n + 4, profile); r[n + 6] = 0x01;
        n += 8;
    }
    FEATURE(0x0001, 0x0b, 0, 0, 0, 8, 1, 0, 0, 0);       /* core: USB, DBE */
    FEATURE(0x0002, 0x07, 0x02, 0, 0, 0);                /* morphing */
    FEATURE(0x0003, 0x03, 0x29, 0, 0, 0);                /* removable medium: tray, eject, lock */
    FEATURE(0x0010, 0x03, 0, 0, 8, 0, 0, 1, 0, 0);       /* random readable: 2048-byte blocks */
    FEATURE(0x001e, 0x0b, 0, 0, 0, 0);                   /* CD read */
    if (profile == 0x0010) FEATURE(0x001f, 0x07, 0, 0, 0, 0); /* DVD read */
#undef FEATURE
    put32(r, n - 4);
    reply(d, r, n);
}

static void event_status(HvUsbMsc *d, const uint8_t *cb) {
    uint8_t r[8] = {0};
    if (!(cb[1] & 1)) { /* asynchronous notification is not supported */
        sense(d, SK_ILLEGAL, 0x24, 0);
        return;
    }
    if (cb[4] & 0x10) { /* media class */
        put16(r, 6);
        r[2] = 0x04, r[3] = 0x10;
        r[4] = d->new_media ? 2 : 0; /* NewMedia once, then NoChange */
        r[5] = 0x02;                  /* media present */
        d->new_media = 0;
        reply(d, r, 8);
    } else {
        put16(r, 2);
        r[2] = 0x80, r[3] = 0x10; /* no event available */
        reply(d, r, 4);
    }
}

static void disc_info(HvUsbMsc *d) {
    uint8_t r[34] = {0};
    put16(r, 32);
    r[2] = 0x0e; /* complete disc, complete session */
    r[3] = 1, r[4] = 1, r[5] = 1, r[6] = 1;
    memset(r + 16, 0xff, 8); /* no lead-in / lead-out start times */
    reply(d, r, 34);
}

static void disc_structure(HvUsbMsc *d, const uint8_t *cb) {
    uint8_t r[24] = {0};
    if (cb[7] == 0x00) { /* physical format: single-layer read-only DVD */
        put16(r, 21);
        r[4] = 0x01, r[5] = 0x0f, r[6] = 0x01;
        put32(r + 8, 0x30000);
        put32(r + 12, 0x30000 + (uint32_t)d->blocks - 1);
        reply(d, r, 23);
    } else if (cb[7] == 0x01) { /* copyright: no protection, all regions */
        put16(r, 6);
        reply(d, r, 8);
    } else {
        sense(d, SK_ILLEGAL, 0x24, 0);
    }
}

static void start_read(HvUsbMsc *d, uint64_t lba, uint64_t count) {
    if (lba > d->blocks || count > d->blocks - lba) {
        sense(d, SK_ILLEGAL, 0x21, 0); /* LBA out of range */
        return;
    }
    d->from_disc = 1;
    d->disc_off = lba * HV_MSC_BLOCK;
    d->reply_len = (uint32_t)(count * HV_MSC_BLOCK);
}

/* Run the SCSI command in the CBW; leaves a reply (or a disc range) and the status. */
static void command(HvUsbMsc *d, const uint8_t *cb) {
    uint8_t r[32] = {0};
    uint8_t last_key = d->sense_key, last_asc = d->asc, last_ascq = d->ascq; /* for REQUEST SENSE */
    d->reply_len = 0;
    d->from_disc = 0;
    sense(d, SK_NONE, 0, 0);
    switch (cb[0]) {
    case 0x00: /* TEST UNIT READY */
    case 0x1b: /* START STOP UNIT */
    case 0x1e: /* PREVENT ALLOW MEDIUM REMOVAL */
    case 0x2b: /* SEEK */
    case 0x35: /* SYNCHRONIZE CACHE */
    case 0xbb: /* SET CD SPEED */
        break;
    case 0x03: { /* REQUEST SENSE */
        r[0] = 0x70, r[2] = last_key, r[7] = 10, r[12] = last_asc, r[13] = last_ascq;
        reply(d, r, 18);
        break;
    }
    case 0x12: inquiry(d, cb); break;
    case 0x1a: mode_sense(d, cb, 0); break;
    case 0x5a: mode_sense(d, cb, 1); break;
    case 0x25: /* READ CAPACITY (10) */
        put32(r, (uint32_t)d->blocks - 1);
        put32(r + 4, HV_MSC_BLOCK);
        reply(d, r, 8);
        break;
    case 0x9e: /* READ CAPACITY (16) */
        if ((cb[1] & 0x1f) != 0x10) { sense(d, SK_ILLEGAL, 0x20, 0); break; }
        put32(r, (uint32_t)((d->blocks - 1) >> 32));
        put32(r + 4, (uint32_t)(d->blocks - 1));
        put32(r + 8, HV_MSC_BLOCK);
        reply(d, r, 32);
        break;
    case 0x28: start_read(d, be32(cb + 2), be16(cb + 7)); break;                        /* READ (10) */
    case 0xa8: start_read(d, be32(cb + 2), be32(cb + 6)); break;                        /* READ (12) */
    case 0x88: start_read(d, (uint64_t)be32(cb + 2) << 32 | be32(cb + 6), be32(cb + 10)); break; /* READ (16) */
    case 0x43: read_toc(d, cb); break;
    case 0x46: get_configuration(d, cb); break;
    case 0x4a: event_status(d, cb); break;
    case 0x51: disc_info(d); break;
    case 0xad: disc_structure(d, cb); break;
    case 0xbd: /* MECHANISM STATUS: one slot, idle, disc present */
        r[5] = 1, r[7] = 0;
        reply(d, r, 8);
        break;
    case 0x0a: case 0x2a: case 0xaa: case 0x8a: case 0x2e: case 0x55: case 0x15: /* writes */
        sense(d, SK_DATA_PROTECT, 0x27, 0);
        break;
    default:
        if (mlog()) fprintf(stderr, "usb-msc: unsupported SCSI command %02x\n", cb[0]);
        sense(d, SK_ILLEGAL, 0x20, 0);
        break;
    }
    if (mlog()) fprintf(stderr, "usb-msc: cmd %02x len %u -> status %u reply %u\n", cb[0], d->expected, d->status, d->reply_len);
}

void hv_usb_msc_out(HvUsbMsc *d, const uint8_t *data, uint32_t len) {
    if (d->state == HV_MSC_DATA_OUT) { /* nothing we take data for: drop it */
        d->done += len;
        if (d->done >= d->expected) d->state = HV_MSC_CSW;
        return;
    }
    if (d->state != HV_MSC_CBW) return;
    uint32_t take = len < sizeof d->cbw - d->cbw_len ? len : (uint32_t)sizeof d->cbw - d->cbw_len;
    memcpy(d->cbw + d->cbw_len, data, take);
    d->cbw_len += take;
    if (d->cbw_len < sizeof d->cbw) return;
    d->cbw_len = 0;
    const uint8_t *c = d->cbw;
    if ((uint32_t)(c[0] | c[1] << 8 | c[2] << 16 | (uint32_t)c[3] << 24) != CBW_SIG) return; /* not a CBW: ignore */
    d->tag = (uint32_t)(c[4] | c[5] << 8 | c[6] << 16 | (uint32_t)c[7] << 24);
    d->expected = (uint32_t)(c[8] | c[9] << 8 | c[10] << 16 | (uint32_t)c[11] << 24);
    d->done = 0;
    int in = c[12] & 0x80;
    if ((c[13] & 0x0f) != 0) sense(d, SK_ILLEGAL, 0x25, 0), d->reply_len = 0, d->from_disc = 0; /* LUN */
    else command(d, c + 15);
    if (d->expected == 0) {
        d->state = HV_MSC_CSW;
    } else if (in) {
        d->actual_left = d->reply_len < d->expected ? d->reply_len : d->expected;
        d->state = HV_MSC_DATA_IN;
    } else {
        d->state = HV_MSC_DATA_OUT;
    }
}

int hv_usb_msc_in_ready(const HvUsbMsc *d) {
    return d->state == HV_MSC_DATA_IN || d->state == HV_MSC_CSW;
}

uint32_t hv_usb_msc_in(HvUsbMsc *d, uint8_t *buf, uint32_t max) {
    if (d->state == HV_MSC_CSW) {
        uint8_t csw[13];
        le32(csw, CSW_SIG);
        le32(csw + 4, d->tag);
        le32(csw + 8, d->expected - d->done);
        csw[12] = d->status;
        uint32_t n = max < 13 ? max : 13;
        memcpy(buf, csw, n);
        d->state = HV_MSC_CBW;
        return n;
    }
    if (d->state != HV_MSC_DATA_IN) return 0;
    if (!d->actual_left) { /* less data than the host asked for: end the stage short */
        d->state = HV_MSC_CSW;
        return 0;
    }
    uint32_t n = max < d->actual_left ? max : d->actual_left;
    if (d->from_disc) {
        if (hv_disk_read(&d->disk, d->disc_off + d->done, buf, n) != 0) {
            memset(buf, 0, n);
            sense(d, 0x03, 0x11, 0); /* medium error: unrecovered read error */
        }
        d->bytes_read += n;
    } else {
        memcpy(buf, d->reply + d->done, n);
    }
    d->done += n;
    d->actual_left -= n;
    if (!d->actual_left && (n < max || d->done == d->expected)) d->state = HV_MSC_CSW;
    return n;
}
