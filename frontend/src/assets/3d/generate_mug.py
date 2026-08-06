#!/usr/bin/env python3
"""Generate mug.glb with 2 materials (PrintArea + GlazedPorcelain) and UV-mapped print area."""

import math
import struct
import json
import zlib
import os

# ── Constants ────────────────────────────────────────────────────────────────

OUTER_RADIUS = 4.0
INNER_RADIUS = 3.2
HEIGHT = 10.0
ANGULAR_SEGMENTS = 64
HEIGHT_SEGMENTS = 8

HANDLE_MAX_X = 7.4
HANDLE_Y_BOTTOM = 2.25
HANDLE_Y_TOP = 7.75
HANDLE_TUBE_RADIUS = 0.424

# Print area: 200mm out of ~251mm circumference -> ~287 deg arc
PRINT_ARC_DEG = 200.0 / (2.0 * math.pi * 40.0) * 360.0  # ~286.48, use full formula
NON_PRINT_ARC_DEG = 360.0 - PRINT_ARC_DEG
HALF_GAP_DEG = NON_PRINT_ARC_DEG / 2.0

# Handle at 0 deg (positive X axis)
PRINT_START_RAD = math.radians(HALF_GAP_DEG)
PRINT_END_RAD = math.radians(360.0 - HALF_GAP_DEG)

PRINT_SEGMENTS = round(ANGULAR_SEGMENTS * PRINT_ARC_DEG / 360.0)
NON_PRINT_SEGMENTS = ANGULAR_SEGMENTS - PRINT_SEGMENTS

PRINT_MARGIN_BOTTOM = 0.3
PRINT_MARGIN_TOP = 0.3
PRINT_Y_BOTTOM = PRINT_MARGIN_BOTTOM
PRINT_Y_TOP = HEIGHT - PRINT_MARGIN_TOP

PRINT_AREA_OFFSET = 0.02

HANDLE_PATH_SEGMENTS = 20
HANDLE_TUBE_SEGMENTS = 10


# ── Geometry Builders ────────────────────────────────────────────────────────

def build_print_area():
    """Outer cylinder wall in the print angular range, with UVs."""
    positions = []
    normals = []
    uvs = []
    indices = []

    n_a = PRINT_SEGMENTS
    n_h = HEIGHT_SEGMENTS

    print_height = PRINT_Y_TOP - PRINT_Y_BOTTOM

    for j in range(n_h + 1):
        y = PRINT_Y_BOTTOM + j * print_height / n_h
        v = 1.0 - (y - PRINT_Y_BOTTOM) / print_height  # V=0 at top, V=1 at bottom
        for i in range(n_a + 1):
            frac = i / n_a
            angle = PRINT_START_RAD + frac * (PRINT_END_RAD - PRINT_START_RAD)
            # U=0 at viewer's left (angle near 323.5 deg), U=1 at viewer's right (angle near 36.5 deg)
            u = 1.0 - frac

            x = (OUTER_RADIUS + PRINT_AREA_OFFSET) * math.cos(angle)
            z = (OUTER_RADIUS + PRINT_AREA_OFFSET) * math.sin(angle)
            nx = math.cos(angle)
            nz = math.sin(angle)

            positions.extend([x, y, z])
            normals.extend([nx, 0.0, nz])
            uvs.extend([u, v])

    for j in range(n_h):
        for i in range(n_a):
            row0 = j * (n_a + 1)
            row1 = (j + 1) * (n_a + 1)
            a = row0 + i
            b = row0 + i + 1
            c = row1 + i
            d = row1 + i + 1
            indices.extend([a, c, b, b, c, d])

    return positions, normals, uvs, indices



def build_non_print_outer():
    """Full 360 deg outer cylinder wall (GlazedPorcelain base behind PrintArea)."""
    positions = []
    normals = []
    indices = []

    n_a = ANGULAR_SEGMENTS
    n_h = HEIGHT_SEGMENTS

    for j in range(n_h + 1):
        y = j * HEIGHT / n_h
        for i in range(n_a + 1):
            angle = i * 2.0 * math.pi / n_a

            x = OUTER_RADIUS * math.cos(angle)
            z = OUTER_RADIUS * math.sin(angle)
            nx = math.cos(angle)
            nz = math.sin(angle)

            positions.extend([x, y, z])
            normals.extend([nx, 0.0, nz])

    for j in range(n_h):
        for i in range(n_a):
            row0 = j * (n_a + 1)
            row1 = (j + 1) * (n_a + 1)
            a = row0 + i
            b = row0 + i + 1
            c = row1 + i
            d = row1 + i + 1
            indices.extend([a, c, b, b, c, d])

    return positions, normals, indices


