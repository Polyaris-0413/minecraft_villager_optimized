# VillagerOptimized

A small Fabric mod for Minecraft 1.21.1 that makes villagers behave a bit more sensibly.

## Features

- **Farmland protection** — villagers no longer turn farmland into dirt when they land on it. Other mobs
  (including iron golems) keep vanilla behaviour, and villagers still take fall damage.
- **Smiths repair iron golems** — armorers, toolsmiths and weaponsmiths walk to a damaged iron golem
  within 16 blocks and repair it (25 HP per second), holding an iron ingot while they work.
  They skip this while sleeping, trading, panicking or during raids.
- **Sleep regeneration** — villagers recover 1 HP per second while sleeping in a bed, filling up over a night.

## Requirements

- Minecraft 1.21.1
- Fabric Loader 0.20.1 or newer
- Fabric API

## Install

Put the jar from `build/libs` into your `mods` folder.

## Development

```bash
./gradlew build        # build the jar and run the server GameTests
./gradlew runGameTest  # run only the GameTests
./gradlew runClient    # launch a dev client with the mod loaded
```

## License

GPL-3.0-only — see [LICENSE](LICENSE).
