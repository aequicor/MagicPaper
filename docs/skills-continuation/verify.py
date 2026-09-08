"""Read-only audit of the continuation baseline and requirement document."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "docs/skills-continuation/baseline.json"
DOC = ROOT / "docs/SKILLS-CONTINUATION.md"
baseline = json.loads(BASE.read_text())


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)


def sha(data):
    return hashlib.sha256(data).hexdigest()


changed = [p for p, expected in baseline["files"].items()
           if not (ROOT / p).is_file() or sha((ROOT / p).read_bytes()) != expected]
assert not changed, changed
assert git("rev-parse", "HEAD").decode().strip() == baseline["head"]
assert sha(git("diff", "--binary")) == baseline["diffSha256"]
assert sha(git("diff", "--cached", "--binary")) == baseline["cachedDiffSha256"]
paths = set(git("ls-files", "--cached", "--others", "--exclude-standard", "-z").decode().split("\0")) - {""}
added = sorted(p for p in paths if p not in baseline["files"]
               and p != "docs/SKILLS-CONTINUATION.md"
               and not p.startswith("docs/skills-continuation/"))
assert not added, added
historical = baseline["historical"]
for path, expected in historical["xml"].items():
    assert sha((ROOT / path).read_bytes()) == expected, path
roots = [ET.parse(ROOT / p).getroot() for p in historical["xml"]]
counts = {k: sum(int(r.get(k, 0)) for r in roots)
          for k in ("tests", "failures", "errors", "skipped")}
assert counts == historical["counts"]
doc = DOC.read_text()
links = re.findall(r"\]\(([^)]+)\)", doc)
missing = [p for p in links if not (DOC.parent / p).resolve().exists()]
assert not missing, missing
for i in range(1, 16):
    assert f"| R{i:02}." in doc and f"S{i:02}:" in doc, i
assert not any(line.rstrip() != line for line in doc.splitlines())
subprocess.run(["git", "diff", "--check"], cwd=ROOT, check=True)
print(json.dumps({"result": "PASS", "preservedFiles": len(baseline["files"]),
                  "headAndBothDiffsUnchanged": True, "unexpectedFiles": added,
                  "historicalXmlSuites": len(roots), "historicalXmlCounts": counts,
                  "documentLinks": len(links), "requirementScenarioPairs": 15,
                  "gradleRun": False, "liveTransportsTested": False},
                 ensure_ascii=False, indent=2))
