#!/usr/bin/env python3
import os, sys, shlex
if len(sys.argv) != 2:
    print('Usage: laner-edit FILE'); raise SystemExit(2)
path = os.path.abspath(sys.argv[1])
try:
    with open(path, 'r', encoding='utf-8') as f: lines = f.read().splitlines()
except FileNotFoundError:
    lines = []
dirty = False
print(f'laner-edit: {path} ({len(lines)} lines)')
print('Commands: p [a b], r N TEXT, i N TEXT, a N TEXT, d N [M], w, q, q!, h')
def show(a=1,b=None):
    if b is None: b = min(len(lines), a+39)
    a=max(1,a); b=min(len(lines),b)
    for n in range(a,b+1): print(f'{n:5d} | {lines[n-1]}')
def save():
    os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
    tmp=path+'.laner-edit.tmp'
    with open(tmp,'w',encoding='utf-8',newline='\n') as f:
        f.write('\n'.join(lines) + ('\n' if lines else ''))
    os.replace(tmp,path)
show()
while True:
    try: raw=input('edit> ').strip()
    except EOFError: raw='q'
    if not raw: continue
    cmd,*rest=raw.split(' ',1); arg=rest[0] if rest else ''
    try:
        if cmd=='p':
            ns=[int(x) for x in arg.split()] if arg else []
            show(*(ns[:2] or [1]))
        elif cmd in ('r','i','a'):
            n_s,text=arg.split(' ',1); n=int(n_s)
            if cmd=='r': lines[n-1]=text
            elif cmd=='i': lines.insert(max(0,n-1),text)
            else: lines.insert(min(len(lines),n),text)
            dirty=True
        elif cmd=='d':
            ns=[int(x) for x in arg.split()]; a=ns[0]; b=ns[1] if len(ns)>1 else a
            del lines[a-1:b]; dirty=True
        elif cmd=='w': save(); dirty=False; print('written')
        elif cmd=='q!': break
        elif cmd=='q':
            if dirty: print('unsaved changes; use w or q!')
            else: break
        elif cmd=='h': print('p [a b] | r N TEXT | i N TEXT | a N TEXT | d N [M] | w | q | q!')
        else: print('unknown command; h for help')
    except Exception as e: print(f'error: {e}')
