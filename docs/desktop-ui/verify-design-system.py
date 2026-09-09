#!/usr/bin/env python3
"""Small deterministic guard for the stage-3 design-system boundary."""
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[2]
module = root / "designSystem"
build = (module / "build.gradle.kts").read_text()
sources = list((module / "src").rglob("*.kt"))

errors = []
if 'project(":shared")' in build:
    errors.append(":designSystem must not depend on :shared")
for source in sources:
    text = source.read_text()
    if "public " in text and "androidx.compose.material" in text:
        # Imports are legitimate implementation details. Reject only a public
        # signature that exposes a Material type.
        for line in text.splitlines():
            if "public " in line and "material3." in line:
                errors.append(f"Material type in public API: {source.relative_to(root)}")
                break

if errors:
    print("FAIL")
    print("\n".join(errors))
    sys.exit(1)
print(f"PASS: :designSystem has no :shared dependency; scanned {len(sources)} Kotlin source files for Material public types")
