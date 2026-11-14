# The Android port

This document is the engineering view of WindowsAndroid: what had to change to move the
[WindowsiOS](https://github.com/ammaarreshi/WindowsiOS) engine from Darwin to Android, how the
Android app is put together, and how to test it. WindowsiOS's own documentation (the iOS app,
its JIT setup and the engine's history) is in that repository.

## Why it ports at all

WindowsiOS does not use a hypervisor. It runs Windows 11 ARM64 by **translating ARM64 to
ARM64** (`core/translate.c`) under a 4 KB software MMU (`core/mmu.c`), against a virtual PC
built in C (`devices/`). Nothing in that needs EL2, so it needs nothing Android withholds
from apps: phones like the Galaxy S26 Ultra and Pixel 10 Pro expose no `/dev/kvm` to apps
(Pixel's pKVM is reserved to system components through the Android Virtualization
Framework), but every app may JIT. The engine is ~20k lines of portable C; what was
Darwin-specific was the host layer around it.

## What changed in the engine

| Area | WindowsiOS (Darwin) | WindowsAndroid (Linux / Android) |
| :--- | :--- | :--- |
| Guest counter (`CNTVCT`) | `mach_absolute_time` via the commpage, 24 MHz | `mrs cntvct_el0`, at `CNTFRQ_EL0` (19.2 MHz on Snapdragon, 24 MHz on the emulator) |
| JIT memory | `MAP_JIT` + `pthread_jit_write_protect_np`, or a debugger-blessed RX/RW pair on iOS | one `memfd` mapped twice, R-X and RW- (what ART's JIT does in app processes), RWX as a fallback |
| I-cache maintenance | `sys_icache_invalidate` | `dc cvau` on the RW alias (unless `CTR_EL0.IDC`), then `__builtin___clear_cache` on the RX alias |
| Guest RAM | ledger-exempt Mach memory entries on iOS | `MAP_NORESERVE` anonymous memory, named in `smaps` |
| Timed waits | `pthread_cond_timedwait_relative_np` | `pthread_cond_clockwait(CLOCK_MONOTONIC)` |
| Thread classes | QoS classes | nice values matching Android's DISPLAY / URGENT_DISPLAY priorities |
| Assembly | Mach-O (`_sym`, `@PAGE`) | ELF (`sym`, `:lo12:`), via `host/hv_asm.h` |
| Audio | CoreAudio (`host/audio_mac.c`) | AAudio low-latency streams (`host/audio_aaudio.c`) |
| Display | Metal (`devices/fb_metal.m`) | GLES 3 render thread (`app/src/main/cpp/display_gles.c`) |
| Screenshots | ImageIO | zlib PNG writer (`devices/fb_png.c`) |
| Shared folder | `copyfile`/`removefile` | `sendfile`/`nftw` |

All of it is in `host/hv_os.{h,c}` or behind `HV_OS_DARWIN`, so the macOS runner that
provisioning uses, and the unit tests on a Mac, still build from the same tree (`make all
test-core`). The iOS app and its iOS-only engine code are not in this repository.

Three Android details worth knowing:

- **The guest counter frequency is the host's.** Translated code reads the host counter
  directly, so the guest's `CNTFRQ_EL0` must be the host's too. Every "24 MHz" constant in the
  engine (WFI timeouts, spin windows, statistics) now goes through `hv_host_freq()`.
- **x18 is the platform register.** Darwin reserves it; Android uses it for the shadow call
  stack. The translator already kept guest x18 in the `HvCpu`, so this cost nothing; the test
  harness, which runs test programs natively, now saves and restores it.
- **Heap pointers are tagged.** Android's allocator puts a tag in the top byte (TBI). Guest RAM
  and the JIT come from `mmap`, so the engine never feeds one to the guest, but the manifest
  still sets `allowNativeHeapPointerTagging="false"` for code that hashes host pointers.

## The app

```text
app/src/main/
├── cpp/
│   ├── CMakeLists.txt     # pulls in the engine (../../../../CMakeLists.txt) as a static library
│   ├── jni_bridge.c       # VmNative: lifecycle, display, input, stats, links/clipboard, unpack
│   └── display_gles.c     # GLES 3 presenter: aspect fit, BGRX swizzle, dirty-band uploads
└── java/com/windowsandroid/
    ├── VmController.kt    # one VM per process; status polling; links + clipboard bridge
    ├── VmService.kt       # foreground service (specialUse) + partial wake lock
    ├── ExternalScreen.kt  # external display: Windows full screen there, the phone as trackpad
    ├── DisplayView.kt     # SurfaceView + touch / S Pen / mouse / keyboard / IME
    ├── InputPump.kt       # ordered HID key events off the UI thread, sticky modifiers
    ├── HidKeys.kt         # Android keycodes and characters -> USB HID usages
    ├── MachineStore.kt    # machine files and settings
    └── ui/                # Compose: home screen, session screen, theme
```

**Input.** The guest sees a USB keyboard and an absolute tablet on the virtual xHCI controller.
Fingers drive it as a laptop trackpad (default) or directly; the S Pen is always direct, with
hover and the side button as right-click; mice (USB, Bluetooth, DeX) are absolute with all
buttons and the wheel. Hardware keyboards are passed through by Linux scan code, which the
engine's xHCI device already maps to HID, so any physical layout works. The IME is a
"visible password" input connection, so text arrives keystroke by keystroke without
suggestions or composing.

**Display.** ramfb lives in guest RAM. The presenter samples it in 16-row bands each vsync and
uploads only the bands that changed, with a full refresh every 250 ms for changes too small for
the sampling to see. Nothing is drawn while the desktop is idle.

**External displays (the phone as a portable desktop).** Plug the phone into a monitor or TV
(USB-C DisplayPort, an HDMI adapter, a dock, a wireless display) during a session and Windows
moves there, full screen, while the phone's own screen becomes the trackpad and keyboard (any
orientation). Android mirrors a second display until an app shows a `Presentation` on it;
`ExternalScreen` does that, and `SurfaceRouter` hands the one GLES presenter to whichever
window should show Windows (the presenter blanks the window it leaves). Unplug, and Windows
comes back to the phone without stopping.

Windows' screen size is fixed at boot. The stock EDK2 ramfb driver offers 640x480, 800x600 and
1024x768 and starts at 800x600, and Windows' basic display driver keeps that mode. So:

- The app ships its own firmware (`app/src/main/assets/firmware/QEMU_EFI.fd`): EDK2
  `edk2-stable202608` ArmVirtQemu plus one patch to `QemuRamfbDxe`
  (`scripts/firmware/0001-…patch`), which reads the host's size from the fw_cfg file
  `opt/org.windowsandroid/ramfb-resolution` ("1920x1080"), offers it as a mode and starts in
  it. Without that file it behaves exactly like stock. `scripts/build_firmware.sh` rebuilds it
  with the NDK's clang. A `code.fd` in the machine folder still overrides it.
- The engine always publishes that file (`HvWinVmConfig.display_width/height`, CLI
  `--resolution WxH`) and can rewrite it in place (`hv_winvm_set_boot_resolution`), which a warm
  reboot picks up.
- The app boots at the external display's size when one is attached at Start (halved until it
  fits 2560x1600, so a 4K screen gets 1920x1080), otherwise at the default 800x600. Plugging in
  or changing a display sets the size for Windows' next restart, and the phone says so.
