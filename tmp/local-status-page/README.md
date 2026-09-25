# FireMUD delivery status page

The page is a manually maintained snapshot. Edit `status.json` when worker lanes or stack stages change. The renderer reads current GitHub PR sizes and the configured review controller, then writes `output/index.html` for the local LAN server. The **Refresh review data** button on the local page reruns that renderer; it updates review counts and PR sizes but does not rewrite worker-lane notes or publish to Hetzner. Allow about a minute for it to finish.

```bash
python3 render.py
```

Publish the rendered snapshot at [status.preview.firedevops.net](https://status.preview.firedevops.net/) with:

```bash
python3 publish-hetzner.py
```

The publisher creates `output/public-index.html` without a refresh endpoint, then updates only the `overseer-status` namespace on the existing Hetzner k3s host. Its Traefik ingress uses the existing `letsencrypt-prod` issuer. The page is publicly readable and contains no credentials or private review records. Its footer links to the current Windows Wi-Fi address for refreshing at home; that link is updated on each publish. The public page does not refresh automatically.

Source and tests are tracked on the local Overseer branch. `status.json`, generated HTML, PID, and logs remain private runtime files under ignored `tmp/local-status-page`.
