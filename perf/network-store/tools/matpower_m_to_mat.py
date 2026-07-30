"""Convert a MATPOWER .m case file to the binary .mat format powsybl can import.

MATPOWER distributes its cases as MATLAB .m scripts, while powsybl-matpower-converter reads the
binary MAT format. This writes a MAT v5 file holding the `mpc` struct with the fields
MatpowerReader requires: version, baseMVA, bus, gen, branch.

    python3 matpower_m_to_mat.py case13659pegase.m case13659pegase.mat

Requires numpy and scipy.
"""
import re, sys
import numpy as np
from scipy.io import savemat

src, dst = sys.argv[1], sys.argv[2]
text = open(src).read()

def matrix(name):
    m = re.search(r'^mpc\.' + name + r'\s*=\s*\[(.*?)\n\s*\];', text, re.S | re.M)
    if not m:
        return None
    rows = []
    for line in m.group(1).split('\n'):
        line = line.split('%')[0].strip().rstrip(';').strip()
        if not line:
            continue
        rows.append([float(v) for v in line.split()])
    width = max(len(r) for r in rows)
    assert all(len(r) == width for r in rows), name + ' ragged'
    return np.array(rows, dtype=np.float64)

bus = matrix('bus')
gen = matrix('gen')
branch = matrix('branch')
version = re.search(r"mpc\.version\s*=\s*'([^']+)'", text).group(1)
base_mva = float(re.search(r'mpc\.baseMVA\s*=\s*([0-9.eE+-]+)', text).group(1))

mpc = {'version': version, 'baseMVA': base_mva, 'bus': bus, 'gen': gen, 'branch': branch}
savemat(dst, {'mpc': mpc}, format='5', do_compression=False, oned_as='row')
print('version=%s baseMVA=%s bus=%s gen=%s branch=%s' % (version, base_mva, bus.shape, gen.shape, branch.shape))