- Windows remembers the desktop size per monitor and would show the old one, scaled. Provisioning
  installs `guest/fit-display.ps1` (`C:\Windows\WindowsAndroid`, run at every sign-in from
  `HKLM\…\Run`), which sets the desktop to the size the display path is actually scanning out
  (`QueryDisplayConfig`'s target mode). The first run compiles a small helper (~1 min under
  emulation) and caches it in `%LOCALAPPDATA%\WindowsAndroid`. For an image provisioned
  before this, download the script and add the same `Run` value (HKLM or HKCU) by hand.

Samsung DeX and Android's desktop windowing are the other way to use a monitor: there the app
itself runs on the monitor in a window, and the session fills it.

**Installing Windows from the ISO.** The home screen's *Install Windows* card takes
Microsoft's "Windows 11 (multi-edition ISO for Arm64)" and needs no computer:

- `devices/usb_msc.c` is a read-only USB DVD drive (Mass Storage, Bulk-Only Transport, the
  SCSI/MMC commands UEFI and Windows send an optical drive). `xhci.c` has two of them on ports
  3 and 4, present only when given a disc (`HvWinVmConfig.dvd[]`, CLI `--dvd`). The firmware
  boots the first one by El Torito, as with a real USB DVD drive.
- The ISO is read in place: the app opens the document (persisted permission) and hands the
  engine `/proc/self/fd/N`. Nothing is copied.
