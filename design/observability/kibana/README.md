# Kibana Dashboards

This directory stores Kibana saved objects for exploring FireMUD logs indexed in Elasticsearch.

These JSON exports can be imported into Kibana to quickly restore log views and dashboards that align with the project’s logging conventions.

## Dashboards and Saved Objects

- [log-volume.json](./log-volume.json) – Saved search and visualization set focused on log volume, error spikes, and severity distribution across services.

To use these objects, open Kibana’s “Saved Objects” management screen and import the JSON file, then point the imported visualizations at the Elasticsearch index pattern configured for FireMUD logs.
