# Ubuntu login-shell policy. This is sourced inside GNU/Linux, never in Android.
# Host owns the bound network; the child owns process routing and local RPC bypass.
if [ "${LANER_NET_ACTIVE:-}" = "1" ] && [ "${LANER_EGRESS_MODE:-}" = "VPN" ]; then
    return
fi
ail_direct_library=/usr/local/lib/ai-limbs/libproxychains4.so
ail_direct_config=/root/laner/tools/laner-net/direct-proxychains.conf

if [ ! -f "$ail_direct_library" ]; then
    printf '%s\n' 'AI Limbs: DIRECT routing library is missing; refusing an unconfigured network session.' >&2
    exit 3
fi
if [ ! -f "$ail_direct_config" ]; then
    mkdir -p /root/laner/tools/laner-net
    # Until the Host snapshot arrives, refuse TCP egress through a local non-route.
    printf 'strict_chain\nquiet_mode\nproxy_dns\nlocalnet 127.0.0.0/255.0.0.0\nlocalnet ::1/128\n[ProxyList]\nsocks5 127.0.0.1 1\n' > "$ail_direct_config"
fi

unset HTTP_PROXY HTTPS_PROXY ALL_PROXY http_proxy https_proxy all_proxy
case " ${LD_PRELOAD:-} " in
    *" $ail_direct_library "*) ;;
    *) LD_PRELOAD="$ail_direct_library${LD_PRELOAD:+ $LD_PRELOAD}" ;;
esac
export LD_PRELOAD
export PROXYCHAINS_CONF_FILE="$ail_direct_config"
export PROXYCHAINS_QUIET_MODE=1
export NO_PROXY=127.0.0.1,localhost,::1
export no_proxy="$NO_PROXY"
export LANER_EGRESS_MODE=DIRECT
export LANER_DIRECT_SCOPE=dynamic_tcp
unset ail_direct_library ail_direct_config