- The second drive holds the *answer disc*, which the app writes on every start of an
  installation (`install/AnswerDisc.kt`, `install/IsoImage.kt`: ISO 9660 with Joliet).
  Windows Setup finds `Autounattend.xml` on removable media by itself. The answer file
  (`assets/install/autounattend.xml`) sets the ISO's language (from its volume label, e.g.
  `CCCOMA_A64FRE_EN-US_DV9`), the US keyboard the app's key mapping assumes, the phone's time
  zone, Windows 11's LabConfig bypasses (no TPM or Secure Boot here), one disk (EFI, MSR,
  Windows), Microsoft's generic Windows 11 Pro installation key, a local account without a
  password, and OOBE without questions. In the specialize pass it runs `WASETUP.CMD` from the
  disc, which installs the bundled NetKVM driver and the display helper.
- Microsoft's disc asks to "Press any key to boot from CD or DVD". With `dvd_boot_keys` the
  engine presses Space until the disc's loader has read 48 MB (Windows Setup's `boot.wim` is
  loading), and the app sets it only while the disk has no partition table. Later boots go
  past the unanswered prompt to the installed Windows.
- The first sign-in runs `curl -X PUT http://10.0.2.4/.henge/setup-done`, retrying for up to
  ten minutes while the network comes up (it waits with `ping`: `timeout` exits at once in
  first-logon commands, which have no console input). The share server
  flags it (`hv_share_take_setup_done`), the app forgets the ISO and deletes the answer disc,
  and the next start boots without the drives. An installation forces the network and the
  share on for this.
- The disk is a 64 GB sparse file; Windows takes about 20 GB of it.

**Staying alive.** The VM runs on native threads; `VmService` keeps the process at foreground
priority (and the CPU awake) while you are in another app or the screen is off. The engine is
single-use per process, so when Windows shuts down the app closes its process.

**Samsung specifics.** The activity handles every configuration change itself, so folding,
rotating, DeX and resizing never restart Windows. `com.samsung.android.keepalive.density` keeps
the process alive across DeX switches. In DeX (or on any large screen) orientation is left to
the window manager; on a phone the session is landscape.

## Building

Requirements: Android Studio (or JDK 21+), Android SDK 37, NDK r30 (30.0.16248370), CMake
4.1.2. For networking: meson, ninja, pkg-config.

```bash
scripts/build_android_deps.sh          # once: libffi 3.8.0, glib 2.90.1, libslirp 4.9.7 for arm64 Android
./gradlew :app:assembleDebug           # app/build/outputs/apk/debug/app-debug.apk
```

Without `android-deps/`, the engine builds with an unplugged network card instead
(`host/net_null.c`).

Release builds (`./gradlew :app:assembleRelease`) are signed with the key named by the Gradle
properties `WINDOWSANDROID_RELEASE_STORE_FILE`, `_STORE_PASSWORD`, `_KEY_ALIAS` and `_KEY_PASSWORD`
(put them in `~/.gradle/gradle.properties`, never in the repository). Without them the release build
is signed with the local debug key.

