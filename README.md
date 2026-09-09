# MDVJustice 1.0.1

Plugin de moderación y condenas personalizadas para **MDVCRAFT / Purpur-Paper 1.21.6**.

## Cambios 1.0.1

- **Selección de mina segura:** la mina no existe hasta ejecutar `/justice mina pos1` y `/justice mina pos2`.
- Al actualizar desde 1.0.0 debes volver a marcar ambas posiciones una vez.
- Si pos1 y pos2 se toman en mundos diferentes, la selección anterior se invalida.
- `mine.regeneration.max-volume` aborta regiones absurdamente grandes antes de tocar bloques.
- El kit de prisión se entrega después del teleport y se revalida periódicamente para evitar conflictos con hotbars de lobby.
- Los ítems propios de prisión nunca quedan como drops del suelo.
- `mine.blocks` acepta **cualquier Material que sea bloque**: STONE, DIORITE, OBSIDIAN, DEEPSLATE, ores, etc.
- TAB sugiere `off`, delays (`3s`, `5s`...) y duraciones (`30m`, `1h`, `1d`, `permanente`).
- `/castigar` sugiere jugadores y cantidades de puntos; `/liberar` sugiere presos conectados.

## Módulos

### Chat
- Slow global: `/justice chat lento 3s`
- Quitar slow global: `/justice chat lento off`
- Slow individual: `/justice slowchat <jugador> 5s 1h`
- Quitar slow individual: `/justice slowchat <jugador> off`
- Mute: `/justice silenciar <jugador> 2h razon`
- Mute permanente: `/justice silenciar <jugador> permanente razon`
- Unmute: `/justice desilenciar <jugador>`
- Anti-spam de repetición y ráfaga.
- Las sanciones temporales persisten en SQLite.

### Condenas
- `/castigar <jugador> <puntos> [razon]`
- `/liberar <jugador>`
- El inventario, armadura, offhand, XP, comida y GameMode original se guardan en SQLite.
- El preso recibe pico de hierro irrompible + comida infinita configurable.
- No puede usar comandos salvo login/register configurados.
- No puede escapar de la región de prisión.
- Reconexión integrada con nLogin: después de autenticar vuelve a prisión.
- BossBar permanente + ActionBar pulsado cada tick durante 14 ticks (0.7 s) tras minar.
- Al terminar se restaura el snapshot original y se envía al punto de libertad.

## Integración MDVSocial

MDVJustice no modifica ni enlaza su API. Ejecuta desde consola:

```text
mdvsocial title punish {player} esclavo
```

al castigar, y:

```text
mdvsocial title unpunish {player}
```

al liberar.

Ambos comandos son editables en `config.yml`.

## Preparar la prisión

Ejecuta desde las esquinas/puntos deseados:

```text
/justice prision setspawn
/justice prision setlibertad
/justice prision pos1
/justice prision pos2

/justice mina pos1
/justice mina pos2
/justice mina regenerar
```

La región `prison.region` es el límite de movimiento.
La región `mine.region` es exclusivamente la zona que se regenera y otorga puntos.

## Regeneración segura

La mina:
- solo repone bloques `AIR`;
- se procesa por lotes (`blocks-per-tick`);
- nunca genera un bloque si el `BoundingBox` de un jugador ocupa ese bloque;
- no genera drops ni XP al minar;
- cada material tiene `weight` y `points` configurables.

## WorldGuard

Si la mina está dentro de una región protegida, debes permitir `block-break` ahí para los presos.
MDVJustice igualmente bloquea la rotura de la mina para jugadores no condenados.

## Compilar

Requiere Java 21.

```bash
mvn clean package
```

Salida:

```text
target/MDVJustice-1.0.1.jar
```

También incluye `.github/workflows/build.yml`, por lo que GitHub Actions compila y sube el JAR automáticamente.

## Estructura

```text
xyz.mdvcraft.justice
├── MDVJusticePlugin
├── chat
│   ├── ChatListener
│   ├── ChatManager
│   └── model
├── command
│   ├── JusticeCommand
│   ├── PunishCommand
│   └── ReleaseCommand
├── database
│   └── JusticeRepository
├── integration
│   └── NLoginBridge
├── prison
│   ├── MineManager
│   ├── PrisonKitManager
│   ├── PrisonListener
│   ├── PrisonManager
│   └── model
└── util
```
