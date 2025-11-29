# Twig Velocity
> Minecraft Velocity proxy plugin that bridges to Discord via gRPC for secure player authentication and access control.

![Java 17](https://img.shields.io/badge/Java-17-orange?logo=openjdk)
![Velocity 3.4.0](https://img.shields.io/badge/Velocity-3.4.0-blue?logo=minecraft)
![gRPC Ready](https://img.shields.io/badge/gRPC-ready-0C7BDC)
![Gradle](https://img.shields.io/badge/Gradle-8.x-02303A?logo=gradle)
![License GPLv3](https://img.shields.io/badge/License-GPLv3-blue)

Twig Velocity is a Minecraft proxy plugin designed for Velocity that implements secure player authentication and server access control through gRPC integration with the [Twig Discord bot](https://github.com/caiostoduto/twig). It enables Discord-based whitelisting, registration workflows, and real-time event streaming to keep your Minecraft infrastructure synchronized with your Discord community.

## Table of contents
- [Highlights](#highlights)
- [Architecture at a glance](#architecture-at-a-glance)
- [Getting started](#getting-started)
- [Configuration reference](#configuration-reference)
- [gRPC surface](#grpc-surface)
- [How it works](#how-it-works)
- [Building from source](#building-from-source)
- [Development workflow](#development-workflow)
- [License](#license)

## Highlights
- **Discord-gated server access** enforcing role-based permissions before players can join specific Minecraft servers.
- **Seamless registration flow** that redirects unauthorized players to a limbo server while they complete Discord OAuth authentication.
- **gRPC-powered bridge** to the Twig Discord bot using [Protocol Buffers](https://protobuf.dev/) for efficient, type-safe communication.
- **Real-time event streaming** via server-streaming RPC to receive player updates and access changes from Discord.
- **Automatic proxy registration** that announces the proxy and its managed servers to the central Twig bot on startup.
- **Production-ready packaging** with Shadow JAR that relocates dependencies to avoid conflicts with other Velocity plugins.

## Architecture at a glance
- **Player authentication flow** intercepts login attempts, queries the gRPC service for access status, and either allows entry, redirects to limbo for signup, or denies access.
- **Limbo integration** uses the `velocity-limbo-handler` to hold players in a lightweight waiting state while they authenticate via Discord OAuth.
- **gRPC client layer** (`MinecraftBridgeClient`) maintains persistent connection to the Twig bot, handling proxy registration, access checks, and event subscriptions.
- **Event listeners** (`AuthenticationLoginHandler`, `PlayerUpdateEventHandler`, `LimboHandler`) react to Velocity lifecycle events and coordinate with the gRPC bridge.
- **Configuration manager** reads `config.yml` to determine proxy UUID, limbo server name, gRPC endpoint, and custom denial messages.

## Getting started

### Prerequisites
- Java 17 or higher (required by Velocity 3.4.0+)
- Velocity proxy server (3.4.0-SNAPSHOT recommended)
- Running instance of the [Twig Discord bot](https://github.com/caiostoduto/twig) with gRPC server enabled
- `velocity-limbo-handler` plugin (optional but recommended for registration flow)

### Installation
1. Download the latest release from the [releases page](https://github.com/caiostoduto/twig-velocity/releases) or [build from source](#building-from-source).
2. Place `Twig.jar` in your Velocity `plugins/` directory.
3. Start the Velocity proxy to generate the default configuration.
4. Configure the plugin in `plugins/twig/config.yml`:
   ```yaml
   # Leave empty to auto-generate a unique proxy UUID
   twig_uuid: ""
   
   # Limbo server name (must match server in velocity.toml)
   proxy_limbo: "limbo"
   
   # Message shown to denied players
   not_allowed_message: "You are not whitelisted on this server!"
   
   # gRPC endpoint for Twig Discord bot
   grpc_host: "127.0.0.1"
   grpc_port: 50051
   ```
5. Restart the Velocity proxy.

### Quick test
Once configured and running:
1. The plugin will automatically register with the Twig bot and send the list of available servers.
2. Try joining a Minecraft server through the proxy.
3. If you're not whitelisted, you'll be redirected to the limbo server with an authentication URL in chat.
4. Complete Discord OAuth to link your account.
5. Rejoin and you should have access to whitelisted servers.

## Configuration reference

| Variable | Required | Description | Default |
| --- | --- | --- | --- |
| `twig_uuid` | Optional | Persistent UUID for this proxy instance. Leave empty to auto-generate and persist. | auto-generated |
| `proxy_limbo` | Optional | Name of the limbo server (must exist in `velocity.toml`) for holding unauthenticated players. | `limbo` |
| `not_allowed_message` | Optional | Message displayed to players who are denied access. | `You are not whitelisted on this server!` |
| `grpc_host` | ✅ | Hostname or IP of the Twig Discord bot's gRPC server. | `127.0.0.1` |
| `grpc_port` | ✅ | Port number for the gRPC server. | `50051` |

The plugin stores configuration in `plugins/twig/config.yml`. Modifying values requires a proxy restart to take effect.

## gRPC surface

The plugin implements a client for the `MinecraftBridge` service defined in `proto/minecraft_bridge.proto`:

| RPC | Direction | Purpose |
| --- | --- | --- |
| `RegisterProxy` | Client → Server | Called on startup to announce proxy UUID and list of managed servers to the Twig bot. Includes retry logic with exponential backoff. |
| `CheckPlayerAccess` | Client → Server | Validates whether a player (by username + IPv4) is allowed to join a specific server. Returns `ALLOWED`, `PROHIBITED`, or `REQUIRES_SIGNUP` with optional auth URL. |
| `SubscribeEvents` | Server → Client (stream) | Long-lived stream receiving `ServerEvent` messages for player updates, role changes, and access modifications from Discord. |

### Protocol Buffer schema
```protobuf
service MinecraftBridge {
  rpc RegisterProxy(ProxyRegistration) returns (RegistrationResponse);
  rpc CheckPlayerAccess(PlayerAccessRequest) returns (PlayerAccessResponse);
  rpc SubscribeEvents(EventSubscription) returns (stream ServerEvent);
}
```

The plugin uses [gRPC-Java](https://grpc.io/docs/languages/java/) with Netty transport, and all dependencies are shaded to avoid classpath conflicts.

## How it works

### 1. Proxy startup
- Plugin initializes, reads or generates `twig_uuid` from `config.yml`
- Collects list of servers from Velocity (excluding limbo)
- Asynchronously calls `RegisterProxy` RPC with retry backoff
- Subscribes to `SubscribeEvents` stream to receive real-time updates

### 2. Player connection
- When a player attempts to join a server, `AuthenticationLoginHandler` fires
- Plugin queries `CheckPlayerAccess` RPC with player name, IP, target server, and proxy ID
- Based on response:
  - **ALLOWED**: Player proceeds to the server
  - **PROHIBITED**: Connection denied with configured message
  - **REQUIRES_SIGNUP**: Player redirected to limbo with authentication URL and expiry time

### 3. Registration flow
- Player is moved to limbo server by `LimboHandler`
- Authentication URL is sent via chat with expiration countdown
- Player completes Discord OAuth in browser
- Twig bot publishes `PlayerUpdate` event via `SubscribeEvents` stream
- `PlayerUpdateEventHandler` processes the event and moves player back to requested server

### 4. Event streaming
- Plugin maintains persistent gRPC stream to receive events
- Handles reconnection with exponential backoff if connection drops
- Events trigger Velocity actions (player movements, access updates, etc.)

## Building from source

### Clone the repository
```bash
git clone https://github.com/caiostoduto/twig-velocity.git
cd twig-velocity
```

### Build with Gradle
```bash
# Build the plugin JAR (includes Shadow JAR generation)
./gradlew build

# The compiled plugin will be at:
# build/libs/twig-1.0.0.jar
```

### Run local test server
```bash
# Start a test Velocity instance with the plugin pre-installed
./gradlew runVelocity

# This will:
# - Download Velocity 3.4.0-SNAPSHOT
# - Install your built plugin
# - Start the server in the run/ directory
```

### Generate Protocol Buffers
The build automatically generates Java code from `proto/minecraft_bridge.proto` using the Protobuf Gradle plugin. Generated sources appear in `build/generated/source/proto/main/grpc/` and `build/generated/source/proto/main/java/`.

To regenerate after modifying the proto file:
```bash
./gradlew clean build
```

## Development workflow

### Code style
- Follow standard Java conventions and maintain consistency with existing code
- Run `./gradlew build` before committing to catch compilation issues

### Testing changes
1. Build the plugin: `./gradlew shadowJar`
2. Copy `build/libs/twig-1.0.0.jar` to your test Velocity server's `plugins/` directory
3. Restart the proxy
4. Monitor logs for gRPC connection status and player authentication events

### Project structure
```
src/main/java/com/github/caiostoduto/twig/
├── Twig.java                          # Main plugin class, initialization
├── auth/
│   ├── AuthenticationEntry.java      # Pending auth session data
│   └── PlayerIdentifier.java         # Composite key (username + IP)
├── config/
│   └── ConfigManager.java            # YAML config reader/writer
├── grpc/
│   └── MinecraftBridgeClient.java    # gRPC client wrapper
└── listeners/
    ├── AuthenticationLoginHandler.java  # Pre-login access checks
    ├── LimboHandler.java                # Limbo server management
    └── PlayerUpdateEventHandler.java   # Event stream processor
```

### Debugging
Enable detailed gRPC logs in your Velocity startup script:
```bash
java -Djava.util.logging.config.file=logging.properties \
     -Dio.grpc.netty.shaded.io.netty.leakDetection.level=advanced \
     -jar velocity.jar
```

Check the plugin's interaction with the Twig bot by monitoring both Velocity console output and the Twig bot logs simultaneously.

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).

---

**Related projects:**
- [Twig Discord Bot](https://github.com/caiostoduto/twig) - The companion Discord bot that manages authentication and permissions
- [Velocity](https://papermc.io/software/velocity) - Modern Minecraft proxy server

**Contributing:** Issues and pull requests welcome! Please open an issue first to discuss significant changes.