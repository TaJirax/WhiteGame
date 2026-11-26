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
    dns_servers=()
    while (( $# )) && [[ $1 != -- ]]; do
        dns_servers+=("$1")
        shift
    done
    if (( $# )); then shift; fi
    dns_domains=("$@")
    workspace_root=/run
    if [[ ${engine##*/} == wg-quick && -d /etc/wireguard ]] &&
        grep -Fxq 'wg-quick (enforce)' /sys/kernel/security/apparmor/profiles 2>/dev/null; then
        # The distribution's enforcing policy reads configs only here.
        workspace_root=/etc/wireguard
    fi
    workspace=$(mktemp -d "$workspace_root/whitegame.XXXXXXXX")
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
    resolved=
    if (( ${#dns_servers[@]} || ${#dns_domains[@]} )) &&
        candidate=$(command -v resolvectl) && "$candidate" status >/dev/null 2>&1; then
        resolved=$candidate
        # The generated config has a single DNS line. Keep it for resolvconf on
        # distributions without systemd-resolved.
        sed -i '/^DNS = /d' -- "$config"
    fi
    "$engine" up "$config" >&2
    connected=1
    if [[ $workspace_root != /run ]]; then
        # wg-quick down needs the link and routing options, but no key material.
        sed -i -E '/^(PrivateKey|PresharedKey) = /d' -- "$config"
    fi
    if [[ -n $resolved ]]; then
        # Wait for systemd-resolved to learn about the new kernel link.
        for ((attempt = 0; attempt < 30; attempt++)); do
            if "$resolved" -- dns "$target" >/dev/null 2>&1; then break; fi
            sleep 0.1
        done
        if (( ${#dns_domains[@]} )); then
            "$resolved" -- default-route "$target" no >&2
            "$resolved" -- domain "$target" "${dns_domains[@]}" >&2
        else
            "$resolved" -- domain "$target" '~.' >&2
        fi
        if (( ${#dns_servers[@]} )); then
            "$resolved" -- dns "$target" "${dns_servers[@]}" >&2
        fi
    fi
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
