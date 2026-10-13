#!/usr/bin/env python3
"""CI 的測試總計：讀 target/surefire-reports/TEST-*.xml，印出類別數、測試數、失敗、錯誤與略過。

為什麼需要：有些測試以 assumeTrue 判斷前置條件（例如示範資料是否已植入）。條件不成立時測試會被
「略過」而不是「失敗」，步驟仍然是綠的，等於沒有驗到東西。這個腳本把總數與略過的測試名稱輸出成
annotation（check-run 的 annotation 可以用 REST API 讀取）；加上 --fail-on-skipped 時，有略過的
測試就讓這一步失敗。

用法（工作目錄為 repo 根目錄）：

  python3 scripts/ci/surefire_totals.py [--fail-on-skipped]

沒有報告（mvn 在編譯階段就失敗）時只印說明並回傳 0；報告讀不出來時回傳 1，因為總數已不可信。
"""
import glob
import sys
import xml.etree.ElementTree as ET

REPORTS = 'target/surefire-reports/TEST-*.xml'


def escape_data(text):
    """workflow command 訊息的轉義規則（與 failure_annotations.py 相同）。"""
    return text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')


def escape_property(text):
    return escape_data(text).replace(':', '%3A').replace(',', '%2C')


def main(argv):
    strict = '--fail-on-skipped' in argv[1:]
    paths = sorted(glob.glob(REPORTS))
    if not paths:
        print('沒有 surefire 報告（mvn test 可能在編譯階段就失敗），略過測試總計')
        return 0

    tests = failures = errors = skipped = 0
    skipped_names = []
    unreadable = []
    for path in paths:
        try:
            suite = ET.parse(path).getroot()
        except ET.ParseError as exc:
            unreadable.append(f'{path}：{exc}')
            continue
        tests += int(suite.get('tests', 0))
        failures += int(suite.get('failures', 0))
        errors += int(suite.get('errors', 0))
        skipped += int(suite.get('skipped', 0))
        for case in suite.iter('testcase'):
            if case.find('skipped') is not None:
                skipped_names.append(f"{case.get('classname', '?')}.{case.get('name', '?')}")

    summary = (f'測試總計：{len(paths)} 個類別、{tests} 個測試，'
               f'失敗 {failures}、錯誤 {errors}、略過 {skipped}')
    print(summary)
    print(f"::notice title={escape_property('測試總計')}::{escape_data(summary)}")
    if skipped_names:
        heading = f'略過的測試（共 {len(skipped_names)} 個）'
        names = '; '.join(skipped_names)
        print(f"::warning title={escape_property(heading)}::{escape_data(names)}")
    if unreadable:
        names = '; '.join(unreadable)
        print(f"::error title={escape_property('無法讀取的測試報告')}::{escape_data(names)}")
        return 1
    if strict and skipped:
        print('有測試被略過（前置條件不成立）。CI 視為失敗：請確認示範資料與測試前置條件。')
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
