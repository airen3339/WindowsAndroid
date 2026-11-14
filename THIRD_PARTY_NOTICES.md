# Third-Party Notices

WindowsAndroid is licensed as a whole under the **GNU General Public License v2.0 or later**
(`GPL-2.0-or-later`; see [`LICENSE`](LICENSE)). It is derived from WindowsiOS, which is under
the same license, and keeps all upstream copyright notices. The components it uses or ships
are listed below with their licenses.

| Component | Authors | License | How WindowsAndroid uses it |
| :--- | :--- | :--- | :--- |
| **WindowsiOS** | [`ammaarreshi/WindowsiOS`](https://github.com/ammaarreshi/WindowsiOS) (Ammaar Reshi & contributors) | `GPL-2.0-or-later` | The base of this repository: the engine (`core/`, `devices/`, `host/`), unit tests, provisioning and Windows-side tools. Its iOS app is not included |
| **Henge** (`HengeVM`) | [`Leviidev/Henge`](https://github.com/Leviidev/Henge) (Levii & contributors) | `GPL-2.0-or-later` | The ARM64-to-ARM64 translator, software MMU, entry trampoline and JIT allocator (`core/`, `host/enter.S`, `host/jit_region.c`) and the `/.henge` WebDAV share server (`host/share.c`), by way of WindowsiOS |
| **Husk** | [`Leviidev/Husk`](https://github.com/Leviidev/Husk) (Levii & contributors) | `GPL-2.0-or-later` | ARM64 translation design that WindowsiOS drew on |
| **libslirp** | [`slirp/libslirp`](https://gitlab.freedesktop.org/slirp/libslirp) (Danny Gasparovski, Fabrice Bellard & contributors) | `BSD-3-Clause` | User-mode TCP/IP, DHCP and DNS for the guest (`host/net_slirp.c`). `scripts/build_android_deps.sh` builds `4.9.7` for Android with a small patch (DNS server from `SLIRP_RESOLV_CONF`) and links it statically into the app |
| **GLib, libffi, PCRE2, proxy-libintl** | GNOME GLib `2.90.1`, libffi `3.8.0`, PCRE2 `10.46`, proxy-libintl | `LGPL-2.1-or-later` / `MIT` / `BSD-3-Clause` / `LGPL-2.1-or-later` | libslirp's dependencies, built from source by `scripts/build_android_deps.sh` and linked statically into the app |
| **TianoCore EDK2** (`ArmVirtQemu`) | [`tianocore/edk2`](https://github.com/tianocore/edk2) (Intel, Arm, Linaro & contributors) | `BSD-2-Clause-Patent` | The UEFI firmware bundled in the app (`app/src/main/assets/firmware/QEMU_EFI.fd`), with EDK2's OpenSSL (`Apache-2.0`), Brotli (`MIT`), Oniguruma (`BSD-2-Clause`) and libfdt (`BSD-2-Clause`) |
| **virtio-win** (`NetKVM`) | [`virtio-win/kvm-guest-drivers-windows`](https://github.com/virtio-win/kvm-guest-drivers-windows) (Red Hat, Inc. & contributors) | `BSD-3-Clause` | Windows' network driver. The WHQL-signed Windows 11 Arm64 build is bundled in the app (`app/src/main/assets/install/netkvm/`) and installed by the in-app Windows installation; the Mac provisioning script takes it from your own `virtio-win.iso` |
| **AndroidX, Jetpack Compose, Kotlin** | Google, JetBrains & contributors | `Apache-2.0` | The Android app's libraries, fetched by Gradle |

---

## WindowsiOS (`GPL-2.0-or-later`)

- **Repository:** <https://github.com/ammaarreshi/WindowsiOS>

WindowsAndroid began as a clone of WindowsiOS. It keeps the engine and moves it from Darwin to
Linux/Android (`host/hv_os.{h,c}` and the changes listed in [`docs/ANDROID.md`](docs/ANDROID.md)),
adds the Android app, the external-display support and the Android build, and removes the iOS
app and its tooling. All files remain under `GPL-2.0-or-later`.

```text
WindowsiOS: Copyright (c) Ammaar Reshi and contributors.
```

## Henge and Husk (`GPL-2.0-or-later`)

- **Repositories:** <https://github.com/Leviidev/Henge>, <https://github.com/Leviidev/Husk>

WindowsiOS adapted and extended Henge's `HengeVM` translator, software MMU, entry trampoline,
JIT allocator and WebDAV share server, and drew on Husk's ARM64 translation work. WindowsiOS
also credits [`mxhm11cr/LinuxRunner`](https://github.com/mxhm11cr/LinuxRunner) for guest
bring-up ideas (no source from it is used).

```text
This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
```


---

## libslirp (`BSD-3-Clause`)

- **Repository:** <https://gitlab.freedesktop.org/slirp/libslirp>

```text
Copyright (c) 1995,1996 Danny Gasparovski. All rights reserved.
Copyright (c) 2004-2008 Fabrice Bellard.
Copyright (c) libslirp contributors.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

1. Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.
3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived
   from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```


### libslirp's dependencies

- **GLib `2.90.1`** and **proxy-libintl**: `LGPL-2.1-or-later`
  (<https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html>). Source:
  <https://download.gnome.org/sources/glib/>. `scripts/build_android_deps.sh` rebuilds them.
- **libffi `3.8.0`**: Copyright (c) 1996-2025 Anthony Green, Red Hat, Inc. and contributors.
  `MIT` License.
- **PCRE2 `10.46`**: Copyright (c) University of Cambridge (Philip Hazel) and contributors.
  `BSD-3-Clause` (with the PCRE2 exception).

---

## TianoCore EDK2 `ArmVirtQemu` (`BSD-2-Clause-Patent`)

- **Repository:** <https://github.com/tianocore/edk2>

The app ships one firmware binary, `app/src/main/assets/firmware/QEMU_EFI.fd`: EDK2
`edk2-stable202608` built for `ArmVirtQemu` with one patch
(`scripts/firmware/0001-OvmfPkg-QemuRamfbDxe-host-resolution-from-fw_cfg.patch`, same license),
rebuilt by `scripts/build_firmware.sh`. The corresponding source is that EDK2 tag with its
submodules, plus the patch. It includes OpenSSL (`Apache-2.0`), Brotli (`MIT`), Oniguruma
(`BSD-2-Clause`) and libfdt (`BSD-2-Clause`) from EDK2's submodules.

```text
SPDX-License-Identifier: BSD-2-Clause-Patent

Copyright (c) 2011 - 2024, ARM Limited. All rights reserved.
Copyright (c) 2014 - 2024, Linaro Limited. All rights reserved.
Copyright (c) 2006 - 2024, Intel Corporation. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

Subject to the terms and conditions of this license, each copyright holder
and contributor hereby grants to those receiving rights under this license
a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable
(except for failure to satisfy the conditions of this license) patent license
to make, have made, use, offer to sell, sell, import, and otherwise transfer
this software, where such license applies only to those patent claims, already
acquired or hereafter acquired, licensable by such copyright holder or
contributor that are necessarily infringed by:

(a) their Contribution(s) (the licensed copyrights of copyright holders and
    non-copyrightable additions of contributors, in source or binary form)
    alone; or
(b) combination of their Contribution(s) with the work of authorship to which
    such Contribution(s) was added by such copyright holder or contributor, if,
    at the time the Contribution is added, such addition causes such combination
    to be necessarily infringed. The patent license shall not apply to any other
    combinations which include the Contribution.

Except as expressly stated above, no patent license is granted either expressly
or by implication, estoppel, or otherwise.

DISCLAIMER

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```


---

## virtio-win `NetKVM` (`BSD-3-Clause`)

- **Repository:** <https://github.com/virtio-win/kvm-guest-drivers-windows>
- **Copyright:** Red Hat, Inc., Google, Inc., Virtuozzo, Inc., IBM Corporation and contributors

The app bundles the WHQL-signed Windows 11 Arm64 `NetKVM` driver (`netkvm.inf`, `netkvm.sys`,
`netkvm.cat`, `netkvmp.exe`; driver version 100.103.104.30200, from virtio-win's
`NetKVM/w11/ARM64`) in `app/src/main/assets/install/netkvm/`, unmodified, with virtio-win's
license as `LICENSE.txt` next to it. The in-app installation puts them on the answer disc and
installs them with `pnputil` on Windows' first boot. `scripts/provision_windows_arm64.py`
does the same with the driver from your own `virtio-win.iso`.

```text
Copyright 2009-2022 Red Hat, Inc. and/or its affiliates.
Copyright 2016 Google, Inc.
Copyright 2016 Virtuozzo, Inc.
Copyright 2007 IBM Corporation

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.

Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.

Neither the name of the copyright holder nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

---

## Software not included

**Microsoft Windows 11 ARM64** is Microsoft's and is governed by the Microsoft Software License
Terms. It is never part of this repository or the app: you download Microsoft's official Arm64
ISO yourself and the app installs from it on your phone (or the provisioning script builds the
disk on your own Mac under `work/`, which git ignores). The answer file uses Microsoft's
published generic installation key for Windows 11 Pro, which selects the edition and does not
activate Windows.
