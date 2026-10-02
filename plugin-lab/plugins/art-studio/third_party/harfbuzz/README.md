# HarfBuzz 11.0.1

Upstream release: https://github.com/harfbuzz/harfbuzz/releases/tag/11.0.1
Original archive: https://github.com/harfbuzz/harfbuzz/releases/download/11.0.1/harfbuzz-11.0.1.tar.xz
Original SHA256: `4a7890090538136db64742073af4b4d776ab8b50e6855676a8165eb8b7f60b7a`

The checked-in `harfbuzz-src-11.0.1.tar.xz` contains the unmodified regular files under `src/` and top-level `COPYING` from that release (473 files). Repacked SHA256: `fec8a07e51d99f4c511eedebd02ff86dbfa744c7a212c825d8d443b101a9a6c8`.
CMake checks this digest before extracting into its build directory. No source download occurs during build. Android NDK 27.0.12077973, CMake 3.31.0, static C++ runtime, four Android ABIs, 16 KiB compatible library alignment.

HarfBuzz Old MIT license and Microsoft USE license are preserved inside the archive and included in the plugin APK under assets/text-licenses. This module uses HarfBuzz's OpenType font functions, explicit font bytes/TTC index/variation axes and Android Canvas.drawGlyphs. It does not call an Android default Typeface and does not copy Krita code. Source construction and static review are complete; compilation and runtime verification have not been performed.
