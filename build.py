#!/usr/bin/env python3
"""src/ui.html の /*CORE*/ に src/core.js を埋め込んで index.html を作る"""
from pathlib import Path
root = Path(__file__).parent
ui = (root / "src/ui.html").read_text(encoding="utf-8")
core = (root / "src/core.js").read_text(encoding="utf-8")
assert "/*CORE*/" in ui
(root / "index.html").write_text(ui.replace("/*CORE*/", core), encoding="utf-8")
print("index.html を作成しました")
