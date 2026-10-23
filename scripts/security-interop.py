#!/usr/bin/env python3
"""Independent framing/AES-GCM interoperability on PUBLIC SYNTHETIC fixtures only.
Requires cryptography. Does not inspect user files or Android Keystore keys.
"""
import hashlib
import os
from pathlib import Path
import struct
import sys
import uuid
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

root = Path(sys.argv[1])
props = dict(line.strip().split('=', 1) for line in (root / 'jvm.properties').read_text().splitlines() if line and not line.startswith('#'))
wire = (root / 'jvm.cv').read_bytes()
plain = (root / 'plain.bin').read_bytes()
header = wire[:20]
chunk = 1024 * 1024
assert header[:8] == b'CVCHNK01' and struct.unpack('>i', header[8:12])[0] == chunk
key = bytes.fromhex(props['key'])
pos, index, output = 20, 0, bytearray()
while True:
    length = struct.unpack('>i', wire[pos:pos + 4])[0]
    assert 0 <= length <= chunk
    pos += 4
    aad = header + props['id'].encode() + struct.pack('>ii', index, length)
    output += AESGCM(key).decrypt(header[12:20] + struct.pack('>i', index), wire[pos:pos + length + 16], aad)
    pos += length + 16
    index += 1
    if length == 0:
        break
assert pos == len(wire) and bytes(output) == plain
assert len(output) == int(props['size']) and hashlib.sha256(output).hexdigest() == props['digest']
print('PASS independent Python/OpenSSL decoder authenticates every JVM frame and exact source bytes')

# Different public fixture key and fresh random prefix for the reverse direction.
key = bytes(reversed(range(32)))
identity = str(uuid.uuid4())
header = b'CVCHNK01' + struct.pack('>i', chunk) + os.urandom(8)
wire = bytearray(header)
frames = [plain[i:i + chunk] for i in range(0, len(plain), chunk)] + [b'']
for index, frame in enumerate(frames):
    length = len(frame)
    aad = header + identity.encode() + struct.pack('>ii', index, length)
    wire += struct.pack('>i', length) + AESGCM(key).encrypt(header[12:20] + struct.pack('>i', index), frame, aad)
(root / 'python.cv').write_bytes(wire)
(root / 'python.properties').write_text(f'key={key.hex()}\nid={identity}\nsize={len(plain)}\ndigest={hashlib.sha256(plain).hexdigest()}\n')
print('CREATED independent Python/OpenSSL ciphertext for production JVM-reader verification')
