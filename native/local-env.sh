#!/usr/bin/env bash
# LOCAL machine paths for cuDSS / CUDA + the benchmark case directory.
# GITIGNORED — never committed or upstreamed. It restores the paths that the generic
# committed defaults (/usr/local/cuda, target/gpu-cases) do not match on this machine.
#
# Auto-sourced by native/build-gpu.sh and native/cuda/run_gpu_tests.sh.
# For an ad-hoc test run, source it first:
#     source native/local-env.sh && mvn -o test -Dtest=GpuBatchedN1Test
#
# The loader resolves cuDSS from CUDSS_ROOT/lib and CUDA from CUDA_HOME|CUDA_TOOLKIT + /lib64,
# so these env vars alone are enough for both the native build AND the JUnit tests.
export CUDA_TOOLKIT="${CUDA_TOOLKIT:-$HOME/Projects/Software/cuda-toolkit}"
export CUDA_HOME="${CUDA_HOME:-$HOME/Projects/Software/cuda-toolkit}"
export CUDSS_ROOT="${CUDSS_ROOT:-$HOME/Projects/powsybl/cuda/cudss}"   # 0.8.0 (pinned 2026-07-10; 0.7.1 kept at cudss-0.7.1 for A/B via native/cudss-swap.sh)
export CUDA_ARCH="${CUDA_ARCH:-86}"
export OLF_GPU_CASE_DIR="${OLF_GPU_CASE_DIR:-$HOME/Projects/powsybl/powerflow/pyloadflow/cases}"
