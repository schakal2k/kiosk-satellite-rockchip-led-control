# Allnet 174716 dual LED bars

How this variant talks to the hardware. Source: the vendor "LED DEMO" app (`libjnielc.so` plus its Java code), not a datasheet. Everything here is what that app does, not a guarantee about the driver.

## Protocol

The app opens `/dev/ledjni` and calls `ioctl(fd, request, value)`.

| Colour    | Right bar (no suffix in the app) | Left bar (`_left` in the app) |
|-----------|----------------------------------|-------------------------------|
| Red       | 0xa1 (161)                       | 0xb1 (177)                    |
| Green     | 0xa2 (162)                       | 0xb2 (178)                    |
| Blue      | 0xa3 (163)                       | 0xb3 (179)                    |
| Cyan      | 0xa4 (164)                       | 0xb4 (180)                    |
| Magenta   | 0xa5 (165)                       | 0xb5 (181)                    |
| Yellow    | 0xa6 (166)                       | 0xb6 (182)                    |
| White     | 0xa7 (167)                       | 0xb7 (183)                    |

- `value` is the brightness, 0 to 15 (the demo's sliders have a maximum of 15).
- `ioctl(fd, 0x99, 0)` switches the driver off.
- In the demo, choosing a colour resets the other six sliders of that bar to 0, so one colour is shown per bar. This plugin does the same: it zeroes the six others first and writes the selected channel last.

## Why the original plugin showed only blue

The upstream plugin writes requests 0xa1, 0xa2 and 0xa3 in turn as red, green and blue. If the channels are exclusive colours rather than additive ones, the last write (0xa3, blue) decides what is shown, and the left bar is never addressed. That matches the reported behaviour. It is an inference, not something the code proves.

## Open points

- Which bar is physically left or right is assumed (no suffix = right). Use "Swap left and right" if it is reversed.
- Whether the colour channels are exclusive or additive was not tested on the device.
