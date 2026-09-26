# FireMUD delivery status page

The page is a manually maintained snapshot. Edit `status.json` when worker lanes or stack stages change. The renderer reads current GitHub PR sizes and lifecycle plus the configured review controller, then writes `output/index.html` for the local LAN server. The **Refresh review data** button renders the local page and then publishes its public copy to Hetzner. While the Python server runs, it also refreshes automatically after 30 minutes, then 30 minutes after each completed manual or automatic attempt. There is no extra refresh at startup. The button shows the active stage and elapsed time, and reloads the local page only after publication succeeds. Open local and public tabs check for a newer snapshot and reload when one is available. The short timestamp beside the button reflects PR data; the queue heading retains detail about partial review evidence. Merged PRs remain in the manual queue until its order is updated, but are marked purple with historical review counts. Stack order, titles, and saved heads remain manual; worker-lane notes retain their individual check times. Allow a few minutes for refresh and publication. The server rejects concurrent refreshes and applies a short cooldown. If either the controller or GitHub status read fails, refresh reports a render failure and preserves both existing pages. If publication fails, it reports that the local page was updated but the public page was not confirmed.

```bash
python3 render.py
```

Publish the rendered snapshot at [status.preview.firedevops.net](https://status.preview.firedevops.net/) with:

```bash
python3 publish-hetzner.py
```

The publisher creates `output/public-index.html` without a refresh endpoint, then updates only the `overseer-status` namespace on the existing Hetzner k3s host. Its Traefik ingress uses the existing `letsencrypt-prod` issuer. The page is publicly readable and contains no credentials or private review records. Its footer links to the current Windows Wi-Fi address for refreshing at home; that link is updated on each publish. The public page updates after a scheduled or manual refresh succeeds. The Windows startup task only launches the Python server at sign-in, so automatic refreshes depend on that server and the PC staying awake.

Source and tests are tracked on the local Overseer branch. `status.json`, generated HTML, PID, and logs remain private runtime files under ignored `tmp/local-status-page`.

To back up source changes from the FireMUD worktree, commit the tracked files under `tmp/local-status-page`, then run this from the repository root. The subtree split includes only that folder's tracked history; it excludes the ignored runtime files and other Overseer notes.

```bash
SITE_SNAPSHOT=$(git subtree split --prefix=tmp/local-status-page codex/project-direction)
git push https://github.com/benhook1013/FireMUD-status-page.git "$SITE_SNAPSHOT:refs/heads/main"
```
