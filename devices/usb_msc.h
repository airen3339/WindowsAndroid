/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef HENGEVM_USB_MSC_H
#define HENGEVM_USB_MSC_H

#include <stdint.h>

#include "disk.h"

/* A read-only USB DVD drive: USB Mass Storage, Bulk-Only Transport, SCSI/MMC commands, an ISO
   image as the disc. UEFI (UsbMassStorageDxe) and Windows (usbstor + cdrom) drive it with their
   own drivers, the way they drive a real USB DVD drive, which is how the app boots a Windows
   installation ISO. The USB side (descriptors, endpoints, rings) is in xhci.c. */

enum { HV_MSC_CBW, HV_MSC_DATA_IN, HV_MSC_DATA_OUT, HV_MSC_CSW };

#define HV_MSC_BLOCK 2048u
#define HV_MSC_BUF (128u * 1024u) /* one xHCI TRB carries at most 128 KB - 1 */

typedef struct HvUsbMsc {
    HvDisk disk;
    uint64_t blocks;
    int state;
    /* the command in progress */
    uint32_t tag, expected, done;
    uint8_t status;
    uint8_t cbw[31];
    uint32_t cbw_len;
    /* data-in: a small reply, or a range of the disc */
    uint8_t reply[256];
    uint32_t reply_len;
    int from_disc;
    uint64_t disc_off;
    uint32_t actual_left; /* data-in bytes the device still has for this command */
    /* sense data for REQUEST SENSE */
    uint8_t sense_key, asc, ascq;
    int new_media;        /* GET EVENT STATUS NOTIFICATION reports the disc once */
    uint64_t bytes_read;  /* disc bytes the guest has read */
    uint8_t buf[HV_MSC_BUF];
} HvUsbMsc;

/* Open path (an ISO image) read-only. Returns NULL on failure. */
HvUsbMsc *hv_usb_msc_open(const char *path);
void hv_usb_msc_close(HvUsbMsc *d);
/* Bulk-Only Mass Storage Reset, or a bus reset. */
void hv_usb_msc_reset(HvUsbMsc *d);
/* Bulk OUT: the bytes of one OUT transfer (a CBW, or data-out). */
void hv_usb_msc_out(HvUsbMsc *d, const uint8_t *data, uint32_t len);
/* Whether the next bulk IN transfer has something to carry (data or the CSW). */
int hv_usb_msc_in_ready(const HvUsbMsc *d);
/* Bulk IN: up to max bytes of the current stage into buf. Fewer than max ends the stage
   (a short packet); the CSW follows in the next transfer. */
uint32_t hv_usb_msc_in(HvUsbMsc *d, uint8_t *buf, uint32_t max);

#endif
