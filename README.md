# ワルキューレの栄光 Wrapper

ワルキューレの栄光, DoJa to MIDP Wrapper.

![](/img.webp)

## How to Use?

You only need [Apache Ant](https://ant.apache.org/bindownload.cgi)!

1. Put the DoJa version JAR/SP/JAM into `games/`;
2. Run `ant`;
3. The MIDP version will be output to `dist/` :)


## Real Devices

JVM Heap ≥ 102400 KB, CPU  ≥ 434 Mhz

Part 1 runs perfectly.

Since Part 2 uses a ton of alpha blending, its performance on the N86 isn't quite ideal either, but it's definitely good enough now.


## Translations

Run the **ant** build once, and Translation.tsv will be generated under `comp/translation` for you to translate.

You can safely ignore Index.tsv, its only used internally to index the translations.


## LLM

Coding: 5.6 Sol Web


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