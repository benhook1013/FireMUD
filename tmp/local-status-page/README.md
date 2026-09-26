# FireMUD delivery status page

The page is a manually maintained snapshot. Edit `status.json` when worker lanes or stack stages change. The renderer reads current GitHub PR sizes and lifecycle plus the configured review controller, then writes `output/index.html`. The **Refresh review data** button renders the local page and then publishes its public copy to Hetzner. The HTTP server, renderer, and publisher all run inside WSL; refreshes do not launch WSL from Windows. Windows exposes only a TCP port forward from the home Wi-Fi address to the WSL server. While the Python server runs, it also refreshes automatically after 30 minutes, then 30 minutes after each completed manual or automatic attempt. There is no extra refresh at startup. The button shows the active stage and elapsed time, and reloads the local page only after publication succeeds. Open local and public tabs check for a newer snapshot and reload when one is available. The short timestamp below the heading reflects PR data; the button occupies a reserved column so it does not shift the heading or timestamp; the queue heading retains detail about partial review evidence. Channel labels distinguish a controller-selected review request from a later eligible PR, provider cooldown, blocked new requests, and an explicit human review stop; none grants merge readiness. Merged PRs remain in the manual queue until its order is updated, but are marked purple with historical review counts. Stack order, titles, and saved heads remain manual; worker-lane notes retain their individual check times. Allow a few minutes for refresh and publication. The server rejects concurrent refreshes and applies a short cooldown. If either the controller or GitHub status read fails, refresh reports a render failure and preserves both existing pages. If publication fails, it reports that the local page was updated but the public page was not confirmed.

The status page uses the selected FireMUD `flame-ember.svg` favicon. The [icon gallery](https://status.preview.firedevops.net/icon-options.html) retains the other SVG concepts for comparison. The renderer copies the gallery and SVGs from `assets/` into the local output, and the publisher includes them in the public ConfigMap.

```bash
python3 render.py
```

Publish the rendered snapshot at [status.preview.firedevops.net](https://status.preview.firedevops.net/) with:

```bash
python3 publish-hetzner.py
```

The publisher creates `output/public-index.html` without a refresh endpoint, then updates only the `overseer-status` namespace on the existing Hetzner k3s host. Its Traefik ingress uses the existing `letsencrypt-prod` issuer. The page is publicly readable and contains no credentials or private review records. Its footer links to the static Windows Wi-Fi address `192.168.50.100` for refreshing at home; the publisher uses this configured address directly and does not query Windows during refresh. The public page updates after a scheduled or manual refresh succeeds. WSL systemd owns the server and its 30-minute refresh cycle through `firemud-status-page.service`; it restarts the server if it exits. Windows only maintains the Private Wi-Fi TCP 8877 firewall rule and the narrow `192.168.50.100:8877` forward to the current WSL NAT address. The forwarder records ownership under `HKLM\SOFTWARE\FireMUD\LocalStatusPage\PortProxy`; an unowned or changed same-port mapping fails closed. The PC and WSL instance must be running for automatic refreshes.

Install the service unit in `/etc/systemd/system/`, then run `systemctl daemon-reload` and `systemctl enable --now firemud-status-page.service` inside WSL. `register-autostart.ps1` installs or updates the Windows logon task, which only checks the firewall and updates the forward to WSL’s current address; it never runs the page server. It requires elevation because Windows portproxy and firewall configuration is privileged. `configure-portproxy.ps1 -WhatIf -ConnectAddress <current-wsl-ip>` and `allow-lan-firewall.ps1 -WhatIf` preview their narrow changes without changing Windows settings. Run `test-portproxy.ps1` to check repeat-start idempotence, WSL address updates, and fail-closed ownership checks. The task refreshes the target address at each Windows user logon; page refreshes run in WSL. `start-lan-server.ps1` remains available only as the rollback server during the cutover.

Source and tests are tracked on the local Overseer branch and backed up to the private `benhook1013/FireMUD-status-page` repository. `status.json`, generated HTML, PID, and logs remain private runtime files outside that backup.

To back up source changes from the FireMUD worktree, commit the tracked files under `tmp/local-status-page`, then run this from the repository root. The subtree split includes only that folder's tracked history; it excludes the ignored runtime files and other Overseer notes.

```bash
SITE_SNAPSHOT=$(git subtree split --prefix=tmp/local-status-page codex/project-direction)
git push https://github.com/benhook1013/FireMUD-status-page.git "$SITE_SNAPSHOT:refs/heads/main"
```