def build_inner_wall():
    """Full 360 deg inner cylinder wall."""
    positions = []
    normals = []
    indices = []

    n_a = ANGULAR_SEGMENTS
    n_h = HEIGHT_SEGMENTS

    for j in range(n_h + 1):
        y = j * HEIGHT / n_h
        for i in range(n_a + 1):
            angle = i * 2.0 * math.pi / n_a

            x = INNER_RADIUS * math.cos(angle)
            z = INNER_RADIUS * math.sin(angle)
            nx = -math.cos(angle)
            nz = -math.sin(angle)

            positions.extend([x, y, z])
            normals.extend([nx, 0.0, nz])

    for j in range(n_h):
        for i in range(n_a):
            row0 = j * (n_a + 1)
            row1 = (j + 1) * (n_a + 1)
            a = row0 + i
            b = row0 + i + 1
            c = row1 + i
            d = row1 + i + 1
            # Reversed winding for inward-facing surface
            indices.extend([a, b, c, b, d, c])

    return positions, normals, indices


def build_bottom_disc():
    """Annular ring at y=0 (bottom of mug)."""
    positions = []
    normals = []
    indices = []

    n_a = ANGULAR_SEGMENTS

    # Outer ring
    for i in range(n_a + 1):
        angle = i * 2.0 * math.pi / n_a
        x = OUTER_RADIUS * math.cos(angle)
        z = OUTER_RADIUS * math.sin(angle)
        positions.extend([x, 0.0, z])
        normals.extend([0.0, -1.0, 0.0])

    # Inner ring
    for i in range(n_a + 1):
        angle = i * 2.0 * math.pi / n_a
        x = INNER_RADIUS * math.cos(angle)
        z = INNER_RADIUS * math.sin(angle)
        positions.extend([x, 0.0, z])
        normals.extend([0.0, -1.0, 0.0])

    outer_start = 0
    inner_start = n_a + 1

    for i in range(n_a):
        a = outer_start + i
        b = outer_start + i + 1
        c = inner_start + i
        d = inner_start + i + 1
        # Bottom faces down
        indices.extend([a, b, c, b, d, c])

    return positions, normals, indices


def build_top_rim():
    """Annular ring at y=HEIGHT (top of mug)."""
    positions = []
    normals = []
    indices = []

    n_a = ANGULAR_SEGMENTS

    # Outer ring
    for i in range(n_a + 1):
        angle = i * 2.0 * math.pi / n_a
        x = OUTER_RADIUS * math.cos(angle)
        z = OUTER_RADIUS * math.sin(angle)
        positions.extend([x, HEIGHT, z])
        normals.extend([0.0, 1.0, 0.0])

    # Inner ring
    for i in range(n_a + 1):
        angle = i * 2.0 * math.pi / n_a
        x = INNER_RADIUS * math.cos(angle)
        z = INNER_RADIUS * math.sin(angle)
        positions.extend([x, HEIGHT, z])
        normals.extend([0.0, 1.0, 0.0])

    outer_start = 0
    inner_start = n_a + 1

    for i in range(n_a):
        a = outer_start + i
        b = outer_start + i + 1
        c = inner_start + i
        d = inner_start + i + 1
        # Top faces up
        indices.extend([a, c, b, b, c, d])

    return positions, normals, indices


def build_handle():
    """Handle as a tube along an elliptical arc in the XY plane."""
    positions = []
    normals = []
    indices = []

    y_center = (HANDLE_Y_BOTTOM + HANDLE_Y_TOP) / 2.0
    y_half = (HANDLE_Y_TOP - HANDLE_Y_BOTTOM) / 2.0
    x_extent = HANDLE_MAX_X - OUTER_RADIUS

    n_path = HANDLE_PATH_SEGMENTS
    n_tube = HANDLE_TUBE_SEGMENTS

    for pi in range(n_path + 1):
        t = -math.pi / 2.0 + pi * math.pi / n_path

        # Path position
        px = OUTER_RADIUS + x_extent * math.cos(t)
        py = y_center + y_half * math.sin(t)

        # Tangent (normalized)
        tx = -x_extent * math.sin(t)
        ty = y_half * math.cos(t)
        t_len = math.sqrt(tx * tx + ty * ty)
        tx /= t_len
        ty /= t_len

        # Normal in XY plane: N = (Ty, -Tx, 0)
        nx_dir = ty
        ny_dir = -tx

        for si in range(n_tube + 1):
            s = si * 2.0 * math.pi / n_tube

            cos_s = math.cos(s)
            sin_s = math.sin(s)

            x = px + HANDLE_TUBE_RADIUS * cos_s * nx_dir
            y = py + HANDLE_TUBE_RADIUS * cos_s * ny_dir
            z = HANDLE_TUBE_RADIUS * sin_s

            snx = cos_s * nx_dir
            sny = cos_s * ny_dir
            snz = sin_s

            positions.extend([x, y, z])
            normals.extend([snx, sny, snz])

    for pi in range(n_path):
        for si in range(n_tube):
            row0 = pi * (n_tube + 1)
            row1 = (pi + 1) * (n_tube + 1)
            a = row0 + si
            b = row0 + si + 1
            c = row1 + si
            d = row1 + si + 1
            indices.extend([a, c, b, b, c, d])

    return positions, normals, indices


