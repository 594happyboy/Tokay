#!/usr/bin/env bash
#
# Tokay —— GeckoView 兼容矩阵（按需工具，不挂进常规构建 / CI 的每次构建）
#
# 对给定的一串 GeckoView 版本，逐个跑与常规构建**完全相同**的断言任务：
#
#     sh gradlew :demo:verifyGeckoviewInstrumentationTargets -PgeckoviewVersion=<ver>
#
# 不再另写 javap/grep 平行实现——否则「矩阵绿」与「构建绿」会变成两件事。
# 「零下载」也不是口头承诺：Gradle 真下载了包，脚本会在该版本的结论里点名。
#
# 用法：
#   bash tools/tokay-compat-matrix.sh                         # 只跑 Gradle 缓存里已有的版本（零下载）
#   bash tools/tokay-compat-matrix.sh 140.0.20250707120347 147.0.20260212191108
#   bash tools/tokay-compat-matrix.sh --yes-download 143.0.20250908174027
#   bash tools/tokay-compat-matrix.sh --out /tmp/matrix.md 140.0.20250707120347
#
# 选项：
#   --yes-download     允许为未缓存的版本联网下载 GeckoView AAR（约 300MB/个；默认直接跳过）
#   --offline          给 Gradle 加 --offline（对只缓存了包、却没建立离线元数据的版本会解析失败）
#   --timeout <sec>    单个版本超时秒数（默认 900；下大包时建议 >=1800）
#   --out <file>       markdown 表同时写入该文件（stdout 仍会打印）
#   -h | --help        帮助
#
# 退出码：0 = 所有被实际执行的版本都跑出了断言结论；1 = 有版本未产出结论；2 = 用法错误。

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE_ROOT="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/org.mozilla.geckoview/geckoview"
LOG_DIR="$REPO_ROOT/build/tokay-compat-matrix"
TASK=":demo:verifyGeckoviewInstrumentationTargets"
ESTIMATED_AAR_MB="约 300MB"

YES_DOWNLOAD=0
USE_OFFLINE=0
TIMEOUT_SEC=900
OUT_FILE=""
VERSIONS=()

log() { printf '[matrix] %s\n' "$*" >&2; }

usage() {
    # 打印脚本头部注释（第 2 行起、连续的 # 行），避免把行号写死
    awk 'NR > 1 { if ($0 ~ /^#/) { sub(/^# ?/, ""); print } else { exit } }' "${BASH_SOURCE[0]}"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --yes-download) YES_DOWNLOAD=1 ;;
        --offline) USE_OFFLINE=1 ;;
        --timeout) shift; TIMEOUT_SEC="${1:?--timeout 需要一个秒数}" ;;
        --out) shift; OUT_FILE="${1:?--out 需要一个文件路径}" ;;
        -h|--help) usage; exit 0 ;;
        -*) log "未知选项: $1"; usage; exit 2 ;;
        *) VERSIONS+=("$1") ;;
    esac
    shift
done

if [ ! -f "$REPO_ROOT/gradlew" ]; then
    log "在 $REPO_ROOT 下找不到 gradlew，脚本必须在仓库内运行"
    exit 2
fi

cached_aar() {
    find "$CACHE_ROOT/$1" -type f -name '*.aar' 2>/dev/null | head -n 1
}

