# AudioMax

App Android para controlar el **Dolby nativo** (DAP) del Redmi Note 14 Pro 4G (`obsidian`, HyperOS 3) con root.

No instala otro Dolby: edita la configuración que ya usa el teléfono (`/data/vendor/dolby/dax_sqlite3.db`) y reinicia el servicio de Dolby (`dms-hal`) y `audioserver` para aplicarla.

## Controles

Por perfil (Dinámico, Película, Música, Voz) y salida (altavoz / auriculares, Bluetooth y USB):

- Realce de bajos: activación, intensidad y frecuencia de corte (altavoz limitado a +12 dB)
- Bajo virtual (altavoz)
- Ecualizador inteligente: Detallado, Equilibrado o Cálido, y cantidad
- Ecualizador gráfico de 20 bandas (±12 dB)
- Sonido envolvente y su amplitud
- Realce de diálogos
- Volumen máximo extra y nivelador de volumen

**Restaurar** vuelve a la base de datos que había la primera vez que se abrió la app.

## Requisitos

- Root (KernelSU) concedido a la app
- Pensada y probada solo para este modelo

## Compilación

GitHub Actions compila y firma la APK en cada push a `main` y la publica en Releases junto a `update.json`. La app revisa cada hora, avisa con una notificación ("Actualizar ahora") y se instala con root. Para publicar una versión nueva: subir `VERSION` y escribir `RELEASE_NOTES.md`.
