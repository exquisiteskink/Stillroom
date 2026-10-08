#!/usr/bin/env python3
"""Reproducible synthetic fixture: EAN-8 with leading zeros and a local QR code."""
import struct,zlib,subprocess,tempfile
from pathlib import Path
left=['0001101','0011001','0010011','0111101','0100011','0110001','0101111','0111011','0110111','0001011']
right=['1110010','1100110','1101100','1000010','1011100','1001110','1010000','1000100','1001000','1110100']
body='0012345'
check=(10-sum(int(c)*(3 if i%2==0 else 1) for i,c in enumerate(body[::-1]))%10)%10
code=body+str(check)
bits='101'+''.join(left[int(c)] for c in code[:4])+'01010'+''.join(right[int(c)] for c in code[4:])+'101'
width,height=900,700
pixels=bytearray([255])*(width*height)
for i,bit in enumerate(bits):
 if bit=='1':
  for y in range(130,290):
   pixels[y*width+130+i*5:y*width+130+(i+1)*5]=b'\x00'*5
def chunk(t,d): return struct.pack('>I',len(d))+t+d+struct.pack('>I',zlib.crc32(t+d)&0xffffffff)
raw=b''.join(b'\x00'+pixels[y*width:(y+1)*width] for y in range(height))
with tempfile.TemporaryDirectory() as scratch:
 root=Path(scratch)
 (root/'ean.png').write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',width,height,8,0,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b''))
 subprocess.run(['qrencode','-s','8','-m','4','-o',str(root/'qr.png'),'STILLROOM-FIXTURE-QR'],check=True)
 subprocess.run(['magick',str(root/'ean.png'),str(root/'qr.png'),'-geometry','+530+340','-composite','app/src/androidTest/assets/scanner/multi.png'],check=True)
print('Synthetic EAN-8 '+code+' + STILLROOM-FIXTURE-QR fixture generated.')
