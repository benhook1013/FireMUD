# FireController and the private status site

The private status site is maintained in [FireMUD-status-page](https://github.com/benhook1013/FireMUD-status-page). Its renderer, local HTTP server, and publisher consume the public projections and private routes in [FireController web](../web.py). The site owns its integration and privacy checks; this repository does not patch a copied website or include private site files.

The private renderer reads public lane and workstream projections from the controller database. Its local server serves job, worker-history, inbox, and workstream pages and expands link markers only in local HTTP responses. Generated static pages contain no private-route links, and the publisher rejects private routes before publication. The local status-page service loads the selected controller package from its configured `--controller-tools` path when the service starts.

Run controller route and projection tests from the FireMUD repository root:

```sh
python3 -m unittest discover -s dev-tools/validation -p 'test_fire_controller_web.py'
```

Run the private renderer, project-map, publisher, and server suite from `tmp/local-status-page` in the project-direction checkout. The private `FireMUD-status-page` subtree mirror stores those same files at its repository root:

```sh
export FIREMUD_CONTROLLER_TOOLS=/absolute/path/to/FireMUD/dev-tools
python3 -m unittest discover -p 'test_*.py'
```

The private site source and tests are backed up separately from this public repository. Runtime promotion and service restart follow the private site's controller-entrypoint procedure; a page refresh updates rendered data but does not reload controller code already imported by the running server.
