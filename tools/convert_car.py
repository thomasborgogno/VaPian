"""
Converts a glTF binary car model (.glb) into the compact `car.bin` the dashboard draws (see CarBinary.kt).

    python tools/convert_car.py path/to/model.glb app/src/main/assets/car/car.bin [--cell 0.035] [--preview out.png]

What it does, offline, so the head unit does no work at all:
- flattens the node tree (translation / rotation / scale) into world-space triangles;
- drops what cannot be seen from outside (interior, brake rotors);
- gives every triangle one colour, sampled from its material's texture at the triangle's centre, and a surface kind
  (paint, glass, matte, metal, light); the paint is forced to black;
- simplifies by vertex clustering (vertices in the same cube of --cell meters are merged, collapsed triangles dropped),
  from tens of thousands of triangles to a few thousand;
- centres the car, puts it on the ground (y = 0) with its nose towards -z, length scaled to 4.06 m.

Needs numpy and Pillow.
"""
import argparse
import io
import json
import struct

import numpy as np
from PIL import Image

PAINT, GLASS, MATTE, METAL, LIGHT = 0, 1, 2, 3, 4
BLACK_PAINT = (20, 21, 24)

COMPONENT = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
COUNT = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def read_glb(path):
    data = open(path, "rb").read()
    magic, _, _ = struct.unpack("<III", data[:12])
    assert magic == 0x46546C67, "not a GLB"
    offset = 12
    gltf, binary = None, None
    while offset < len(data):
        length, kind = struct.unpack("<II", data[offset:offset + 8])
        chunk = data[offset + 8:offset + 8 + length]
        if kind == 0x4E4F534A:
            gltf = json.loads(chunk)
        elif kind == 0x004E4942:
            binary = chunk
        offset += 8 + length
    return gltf, binary


def accessor(gltf, binary, index):
    acc = gltf["accessors"][index]
    view = gltf["bufferViews"][acc["bufferView"]]
    dtype = np.dtype(COMPONENT[acc["componentType"]])
    n = COUNT[acc["type"]]
    start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    stride = view.get("byteStride", 0) or dtype.itemsize * n
    raw = np.frombuffer(binary, dtype=np.uint8, count=stride * (acc["count"] - 1) + dtype.itemsize * n, offset=start)
    out = np.lib.stride_tricks.as_strided(raw, shape=(acc["count"], n * dtype.itemsize), strides=(stride, 1)).copy()
    out = out.view(dtype).reshape(acc["count"], n)
    if acc.get("normalized") and dtype != np.float32:
        out = out.astype(np.float32) / np.iinfo(dtype).max
    return out


def image(gltf, binary, texture_index):
    source = gltf["textures"][texture_index]["source"]
    view = gltf["bufferViews"][gltf["images"][source]["bufferView"]]
    start = view.get("byteOffset", 0)
    return np.asarray(Image.open(io.BytesIO(binary[start:start + view["byteLength"]])).convert("RGB"))


def node_matrix(node):
    if "matrix" in node:
        return np.array(node["matrix"], dtype=np.float64).reshape(4, 4).T
    t = np.array(node.get("translation", [0, 0, 0]), dtype=np.float64)
    x, y, z, w = node.get("rotation", [0, 0, 0, 1])
    s = np.array(node.get("scale", [1, 1, 1]), dtype=np.float64)
    r = np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ])
    m = np.eye(4)
    m[:3, :3] = r * s
    m[:3, 3] = t
    return m


def classify(name):
    """(kind, keep) from the material and node names."""
    n = name.lower()
    if "interior" in n or "rotor" in n:
        return None
    if "glass" in n or "window" in n:
        return GLASS
    if "light" in n or "lamp" in n:
        return LIGHT
    if "chrom" in n or "metal" in n or "wheel" in n:
        return METAL
    if "tire" in n or "tyre" in n or "black" in n:
        return MATTE
    if "body" in n or "paint" in n:
        return PAINT
    return MATTE


def collect(gltf, binary):
    positions, colors, kinds, fine = [], [], [], []
    textures = {}

    def visit(index, parent):
        node = gltf["nodes"][index]
        world = parent @ node_matrix(node)
        if "mesh" in node:
            for prim in gltf["meshes"][node["mesh"]]["primitives"]:
                if prim.get("mode", 4) != 4:
                    continue
                material = gltf["materials"][prim["material"]] if "material" in prim else {"name": ""}
                kind = classify(material.get("name", "") + " " + node.get("name", ""))
                if kind is None:
                    continue
                pos = accessor(gltf, binary, prim["attributes"]["POSITION"]).astype(np.float64)
                pos = (np.c_[pos, np.ones(len(pos))] @ world.T)[:, :3]
                idx = accessor(gltf, binary, prim["indices"]).reshape(-1, 3) if "indices" in prim else np.arange(len(pos)).reshape(-1, 3)
                tri = pos[idx]  # (n, 3, 3)
                if np.linalg.det(world[:3, :3]) < 0:
                    tri = tri[:, ::-1]
                # one colour per triangle from the texture at the triangle's centre (or the base colour)
                pbr = material.get("pbrMetallicRoughness", {})
                factor = np.array(pbr.get("baseColorFactor", [1, 1, 1, 1])[:3])
                if kind == PAINT:
                    col = np.tile(np.array(BLACK_PAINT, dtype=np.float64), (len(tri), 1))
                elif "baseColorTexture" in pbr and "TEXCOORD_0" in prim["attributes"]:
                    t = pbr["baseColorTexture"]["index"]
                    if t not in textures:
                        textures[t] = image(gltf, binary, t)
                    img = textures[t]
                    uv = accessor(gltf, binary, prim["attributes"]["TEXCOORD_0"])[idx].mean(axis=1)
                    u = (np.mod(uv[:, 0], 1.0) * (img.shape[1] - 1)).astype(int)
                    v = (np.mod(uv[:, 1], 1.0) * (img.shape[0] - 1)).astype(int)
                    col = img[v, u].astype(np.float64) * factor
                else:
                    col = np.tile(factor * 255, (len(tri), 1))
                positions.append(tri)
                colors.append(col)
                kinds.append(np.full(len(tri), kind, dtype=np.uint8))
                # round parts (tyres, rims) lose their shape quickly: they are simplified on a finer grid
                n = (material.get("name", "") + " " + node.get("name", "")).lower()
                fine.append(np.full(len(tri), "tire" in n or "wheel" in n, dtype=bool))
        for child in node.get("children", []):
            visit(child, world)

    scene = gltf["scenes"][gltf.get("scene", 0)]
    for root in scene["nodes"]:
        visit(root, np.eye(4))
    return np.concatenate(positions), np.concatenate(colors), np.concatenate(kinds), np.concatenate(fine)


