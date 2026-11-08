#!/usr/bin/env python3
import struct
import sys
from pathlib import Path


def u1(data, pos):
    return data[pos], pos + 1


def u2(data, pos):
    return struct.unpack_from(">H", data, pos)[0], pos + 2


def u4(data, pos):
    return struct.unpack_from(">I", data, pos)[0], pos + 4


def parse_constant_pool(data, pos):
    count, pos = u2(data, pos)
    names = {}
    index = 1
    while index < count:
        tag, pos = u1(data, pos)
        if tag == 1:
            length, pos = u2(data, pos)
            names[index] = data[pos:pos + length].decode("utf-8", "replace")
            pos += length
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            pos += 2
        elif tag == 15:
            pos += 3
        else:
            raise ValueError(f"constant pool tag {tag} at index {index}")
        index += 1
    return names, pos


def parse_member(data, pos):
    start = pos
    _, pos = u2(data, pos)
    _, pos = u2(data, pos)
    _, pos = u2(data, pos)
    attribute_count, pos = u2(data, pos)
    attributes = []
    for _ in range(attribute_count):
        attr_start = pos
        name_index, pos = u2(data, pos)
        length, pos = u4(data, pos)
        pos += length
        attributes.append((name_index, data[attr_start:pos]))
    return data[start:pos], attributes, pos


def rebuild_member(header, attributes):
    _, _, _, count_start = header
    out = bytearray(header)
    out[6:8] = struct.pack(">H", len(attributes))
    for _, raw in attributes:
        out += raw
    return bytes(out)


def process(path):
    data = path.read_bytes()
    if data[:4] != b"\xca\xfe\xba\xbe":
        return
    names, pos = parse_constant_pool(data, 8)
    header = data[:pos]
    pos_after_cp = pos
    access, this_class, super_class, interfaces_count = struct.unpack_from(">HHHH", data, pos)
    pos += 8 + interfaces_count * 2
    out = bytearray(data[:pos])

    fields_count, pos = u2(data, pos)
    out += struct.pack(">H", fields_count)
    for _ in range(fields_count):
        raw, _, pos = parse_member(data, pos)
        out += raw

    methods_count, pos = u2(data, pos)
    out += struct.pack(">H", methods_count)
    for _ in range(methods_count):
        method_start = pos
        _, _, _, attr_count = struct.unpack_from(">HHHH", data, pos)
        pos += 8
        kept = []
        for _ in range(attr_count):
            attr_start = pos
            name_index, pos = u2(data, pos)
            length, pos = u4(data, pos)
            pos += length
            if names.get(name_index) != "MethodParameters":
                kept.append((name_index, data[attr_start:pos]))
        out += data[method_start:method_start + 6]
        out += struct.pack(">H", len(kept))
        for _, raw in kept:
            out += raw

    out += data[pos:]
    path.write_bytes(bytes(out))


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    count = 0
    for path in root.rglob("*.class"):
        process(path)
        count += 1
    print(f"strip_method_parameters: {count} clases procesadas")


if __name__ == "__main__":
    main()