cached_versions() {
    [ -d "$CACHE_ROOT" ] || return 0
    local dir
    for dir in "$CACHE_ROOT"/*; do
        [ -d "$dir" ] || continue
        local ver
        ver="$(basename "$dir")"
        if [ -n "$(cached_aar "$ver")" ]; then
            printf '%s\n' "$ver"
        fi
    done | sort -V
}

human_size() {
    local bytes="$1"
    if [ -z "$bytes" ]; then
        printf '未知'
        return
    fi
    if command -v numfmt >/dev/null 2>&1; then
        numfmt --to=iec-i --suffix=B "$bytes"
    else
        awk -v b="$bytes" 'BEGIN { printf "%.1fMB", b / 1048576 }'
    fi
}

if [ "${#VERSIONS[@]}" -eq 0 ]; then
    while IFS= read -r ver; do
        [ -n "$ver" ] && VERSIONS+=("$ver")
    done < <(cached_versions)
    if [ "${#VERSIONS[@]}" -eq 0 ]; then
        log "没有显式给版本，Gradle 缓存里也没有 GeckoView AAR；请显式传版本（未缓存需 --yes-download）"
        exit 2
    fi
    log "未指定版本，使用 Gradle 缓存里已有的版本：${VERSIONS[*]}"
fi

mkdir -p "$LOG_DIR"

ROWS=""
UNRESOLVED=0

for ver in "${VERSIONS[@]}"; do
    aar="$(cached_aar "$ver")"
    offline_args=()
    if [ -n "$aar" ]; then
        log "版本 ${ver}：缓存命中 $aar（$(human_size "$(stat -c %s "$aar")" )）"
        [ "$USE_OFFLINE" -eq 1 ] && offline_args+=(--offline)
    else
        if [ "$YES_DOWNLOAD" -eq 0 ]; then
            log "版本 ${ver}：未缓存 → 跳过（该版本 GeckoView AAR ${ESTIMATED_AAR_MB}；确认要下载请加 --yes-download）"
            ROWS+="| ${ver} | ⏭ 跳过（未缓存） | — | 未缓存；需 \`--yes-download\` 才会下载（${ESTIMATED_AAR_MB}） |"$'\n'
            continue
        fi
        log "版本 ${ver}：未缓存 → 将联网下载 GeckoView AAR（${ESTIMATED_AAR_MB}），单版本超时 ${TIMEOUT_SEC}s"
    fi

    log_file="$LOG_DIR/${ver}.log"
    log "运行: sh gradlew ${TASK} -PgeckoviewVersion=${ver} ${offline_args[*]:-}"
    (
        cd "$REPO_ROOT" || exit 2
        timeout "$TIMEOUT_SEC" sh gradlew "$TASK" "-PgeckoviewVersion=$ver" "${offline_args[@]}"
    ) > "$log_file" 2>&1
    rc=$?

    # 结论只从断言任务自己的稳定标记行读（见 VerifyGeckoviewInstrumentationTargetsTask）
    result_line="$(grep -E '\[GeckoViewASM\] 插桩目标校验(通过|失败):' "$log_file" | tail -n 1)"
    missing_lines="$(grep -E '\[GeckoViewASM\] 缺失目标:' "$log_file" | sed 's/^.*\[GeckoViewASM\] 缺失目标: //')"

    if printf '%s' "$result_line" | grep -q '插桩目标校验通过:'; then
        targets="$(printf '%s' "$result_line" | sed -n 's/.*targets=\([0-9]*\).*/\1/p')"
        result='✅ 通过'
        missing_cell='—'
        conclusion="断言表与该版本兼容：${targets:-6} 个插桩目标全部命中"
    elif printf '%s' "$result_line" | grep -q '插桩目标校验失败:'; then
        missing_count="$(printf '%s' "$result_line" | sed -n 's/.*missing=\([0-9]*\).*/\1/p')"
        result='❌ 失败'
        if [ -n "$missing_lines" ]; then
            missing_cell="$(printf '%s' "$missing_lines" | paste -sd '<br>' -)"
        else
            missing_cell="缺失 ${missing_count:-?} 个目标（见日志）"
        fi
        conclusion="该版本签名已变（缺失 ${missing_count:-?} 个）：需同步更新 asm-plugin 的 InstrumentationTargets 与 visitor"
        UNRESOLVED=1
    else
        result="⚠️ 未产出结论（exit=${rc}）"
        missing_cell="—"
        conclusion="断言任务没跑出结论（依赖解析/超时等）；原始输出见 ${log_file#"$REPO_ROOT"/}"
        UNRESOLVED=1
    fi

    download_lines="$(grep -E 'Download(ing)?[^ ]* .*\.(aar|7z|zip)' "$log_file" | head -n 3)"
    if [ -n "$download_lines" ]; then
        log "⚠️ 版本 ${ver}：本次运行发生了下载 → $(printf '%s' "$download_lines" | paste -sd ';' -)"
        conclusion="${conclusion}；⚠️ 本次运行发生了下载：$(printf '%s' "$download_lines" | paste -sd ';' -)"
    fi

    ROWS+="| ${ver} | ${result} | ${missing_cell} | ${conclusion} |"$'\n'
    log "版本 ${ver} → ${result}"
done
TABLE=$(cat <<EOF
| GeckoView 版本 | 结果 | 缺失目标 | 结论 |
| --- | --- | --- | --- |
${ROWS}
EOF
)

printf '%s\n' "$TABLE"

if [ -n "$OUT_FILE" ]; then
    mkdir -p "$(dirname "$OUT_FILE")"
    printf '%s\n' "$TABLE" > "$OUT_FILE"
    log "markdown 表已写入 $OUT_FILE"
fi

log "原始 Gradle 输出：$LOG_DIR/<版本>.log"
[ "$UNRESOLVED" -eq 0 ] || exit 1
exit 0
