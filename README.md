# DCIM Sort

## Description

DCIM is a folder where Android smartphones save videos and photos.
Over time people can gather thousands of photos in one single folder.
This utility app allows to group photos into subfolders in a batch manner.
Photos can be grouped by month or day and by place (if saving of geolocation was enabled), which is similar to how standard Google Photos app groups them.

Besides moving, photos can be compressed along the way, either into WebP, HEIC or JPEG, using the [state-of-the-art encoder library from Google "jpegli"](https://opensource.googleblog.com/2024/04/introducing-jpegli-new-jpeg-coding-library.html). AVIF encoding is available on Android 16+.

The app tries to preserve as much metadata as possible: EXIF (gelocation and camera properties), UltraHDR (JPEG only). After moving and/or compressing, photos are still visible in your camera app.

To select photos, either choose a folder with them (DCIM by default), optionally reducing date range and excluding the liked (starred) photos from compression,
or pick photo files one-by-one.

In Preview, if you press-n-hold a photo, you will see how it will look after compression, which is handy for choosing the right compression ratio.
