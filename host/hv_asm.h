/* SPDX-License-Identifier: GPL-2.0-or-later */
/* Assembly portability: the same .S files assemble for Mach-O (Darwin, where C
   symbols carry a leading underscore and page references are @PAGE/@PAGEOFF)
   and ELF (Linux / Android: no underscore, :lo12: page offsets). Use one
   directive per line: ';' is a comment on Darwin arm64 but a separator on ELF. */
#ifndef HV_ASM_H
#define HV_ASM_H

#if defined(__APPLE__)
#define SYM(n) _##n
#define PAGE(n) _##n@PAGE
#define PAGEOFF(n) _##n@PAGEOFF
#define LPAGE(n) n@PAGE /* a local label (no underscore either way) */
/* Mach-O: nothing extra for symbol type / visibility. */
#define HV_FUNC(n) .p2align 2
#define HV_HIDDEN(n) .p2align 0
#else
#define SYM(n) n
#define PAGE(n) n
#define PAGEOFF(n) :lo12:n
#define LPAGE(n) n
#define HV_FUNC(n) .type n, %function
#define HV_HIDDEN(n) .hidden n
#endif

#endif