# ── PNG Helper ───────────────────────────────────────────────────────────────

def make_png_1x1_white():
    """Create a minimal 1x1 white PNG (RGB)."""
    def png_chunk(chunk_type, data):
        chunk = chunk_type + data
        crc = zlib.crc32(chunk) & 0xFFFFFFFF
        return struct.pack('>I', len(data)) + chunk + struct.pack('>I', crc)

    ihdr_data = struct.pack('>IIBBBBB', 1, 1, 8, 6, 0, 0, 0)
    raw_data = b'\x00\xff\xff\xff\xff'  # filter=None + white RGBA pixel
    idat_data = zlib.compress(raw_data)

    return (b'\x89PNG\r\n\x1a\n' +
            png_chunk(b'IHDR', ihdr_data) +
            png_chunk(b'IDAT', idat_data) +
            png_chunk(b'IEND', b''))


# ── Binary Helpers ───────────────────────────────────────────────────────────

def pack_floats(values):
    return struct.pack(f'<{len(values)}f', *values)


def pack_uint16(values):
    return struct.pack(f'<{len(values)}H', *values)


def compute_min_max_vec3(flat_values):
    xs = flat_values[0::3]
    ys = flat_values[1::3]
    zs = flat_values[2::3]
    return [min(xs), min(ys), min(zs)], [max(xs), max(ys), max(zs)]


# ── GLB Assembly ─────────────────────────────────────────────────────────────

