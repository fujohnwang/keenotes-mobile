#!/usr/bin/env python3
"""合并已完成的主回归与补跑 xcresult；只使用各来源测试最新的通过结果，默认不更新演示稿。"""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile


MAIN = "testCapturePurchaseAndHistoryDemonstrationInEnglish"
LATE = "testLateFixtureDeliveryAfterDismissingSubscriptionDoesNotOverwriteDraftOrCurrentConfiguration"
LANGUAGES = {
    "en": "testIAPInterfaceUsesEnglishWhenRegionIsChina",
    "zh-Hans": "testIAPInterfaceUsesSimplifiedChineseWhenRegionIsUS",
}
STEPS = ["01-settings", "02-subscription", "03-filled", "04-saved",
         "05-history", "06-confirm", "07-switched"]
WANTED = [(MAIN, "en-" + name, name + ".png") for name in STEPS]
WANTED.append((LATE, "08-late-draft", "08-late-draft.png"))
for language, method in LANGUAGES.items():
    for screen in ["settings", "subscription", "history", "confirm"]:
        name = "language-" + language + "-" + screen
        WANTED.append((method, name, name + ".png"))


def report(command, result):
    return json.loads(subprocess.check_output(
        ["xcrun", "xcresulttool", "get", "test-results", command,
         "--path", str(result), "--compact"], text=True))


def cases(nodes):
    for node in nodes:
        if node.get("nodeType") == "Test Case":
            yield node
        yield from cases(node.get("children", []))


def identifies(identifier, method):
    return identifier.removesuffix("()").endswith("SettingsFlowUITests/" + method)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path, nargs="+", help="已完成的主回归与补跑 .xcresult；不要传入仍在运行的结果")
    parser.add_argument("--apply", action="store_true", help="全部来源测试通过且16张截图齐全后，更新演示图片及来源清单")
    args = parser.parse_args()
    runs = []
    for result in dict.fromkeys(path.resolve() for path in args.results):
        summary = report("summary", result)
        if not summary.get("finishTime"):
            raise SystemExit("结果尚无完成时间；未更新任何演示图片：" + str(result))
        tree = report("tests", result)
        runs.append({"resultBundle": str(result), "summary": summary,
                     "cases": list(cases(tree.get("testNodes", [])))})
    runs.sort(key=lambda run: run["summary"]["finishTime"])
    sources = {}
    for method in {method for method, _, _ in WANTED}:
        matching_runs = [(index, [case for case in run["cases"]
                                  if identifies(case.get("nodeIdentifier", ""), method)])
                         for index, run in enumerate(runs)]
        matching_runs = [(index, matches) for index, matches in matching_runs if matches]
        if not matching_runs:
            raise SystemExit("截图来源测试缺失：" + method)
        index, latest = matching_runs[-1]
        if any(case.get("result") != "Passed" for case in latest):
            raise SystemExit("截图来源测试的最新结果未通过：" + method)
        sources[method] = index

    demo = Path(__file__).resolve().parent
    preserved = demo / "screenshots/09-xcode-purchase.png"
    preserved_hash = hashlib.sha256(preserved.read_bytes()).hexdigest()
    with tempfile.TemporaryDirectory(prefix="keenotes-iap-export-") as temporary:
        manifests = {}
        for index in sorted(set(sources.values())):
            exported = Path(temporary) / str(index)
            exported.mkdir()
            subprocess.run(["xcrun", "xcresulttool", "export", "attachments",
                            "--path", runs[index]["resultBundle"], "--output-path", str(exported),
                            "--filter", "*.png"], check=True, capture_output=True, text=True)
            manifests[index] = (exported, json.loads((exported / "manifest.json").read_text()))
        selected = []
        for method, label, filename in WANTED:
            index = sources[method]
            exported, manifest = manifests[index]
            matches = []
            for test in manifest:
                if not identifies(test.get("testIdentifier", ""), method):
                    continue
                for attachment in test.get("attachments", []):
                    suggested = attachment.get("suggestedHumanReadableName", "")
                    if suggested == label + ".png" or suggested.startswith(label + "_"):
                        matches.append((test, attachment))
            if len(matches) != 1:
                raise SystemExit(f"附件必须唯一：{label}，找到 {len(matches)} 项；未更新图片。")
            test, attachment = matches[0]
            source_name = attachment["exportedFileName"]
            if Path(source_name).name != source_name or attachment.get("isAssociatedWithFailure"):
                raise SystemExit("附件来源无效：" + label)
            source = exported / source_name
            if not source.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
                raise SystemExit("附件不是 PNG：" + label)
            record = {
                "file": filename, "test": test["testIdentifier"], "attachment": label,
                "timestamp": attachment.get("timestamp"), "deviceId": attachment.get("deviceId"),
                "resultBundle": runs[index]["resultBundle"], "testResult": "Passed",
                "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            }
            selected.append((source, record))

        if args.apply:
            for source, record in selected:
                destination = demo / "screenshots" / record["file"]
                staged = destination.with_suffix(".png.tmp")
                shutil.copyfile(source, staged)
                staged.replace(destination)
            (demo / "evidence/screenshots.json").write_text(
                json.dumps([record for _, record in selected], ensure_ascii=False, indent=2) + "\n")
            run_records = []
            for run in runs:
                record = {"resultBundle": run["resultBundle"], "summary": run["summary"]}
                stamp = hashlib.sha256(json.dumps(record, sort_keys=True).encode()).hexdigest()[:12]
                evidence_name = "screenshot-run-" + stamp + ".json"
                evidence = demo / "evidence" / evidence_name
                serialized = json.dumps(record, ensure_ascii=False, indent=2) + "\n"
                if evidence.exists() and evidence.read_text() != serialized:
                    raise SystemExit("已有结果证据不一致，停止覆盖：" + evidence_name)
                evidence.write_text(serialized)
                run_records.append({**record, "evidence": evidence_name})
            (demo / "evidence/screenshot-run-summary.json").write_text(
                json.dumps({"runs": run_records, "selection": "每项来源测试使用所给结果中最新的通过记录；保留原失败结果。"},
                           ensure_ascii=False, indent=2) + "\n")
        assert hashlib.sha256(preserved.read_bytes()).hexdigest() == preserved_hash
        print(("已更新" if args.apply else "检查通过，尚未更新") + f"：{len(selected)} 张真实截图；09 系统弹窗保留。")
        for _, record in selected:
            print(record["attachment"] + " -> screenshots/" + record["file"])


if __name__ == "__main__":
    main()
