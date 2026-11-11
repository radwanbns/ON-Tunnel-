#!/usr/bin/env python3
"""يحوّل موديول :main في ics-openvpn من application إلى library حتى ينتج AAR.
يُشغَّل من جذر مستودع ics-openvpn. يطبع الملف الأصلي والمعدّل في السجل للتشخيص."""
import glob
import re
import sys

paths = glob.glob("main/build.gradle.kts") + glob.glob("main/build.gradle")
if not paths:
    sys.exit("main/build.gradle(.kts) not found")
path = paths[0]
kts = path.endswith(".kts")
src = open(path, encoding="utf-8").read()

print("=" * 20, "ORIGINAL", path, "=" * 20)
print(src)

lib = 'id("com.android.library")' if kts else "id 'com.android.library'"
s = src

# 1) المكوّن الإضافي: id / alias / apply plugin
s = re.sub(r"""id\s*\(?\s*["']com\.android\.application["']\s*\)?""", lib, s)
s = re.sub(r"""alias\s*\(?\s*libs\.plugins\.[\w.]*application[\w.]*\s*\)?""", lib, s)
s = re.sub(r"""apply\s+plugin\s*:\s*["']com\.android\.application["']""",
           "apply plugin: 'com.android.library'", s)


# 2) كتل خاصة بالتطبيقات فقط (غير مسموحة في المكتبات)
def remove_block(text: str, name: str) -> str:
    pat = re.compile(r"^[ \t]*" + name + r"\s*(?:=\s*)?\{", re.M)
    while True:
        m = pat.search(text)
        if not m:
            return text
        depth, i = 0, m.end() - 1
        while i < len(text):
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        end = i + 1
        if end < len(text) and text[end] == "\n":
            end += 1
        text = text[: m.start()] + text[end:]


for blk in ("signingConfigs", "splits", "dependenciesInfo", "bundle", "optimization"):
    s = remove_block(s, blk)

# 3) أسطر خاصة بالتطبيقات فقط (غير موجودة في DSL المكتبات في AGP 9)
APP_ONLY = (
    "applicationId|applicationIdSuffix|testApplicationId|signingConfig|"
    "versionNameSuffix|versionCode|versionName|"
    "isShrinkResources|shrinkResources|targetSdk|targetSdkPreview|"
    "generateLocaleConfig|isCrunchPngs|crunchPngs"
)
s = re.sub(r"^[ \t]*(?:" + APP_ONLY + r")\b.*\n", "", s, flags=re.M)



def remove_signing_refs(text: str) -> str:
    """يحذف أي سطر يذكر signingConfig/signingConfigs في أي موضع؛ وإن كان السطر يفتح كتلة
    ({ بلا } مقابلة) يحذف الكتلة كلها حتى لا تختل الأقواس."""
    pat = re.compile(r"^.*\bsigningConfigs?\b.*$", re.M)
    while True:
        m = pat.search(text)
        if not m:
            return text
        line = m.group(0)
        net = line.count("{") - line.count("}")
        end = m.end()
        if net > 0:
            depth, i = 0, m.start()
            while i < len(text):
                if text[i] == "{" and i >= m.start():
                    depth += 1
                elif text[i] == "}":
                    depth -= 1
                    if depth <= 0 and i >= m.end() - 1:
                        break
                i += 1
            end = i + 1
        if end < len(text) and text[end] == "\n":
            end += 1
        text = text[: m.start()] + text[end:]


s = remove_signing_refs(s)

# 4) متغيرات التطبيق -> المكتبة
s = s.replace("applicationVariants", "libraryVariants")

if s == src:
    sys.exit("PATCH DID NOT CHANGE ANYTHING: check the ORIGINAL printed above")
if "com.android.library" not in s:
    sys.exit("library plugin not applied: check the ORIGINAL printed above")

open(path, "w", encoding="utf-8").write(s)
print("=" * 20, "PATCHED", path, "=" * 20)
print(s)
