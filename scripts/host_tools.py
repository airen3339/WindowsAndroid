"""Where the host tools the provisioning scripts use live, on any machine.

Tools are looked up on PATH first, then in the usual Homebrew prefixes (Apple silicon
/opt/homebrew, Intel /usr/local, Linuxbrew) and /usr, so nothing depends on one
person's setup. QEMU's EDK2 firmware files are found the same way, through
`brew --prefix qemu`, or from QEMU_FIRMWARE_DIR if set.
"""
import os
import shutil
import subprocess
import sys
from functools import lru_cache
from pathlib import Path

_PREFIXES = [p for p in (os.environ.get("HOMEBREW_PREFIX"), "/opt/homebrew", "/usr/local",
                         "/home/linuxbrew/.linuxbrew", "/usr") if p]


@lru_cache(maxsize=None)
def tool(name: str, install_hint: str) -> str:
    """The path of command `name`, or exit saying how to install it."""
    found = shutil.which(name)
    if found:
        return found
    for prefix in _PREFIXES:
        candidate = Path(prefix) / "bin" / name
        if candidate.exists():
            return str(candidate)
    sys.exit(f"{name} not found: install it ({install_hint}) or put it on PATH")


def wimlib() -> str:
    return tool("wimlib-imagex", "brew install wimlib, or your distribution's wimtools")


@lru_cache(maxsize=None)
def qemu_firmware(name: str) -> Path:
    """One of QEMU's firmware files (edk2-aarch64-code.fd, edk2-arm-vars.fd)."""
    dirs = []
    if os.environ.get("QEMU_FIRMWARE_DIR"):
        dirs.append(Path(os.environ["QEMU_FIRMWARE_DIR"]))
    try:
        brew_qemu = subprocess.run(["brew", "--prefix", "qemu"], capture_output=True, text=True, check=True).stdout.strip()
        if brew_qemu:
            dirs.append(Path(brew_qemu) / "share" / "qemu")
    except (OSError, subprocess.CalledProcessError):
        pass
    dirs += [Path(prefix) / "share" / "qemu" for prefix in _PREFIXES]
    for d in dirs:
        if (d / name).exists():
            return d / name
    sys.exit(f"QEMU's {name} not found: brew install qemu, or set QEMU_FIRMWARE_DIR to the folder that has it")
