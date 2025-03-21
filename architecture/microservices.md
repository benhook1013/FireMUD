# Microservices and Data Domains (Collapsed AI & Scripting)

| Data Domain                      | Microservice                                            | Functionality                                                                                                   |
|:---------------------------------|:--------------------------------------------------------|:----------------------------------------------------------------------------------------------------------------|
| Game Management                  | Game Management Service                                 | Handles game creation, settings, and versioning.                                                                |
| Entity Management                | Entity Management Service                               | Stores all game entities (players, NPCs, monsters, items) and manages their state, inventories, and attributes. |
| Player Authentication & Accounts | Account & Authentication Service                        | Manages user accounts, authentication, and session tracking. Ensures security and access control.               |
| Command Processing               | Command Parsing Service                                 | Interprets player commands and maps them to game actions.                                                       |
| Game Logic                       | Game Logic Service                                      | Executes game rules, combat resolution, and world interactions.                                                 |
| AI, Scripting & Automation       | AI, Scripting & Automation Service                      | Manages NPC behaviors, AI decision-making, scripted events, and automation of game mechanics.                   |
| World and Environment            | World Management Service                                | Handles room creation, spatial relationships, environmental changes, and dynamic world updates.                 |
| Communication & Social           | Chat & Messaging Service, Guild & Group Service         | Manages real-time chat, private messaging, guilds, and player interactions.                                     |
| Logging & Moderation             | Logging & Analytics Service, Moderation & Admin Service | Tracks in-game events, player behavior, logs game analytics, and enforces moderation policies.                  |
| Networking & API Gateway         | Networking Service, API Gateway Service                 | Handles real-time player connections (WebSockets/TCP) and routes API requests securely.                         |
