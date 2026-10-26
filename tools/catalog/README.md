# The TV app's browse catalog

How the shows on the TV home screen's pages (What to Watch, Featured Free,
Live TV, Sports) and their art were made.

- `plan.py`: every title, the page and row it's on, the app it opens and its link there.
- `fetch.py titles.txt out.json`: show data and art links from TVMaze's free API.
- `commons.py`: public-domain film stills from Wikimedia Commons (the Featured Free films, all free on Tubi).
- `process.py`: crops each image to 16:9, runs it through Upscayl's High Fidelity model at 2x
  (the same clarity pass as the home wallpaper), and writes a 2560 x 1440 backdrop and a
  512 x 288 tile as WebP.
- `gen.py`: writes `android-tv/.../browse/Catalog.kt` from the above, with our own descriptions.
- `to_vector.py icon.svg name size colour note`: turns a Lucide icon into an Android vector drawable.

Show art is the networks' promotional art as published on TVMaze; the films are public domain.
