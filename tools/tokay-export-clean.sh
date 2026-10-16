#!/usr/bin/env bash
# 把当前工作区导出成一个干净仓（无历史、无内部笔记、无本机路径）。
# 用「新建仓 + 单次初始提交」导出，不沿用原仓历史。
#
# 安全约束：目标目录必须不存在或为空，绝不覆盖任何已存在的目录。
# 本脚本只读源仓；导出后新仓与源仓各自独立演进。
#
# 用法：
#   bash tools/tokay-export-clean.sh --check          # 只打印将包含/排除什么，不落盘
#   bash tools/tokay-export-clean.sh [目标目录]        # 默认 ~/Projects/tokay
set -euo pipefail

REPO="$(git rev-parse --show-toplevel)"
TARGET="${1:-$HOME/Projects/tokay}"
[ "${1:-}" = "--check" ] && TARGET=""

cd "$REPO"

EXCLUDES=(
  --exclude='.git/'
  --exclude='.gradle/'
  --exclude='.idea/'
  --exclude='.kotlin/'
  --exclude='.vscode/'
  --exclude='build/'
  --exclude='*/build/'
  --exclude='local.properties'
  --exclude='.staged-delivery/'
  --exclude='*.iml'
  --exclude='.DS_Store'
  --exclude='captures/'
  --exclude='.cxx/'
  --exclude='*.log'
  # 内核 OTA 包是运行时下发的产物，单个 arm64 包就有 57 MB
  --exclude='*.7z'
  --exclude='*.7z.sha256'
  # 内部笔记：工程证据（会话交接 / 调研 / 真机记录）与其目录说明，只留在源仓
  --exclude='docs/reports/'
  --exclude='docs/README.md'
)

echo "════ 将排除 ════"
printf '  %s\n' "${EXCLUDES[@]#--exclude=}"
echo
echo "════ 将包含的顶层条目 ════"
ls -A | while read -r e; do
  case "$e" in
    .git|.gradle|.idea|.kotlin|.vscode|.staged-delivery|local.properties|build) ;;
    *) printf '  %s\n' "$e" ;;
  esac
done

if [ -z "$TARGET" ]; then
  echo
  echo "（--check 模式，未落盘）"
  exit 0
fi

if [ -e "$TARGET" ] && [ -n "$(ls -A "$TARGET" 2>/dev/null)" ]; then
  echo "拒绝执行：目标目录已存在且非空 → $TARGET" >&2
  echo "请先自行确认并移走它，或换一个目标路径。" >&2
  exit 1
fi

echo
echo "════ 导出到 $TARGET ════"
mkdir -p "$TARGET"
rsync -a "${EXCLUDES[@]}" "$REPO"/ "$TARGET"/

echo
echo "════ 落盘校验 ════"
LEFT="$(find "$TARGET" \( -name build -o -name .git -o -name .staged-delivery -o -name local.properties -o -name '*.iml' -o -name '*.7z' -o -path '*/docs/reports' -o -path '*/docs/README.md' \) -print | head -10)"
if [ -n "$LEFT" ]; then
  echo "⚠ 仍存在应排除的路径：" >&2; echo "$LEFT" >&2; exit 1
fi
echo "  ✓ 无 build/ .git/ .staged-delivery/ local.properties *.iml *.7z docs/reports/ docs/README.md"

# gradlew 在源仓里没有执行位，开源仓应当补上，否则新克隆的人会踩
chmod +x "$TARGET/gradlew" 2>/dev/null || true
echo "  ✓ gradlew 执行位已补"

echo "  文件数: $(find "$TARGET" -type f | wc -l)"
echo "  体积  : $(du -sh "$TARGET" | cut -f1)"

echo
echo "下一步（由用户执行，涉及远端与凭据，agent 不做）："
echo "  cd $TARGET"
echo "  git init -b main && git add -A && git commit -m '...'"
echo "  git remote add origin <你的新仓库地址>"
echo "  git push -u origin main"
