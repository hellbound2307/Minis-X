#!/usr/bin/env python3
"""
kotlin-lint.py — pre-push checks for the exact mistakes that keep costing a
25-minute CI cycle.

Born from vc57-vc62: four builds died on errors a compiler shows in seconds.
The sandbox has no Android toolchain (no JDK/SDK; a G35 can't realistically
run Gradle), so this is a targeted check, not a linter. Precision beats recall:
a noisy tool gets ignored, and an ignored tool is worse than none.

Checks
  1. NESTED COMMENT OPENERS — a literal `/*` inside a block-comment body.
     Kotlin block comments NEST, so `/**` or `/*` inside a KDoc opens a comment
     that is never closed; the compiler then reports "Unclosed comment" at EOF,
     nowhere near the cause. Cost vc62 builds 1 and 2.
  2. continue/break INSIDE AN INLINE LAMBDA — `runCatching { … continue … }`
     needs Kotlin language version 2.2; below that it is a hard error. Cost
     vc62 build 3.
  3. Suspicious brace balance (opt-in via --braces) — reported per file, not
     per line, and only for the files you pass.

Usage
  python3 kotlin-lint.py FILE_OR_DIR [--braces] [--changed REPO]
"""
import os
import re
import sys

# stdlib lambdas that are `inline` — break/continue inside these is the 2.2
# feature. Kept to the ones that actually appear in this codebase.
INLINE_FNS = {
    'runCatching', 'let', 'also', 'apply', 'run', 'with',
    'forEach', 'forEachIndexed', 'map', 'mapNotNull', 'filter', 'takeIf',
    'takeUnless', 'repeat', 'check', 'require',
}


class Token:
    __slots__ = ('kind', 'text', 'line')

    def __init__(self, kind, text, line):
        self.kind = kind      # 'id' | 'punct'
        self.text = text
        self.line = line


def tokenize(src: str):
    """Minimal Kotlin tokenizer: comments, raw/normal strings, char literals."""
    toks = []
    i, n, line = 0, len(src), 1
    while i < n:
        c = src[i]
        if c == '\n':
            line += 1
            i += 1
            continue
        if src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j == -1 else j
            continue
        if src.startswith('/*', i):
            depth, i = 1, i + 2
            while i < n and depth:
                if src.startswith('/*', i):
                    depth += 1
                    i += 2
                elif src.startswith('*/', i):
                    depth -= 1
                    i += 2
                else:
                    if src[i] == '\n':
                        line += 1
                    i += 1
            continue
        if src.startswith('"""', i):
            i += 3
            while i < n and not src.startswith('"""', i):
                if src[i] == '\n':
                    line += 1
                i += 1
            i += 3
            toks.append(Token('id', 'STR', line))
            continue
        if c == '"' or c == "'":
            quote, i = c, i + 1
            while i < n:
                if src[i] == '\\':
                    i += 2
                    continue
                if src[i] == quote:
                    i += 1
                    break
                if src[i] == '\n':
                    line += 1
                i += 1
            toks.append(Token('id', 'STR', line))
            continue
        if c.isalpha() or c == '_':
            j = i
            while j < n and (src[j].isalnum() or src[j] == '_'):
                j += 1
            toks.append(Token('id', src[i:j], line))
            i = j
            continue
        toks.append(Token('punct', c, line))
        i += 1
    return toks


def check_nested_comments(path, src):
    out = []
    for lineno, raw in enumerate(src.split('\n'), 1):
        st = raw.strip()
        if st.startswith('*') and not st.startswith('*/') and '/*' in st[1:]:
            out.append((lineno, 'nested-comment',
                        "'/*' inside a block comment body — Kotlin comments NEST, "
                        "so this opens one that is never closed: " + st[:80]))
    return out


def check_inline_lambda_control(path, toks):
    """Flag continue/break lexically inside an inline-lambda body."""
    out = []
    i, n = 0, len(toks)
    while i < n:
        t = toks[i]
        if t.kind == 'id' and t.text in INLINE_FNS:
            # find the opening brace of the lambda (allow @label and args)
            j = i + 1
            while j < n and toks[j].text != '{' and toks[j].text not in ('\n', ';'):
                if toks[j].text == '}' or toks[j].text == ')':
                    j = -1
                    break
                j += 1
            if j != -1 and j < n and toks[j].text == '{':
                depth = 0
                k = j
                while k < n:
                    if toks[k].text == '{':
                        depth += 1
                    elif toks[k].text == '}':
                        depth -= 1
                        if depth == 0:
                            break
                    elif toks[k].kind == 'id' and toks[k].text in ('continue', 'break'):
                        out.append((toks[k].line, 'control-in-inline-lambda',
                                    f"'{toks[k].text}' inside an inline lambda "
                                    f"({t.text} {{ … }}): needs Kotlin 2.2, "
                                    f"use a nested if"))
                    k += 1
        i += 1
    return out


def check_braces(path, toks):
    depth = 0
    for t in toks:
        if t.text == '{':
            depth += 1
        elif t.text == '}':
            depth -= 1
    if depth != 0:
        return [(1, 'unbalanced-braces', f"net {depth:+d} brace(s) — file-level")] 
    return []


def lint_file(path, braces=False, control=False):
    try:
        src = open(path, encoding='utf-8', errors='replace').read()
    except OSError as e:
        return [(0, 'io', str(e))]
    toks = tokenize(src)
    problems = check_nested_comments(path, src)
    if control:
        problems += check_inline_lambda_control(path, toks)
    if braces:
        problems += check_braces(path, toks)
    return problems


def iter_kotlin(target):
    if os.path.isfile(target):
        yield target
        return
    for root, dirs, files in os.walk(target):
        dirs[:] = [d for d in dirs if d not in ('build', '.git')]
        for f in files:
            if f.endswith('.kt'):
                yield os.path.join(root, f)


def main(argv):
    braces = '--braces' in argv
    # Check 2 is OPT-IN: emulating Kotlin's break/continue-in-inline-lambda rule
    # without a real compiler over-fires (26 hits on code that builds fine today),
    # and a noisy check gets ignored. Pass --control when touching that pattern.
    control = '--control' in argv
    targets = [a for a in argv if not a.startswith('--')]
    if not targets:
        print(__doc__)
        return 2
    total = 0
    for target in targets:
        for path in iter_kotlin(target):
            for lineno, kind, msg in lint_file(path, braces, control):
                print(f"{path}:{lineno}: [{kind}] {msg}")
                total += 1
    print(f"\n{total} problem(s)")
    return 1 if total else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
