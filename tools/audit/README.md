# Sound audit

Measures on the phone whether each Dolby setting really changes the sound.

1. Install the `measure` build (branch `pruebas`, artifact `DolbyAtmos-measure`) and grant the permission:
   `adb shell pm grant com.dolbyatmos.obsidian android.permission.RECORD_AUDIO`
2. `python gen.py` creates the test signals in `sig/`; push them with `adb push` to
   `/sdcard/Android/data/com.dolbyatmos.obsidian/files/`.
3. Back up the Dolby database to `dax_now.db` (used to restore it afterwards):
   `adb exec-out su -c 'cat /data/vendor/dolby/dax_sqlite3.db' > dax_now.db`
4. Run the tests `t1.py` … `t7.py`. Nothing else may play on the phone meanwhile.

The capture (a Visualizer on session 0) sits after Dolby and before the volume, at 48 kHz, mono.