def normalise(tri, nose_negative_z):
    flat = tri.reshape(-1, 3)
    lo, hi = flat.min(axis=0), flat.max(axis=0)
    # the length is the longest horizontal extent; make it run along z
    if (hi[0] - lo[0]) > (hi[2] - lo[2]):
        tri = tri[..., [2, 1, 0]] * np.array([-1, 1, 1])  # rotate 90° about y (a proper rotation)
        flat = tri.reshape(-1, 3)
        lo, hi = flat.min(axis=0), flat.max(axis=0)
    centre = np.array([(lo[0] + hi[0]) / 2, lo[1], (lo[2] + hi[2]) / 2])
    tri = (tri - centre) * (4.06 / (hi[2] - lo[2]))
    if not nose_negative_z:
        tri = tri * np.array([-1, 1, -1])  # 180° about y
    return tri


def simplify(tri, colors, kinds, fine, cell):
    """Vertex clustering per surface kind (so glass and paint never merge into each other); round parts on a finer grid."""
    out_t, out_c, out_k = [], [], []
    for k, f in sorted({(int(a), bool(b)) for a, b in zip(kinds, fine)}):
        sel = (kinds == k) & (fine == f)
        t = tri[sel]
        flat = t.reshape(-1, 3)
        keys = np.floor(flat / (cell * 0.6 if f else cell)).astype(np.int64)
        uniq, inverse = np.unique(keys, axis=0, return_inverse=True)
        inverse = inverse.reshape(-1)
        sums = np.zeros((len(uniq), 3))
        np.add.at(sums, inverse, flat)
        counts = np.bincount(inverse, minlength=len(uniq))[:, None]
        reps = sums / counts
        ids = inverse.reshape(-1, 3)
        alive = (ids[:, 0] != ids[:, 1]) & (ids[:, 1] != ids[:, 2]) & (ids[:, 0] != ids[:, 2])
        ids = ids[alive]
        c = colors[sel][alive]
        # duplicates (same three clusters, any order) collapse to one
        _, first = np.unique(np.sort(ids, axis=1), axis=0, return_index=True)
        ids = ids[np.sort(first)]
        c = c[np.sort(first)]
        out_t.append(reps[ids])
        out_c.append(c)
        out_k.append(np.full(len(ids), k, dtype=np.uint8))
    return np.concatenate(out_t), np.concatenate(out_c), np.concatenate(out_k)


def write_bin(path, tri, colors, kinds):
    """'VPC1', count, then per triangle: 9 float32 (x y z ×3), ARGB uint32, kind uint8 (little endian)."""
    n = len(tri)
    rec = np.zeros(n, dtype=[("p", "<f4", 9), ("c", "<u4"), ("k", "u1")])
    rec["p"] = tri.reshape(n, 9).astype(np.float32)
    c = np.clip(colors, 0, 255).astype(np.uint32)
    rec["c"] = (0xFF << 24) | (c[:, 0] << 16) | (c[:, 1] << 8) | c[:, 2]
    rec["k"] = kinds
    with open(path, "wb") as f:
        f.write(b"VPC1")
        f.write(struct.pack("<I", n))
        f.write(rec.tobytes())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("glb")
    ap.add_argument("out")
    ap.add_argument("--cell", type=float, default=0.045, help="clustering cube size in meters (bigger = fewer triangles)")
    ap.add_argument("--nose-positive-z", action="store_true", help="the source model's nose points to +z")
    args = ap.parse_args()
    gltf, binary = read_glb(args.glb)
    tri, colors, kinds, fine = collect(gltf, binary)
    print("source triangles (visible parts):", len(tri))
    tri = normalise(tri, nose_negative_z=not args.nose_positive_z)
    tri, colors, kinds = simplify(tri, colors, kinds, fine, args.cell)
    print("after simplification:", len(tri), {int(k): int((kinds == k).sum()) for k in np.unique(kinds)})
    write_bin(args.out, tri, colors, kinds)


if __name__ == "__main__":
    main()
