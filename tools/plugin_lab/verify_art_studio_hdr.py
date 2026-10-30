#!/usr/bin/env python3
"""Independent OpenEXR 3.3.5 reader checks the Kotlin writer's cloud-test output."""
import argparse
from pathlib import Path
import OpenEXR
import numpy as np

parser = argparse.ArgumentParser()
parser.add_argument("path", type=Path)
args = parser.parse_args()
image = OpenEXR.File(str(args.path), separate_channels=True)
channels = image.channels()
index = np.arange(95).reshape(19, 5)
expected = {"R": 4 + index * .125, "G": np.full((19, 5), -.25),
            "B": np.full((19, 5), .03125), "A": np.where(index % 3 == 0, 0, .5)}
assert set(channels) == set(expected)
for name, values in expected.items():
    np.testing.assert_allclose(channels[name].pixels, values, rtol=0, atol=.0001)
print("Independent OpenEXR reader: signed HDR, HALF ZIP, alpha and emissive zero-alpha pixels OK")
