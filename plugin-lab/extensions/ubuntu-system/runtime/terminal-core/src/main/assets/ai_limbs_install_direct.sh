# Publish Linux DSOs without truncating an inode mapped by a running process.
# Android callers supply their busybox; no Linux preload is used in the Host.
ail_install_direct_library() (
    [ "$#" -eq 3 ] || return 2
    if "$3" cmp -s "$1" "$2"; then
        return 0
    fi
    ail_direct_temporary="$2.new.$$"
    trap '"$3" rm -f "$ail_direct_temporary"' EXIT
    "$3" cp "$1" "$ail_direct_temporary" || return 1
    "$3" chmod 644 "$ail_direct_temporary" || return 1
    "$3" mv -f "$ail_direct_temporary" "$2" || return 1
)
