INSERT INTO region (id, name, tenant_id)
VALUES (100, 'Demo Region', 1);

INSERT INTO zone (id, region_id, name)
VALUES (200, 100, 'Demo Zone');

INSERT INTO room (id, zone_id, name, description)
VALUES
  (1021, 200, 'Candle-lit Antechamber', 'Stalactites drip along the northern wall while a faint draft carries the smell of damp earth from the lower tunnels. Torches flicker in alcoves, casting motion into the shadowy archway to the north.'),
  (2045, 200, 'Crafting Hall of Ember', 'Sparks drift upward from the forges while metalworkers shout over the rhythm of hammers; the far wall is dominated by the etched sigil of the Ember Guild.'),
  (3050, 200, 'Gallery Landing', 'A quiet gallery overlooks the regional atrium; tapestries show travelers crossing the Ember Rift.');

INSERT INTO room_exit (id, tenant_id, from_room_id, to_room_id, cost)
VALUES
  (5001, 1, 1021, 2045, 1),
  (5002, 1, 1021, 3050, 2),
  (5003, 1, 2045, 1021, 1),
  (5004, 1, 2045, 3050, 1);

SELECT setval(pg_get_serial_sequence('region', 'id'), 200, true);
SELECT setval(pg_get_serial_sequence('zone', 'id'), 400, true);
SELECT setval(pg_get_serial_sequence('room', 'id'), 4000, true);
SELECT setval(pg_get_serial_sequence('room_exit', 'id'), 7000, true);
