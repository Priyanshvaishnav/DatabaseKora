#!/usr/bin/env bash
# ==============================================================================
# KoraDB Installer for Linux & macOS
#
# Quick install:
#   curl -fsSL https://raw.githubusercontent.com/Priyanshvaishnav/DatabaseKora/main/install.sh | bash
# ==============================================================================
set -e

REPO="Priyanshvaishnav/DatabaseKora"
INSTALL_DIR="${KORADB_DIR:-$HOME/.koradb}"
BIN_DIR="$INSTALL_DIR/bin"
LIB_DIR="$INSTALL_DIR/lib"

BOLD='\033[1m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}${BOLD}"
cat << 'EOF'
  _  __              _____  ____  
 | |/ /___  _ __ __ |  __ \|  _ \ 
 | ' // _ \| '__/ _` | |  | | |_) |
 | . \ (_) | | | (_| | |__| |  _ < 
 |_|\_\___/|_|  \__,_|_____/|____/ 
                                   
 Relational Database Engine Built from Scratch in Java 21
EOF
echo -e "${NC}"

echo -e "Installing KoraDB to ${BOLD}$INSTALL_DIR${NC}..."

# 1. Check prerequisites: Java 21+
if command -v java >/dev/null 2>&1; then
    JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
    if [ -n "$JAVA_VER" ] && [ "$JAVA_VER" -lt 21 ] 2>/dev/null; then
        echo -e "${YELLOW}Warning: Detected Java $JAVA_VER. KoraDB requires Java 21+.${NC}"
        echo -e "You can install OpenJDK 21 via: https://adoptium.net/temurin/releases/?version=21"
    else
        echo -e "✓ Found Java (version $JAVA_VER)"
    fi
else
    echo -e "${YELLOW}Note: Java is not currently installed or in PATH.${NC}"
    echo -e "KoraDB requires Java 21+. Please install it before running KoraDB:"
    echo -e "  - Ubuntu/Debian: sudo apt install openjdk-21-jre-headless"
    echo -e "  - macOS (Homebrew): brew install openjdk@21"
    echo -e "  - Direct download: https://adoptium.net/temurin/releases/?version=21"
fi

# 2. Check curl or wget
if command -v curl >/dev/null 2>&1; then
    DOWNLOAD_CMD="curl -fsSL"
elif command -v wget >/dev/null 2>&1; then
    DOWNLOAD_CMD="wget -qO-"
else
    echo -e "${RED}Error: Neither curl nor wget was found. Please install curl or wget.${NC}" >&2
    exit 1
fi

# 3. Create directories
mkdir -p "$BIN_DIR" "$LIB_DIR"

# 4. Resolve download URL (latest GitHub release)
echo -e "Fetching latest release information..."
RELEASE_JSON=$(curl -fsSL -H "Accept: application/vnd.github.v3+json" "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null || true)
TAR_URL=$(echo "$RELEASE_JSON" | grep "browser_download_url.*koradb-dist.tar.gz" | head -n 1 | cut -d '"' -f 4 || true)

if [ -z "$TAR_URL" ]; then
    # Fallback to direct releases download URL if API is unauthenticated/rate-limited
    TAR_URL="https://github.com/$REPO/releases/latest/download/koradb-dist.tar.gz"
fi

TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT

echo -e "Downloading KoraDB package..."
if curl -fsSL -L -o "$TEMP_DIR/koradb-dist.tar.gz" "$TAR_URL" 2>/dev/null; then
    echo -e "Extracting archive..."
    tar -xzf "$TEMP_DIR/koradb-dist.tar.gz" -C "$TEMP_DIR"
    cp -r "$TEMP_DIR"/*/bin/* "$BIN_DIR/" 2>/dev/null || cp -r "$TEMP_DIR"/bin/* "$BIN_DIR/" 2>/dev/null || true
    cp -r "$TEMP_DIR"/*/lib/* "$LIB_DIR/" 2>/dev/null || cp -r "$TEMP_DIR"/lib/* "$LIB_DIR/" 2>/dev/null || true
else
    # Fallback when running inside repo or before first release tag is published:
    # Download launcher directly from main branch
    echo -e "${YELLOW}Notice: Release bundle not yet published; fetching launcher from main branch...${NC}"
    curl -fsSL -o "$BIN_DIR/koradb" "https://raw.githubusercontent.com/$REPO/main/bin/koradb"
fi

chmod +x "$BIN_DIR/koradb" 2>/dev/null || true

# 5. Configure PATH in shell profile
ADD_PATH="export PATH=\"\$HOME/.koradb/bin:\$PATH\""
UPDATED_PROFILE=false

for PROFILE in "$HOME/.zshrc" "$HOME/.bashrc" "$HOME/.profile"; do
    if [ -f "$PROFILE" ]; then
        if ! grep -q ".koradb/bin" "$PROFILE"; then
            echo "" >> "$PROFILE"
            echo "# KoraDB" >> "$PROFILE"
            echo "$ADD_PATH" >> "$PROFILE"
            UPDATED_PROFILE=true
            echo -e "✓ Added KoraDB to ${BOLD}$PROFILE${NC}"
        fi
    fi
done

echo ""
echo -e "${GREEN}${BOLD}✓ KoraDB successfully installed!${NC}"
echo ""
echo -e "To get started:"
if [ "$UPDATED_PROFILE" = true ]; then
    echo -e "  1. Reload your shell:  ${BOLD}source ~/.bashrc${NC} (or ~/.zshrc)"
fi
echo -e "  2. Start the server:   ${BOLD}koradb server --port 8765${NC}"
echo -e "  3. Open the CLI:       ${BOLD}koradb${NC} (or ${BOLD}koradb cli${NC})"
echo -e "  4. Try AI query:       ${BOLD}\\ai show all products${NC}"
echo ""
