#!/usr/bin/env python3
"""CI 失敗時，把失敗原因寫成 GitHub Actions annotation。

為什麼需要：步驟日誌只能在 Actions 網頁上看；check-run 的 annotation 則可以用 REST API 讀取
（gh api repos/<owner>/<repo>/check-runs/<job id>/annotations），失敗時不必登入網頁也能判讀。

用法（工作目錄為 repo 根目錄；來源檔不存在就略過；本腳本只輸出 workflow command，不會讓步驟失敗）：

  python3 scripts/ci/failure_annotations.py surefire <mvn 輸出檔>
      mvn test 失敗：讀 target/surefire-reports/TEST-*.xml；沒有報告時改讀 mvn 輸出的 [ERROR] 行。

  python3 scripts/ci/failure_annotations.py checks <端對端輸出> <UI 輸出> <後端 app.log>
      端對端與 UI 腳本對每條失敗的檢查印一行「  ✗ 名稱」；Node 未捕捉的例外印成「XxxError: …」；
      後端取 ERROR 行，並附上其後最後一行 Caused by:。
"""
import glob
import re
import sys
import xml.etree.ElementTree as ET

DETAIL_LIMIT = 9     # 單一步驟的 error annotation 上限附近，細節只給前幾筆；完整名稱另放在一則 warning
HEAD_LIMIT = 400     # 訊息本體上限
CAUSE_LIMIT = 500    # 根因（Caused by:）上限
NAME_LIMIT = 3000    # warning 內名稱清單上限

TIMESTAMP = re.compile(r'^\d{4}-\d{2}-\d{2} ')
ERROR_LOGGER = re.compile(r'\sERROR \[[^\]]*\] (\S+) - ')
NODE_ERROR = re.compile(r'^\s*\w*Error: ')


def escape_data(text):
    """workflow command 訊息的轉義規則。"""
    return text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')


def escape_property(text):
    return escape_data(text).replace(':', '%3A').replace(',', '%2C')


def read_lines(path):
    try:
        with open(path, encoding='utf-8', errors='replace') as fh:
            return fh.read().splitlines()
    except OSError:
        return None


def dedupe(items):
    seen = set()
    unique = []
    for item in items:
        if item not in seen:
            seen.add(item)
            unique.append(item)
    return unique


def emit(found):
    """found 為 [(標題, 細節)]：印出全部、輸出前幾筆 error，另加一則 warning 列出全部名稱。"""
    print(f'失敗項目：{len(found)} 筆')
    for title, detail in found:
        print(f'- {title} :: {detail}')
    for title, detail in found[:DETAIL_LIMIT]:
        print(f'::error title={escape_property(title)}::{escape_data(detail)}')
    if found:
        names = '; '.join(title for title, _ in found)
        if len(names) > NAME_LIMIT:
            names = names[:NAME_LIMIT] + '…（其餘略）'
        heading = f'失敗清單（共 {len(found)} 筆）'
        print(f'::warning title={escape_property(heading)}::{escape_data(names)}')


def root_cause(trace):
    causes = [line.strip() for line in trace.splitlines() if line.strip().startswith('Caused by:')]
    return causes[-1] if causes else ''


def surefire_failures(report_glob):
    found = []
    for path in sorted(glob.glob(report_glob)):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            print(f'無法解析 {path}：{exc}')
            continue
        for case in root.iter('testcase'):
            for kind in ('failure', 'error'):
                node = case.find(kind)
                if node is None:
                    continue
                title = f"{case.get('classname', '?')}.{case.get('name', '?')}"
                head = (node.get('message') or '').strip()[:HEAD_LIMIT]
                cause = root_cause(node.text or '')[:CAUSE_LIMIT]
                detail = f"{node.get('type') or kind}: {head}"
                if cause:
                    detail += f' ‖ {cause}'
                found.append((title, detail))
    return found


def surefire_mode(mvn_log):
    found = surefire_failures('target/surefire-reports/TEST-*.xml')
    if not found:
        print('沒有 surefire 失敗報告，改以 mvn 的 [ERROR] 行做摘要')
        lines = read_lines(mvn_log) or []
        found = [(f'mvn 第 {i} 行', line.strip()[:HEAD_LIMIT + CAUSE_LIMIT])
                 for i, line in enumerate(lines, 1) if '[ERROR]' in line]
    emit(dedupe(found))


def script_failures(lines, source):
    """端對端與 UI 腳本的失敗行（✗）與 Node 未捕捉的例外。"""
    found = []
    for line in lines or []:
        text = line.strip()
        if text.startswith('✗'):
            body = text[1:].strip()
            found.append((f"{source}：{body.split('  →')[0].strip()}", body[:HEAD_LIMIT + CAUSE_LIMIT]))
        elif NODE_ERROR.match(line):
            found.append((f'{source} 例外', text[:HEAD_LIMIT + CAUSE_LIMIT]))
    return dedupe(found)


def app_failures(lines):
    """後端 ERROR 行；細節附上其後（同一筆日誌內）最後一行 Caused by:。"""
    found = []
    lines = lines or []
    for i, line in enumerate(lines):
        if ' ERROR ' not in line:
            continue
        match = ERROR_LOGGER.search(line)
        title = f'後端 {match.group(1)}' if match else '後端 ERROR'
        cause = ''
        for follow in lines[i + 1:i + 80]:
            if TIMESTAMP.match(follow):
                break
            if follow.strip().startswith('Caused by:'):
                cause = follow.strip()[:CAUSE_LIMIT]
        detail = line.strip()[:HEAD_LIMIT]
        if cause:
            detail += ' ‖ ' + cause
        found.append((title, detail))
    return dedupe(found)


def checks_mode(e2e_log, ui_log, app_log):
    found = script_failures(read_lines(e2e_log), '端對端')
    found += script_failures(read_lines(ui_log), 'UI')
    found += app_failures(read_lines(app_log))
    emit(dedupe(found))


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 0
    mode, paths = argv[1], argv[2:]
    if mode == 'surefire':
        surefire_mode(paths[0] if paths else '')
    elif mode == 'checks':
        e2e_log, ui_log, app_log = (paths + ['', '', ''])[:3]
        checks_mode(e2e_log, ui_log, app_log)
    else:
        print(f'未知模式：{mode}')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
