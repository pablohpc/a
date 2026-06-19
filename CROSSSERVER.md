# ProtectionStones cross-server (FancyMonolith / FancyCore)

ProtectionStones se integra con la infraestructura cross-server de **FancyMonolith** (módulo
`core` / FancyCore) para sincronizar regiones entre servidores y permitir teletransportes a
regiones alojadas en otro servidor de la red.

ProtectionStones **no** levanta su propia instancia de FancyCore: reutiliza la que ya inicializa el
plugin host (FancySurvival) y solo toma prestado su mensajero de Redis. Si FancyCore no está
presente, ProtectionStones funciona en modo de un solo servidor sin cambios de comportamiento.

## Qué se sincroniza

- **Datos de región** (`dev.espi.protectionstones.crosserver.RegionSyncPacket`): creación, borrado y
  modificaciones (owners, members, nombre `/ps name`, home `/ps sethome`, tipo de bloque y todos los
  flags de WorldGuard). Cada cambio local se difunde por Redis y cada servidor que aloja el mismo
  mundo aplica el cambio en su `RegionManager`. Los flags viajan en la forma *marshalled* canónica de
  WorldGuard, la misma que usa para persistir regiones.
- **Teletransporte cross-server**: `/ps tp <id|nombre>` y `/ps home <id|nombre>`. Si la región no
  existe localmente pero sí en otro servidor (según el índice en Redis), el jugador es enviado a ese
  servidor por BungeeCord y, al llegar, se le teletransporta al home de la región. `/ps home` exige
  además que el jugador sea owner o member.
- **Comandos de listado cross-server**: `/ps list [jugador]`, `/ps count [jugador]`,
  `/ps tp <jugador> <n>` y `/ps home` (sin argumento) agregan también las regiones que el jugador
  tiene en otros servidores. Las regiones remotas se marcan con `[servidor]` y, al seleccionarlas,
  enrutan el teletransporte cross-server. Se apoyan en un índice por jugador en Redis
  (`protectionstones:player:<uuid>:<mundo>:<id>`). Las regiones ya presentes en el WorldGuard local
  (réplica de mundos compartidos) se excluyen del listado remoto para no duplicarlas.

## Requisito de compilación

`core` se consume desde el repositorio Maven local. Antes de compilar ProtectionStones hay que
publicarlo desde el proyecto FancyMonolith:

```
cd FancyMonolith
./gradlew :core:publishToMavenLocal
```

Esto deja `dev.ephemeral.fancy:core:0.1.0-SNAPSHOT` en `~/.m2`. Se declara con scope `provided`
(junto con `jedis` y `gson`, solo necesarios en tiempo de compilación), por lo que **no** se empaqueta
dentro de ProtectionStones: en runtime lo aporta el plugin host.

## Requisitos de ejecución

- El plugin host (FancySurvival) debe estar presente y haber inicializado FancyCore. ProtectionStones
  lo declara como `softdepend` en `plugin.yml` para que Paper enlace los classloaders y se cargue
  después del host.
- Redis accesible (configurado en FancyCore) y un proxy (BungeeCord/Velocity) cuyos nombres de
  servidor coincidan con el `server.id` de cada FancyCore. El `server.id` se reutiliza tal cual.
- Para la sincronización de datos de región, los servidores deben compartir los mismos mundos.

## Limitaciones conocidas

- Las fusiones de regiones (`/ps merge`, auto-merge) modifican WorldGuard directamente sin pasar por
  los mutadores de ProtectionStones, por lo que esos cambios de geometría no se difunden todavía.
- Ediciones individuales sobre sub-regiones fusionadas (`PSMergedRegion`) se reflejan al
  sincronizarse la región de grupo, pero no se difunden de forma aislada.
- Los comandos de listado consultan Redis (un `scan` por jugador, más un `get` por región). Es
  apto para redes de tamaño moderado; con cientos de miles de regiones convendría un índice más
  especializado.
