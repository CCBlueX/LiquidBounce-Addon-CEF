# LiquidBounce CEF

The [LiquidBounce](https://github.com/CCBlueX/LiquidBounce) add-on that shows the client's menus with Chromium, through
the [Chromium Embedded Framework](https://bitbucket.org/chromiumembedded/cef). LiquidBounce comes with it, it is not
installed on its own. [Wry](https://github.com/CCBlueX/LiquidBounce-Addon-Wry) and
[Ultralight](https://github.com/CCBlueX/LiquidBounce-Addon-Ultralight) are the alternatives.

It is a fork of [MCEF](https://github.com/CinemaMod/mcef) and uses [our fork of java-cef](https://github.com/CCBlueX/java-cef).
On the first start it downloads the java-cef natives built from the commit in [`java-cef`](java-cef) from the
LiquidBounce API. Chromium runs on Windows 10 and 11, macOS 10.15 and later, and Linux, on x86-64 and ARM64.

## Building

```
git submodule update --init
./gradlew build
```

`./gradlew runClientGameTest` starts the client with the add-on and checks that it shows the client's pages.
`PROVIDED_JCEF_PATH=<dir>` loads the natives of a java-cef build of your own instead of downloading them.

## License

The add-on is licensed under the LGPL 2.1 or later, see [LICENSE](LICENSE). MCEF is by montoyo and the CinemaMod
Group, java-cef and CEF are under the BSD license.
