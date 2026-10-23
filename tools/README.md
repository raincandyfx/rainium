# Converters

Both converters write Rainium JSON meshes. They follow the same rules as the in-game OBJ import, so a model looks the same whichever one you use.

| Tool | Use |
|---|---|
| `obj-converter.html` | Open it in a browser. Drop in an OBJ, adjust axes, units and size with a live preview, and download the JSON. The model never leaves your computer (the page only loads web fonts). |
| `mesh_convert.py` | Command line, Python 3.8+, no packages needed. Handles OBJ, glTF and GLB. A copy is also in your `config/rainium` folder. |

```sh
python mesh_convert.py model.glb wings.json --scale 0.06
python mesh_convert.py --help
```
