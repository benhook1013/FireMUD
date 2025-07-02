# 📈 System Architecture Diagram

```plaintext
      +------------+      TCP (Telnet)         +-------------------+
      | MUD Client | <-----------------------> | TCP Proxy Service |
      +------------+                           +---------+---------+
                                                         |
                                                         | WebSocket (wss)
                                                         |
                                                         v
      +------------+      WebSocket/HTTP     +----------------------+
      | Web Client | <----------------------> | Spring Cloud Gateway |
      +------------+                          +----------+-----------+
                                                         |
                                                         | WebSocket (wss)
                                                         |
                                                         v
                                          +----------------------------+
                                          | Game Session Service       |
                                          +--------------+-------------+
                                                         |
       +--------------------+----------------------------+--------------------+
       |                    |                            |                    |
       v                    v                            v                    v
Game Design         Account Service              Entity Management     World Management
    Service              (Auth)                    Service (Players,        Service
 (Templates, Backups)                             NPCs, Items)          (Maps/Rooms)

                              +-----------+-------------+
                              | Game Logic Service      |
                              | (Rules, Commands, etc.) |
                              +-----------+-------------+
                                          |
                                          v
                             +--------------------------------+
                             | Automation & Scripting Service |
                             +--------------------------------+

             +-------------------------+      +-------------------------+
             | Social & Groups Service |      | Logging & Admin Service |
             +-------------------------+      +-------------------------+

                       +--------------------------------------+
                       | Game Design Service (Passive Editor) |
                       +--------------------------------------+
```
