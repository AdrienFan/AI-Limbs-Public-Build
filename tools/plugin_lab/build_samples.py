#!/usr/bin/env python3
"""Build deterministic .ailp fixtures without compiling the Android app."""

from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

REPO = Path(__file__).resolve().parents[2]
SAMPLES = REPO / "plugin-lab" / "samples"
OUTPUT = REPO / "app" / "src" / "pluginLab" / "assets" / "plugin_samples"
PACKAGES = {
    "headless-echo": "headless-echo.ailp",
    "runtime-log-viewer": "runtime-log-viewer.ailp",
}


def build(source_name: str, output_name: str) -> None:
    source = SAMPLES / source_name
    target = OUTPUT / output_name
    temporary = target.with_suffix(target.suffix + ".tmp")
    target.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(temporary, "w") as archive:
        for file in sorted(path for path in source.rglob("*") if path.is_file()):
            relative = file.relative_to(source).as_posix()
            info = ZipInfo(relative, date_time=(2020, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, file.read_bytes())
    temporary.replace(target)
    print(target.relative_to(REPO))


if __name__ == "__main__":
    for source_name, output_name in PACKAGES.items():
        build(source_name, output_name)
