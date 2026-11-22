# Changelog

## 1.1.1

- 60 fps: the game draws through DXVK instead of WineD3D. Settings, Renderer switches back.
- Cameras and maintenance move between the screens: the office turns toward the panel live on the top screen, and the panel drops onto the lower screen and lifts out when closed.
- Ventilation failure no longer locks out the cameras and maintenance, and panels stay up through blackouts.
- The lower screen shows the Shift Manager during loading and setup, and the controller keeps working there.
- The pause menu starts on Keep playing; open panels stay put while paused.
- Shift Manager tabs renamed to match the title menu (Arcade, Settings).
- BB's Air Adventure practice shows its movement keys again.
- The display bridge source is now included.

## 1.1.0

- One title, like FNaF 1 and 2 Redone. The app boots straight into the game, and choosing a night starts it with no separate launcher page.
- Lower screen redesigned: the Fazbear Shift Manager between nights and the Security Desk during them, using the game's own interface art read from your imported file.
- Full controller support on every menu, office look, cameras and maintenance. START opens a pause menu with Keep playing, Quit to title and Exit.
- Arcade practice for all six minigames on separate saves. Guides stay hidden until you open them.
- Winlator's loading dialogs no longer appear. The one-time runtime unpack runs behind the title.
- Open-source licenses page under Settings.

## 1.0.0

- First release. Runs the Steam build of FNaF 3 through the bundled Wine and Box64 runtime, with the office on the upper screen and cameras and maintenance on the lower screen.
