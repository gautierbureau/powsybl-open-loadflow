# Upstream powsybl-core patches

These patches apply to [`powsybl-core`](https://github.com/powsybl/powsybl-core)
(based on tag `v7.3.0-RC2`), not to this repository. They are kept here so the
DC load flow / security analysis / sensitivity performance work in this branch
can be reproduced end-to-end, since part of the gain comes from powsybl-core.

## Patches

- `powsybl-core-dense-matrix-double-array.patch`
  Backs `DenseMatrix` with a column-major `double[]` instead of a direct
  `ByteBuffer`, so `get`/`set`/`add` become JIT-friendly array accesses. The
  direct buffer is kept only to feed the native KLU solver (`getBuffer()` /
  `syncFromBuffer()` round-trip around each native `solve`), so no JNI/native
  code change is needed. Measured ~18% off the full DC sensitivity correction
  path on case13659pegase (5000 factors, 100 monitored branches, 500
  contingencies).

## Applying

```sh
cd /path/to/powsybl-core
git checkout v7.3.0-RC2
git am /path/to/powsybl-open-loadflow/patches/powsybl-core-dense-matrix-double-array.patch
# then build & install locally so this project picks it up:
mvn -pl math install -DskipTests
```
