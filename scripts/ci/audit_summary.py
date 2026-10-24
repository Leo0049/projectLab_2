#!/usr/bin/env python3
"""相依稽核摘要（N-29）：把 OWASP dependency-check 與 npm audit 的 JSON 報告整理成 Markdown。

報告模式：稽核的用途是讓維護者看見已知漏洞。升級相依套件可能破壞相容性，要另外評估，
所以發現漏洞本身不讓 CI 失敗。這個腳本的結束碼只回答一件事：報告是否可讀。

  - 報告可讀（有漏洞或沒有漏洞）→ 回傳 0
  - 找不到報告、不是 JSON、OWASP 報告沒有相依項目、或 npm audit 自己回報錯誤 → 回傳 1

用法（工作目錄為 repo 根目錄）：

  python3 scripts/ci/audit_summary.py owasp target/dependency-check-report.json
  python3 scripts/ci/audit_summary.py npm "$RUNNER_TEMP/npm-audit.json"

輸出為 Markdown，CI 以 `| tee -a "$GITHUB_STEP_SUMMARY"` 寫進步驟摘要。
"""
import json
import sys

SEVERITY_RANK = {'CRITICAL': 4, 'HIGH': 3, 'MEDIUM': 2, 'LOW': 1}
TOP_N = 20
NPM_LEVELS = (('critical', 'CRITICAL'), ('high', 'HIGH'), ('moderate', 'MODERATE'),
              ('low', 'LOW'), ('info', 'INFO'))


def load(path):
    """回傳 (資料, 問題說明)，兩者只會有一個是 None。"""
    try:
        with open(path, encoding='utf-8') as fh:
            data = json.load(fh)
    except FileNotFoundError:
        return None, f'找不到報告：{path}'
    except (OSError, ValueError) as exc:
        return None, f'無法解析報告 {path}：{exc}'
    if not isinstance(data, dict):
        return None, f'報告的最上層不是 JSON 物件：{path}'
    return data, None


def cell(text):
    """Markdown 表格的儲存格：轉義管線符號，換行壓成空白。"""
    return str(text).replace('|', '\\|').replace('\r', ' ').replace('\n', ' ')


def severity_of(vuln):
    for raw in (vuln.get('severity'),
                (vuln.get('cvssv3') or {}).get('baseSeverity'),
                (vuln.get('cvssv2') or {}).get('severity')):
        if raw:
            return str(raw).upper()
    return 'UNKNOWN'


def score_of(vuln):
    for node in (vuln.get('cvssv3') or {}, vuln.get('cvssv2') or {}):
        for key in ('baseScore', 'score'):
            try:
                return float(node[key])
            except (KeyError, TypeError, ValueError):
                continue
    return None


def owasp_summary(data):
    deps = data.get('dependencies')
    if not isinstance(deps, list) or not deps:
        return None, '報告沒有相依項目，掃描可能沒有實際執行，不能視為「沒有漏洞」'

    rows = []
    seen = set()
    vulnerable = 0
    for dep in deps:
        name = dep.get('fileName') or dep.get('filePath') or '?'
        vulns = dep.get('vulnerabilities') or []
        if vulns:
            vulnerable += 1
        for vuln in vulns:
            cve = vuln.get('name') or '?'
            if (name, cve) in seen:
                continue
            seen.add((name, cve))
            rows.append((severity_of(vuln), score_of(vuln), name, cve))
    rows.sort(key=lambda r: (-SEVERITY_RANK.get(r[0], 0), -(r[1] or 0), r[2], r[3]))

    lines = ['## Maven 相依稽核（OWASP dependency-check，報告模式）', '',
             f'- 掃描的相依檔：{len(deps)} 個，其中 {vulnerable} 個有已知漏洞紀錄',
             f'- 不重複的（相依檔，CVE）組合：{len(rows)} 筆']
    if not rows:
        lines += ['', '沒有發現已知漏洞（以本次執行時的 NVD 資料為準）。']
        return lines, None

    counts = {}
    for sev, _, _, _ in rows:
        counts[sev] = counts.get(sev, 0) + 1
    lines += ['', '| 嚴重度 | 數量 |', '|---|---:|']
    for sev in sorted(counts, key=lambda s: -SEVERITY_RANK.get(s, 0)):
        lines.append(f'| {cell(sev)} | {counts[sev]} |')
    lines += ['', f'前 {min(TOP_N, len(rows))} 筆（依嚴重度與分數排序）：', '',
              '| 嚴重度 | 分數 | 相依檔 | CVE |', '|---|---:|---|---|']
    for sev, score, name, cve in rows[:TOP_N]:
        shown = '-' if score is None else f'{score:.1f}'
        lines.append(f'| {cell(sev)} | {shown} | {cell(name)} | {cell(cve)} |')
    return lines, None


def npm_summary(data):
    if 'error' in data:
        err = data.get('error') or {}
        detail = f"{err.get('code', '?')} {err.get('summary', '')}".strip()
        return None, f'npm audit 無法完成：{detail}'
    meta = (data.get('metadata') or {}).get('vulnerabilities')
    vulns = data.get('vulnerabilities')
    if not isinstance(meta, dict) or not isinstance(vulns, dict):
        return None, 'npm audit 的輸出不含 metadata.vulnerabilities，格式不符'

    lines = ['## npm 相依稽核（scripts/，報告模式）', '', '| 嚴重度 | 數量 |', '|---|---:|']
    for key, label in NPM_LEVELS:
        lines.append(f'| {label} | {meta.get(key, 0)} |')
    lines.append(f"| 合計 | {meta.get('total', len(vulns))} |")

    if not vulns:
        lines += ['', '沒有發現已知漏洞。']
        return lines, None

    def rank(name):
        sev = str((vulns[name] or {}).get('severity', '')).upper()
        return -SEVERITY_RANK.get(sev, 0), name

    lines += ['', '| 套件 | 嚴重度 | 直接相依 | 可修補 |', '|---|---|---|---|']
    for name in sorted(vulns, key=rank):
        v = vulns[name] or {}
        fix = v.get('fixAvailable')
        if isinstance(fix, dict):
            fix_text = f"是（{fix.get('name', '?')}@{fix.get('version', '?')}）"
        else:
            fix_text = '是' if fix else '否'
        direct = '是' if v.get('isDirect') else '否'
        sev = str(v.get('severity', '?')).upper()
        lines.append(f'| {cell(name)} | {cell(sev)} | {direct} | {cell(fix_text)} |')
    return lines, None


def main(argv):
    if len(argv) != 3 or argv[1] not in ('owasp', 'npm'):
        print(__doc__, file=sys.stderr)
        return 2
    kind, path = argv[1], argv[2]
    data, problem = load(path)
    lines = None
    if problem is None:
        lines, problem = owasp_summary(data) if kind == 'owasp' else npm_summary(data)
    if problem is not None:
        print('## 相依稽核沒有產出可讀的報告')
        print()
        print(f'- {problem}')
        print('- 這是稽核步驟本身的失敗，不代表沒有漏洞。請看該步驟的日誌。')
        return 1
    print('\n'.join(lines))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
