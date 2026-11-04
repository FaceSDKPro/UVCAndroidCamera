"""Small dependency-free reader for the ELF dynamic metadata we verify in shipped Android libraries."""
import struct


def dynamic_metadata(data):
    assert data[:4] == b'\x7fELF' and data[5] == 1, 'Expected little-endian ELF'
    wide = data[4] == 2
    assert wide or data[4] == 1, 'Unsupported ELF class'
    machine = struct.unpack_from('<H', data, 18)[0]
    section_offset = struct.unpack_from('<Q' if wide else '<I', data, 40 if wide else 32)[0]
    entry_size, count = struct.unpack_from('<HH', data, 58 if wide else 46)
    assert 0 < count < 4096 and entry_size == (64 if wide else 40)
    assert section_offset + count * entry_size <= len(data)
    sections = [struct.unpack_from('<IIQQQQIIQQ' if wide else '<IIIIIIIIII', data, section_offset + i * entry_size) for i in range(count)]
    exports, imports, needed = set(), set(), set()

    def section_bytes(section):
        offset, size = section[4:6]
        assert offset + size <= len(data)
        return data[offset:offset + size]

    def string(table, offset):
        assert offset < len(table)
        end = table.index(b'\0', offset)
        return table[offset:end].decode('utf-8')

    symbols_seen = dynamic_seen = bind_now = False
    for section in sections:
        kind = section[1]
        if kind not in (6, 11):
            continue
        assert section[6] < len(sections)
        strings = section_bytes(sections[section[6]])
        content = section_bytes(section)
        stride = section[9]
        if kind == 11:
            symbols_seen = True
            assert stride == (24 if wide else 16) and len(content) % stride == 0
            for offset in range(0, len(content), stride):
                item = struct.unpack_from('<IBBHQQ' if wide else '<IIIBBH', content, offset)
                name = string(strings, item[0])
                info, visibility, index = (item[1], item[2] & 3, item[3]) if wide else (item[3], item[4] & 3, item[5])
                if name and info >> 4 in (1, 2):
                    if index == 0:
                        imports.add(name)
                    elif visibility in (0, 3):
                        exports.add(name)
        else:
            dynamic_seen = True
            assert stride == (16 if wide else 8) and len(content) % stride == 0
            for offset in range(0, len(content), stride):
                tag, value = struct.unpack_from('<qQ' if wide else '<iI', content, offset)
                if tag == 1:
                    needed.add(string(strings, value))
                elif tag == 24 or (tag == 30 and value & 8) or (tag == 0x6ffffffb and value & 1):
                    bind_now = True
    assert symbols_seen and dynamic_seen, 'Missing dynamic metadata'
    return {'machine': machine, 'exports': exports, 'imports': imports, 'needed': needed, 'bind_now': bind_now}


def segment_metadata(data):
    """Read LOAD alignment and memory protection from either Android ELF class."""
    assert data[:4] == b'\x7fELF' and data[5] == 1
    wide = data[4] == 2
    assert wide or data[4] == 1
    offset = struct.unpack_from('<Q' if wide else '<I', data, 32 if wide else 28)[0]
    stride, count = struct.unpack_from('<HH', data, 54 if wide else 42)
    assert stride == (56 if wide else 32) and 0 < count < 4096
    assert offset + stride * count <= len(data)
    loads, stacks, relro = [], [], False
    for index in range(count):
        item = struct.unpack_from('<IIQQQQQQ' if wide else '<IIIIIIII', data, offset + index * stride)
        kind = item[0]
        flags, file_offset, address = (item[1], item[2], item[3]) if wide else (item[6], item[1], item[2])
        if kind == 1:
            loads.append({'alignment': item[7], 'offset': file_offset, 'address': address, 'flags': flags})
        elif kind == 0x6474e551:
            stacks.append(flags)
        elif kind == 0x6474e552:
            relro = True
    assert loads and len(stacks) == 1, 'Missing LOAD/GNU_STACK segments'
    return {'loads': loads, 'relro': relro, 'executable_stack': bool(stacks[0] & 1)}
