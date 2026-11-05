#!/usr/bin/env bash
# PixelPal bridge installer — one line, no clone, no pipx, no venv.
#
#   curl -fsSL https://raw.githubusercontent.com/riddhibantia/PixelPal/main/install.sh | bash
#
# or, to pick a specific release:
#   INSTALL_VERSION=v1.3.0 bash install.sh
#
# What it does:
#   1. Downloads the bridge tarball (pure Python stdlib, ~44 KB) from the
#      latest GitHub Release, and verifies its SHA-256 against the release's
#      SHA256SUMS.
#   2. Extracts it to ~/.local/share/pixelpal-bridge.
#   3. Writes two launchers into ~/.local/bin:
#        pixelpal-bridge   (setup / serve / doctor / uninstall)
#        pixelpal-hook     (the hook entry point agents invoke)
#
# Requires: bash, curl (or wget), tar, python3 >= 3.9. Nothing else.

set -euo pipefail

REPO="${INSTALL_REPO:-riddhibantia/PixelPal}"
VERSION="${INSTALL_VERSION:-}"
PREFIX="${INSTALL_PREFIX:-$HOME/.local}"
INSTALL_DIR="$PREFIX/share/pixelpal-bridge"
BIN_DIR="$PREFIX/bin"

log() { printf '\033[1;35mpixelpal\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mpixelpal install failed:\033[0m %s\n' "$*" >&2; exit 1; }

command -v python3 >/dev/null 2>&1 || die "python3 is required (3.9+)"
command -v tar >/dev/null 2>&1 || die "tar is required"
if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
    die "curl or wget is required"
fi

fetch() { # fetch <url> <outfile>
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$1" -o "$2" || return 1
    else
        wget -qO "$2" "$1" || return 1
    fi
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ---- Resolve the release to install -----------------------------------------
if [ -z "$VERSION" ]; then
    log "Finding the latest release..."
    fetch "https://api.github.com/repos/$REPO/releases/latest" "$TMP/release.json" \
        || die "could not reach GitHub. Are you online?"
    VERSION="$(grep -o '"tag_name": *"[^"]*"' "$TMP/release.json" | head -1 | sed 's/.*"v\([^"]*\)"/\1/')"
    [ -n "$VERSION" ] || die "no published release found at github.com/$REPO"
fi
log "Installing bridge from release v$VERSION"

# ---- Download the tarball + checksums ---------------------------------------
BASE="https://github.com/$REPO/releases/download/v$VERSION"
TARBALL="pixelpal-bridge-$VERSION.tar.gz"
fetch "$BASE/$TARBALL" "$TMP/$TARBALL" || die "download failed: $BASE/$TARBALL"
if fetch "$BASE/SHA256SUMS" "$TMP/SHA256SUMS" 2>/dev/null; then
    (cd "$TMP" && grep "$TARBALL" SHA256SUMS | sha256sum -c - >/dev/null 2>&1) \
        || die "checksum mismatch for $TARBALL — download tampered or corrupt"
    log "Checksum verified."
else
    log "No SHA256SUMS asset on this release; skipping verification."
fi

# ---- Install -----------------------------------------------------------------
rm -rf "$INSTALL_DIR"
mkdir -p "$INSTALL_DIR" "$BIN_DIR"
tar -xzf "$TMP/$TARBALL" -C "$INSTALL_DIR"

for cmd in "pixelpal-bridge:pixelpal_bridge.cli" "pixelpal-hook:pixelpal_bridge.hook"; do
    name="${cmd%%:*}"; mod="${cmd##*:}"
    cat > "$BIN_DIR/$name" <<EOF
#!/usr/bin/env bash
export PYTHONPATH="$INSTALL_DIR\${PYTHONPATH:+:\$PYTHONPATH}"
exec python3 -m "$mod" "\$@"
EOF
    chmod +x "$BIN_DIR/$name"
done

case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *) log "Note: $BIN_DIR is not on your PATH. Add it with:"
       log "  echo 'export PATH=\"$BIN_DIR:\$PATH\"' >> ~/.bashrc" ;;
esac

"$BIN_DIR/pixelpal-bridge" --help >/dev/null 2>&1 || die "installed but the launcher failed to run"

log "Bridge installed. Next step:"
log "  pixelpal-bridge setup"
log "It hooks every coding agent it finds, starts the bridge, and prints"
log "the address to paste into the PixelPal app (Agent tab)."