## Testing on the emulator

The arm64 system images run on Apple-silicon Macs under Hypervisor.framework, and the JIT runs
inside them at native speed, so the whole stack can be tested without a phone.

```bash
# Engine unit tests on the device/emulator
ANDROID_SERIAL=emulator-5554 scripts/android_test_engine.sh

# Headless VM from adb shell, screenshots as PNG
adb push build/android/winvm app/src/main/assets/firmware/QEMU_EFI.fd work/vars.fd /data/local/tmp/winvm/
adb shell 'cd /data/local/tmp/winvm && ./winvm --code QEMU_EFI.fd --vars vars.fd --ram 2048 \
             --resolution 1280x720 --screenshot shot.png --screenshot-after 20 --timeout 25'

# The app, booting straight into Windows, with a command file for automation (debug builds)
adb shell am start -n com.windowsandroid/.MainActivity --ez autostart true --ei cpus 4 --el ram_mb 3072
adb shell 'echo "type hello" >> /sdcard/Android/data/com.windowsandroid/files/commands.txt'
adb shell cat /sdcard/Android/data/com.windowsandroid/files/winvm.log
```

An external display can be simulated: the emulator's console adds one (Android sees a
presentation display, as with a real HDMI or USB-C monitor), and `screencap -d` captures it:

```bash
adb emu multidisplay add 1 1920 1080 160 0     # plug in a 1080p monitor (del 1 unplugs it)
adb shell dumpsys SurfaceFlinger --display-id  # its id, for:
adb exec-out screencap -p -d <id> > monitor.png
```

An installation can be run the same way on a Mac (the answer disc comes from the app's unit
test, `./gradlew :app:testDebugUnitTest`, as `app/build/answer-test.iso`):

```bash
mkdir -p work/inst && truncate -s 64g work/inst/disk.img && truncate -s 64m work/inst/vars.fd  # blank, sparse
build/winvm --code app/src/main/assets/firmware/QEMU_EFI.fd --vars work/inst/vars.fd --disk work/inst/disk.img \
  --dvd Win11_Arm64.iso --dvd app/build/answer-test.iso --dvd-boot-keys --share work/inst/share --headless
```

The command language is in `host/vm_commands.h` (`click X Y`, `type TEXT`, `win`, `screenshot
PATH`, ...). In the app (debug builds only) the command file is followed from its end: lines
appended after Windows starts run, and a file replaced with `adb push` runs from the top, so a
script left behind by an earlier test never replays.

## Provisioning notes

`scripts/provision_windows_arm64.py` builds the disk on a Mac with the macOS build of this
engine. Windows has no driver for the virtual NIC out of the box; the script puts virtio-win's
WHQL NetKVM ARM64 driver inside Windows PE (`X:\drivers\netkvm`), copies it to the new
installation, and installs it with `pnputil` in the specialize pass of the first boot.
(Offline injection with `dism /Add-Driver` fails in this Windows PE with 0x80004002.) For an
image made without it, attach a disk holding the driver as `installer.img` and run
`pnputil /add-driver D:\netkvm\netkvm.inf /install` from an elevated prompt.

Guest DNS: libslirp forwards the guest's DNS server (10.0.2.3) to the host's, which it reads
from `resolv.conf`. Android has none, so `build_android_deps.sh` patches libslirp to take the
path from `SLIRP_RESOLV_CONF`, and the app keeps that file in sync with the default network.

If a new emulator hangs at start with *"detected a hanging thread 'QEMU2 CPU0 thread'"*, its
virtio-wifi device is waiting on a wedged `netsimd`; start it without the netsim devices:

```bash
emulator -avd <name> -feature -WiFiPacketStream,-Uwb,-BluetoothEmulation,-VirtioWifi
```
