# ワルキューレの栄光 Wrapper

ワルキューレの栄光, DoJa to MIDP Wrapper.

> [!NOTE]
> This project provides the wrapper's source code and original assets under [GPLv3](LICENSE).
>
> You may redistribute this wrapper and modified versions in source or compiled form under GPLv3, provided that you retain copyright and license notices, keep the covered work under GPLv3, and provide the complete Corresponding Source as required by the license. Modified versions must also include notices of modification and the relevant dates.

![](/img.webp)

## How to Use?

You only need [Apache Ant](https://ant.apache.org/bindownload.cgi)!

1. Put the DoJa version JAR/SP/JAM into `games/`;
2. Run `ant`;
3. The MIDP version will be output to `dist/` :)

*This requires JDK8 or newer.(but, OpenJDK8 is the best!)*

## Real Devices

JVM Heap ≥ 102400 KB, CPU  ≥ 434 Mhz

Part 1 runs perfectly.

Since Part 2 uses a ton of alpha blending, its performance on the N86 isn't quite ideal either, but it's definitely good enough now.


## Translations

Run the **ant** build once, and Translation.tsv will be generated under `comp/translation` for you to translate.

You can safely ignore Index.tsv, its only used internally to index the translations.


## Staff

**Framework**: Magstic

**Coding**: 5.6 Sol Web

**Artist**: Magstic


## Test

Test video: [Blibili](https://www.bilibili.com/video/BV1Q1hi6KEm2/)

- KEmulator nnmod: Works
- FreeJ2ME-Plus: Works, use the libretro core.. smoother speed
- J2ME-Loader: Works
- Real device: Works, but 2 needs a powerful real device (it relies heavily on alpha blending)

**The JSR135 implementation in existing emulators might be broken, causing the game to go silent instantly.**


## Modules

- [MLD Player](https://github.com/Magstic/MLD_Player) — MLD to MID/WAV conversion.
- [DoJa to MIDP Core](https://github.com/RetroJ2ME/DoJa-to-MIDP-Core) — Core runtime and tools.
- [ProGuard 6.0.3](https://mvnrepository.com/artifact/com.guardsquare/proguard-base) — Game obfuscation and preverification.
- [CLDC API 1.1](https://mvnrepository.com/artifact/javax.microedition/cldc) — Basic CLDC API support.
- [MIDP API 2.0](https://mvnrepository.com/artifact/javax.microedition/midp) — Basic MIDP API support.

## Thanks

[J2ME Docs](https://nikita36078.github.io/J2ME_Docs/)

[Keitai Wiki](https://keitaiwiki.com/wiki/KeitaiWiki)

[Fusion Pixel Font](https://github.com/TakWolf/fusion-pixel-font)

## LICENSE

本軟體採用 GNU 通用公眾授權條款第 3 版（僅限此版本，`GPL-3.0-only`）。

您可以依照授權條款使用、修改及再散布本軟體；散布本軟體或受其授權涵蓋的修改版時，須保留著作權與授權聲明，維持 GPLv3 授權，並依條款提供對應原始碼。本軟體不提供任何擔保，包括適售性或特定用途適用性的擔保。完整條款請參閱 [LICENSE](LICENSE)。

本授權適用於本專案原創的程式碼與資產。

This software is licensed under the GNU General Public License, version 3 only (`GPL-3.0-only`). 

You may use, modify, and redistribute it under that license. When distributing this software or covered modified versions, you must retain copyright and license notices, keep them under GPLv3, and provide the corresponding source code as required by the license. This software comes without any warranty, including warranties of merchantability or fitness for a particular purpose. See [LICENSE](LICENSE) for the full terms.

This license applies to the original code and assets created for this project.
