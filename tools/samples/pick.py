"""Choose VSCO-2-CE files per instrument (one dynamic, one round robin, notes ~4-6 semitones apart)."""
import re, sys, json

tree = [l.rstrip('\n').split('\t') for l in open(sys.argv[1], encoding='utf-8')]
files = [(int(s), p) for s, p in tree if p.lower().endswith('.wav')]

NOTE = {'C': 0, 'D': 2, 'E': 4, 'F': 5, 'G': 7, 'A': 9, 'B': 11}
def midi(name):
    m = re.match(r'([A-G])(#|b)?(-?\d)$', name)
    if not m: return None
    n = NOTE[m.group(1)] + (1 if m.group(2) == '#' else -1 if m.group(2) == 'b' else 0)
    return n + 12 * (int(m.group(3)) + 1)

# id: (path regex, note regex group, spacing, lo, hi, kind)
SPEC = {
  'cello':     (r'Strings/Cello Section/susvib/susvib_([A-G]#?\d)_v1_1\.wav', 4, 36, 69, 'sus'),
  'violin':    (r'Strings/Solo Violin/Arco Vib/LLVln_ArcoVib_([A-G]#?\d)_p\.wav', 4, 55, 96, 'sus'),
  'strings':   (r'Strings/Violin Section/susVib/VlnEns_susVib_([A-G]#?\d)_v1\.wav', 4, 55, 88, 'sus'),
  'viola':     (r'Strings/Viola Section/susvib/.*?_([A-G]#?\d)_v1.*\.wav', 5, 48, 76, 'sus'),
  'contrabass':(r'Strings/Solo Contrabass/Pizz/BKCtbss_Pizz_([A-G]#?\d)_v1_rr1\.wav', 4, 28, 55, 'hit'),
  'harp':      (r'Strings/Harp/KSHarp_([A-G]#?\d)_mf\.wav', 4, 36, 96, 'hit'),
  'flute':     (r'Woodwinds/Flute/susvib/LDFlute_susvib_([A-G]#?\d)_v1_1\.wav', 4, 60, 96, 'sus'),
  'clarinet':  (r'Woodwinds/Clarinet/susLong/DCClar_susLong_([A-G]#?\d)_v2_rr1_sum\.wav', 4, 50, 88, 'sus'),
  'oboe':      (r'Woodwinds/Oboe/Vib/Oboe_Vib_([A-G]#?\d)_v1_Main\.wav', 4, 58, 89, 'sus'),
  'bassoon':   (r'Woodwinds/Bassoon/sus/PSBassoon_([A-G]#?\d)_v1_1\.wav', 4, 34, 72, 'sus'),
  'horn':      (r'Brass/F Horn/sus/MOHorn_sus_([A-G]#?\d)_v2_1\.wav', 4, 40, 77, 'sus'),
  'trumpet':   (r'Brass/Trumpet/susvib/Sum_SHTrumpet_susvib_([A-G]#?\d)_v1_rr1\.wav', 4, 54, 84, 'sus'),
  'glock':     (r'Percussion/Glock/glock_medium_([A-G]#?\d)\.wav', 5, 67, 108, 'hit'),
  'marimba':   (r'Percussion/Marimba/Marimba_hit_Outrigger_([A-G]#?\d)_loud_01\.wav', 4, 36, 96, 'hit'),
  'xylophone': (r'Percussion/Xylo/Xylo_Medium_([A-G]#?\d)_ff_01_far\.wav', 5, 55, 108, 'hit'),
}

out = {}
for iid, (rx, spacing, lo, hi, kind) in SPEC.items():
    cands = {}
    for size, p in files:
        m = re.fullmatch(rx, p)
        if m:
            n = midi(m.group(1))
            if n is not None and n not in cands: cands[n] = p
    notes = sorted(cands)
    chosen, last = [], -99
    for n in notes:
        if n - last >= spacing or n == notes[-1]:
            chosen.append(n); last = n
    out[iid] = {'kind': kind, 'notes': {str(n): cands[n] for n in chosen}}
    print(iid, kind, len(notes), '->', chosen, file=sys.stderr)

# Upright piano: key numbers from the mapping chart (file 000 = key 21), soft dynamic.
pn = {}
for size, p in files:
    m = re.fullmatch(r'Keys/Upright Piano/Player_dyn2_rr1_(\d{3})\.wav', p)
    if m: pn[21 + 2 * int(m.group(1))] = p
if not pn:
    for size, p in files:
        m = re.fullmatch(r'Keys/Upright Piano/Player_dyn1_rr1_(\d{3})\.wav', p)
        if m: pn[21 + int(m.group(1))] = p
sel, last = {}, -99
for n in sorted(pn):
    if n >= 29 and n <= 105 and n - last >= 4: sel[str(n)] = pn[n]; last = n
out['piano'] = {'kind': 'hit', 'notes': sel}
print('piano', sorted(int(k) for k in sel), file=sys.stderr)

# Organ (quiet manual): numbered keys; the pitch is detected later, so take every 4th file.
org = sorted(p for s, p in files if p.startswith('Keys/Organ/Quiet/') and 'Pedal' not in p)
out['organ'] = {'kind': 'sus', 'notes': {f'?{i}': p for i, p in enumerate(org[::2])}}
print('organ', len(org), org[:3], file=sys.stderr)

# Percussion one-shots (unpitched or fixed pitch).
def first(rx):
    for s, p in files:
        if re.fullmatch(rx, p): return p
    raise SystemExit('missing ' + rx)
out['perc'] = {'kind': 'perc', 'notes': {
    'timpani_lo': first(r'Percussion/Timpani/Timpani1_Hit_v3_rr1_Sum\.wav'),
    'timpani_hi': first(r'Percussion/Timpani/Timpani3_Hit_v1_rr1_Sum\.wav'),
    'bassdrum': first(r'Percussion/BDrumNewhit_v3_rr1_Sum\.wav'),
    'snaretap': first(r'Percussion/Snare2-taps_v2_rr1_Sum\.wav'),
    'triangle': first(r'Percussion/Triangle3-HitM_v1_rr1_Sum\.wav'),
    'tambshake': first(r'Percussion/Tamb1-Shake_v1_rr1_Sum\.wav'),
    'sleigh': first(r'Percussion/Sleighbells_Hit_v1_rr1_Mid\.wav'),
    'claves': first(r'Percussion/Claves1_Hit_v1_rr1_Sum\.wav'),
    'conga': first(r'Percussion/Conga-HitN_v2_rr1_Sum\.wav'),
    'tumba': first(r'Percussion/Tumba-HitN_v2_rr1_Sum\.wav'),
    'cymbal': first(r'Percussion/susCymb1-hit_pp_rr1\.wav'),
}}
json.dump(out, open(sys.argv[2], 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print('total files', sum(len(v['notes']) for v in out.values()), file=sys.stderr)
