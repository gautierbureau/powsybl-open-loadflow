"""Diff two `jcmd GC.class_histogram` dumps and show the biggest growers."""
import re
import sys


def load(path):
    out = {}
    # columns: num  #instances  #bytes  class name
    pat = re.compile(r'^\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)')
    with open(path) as f:
        for line in f:
            m = pat.match(line)
            if m:
                out[m.group(3)] = (int(m.group(1)), int(m.group(2)))
    return out


early, late = load(sys.argv[1]), load(sys.argv[2])
iters = int(sys.argv[3]) if len(sys.argv) > 3 else 1

rows = []
for cls, (n_late, b_late) in late.items():
    n_early, b_early = early.get(cls, (0, 0))
    rows.append((b_late - b_early, n_late - n_early, cls, n_early, n_late))

rows.sort(reverse=True)
print(f'{"bytes grown":>14} {"B/iter":>8} {"inst grown":>11} {"early":>9} {"late":>9}  class')
print('-' * 100)
total = 0
for db, dn, cls, ne, nl in rows[:25]:
    total += db
    print(f'{db:>14} {db / iters:>8.2f} {dn:>11} {ne:>9} {nl:>9}  {cls}')
print('-' * 100)
print(f'total growth of top 25: {total} bytes over {iters} iterations = {total / iters:.2f} B/iter')
print(f'total heap growth     : {sum(r[0] for r in rows)} bytes = {sum(r[0] for r in rows) / iters:.2f} B/iter')
