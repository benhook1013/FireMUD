# Shared base image for FireMUD services
FROM eclipse-temurin:25.0.5.1_2-jre@sha256:4ed132f6832e9804b123e89a849a7b4502756ae64a4ee08ebab939d9d5d0a9fa
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
