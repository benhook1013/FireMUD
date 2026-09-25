# FireMUD delivery status page

The page is a manually maintained snapshot. Edit `status.json` when worker lanes or stack stages change. The renderer reads current GitHub PR sizes and lifecycle plus the configured review controller, then writes `output/index.html` for the local LAN server. The **Refresh review data** button keeps the local page open and shows elapsed time while that renderer runs; it reloads the page when finished. The queue heading shows when review and PR details were refreshed. Merged PRs remain in the manual queue until its order is updated, but are marked purple with historical review counts. Stack order, titles, and saved heads remain manual; worker-lane notes retain their individual check times. Refresh does not publish to Hetzner. Allow about a minute for it to finish. The server rejects concurrent refreshes and applies a short cooldown. If either the controller or GitHub status read fails, refresh reports failure and preserves the existing page instead of replacing its data with "unavailable."

```bash
python3 render.py
```

Publish the rendered snapshot at [status.preview.firedevops.net](https://status.preview.firedevops.net/) with:

```bash
python3 publish-hetzner.py
```

The publisher creates `output/public-index.html` without a refresh endpoint, then updates only the `overseer-status` namespace on the existing Hetzner k3s host. Its Traefik ingress uses the existing `letsencrypt-prod` issuer. The page is publicly readable and contains no credentials or private review records. Its footer links to the current Windows Wi-Fi address for refreshing at home; that link is updated on each publish. The public page does not refresh automatically.

Source and tests are tracked on the local Overseer branch. `status.json`, generated HTML, PID, and logs remain private runtime files under ignored `tmp/local-status-page`.
