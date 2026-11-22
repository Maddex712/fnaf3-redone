# FNaF 3 Redone for AYN Thor 1.1.1

An unofficial launcher and companion for the Steam release of Five Nights at Freddy's 3. The APK contains no game files. Import your own `FiveNightsatFreddys3.exe` from Steam build 1892839 on first launch. The app verifies it and runs it unmodified.

## Requirements
- ARM64 Android 8 or newer. Built and tested on the AYN Thor (dual screen). Other devices use the single-screen layout.
- Your own Steam copy of FNaF 3, build 1892839. Other builds are rejected.

## What's new in 1.1.1
- 60 fps. The game now draws through DXVK (Direct3D 9 to Vulkan) instead of WineD3D and runs at 60 fps on the Thor, up from about 30. The cameras, maintenance and the live office on the other screen keep up with it. Settings, Renderer switches back to WineD3D.
- Cameras and maintenance move between the screens. The office turns toward the panel on the top screen, then the panel drops onto the lower screen and lifts back out when closed. The original flip no longer plays over the office.
- Ventilation failure no longer locks you out. Cameras and maintenance open, stay up and close properly while the office is dark or the alarm is flashing.
- While the game loads or setup is open, the lower screen shows the Shift Manager instead of the Android home screen, and the controller keeps working.
- The pause menu starts on Keep playing, and an open panel stays where it is while paused.
- The Shift Manager tabs match the title menu: Arcade and Settings.
- Arcade practice for BB's Air Adventure shows its movement keys again.

## Updating
Install over 1.1.0 or 1.0. Saves, points and unlocks are kept.

## Source and licences
`FNaF3-Redone-Thor-1.1.1-source.zip` holds the complete source of this app, including all changes, under the LGPL 2.1. It also contains the source of the display bridge (the `d3d9.dll` that sits between the game and Direct3D) and the Gladio and Vortek renderer source, which is part of Winlator. The licence list appears in the app under Settings, Open-source licenses.

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
