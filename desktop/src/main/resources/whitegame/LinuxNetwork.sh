set -euo pipefail
export LC_ALL=C
export PATH=/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/sbin:/usr/local/bin
umask 077

operation=$1
engine=$2
target=$3
shift 3

start_xray_lease() {
    [[ $target =~ ^wg[0-9a-f]{10}$ && $engine = /* && -x $engine ]] || return 2
    local family=${1:-dual} system_dns=${2:-no-dns}
    local payload original_uid=${PKEXEC_UID:-0} original_group attempt status
    local -a launch=(setpriv --no-new-privs --pdeathsig KILL --bounding-set=-all,+net_admin,+net_raw)
    fail_xray() { printf 'ERROR %s\n' "$1"; exit 1; }
    [[ $family == dual || $family == ipv4 ]] || return 2
    [[ $system_dns == resolved || $system_dns == no-dns ]] || return 2
    [[ -c /dev/net/tun ]] || fail_xray 'Linux TUN is unavailable. Check that /dev/net/tun is present.'
    if ip link show dev "$target" >/dev/null 2>&1; then
        fail_xray 'The Linux Xray interface name is already in use.'
    fi
    if [[ -z $(ip -4 -o route show table main default) && -z $(ip -6 -o route show table main default) ]]; then
        fail_xray 'Xray needs an active Linux default route before starting the tunnel.'
    fi
    if [[ $original_uid != 0 ]]; then
        [[ $original_uid =~ ^[1-9][0-9]*$ ]] || return 2
        original_group=$(id -g -- "$original_uid")
        launch+=(--reuid "$original_uid" --regid "$original_group" --init-groups)
    fi
    launch+=(--inh-caps=-all,+net_admin,+net_raw --ambient-caps=-all,+net_admin,+net_raw)
    launch+=(env -u XRAY_TUN_FD -u xray.tun.fd "$engine" run -config stdin: -format json)

    # Xray owns a nonpersistent TUN file descriptor, its routes and per-link DNS.
    # Closing the descriptor also removes its interface/routes if graceful exit fails.
    cleanup_xray() {
        local result=$? attempt
        trap - EXIT
        trap '' HUP INT TERM
        if [[ -n ${xray_pid:-} ]]; then
            if kill -TERM "$xray_pid" 2>/dev/null; then
                for ((attempt=0; attempt<50; attempt++)); do
                    kill -0 "$xray_pid" 2>/dev/null || break
                    sleep 0.1
                done
                kill -KILL "$xray_pid" 2>/dev/null || true
            fi
            wait "$xray_pid" || true
            if ip link show dev "$target" >/dev/null 2>&1; then result=1; fi
        fi
        exit "$result"
    }
    route_uses_tun() {
        local route
        route=$(ip "-$1" -o route get "$2" 2>/dev/null) || return 1
        [[ " $route " == *" dev $target "* ]]
    }
    xray_ready() {
        local ipv6_addresses
        route_uses_tun 4 1.0.0.1 && route_uses_tun 4 198.51.100.254 || return 1
        if [[ $family == dual ]]; then
            ipv6_addresses=$(ip -6 -o addr show dev "$target" scope global -tentative -dadfailed 2>/dev/null) || return 1
            [[ -n $ipv6_addresses ]] || return 1
            route_uses_tun 6 2001:db8::100 && route_uses_tun 6 8000::100 || return 1
        fi
        if [[ $system_dns == resolved ]]; then
            [[ $(timeout 1s resolvectl dns "$target" 2>/dev/null) == *'198.18.0.2'* ]] &&
                [[ $(timeout 1s resolvectl domain "$target" 2>/dev/null) == *'~.'* ]] || return 1
        fi
    }

    IFS= read -r payload || fail_xray 'The Linux Xray config was not received.'
    xray_pid=
    trap cleanup_xray EXIT
    trap 'exit 1' HUP INT TERM
    printf '%s' "$payload" | base64 --decode | "${launch[@]}" >&2 &
    xray_pid=$!
    unset payload
    for ((attempt=0; attempt<80; attempt++)); do
        kill -0 "$xray_pid" 2>/dev/null || fail_xray 'Xray stopped before the Linux tunnel was ready. Check the profile, TUN access and resolver settings.'
        if xray_ready; then
            printf 'READY\n'
            while kill -0 "$xray_pid" 2>/dev/null; do
                if IFS= read -r -t 0.5 _; then break; else status=$?; fi
                # Bash reports >128 for a timeout, and a smaller status for EOF.
                (( status > 128 )) || break
            done
            exit 0
        fi
        sleep 0.1
    done
    fail_xray 'The Linux routes or DNS did not switch to the Xray tunnel.'
}

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
        { [[ -f /etc/apparmor.d/wg-quick ]] ||
            grep -Fxq 'wg-quick (enforce)' /sys/kernel/security/apparmor/profiles 2>/dev/null; }; then
        # Look for the on-disk policy too because a network namespace may hide securityfs.
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
elif [[ $operation == xray ]]; then
    start_xray_lease "$@"
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