def main():
    # Build geometry
    pa_pos, pa_norm, pa_uv, pa_idx = build_print_area()

    npo_pos, npo_norm, npo_idx = build_non_print_outer()
    iw_pos, iw_norm, iw_idx = build_inner_wall()
    bd_pos, bd_norm, bd_idx = build_bottom_disc()
    tr_pos, tr_norm, tr_idx = build_top_rim()
    hd_pos, hd_norm, hd_idx = build_handle()

    # Merge all GlazedPorcelain geometry
    gp_pos = []
    gp_norm = []
    gp_idx = []

    offset = 0
    for pos, norm, idx in [
        (npo_pos, npo_norm, npo_idx),
        (iw_pos, iw_norm, iw_idx),
        (bd_pos, bd_norm, bd_idx),
        (tr_pos, tr_norm, tr_idx),
        (hd_pos, hd_norm, hd_idx),
    ]:
        n_verts = len(pos) // 3
        gp_pos.extend(pos)
        gp_norm.extend(norm)
        gp_idx.extend([i + offset for i in idx])
        offset += n_verts

    # Pack binary data
    pa_pos_bytes = pack_floats(pa_pos)
    pa_norm_bytes = pack_floats(pa_norm)
    pa_uv_bytes = pack_floats(pa_uv)
    pa_idx_bytes = pack_uint16(pa_idx)

    gp_pos_bytes = pack_floats(gp_pos)
    gp_norm_bytes = pack_floats(gp_norm)
    gp_idx_bytes = pack_uint16(gp_idx)

    png_bytes = make_png_1x1_white()

    # Build buffer with aligned views
    buffer_data = bytearray()
    buffer_views = []

    def add_view(data, target=None):
        while len(buffer_data) % 4 != 0:
            buffer_data.append(0)
        bv_offset = len(buffer_data)
        buffer_data.extend(data)
        bv = {"buffer": 0, "byteOffset": bv_offset, "byteLength": len(data)}
        if target is not None:
            bv["target"] = target
        buffer_views.append(bv)
        return len(buffer_views) - 1

    # PrintArea buffer views
    bv_pa_pos = add_view(pa_pos_bytes, 34962)
    bv_pa_norm = add_view(pa_norm_bytes, 34962)
    bv_pa_uv = add_view(pa_uv_bytes, 34962)
    bv_pa_idx = add_view(pa_idx_bytes, 34963)

    # GlazedPorcelain buffer views
    bv_gp_pos = add_view(gp_pos_bytes, 34962)
    bv_gp_norm = add_view(gp_norm_bytes, 34962)
    bv_gp_idx = add_view(gp_idx_bytes, 34963)

    # PNG image buffer view
    bv_png = add_view(png_bytes)

    # Pad buffer to 4-byte alignment
    while len(buffer_data) % 4 != 0:
        buffer_data.append(0)

    # Compute accessor min/max
    pa_n_verts = len(pa_pos) // 3
    gp_n_verts = len(gp_pos) // 3
    pa_pos_min, pa_pos_max = compute_min_max_vec3(pa_pos)
    gp_pos_min, gp_pos_max = compute_min_max_vec3(gp_pos)

    # Build glTF JSON
    gltf = {
        "asset": {"version": "2.0", "generator": "generate_mug.py"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"mesh": 0}],
        "meshes": [{
            "primitives": [
                {
                    "attributes": {
                        "POSITION": 0,
                        "NORMAL": 1,
                        "TEXCOORD_0": 2
                    },
                    "indices": 3,
                    "material": 0
                },
                {
                    "attributes": {
                        "POSITION": 4,
                        "NORMAL": 5
                    },
                    "indices": 6,
                    "material": 1
                }
            ]
        }],
        "materials": [
            {
                "name": "PrintArea",
                "alphaMode": "BLEND",
                "pbrMetallicRoughness": {
                    "baseColorTexture": {"index": 0},
                    "metallicFactor": 0.04,
                    "roughnessFactor": 0.25
                }
            },
            {
                "name": "GlazedPorcelain",
                "pbrMetallicRoughness": {
                    "baseColorFactor": [0.88, 0.86, 0.82, 1.0],
                    "metallicFactor": 0.04,
                    "roughnessFactor": 0.25
                }
            }
        ],
        "textures": [{"source": 0, "sampler": 0}],
        "images": [{"bufferView": bv_png, "mimeType": "image/png"}],
        "samplers": [{
            "magFilter": 9729,
            "minFilter": 9987,
            "wrapS": 33071,
            "wrapT": 33071
        }],
        "accessors": [
            {
                "bufferView": bv_pa_pos,
                "componentType": 5126,
                "count": pa_n_verts,
                "type": "VEC3",
                "min": pa_pos_min,
                "max": pa_pos_max
            },
            {
                "bufferView": bv_pa_norm,
                "componentType": 5126,
                "count": pa_n_verts,
                "type": "VEC3"
            },
            {
                "bufferView": bv_pa_uv,
                "componentType": 5126,
                "count": pa_n_verts,
                "type": "VEC2"
            },
            {
                "bufferView": bv_pa_idx,
                "componentType": 5123,
                "count": len(pa_idx),
                "type": "SCALAR"
            },
            {
                "bufferView": bv_gp_pos,
                "componentType": 5126,
                "count": gp_n_verts,
                "type": "VEC3",
                "min": gp_pos_min,
                "max": gp_pos_max
            },
            {
                "bufferView": bv_gp_norm,
                "componentType": 5126,
                "count": gp_n_verts,
                "type": "VEC3"
            },
            {
                "bufferView": bv_gp_idx,
                "componentType": 5123,
                "count": len(gp_idx),
                "type": "SCALAR"
            }
        ],
        "bufferViews": buffer_views,
        "buffers": [{"byteLength": len(buffer_data)}]
    }

    # Serialize JSON
    json_str = json.dumps(gltf, separators=(',', ':'))
    json_bytes = json_str.encode('utf-8')
    while len(json_bytes) % 4 != 0:
        json_bytes += b' '

    # Assemble GLB
    total_length = 12 + 8 + len(json_bytes) + 8 + len(buffer_data)
    glb_header = struct.pack('<III', 0x46546C67, 2, total_length)
    json_chunk = struct.pack('<II', len(json_bytes), 0x4E4F534A) + json_bytes
    bin_chunk = struct.pack('<II', len(buffer_data), 0x004E4942) + bytes(buffer_data)

    glb = glb_header + json_chunk + bin_chunk

    out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'mug.glb')
    with open(out_path, 'wb') as f:
        f.write(glb)

    print(f"Written {len(glb)} bytes to {out_path}")
    print(f"PrintArea: {pa_n_verts} vertices, {len(pa_idx)} indices")
    print(f"GlazedPorcelain: {gp_n_verts} vertices, {len(gp_idx)} indices")


if __name__ == '__main__':
    main()
