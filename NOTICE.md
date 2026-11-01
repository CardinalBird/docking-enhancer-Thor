# Notices and attributions

Docking Enhancer (Odin Input Mirror)
Copyright (C) 2026 Ezequiel-CE

Control Switch (formerly Docking Enhancer for AYN Thor)
Modified from the above for the AYN Thor by CardinalBird
(https://github.com/CardinalBird/control-switch), 2026. Changes include
the Home button modes, the L+R player 1 lock, Thor dock detection, the
controller-matching fixes, the redesigned UI and launcher icon and the rename;
the git history records each one.

This program is free software, distributed under the terms of the GNU General
Public License v2.0. See [LICENSE](LICENSE) for the full text.

## Third-party techniques and code

### No-root PServer technique

The no-root approach used by this project — obtaining the stock firmware's
`PServerBinder` service via reflection and running privileged commands through
it — was pioneered by **ClusterTune**
(https://github.com/AurelioB/ClusterTune) and popularized by **PULSE**
(https://github.com/keiretrogaming/pulse), which is licensed under the GNU
General Public License v2.0.

The PServerBinder access code in
`android/data/src/main/java/com/controlswitch/data/PServerExec.kt` is adapted
from PULSE's `RootExec.kt`. This project is licensed under the GPL v2.0 in part
because it incorporates that work.

Please keep this attribution intact in any fork or redistribution; the GPL
requires it.

### Poppins

The app's typeface is Poppins SemiBold, copyright 2020 The Poppins Project Authors
(https://github.com/itfoundry/Poppins), under the SIL Open Font License 1.1. See
[third-party/poppins/NOTICE.md](third-party/poppins/NOTICE.md).

### SDL GameControllerDB

`android/data/src/main/assets/gamecontrollerdb.txt` is the community SDL game controller database
(https://github.com/mdqinc/SDL_GameControllerDB), under the zlib license, copyright Sam Lantinga and
the contributors. It seeds the default mapping for pads it knows. See
[third-party/gamecontrollerdb/NOTICE.md](third-party/gamecontrollerdb/NOTICE.md).

### Kenney Input Prompts

The button and controller prompts (`android/app/src/main/res/drawable/kp_*.xml`) are converted from
Kenney Input Prompts 1.5 by Kenney (https://kenney.nl), released under CC0 1.0 (public domain).
