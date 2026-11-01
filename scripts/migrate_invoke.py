#!/usr/bin/env python3
"""Migrate graph.invoke(Map, cfg) -> graph.invoke(GraphInput.args(Map), cfg)."""
import re
from pathlib import Path

ROOT = Path("/Users/zhangyiqian/projects/agent_design_model/src/main/java/com/example/langgraph4j")

def find_matching(s, open_idx):
    """index just past the ')' matching s[open_idx] == '('"""
    depth = 0
    for i in range(open_idx, len(s)):
        c = s[i]
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return i + 1
    return -1

changed_files = []
for f in sorted(ROOT.rglob("*.java")):
    src = f.read_text(encoding="utf-8")
    out = []
    i = 0
    hits = 0
    for m in re.finditer(r'\.invoke\(', src):
        pass
    # iterate with edits from the end to keep indices valid
    spans = []
    for m in re.finditer(r'(?<!\.invokeFinal)\.invoke\(', src):
        open_idx = m.end() - 1
        close_idx = find_matching(src, open_idx)
        if close_idx < 0:
            continue
        inner = src[open_idx + 1:close_idx - 1].strip()
        if not inner.startswith("Map.of("):
            continue
        spans.append((open_idx, close_idx))
    if not spans:
        continue
    new = src
    for open_idx, close_idx in reversed(spans):
        # wrap first argument: insert after 'invoke(' and add extra ')' before close
        new = new[:open_idx + 1] + "GraphInput.args(" + new[open_idx + 1:close_idx - 1] + ")" + new[close_idx - 1:]
        hits += 1
    if "import org.bsc.langgraph4j.GraphInput;" not in new:
        # insert after package / first existing org.bsc import
        imp = re.search(r'^import org\.bsc\.langgraph4j\.[^\n]*\n', new, re.M)
        if imp:
            new = new[:imp.end()] + "import org.bsc.langgraph4j.GraphInput;\n" + new[imp.end():]
        else:
            pkg = re.search(r'^package [^\n]*\n\n', new, re.M)
            new = new[:pkg.end()] + "import org.bsc.langgraph4j.GraphInput;\n\n" + new[pkg.end():]
    f.write_text(new, encoding="utf-8")
    changed_files.append((f.name, hits))

for name, n in changed_files:
    print(f"{name}: {n} 处")
print(f"共 {sum(n for _, n in changed_files)} 处")
