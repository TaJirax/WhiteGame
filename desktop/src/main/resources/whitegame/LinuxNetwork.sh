set -euo pipefail
export LC_ALL=C
export PATH=/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/sbin:/usr/local/bin
umask 077

operation=$1
engine=$2
target=$3
shift 3

if [[ $operation == tunnel ]]; then
    [[ $target =~ ^wg[0-9a-f]{10}$ ]] || exit 2
    workspace=$(mktemp -d /run/whitegame.XXXXXXXX)
    config="$workspace/$target.conf"
    connected=0
    cleanup() {
        local result=$?
        trap - EXIT
        if (( connected )); then
            "$engine" down "$config" >&2 || result=1
        fi
        rm -f -- "$config"
        rmdir -- "$workspace"
        exit "$result"
    }
    trap cleanup EXIT
    trap 'exit 1' HUP INT TERM
    IFS= read -r payload
    printf '%s' "$payload" | base64 --decode > "$config"
    "$engine" up "$config" >&2
    connected=1
elif [[ $operation == dns ]]; then
    [[ $target =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,14}$ ]] || exit 2
    current_dns() { "$engine" dns "$target" | sed 's/^[^:]*:[[:space:]]*//'; }
    baseline=$(current_dns)
    applied=''
    cleanup() {
        local result=$? current
        local -a original
        trap - EXIT
        if [[ -n $applied ]] && current=$(current_dns) && [[ $current == "$applied" ]]; then
            if [[ -n $baseline ]]; then
                read -r -a original <<< "$baseline"
                "$engine" dns "$target" "${original[@]}" >&2 || result=1
            else
                "$engine" dns "$target" "" >&2 || result=1
            fi
        fi
        exit "$result"
    }
    trap cleanup EXIT
    trap 'exit 1' HUP INT TERM
    "$engine" dns "$target" "$@" >&2
    applied="$*"
    if current=$(current_dns); then applied=$current; fi
else
    exit 2
fi

printf 'READY\n'
# EOF means the desktop closed or its process exited.
IFS= read -r _ || true
