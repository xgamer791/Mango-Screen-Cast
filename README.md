# Mango Screen Cast For macOS/iOS

Mango Screen Cast For macOS/iOS: AirPlay screen mirroring from a Mac, iPhone or iPad to an Android TV.

## TV screen

`index.html` is the "Ready to Cast" home screen, authored at 2560×1440 (1440p) and scaled to fit
whatever window it opens in (`app.js`). Open the file in a browser, or serve the folder:

```sh
python3 -m http.server 8080
# http://localhost:8080
```

- `styles.css` holds every coordinate, measured from the reference mock and scaled to 1440p.
- The wallpaper layer (`.wallpaper`) is blank on purpose.
- The center strip's frosted layer is `.glass__base`; its tint is the `--glass-base` custom property.
- Brand marks live in `assets/logos/`.
