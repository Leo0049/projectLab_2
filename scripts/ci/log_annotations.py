#!/usr/bin/env python3
"""稽核排查註解（N-29）：把 mvn 輸出整理成 GitHub Actions 註解。

報告讀不到時，維護者需要知道 mvn 停在哪裡。日誌下載需要登入，也可能被擋；
註解會顯示在執行頁面上，也能由 REST API 的 check-runs/{id}/annotations 讀取。

輸出兩段，各取最後 --lines 行（預設 40）：
  1. 錯誤行：含 [ERROR]、[WARNING]、Exception、Caused by 的行
  2. 尾段：mvn 輸出的最後幾行
每段以 `::notice::` 指令輸出，每則約 3000 字元，每段最多 4 則。
設定了 GITHUB_STEP_SUMMARY 時，同時把兩段寫進步驟摘要。

只做格式整理，不判斷稽核成功與否：檔案不存在或是空的也只輸出警告並回傳 0，
讓排查步驟本身不會讓工作流失敗。參數錯誤才回傳 2（argparse）。

用法（工作目錄為 repo 根目錄）：

  python3 scripts/ci/log_annotations.py "$RUNNER_TEMP/audit/owasp-mvn.log"
"""
import argparse
import os
import sys

CHUNK_CHARS = 3000
MAX_CHUNKS = 4
KEYWORDS = ('[ERROR]', '[WARNING]', 'Exception', 'Caused by')
TITLE = 'OWASP 稽核排查'


def escape_data(text):
    """工作流指令的訊息轉義：% 與換行（CR、LF）。"""
    return text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')


def chunks(lines, limit=CHUNK_CHARS):
    """依行邊界切段，每段不超過 limit 字元；單行超過 limit 時截斷。"""
    out = []
    buf = []
    size = 0
    for line in lines:
        if len(line) > limit:
            line = line[:limit - 1] + '…'
        added = len(line) + (1 if buf else 0)
        if buf and size + added > limit:
            out.append('\n'.join(buf))
            buf = [line]
            size = len(line)
        else:
            buf.append(line)
            size += added
    if buf:
        out.append('\n'.join(buf))
    return out


def emit(label, lines):
    """以 ::notice:: 輸出一段；超過 MAX_CHUNKS 則註明未顯示的段數。"""
    parts = chunks(lines)
    for index, part in enumerate(parts[:MAX_CHUNKS]):
        message = f'[{label} {index + 1}/{len(parts)}]\n{part}'
        print(f'::notice title={TITLE}::{escape_data(message)}')
    if len(parts) > MAX_CHUNKS:
        hidden = len(parts) - MAX_CHUNKS
        print(f'::notice title={TITLE}::[{label}] 另有 {hidden} 段未顯示，完整輸出見 owasp-mvn-log artifact')


def main(argv=None):
    parser = argparse.ArgumentParser(description='把 mvn 輸出整理成 GitHub Actions 註解（僅供排查）')
    parser.add_argument('log', help='mvn 輸出檔')
    parser.add_argument('--lines', type=int, default=40, help='每段保留的行數（預設 40）')
    args = parser.parse_args(argv)

    try:
        with open(args.log, encoding='utf-8', errors='replace') as fh:
            lines = [line.rstrip('\r\n') for line in fh]
    except OSError:
        print(f'::warning title={TITLE}::找不到 mvn 輸出檔，步驟可能在啟動前就失敗')
        return 0
    if not any(line.strip() for line in lines):
        print(f'::warning title={TITLE}::mvn 輸出是空的，步驟可能在啟動前就失敗')
        return 0

    errors = [line for line in lines if any(key in line for key in KEYWORDS)][-args.lines:]
    tail = lines[-args.lines:]

    if errors:
        emit('錯誤行', errors)
    else:
        print(f'::notice title={TITLE}::mvn 輸出中沒有 [ERROR]、[WARNING] 或例外行')
    emit('尾段', tail)

    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as fh:
            fh.write(f'\n### mvn 錯誤行（最後 {len(errors)} 行）\n\n```text\n')
            fh.write('\n'.join(errors) + '\n```\n')
            fh.write(f'\n### mvn 輸出尾段（最後 {len(tail)} 行）\n\n```text\n')
            fh.write('\n'.join(tail) + '\n```\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
