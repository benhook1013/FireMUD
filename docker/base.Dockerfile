# Shared base image for FireMUD services
FROM eclipse-temurin:25.0.5_8-jre@sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0
LABEL org.opencontainers.image.source="https://github.com/benhook1013/FireMUD"
RUN set -eu; \
    sources="/etc/apt/sources.list.d/ubuntu.sources"; \
    mirror_list="/etc/apt/ubuntu-archive-mirrors.txt"; \
    test -f "$sources"; \
    if { \
        grep -Eq '^URIs:[[:space:]]*https?://archive\.ubuntu\.com/ubuntu/?[[:space:]]*$' "$sources" && \
        grep -Eq '^URIs:[[:space:]]*https?://security\.ubuntu\.com/ubuntu/?[[:space:]]*$' "$sources"; \
    }; then \
        use_archive_mirror_failover=true; \
    elif grep -Eq '^URIs:[[:space:]]*https?://ports\.ubuntu\.com/ubuntu-ports/?[[:space:]]*$' "$sources"; then \
        use_archive_mirror_failover=false; \
    else \
        echo "Ubuntu APT sources do not match a supported canonical layout." >&2; \
        exit 1; \
    fi; \
    sed -i \
        -e 's|http://archive\.ubuntu\.com/ubuntu|https://archive.ubuntu.com/ubuntu|g' \
        -e 's|http://security\.ubuntu\.com/ubuntu|https://security.ubuntu.com/ubuntu|g' \
        -e 's|http://ports\.ubuntu\.com/ubuntu-ports|https://ports.ubuntu.com/ubuntu-ports|g' \
        "$sources"; \
    if [ "$use_archive_mirror_failover" = true ]; then \
        printf '%s\tpriority:1\n%s\tpriority:2\n' \
            'https://archive.ubuntu.com/ubuntu/' \
            'https://mirror.math.princeton.edu/pub/ubuntu/' \
            > "$mirror_list"; \
        mirror_uri="mirror+file:$mirror_list"; \
        sed -i -E "s|^URIs:[[:space:]]*https://archive[.]ubuntu[.]com/ubuntu/?[[:space:]]*\$|URIs: $mirror_uri|" "$sources"; \
        sed -i -E "s|^URIs:[[:space:]]*https://security[.]ubuntu[.]com/ubuntu/?[[:space:]]*\$|URIs: $mirror_uri|" "$sources"; \
    fi; \
    if grep -Eq '^URIs:[[:space:]]*http://(archive|security)\.ubuntu\.com/ubuntu/?[[:space:]]*$|^URIs:[[:space:]]*http://ports\.ubuntu\.com/ubuntu-ports/?[[:space:]]*$' "$sources"; then \
        echo "Ubuntu sources must use HTTPS." >&2; \
        exit 1; \
    fi; \
    apt-get --error-on=any -o Acquire::Retries=3 update \
    && apt-get -o Acquire::Retries=3 install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system firemud \
    && useradd --system --gid firemud --create-home firemud
WORKDIR /app
USER firemud
