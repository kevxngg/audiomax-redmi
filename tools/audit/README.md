# Auditoría del sonido

Mide en el teléfono si cada ajuste de Dolby cambia de verdad el sonido.

1. Instala la compilación `measure` (rama `pruebas`, artefacto `DolbyAtmos-measure`) y da permiso:
   `adb shell pm grant com.dolbyatmos.obsidian android.permission.RECORD_AUDIO`
2. `python gen.py` genera las señales en `sig/`; súbelas con `adb push` a
   `/sdcard/Android/data/com.dolbyatmos.obsidian/files/`.
3. Haz una copia de la base de datos de Dolby en `dax_now.db` (se usa para restaurar):
   `adb exec-out su -c 'cat /data/vendor/dolby/dax_sqlite3.db' > dax_now.db`
4. Ejecuta las pruebas `t1.py` … `t7.py`. No puede sonar nada más en el teléfono mientras tanto.

La captura (Visualizer en la sesión 0) va después de Dolby y antes del volumen, a 48 kHz y en mono.
