/* SPDX-License-Identifier: GPL-2.0-or-later */
/* Networking stand-in for builds without libslirp (WINVM_NET=OFF): the guest
   still sees its virtio-net adapter, just with the cable unplugged. */
#include "net_slirp.h"

#include <stdio.h>

uint64_t hv_net_tx_frames, hv_net_rx_frames;

void hv_net_set_share_socket(const char *path) {
    (void)path;
}

int hv_net_attach(HvMachine *m, HvVirtioNet *n) {
    (void)m;
    (void)n;
    fprintf(stderr, "winvm: built without libslirp: no guest networking\n");
    return -1;
}

void hv_net_poll(void) {}

void hv_net_add_hostfwd(int host_port, int guest_port) {
    (void)host_port;
    (void)guest_port;
}
