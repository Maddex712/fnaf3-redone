# FNaF 3 Redone for AYN Thor 1.1.0

An unofficial launcher and companion for the Steam release of Five Nights at Freddy's 3. The APK contains no game files. Import your own `FiveNightsatFreddys3.exe` from Steam build 1892839 on first launch. The app verifies it and runs it unmodified.

## Requirements
- ARM64 Android 8 or newer. Built and tested on the AYN Thor (dual screen). Other devices use the single-screen layout.
- Your own Steam copy of FNaF 3, build 1892839. Other builds are rejected.

## What's new since 1.0
- One title, like FNaF 1 and 2 Redone. The app boots straight into the game, and picking a night starts it with no separate launcher page.
- Lower screen: the Fazbear Shift Manager between nights and the Security Desk during them, built from the game's own interface art in your imported file.
- Full controller support on every menu, office look, cameras and maintenance. START pauses with Keep playing, Quit to title and Exit.
- Arcade practice for all six minigames on separate saves, with guides hidden until you ask.
- No Winlator dialogs. The one-time runtime unpack runs behind the title.
- Open-source licenses page under Settings.

## Updating from 1.0
Install over 1.0. Saves, points and unlocks are kept.

## Source and licences
`FNaF3-Redone-Thor-1.1.0-source.zip` holds the complete source of this app, including all changes, under the LGPL 2.1. It also contains the Gladio and Vortek renderer source, which is part of Winlator. The licence list appears in the app under Settings, Open-source licenses.

Upstream source for the bundled runtime binaries:
- Winlator: https://github.com/brunodev85/winlator-app
- Wine 10.10: https://dl.winehq.org/wine/source/10.x/
- Box64 0.4.4: https://github.com/ptitSeb/box64
- Mesa (Turnip, VirGL, Zink): https://archive.mesa3d.org/
- DXVK 1.10.3 and 2.4.1: https://github.com/doitsujin/dxvk
- D8VK 1.0: https://github.com/AlpyneDreams/d8vk
- D7VK 1.11: DXVK-based Direct3D 7 layer, published by its authors on GitHub
- VKD3D-Proton 2.14.1: https://github.com/HansKristian-Work/vkd3d-proton
- cnc-ddraw 6.6: https://github.com/FunkyFr3sh/cnc-ddraw
- PulseAudio: https://www.freedesktop.org/wiki/Software/PulseAudio/
