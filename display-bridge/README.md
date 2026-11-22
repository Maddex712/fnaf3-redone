# Display bridge

`d3d9_bridge.c` builds the `d3d9.dll` in `app/src/main/assets/thor-dual`. The game loads it in place of Direct3D 9. It passes every call on to the real renderer (DXVK when `thor-dxvk-d3d9.dll` is present, otherwise Wine's own d3d9) and copies a half-size picture of each frame, plus the office layer while a panel is up, into two shared frame files. The app reads those to draw the cameras, maintenance and office on the other screen. It also reports whether the office, the cameras, maintenance or their switches are on screen.

Build with an llvm-mingw toolchain:

```
clang --target=i686-w64-mingw32 -std=gnu11 -O2 -msse2 -Wall -Wextra -Werror \
  -Wno-dll-attribute-on-redeclaration -shared -Wl,--kill-at -o d3d9.dll d3d9_bridge.c
```

`scene-classifier-verify.c` checks the screen classifier against captured frames. Those frames are pictures of the game, so they are not included here.
