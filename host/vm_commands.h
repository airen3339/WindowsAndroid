/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef HV_VM_COMMANDS_H
#define HV_VM_COMMANDS_H

/* Text commands that drive a running VM: what `--cmd-file` / HV_INPUT_FILE tail,
   and what the Android app's automation hook feeds in. One command per line:
     click X Y | rclick X Y | move X Y      (X, Y normalised 0..1)
     hidkey N | win | winr | enter | cad | power
     type TEXT | screenshot PATH | cpus | wait MS | quit
     oauthmode 0|1 | oauthstatus */

/* Type one printable ASCII character (or \n, \t) as USB HID key presses. */
void hv_vm_type_char(char ch);
/* Type a UTF-8 string; characters with no US-keyboard key are skipped. */
void hv_vm_type_text(const char *text);
/* Run one command line. Returns 1 for "quit", 0 otherwise. */
int hv_vm_command(const char *line);
/* Tail `path` on a background thread, running each new line until "quit" or *stop. A file that is
   replaced (a new inode: adb push, an editor saving) is run again from its first line. */
void hv_vm_command_file_start(const char *path, volatile int *stop);
/* The same, but lines already in the file are skipped: only what is appended (or a replacement
   file) runs. For a long-lived file that must not replay an old script on the next start. */
void hv_vm_command_file_follow(const char *path, volatile int *stop);

#endif
