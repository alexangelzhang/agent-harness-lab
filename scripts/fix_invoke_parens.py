#!/usr/bin/env python3
"""修正 invoke 迁移：把 GraphInput.args 的右括号从 invoke 末尾移到 Map 参数末尾。"""
import re
from pathlib import Path

ROOT = Path("/Users/zhangyiqian/projects/agent_design_model/src/main/java/com/example/langgraph4j")

def find_matching(s, open_idx):
    depth = 0
    for i in range(open_idx, len(s)):
        c = s[i]
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return i
    return -1

total = 0
for f in sorted(ROOT.rglob("*.java")):
    src = f.read_text(encoding="utf-8")
    edits = []
    for m in re.finditer(r'\.invoke\(GraphInput\.args\(', src):
        invoke_open = m.end() - 1 - len("GraphInput.args(")  # '(' of invoke(...)
        # recompute: m.end()-1 is '(' of args(
        args_open = m.end() - 1
        invoke_open = src.rindex('(', 0, m.start() + len('.invoke('))
        # map starts after args_open, skipping whitespace
        i = args_open + 1
        while src[i].isspace():
            i += 1
        map_start = i
        of_open = src.index('of(', map_start) + 2  # '(' of of(
        map_end = find_matching(src, of_open)      # index of map's closing ')'
        invoke_close = find_matching(src, invoke_open)
        # 现状：invoke( GraphInput.args( MAP , RC )) —— invoke_close-1 处是多余的 args 右括号
        edits.append((map_end, invoke_close))
    if not edits:
        continue
    new = src
    for map_end, invoke_close in reversed(edits):
        # 在 map 末尾插入 ')'，并删除 invoke_close-1 处的 ')'
        assert new[invoke_close - 1] == ')', f"{f}: 预期位置不是右括号"
        new = new[:invoke_close - 1] + new[invoke_close:]
        new = new[:map_end + 1] + ')' + new[map_end + 1:]
        total += 1
    f.write_text(new, encoding="utf-8")
    print(f"{f.name}: {len(edits)} 处修正")
print(f"共修正 {total} 处")
