# Copyright (c) 2026, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
# SPDX-License-Identifier: MPL-2.0
"""
JAX ``custom_vjp`` wrapper exposing an OpenLoadFlow AC solve as a differentiable op, for
self-supervised training (e.g. tertiary voltage control, Donon et al.).

Design decision (see ``smooth_pvpq_reactive_limits.md``): the forward is the **classic PV/PQ
outer-loop load flow** (robust — converges wherever a normal LF does), and the backward is the
**active-set adjoint at the solution** with the binding set frozen, implemented in OLF by
``ClassicVoltageControlVjp`` (generator setpoints) and ``LoadFlowAdjoint`` (current/Joule objective
terms and the relaxed transformer-ratio / shunt-susceptance levers), validated on IEEE 14 where the
smooth in-solve reformulations fail. This gives exact gradients, piecewise-valid within the current
binding regime, without the parked smooth solver.

The op maps the full continuous lever vector to the monitored outputs::

    theta   = concat[ targetV (generators) , ratio (transformers) , B (shunts) ]
    outputs = concat[ V (controlled buses)  , I1 (monitored)       , P1 (monitored) ]

so the TVC objective ``f = f_V(V) + f_I(I) + f_J(P)`` is pure JAX on ``outputs`` and its gradient
flows back to ``theta`` (and on to the GNN scores via the relaxation maps, by ordinary autodiff).

It runs as a host callback (``jax.pure_callback``): OpenLoadFlow is foreign CPU code in the
GraalVM/pypowsybl isolate, so there is no XLA fusion and one host bounce per call. ``vmap`` does not
cross the callback — batch with the internal loop / threads of ``_olf_*``.

This module targets the pypowsybl bindings; the native functions it relies on
(``pp_diff.run_keep_context``, ``pp_diff.loadflow_vjp``, ``pp_diff.free_context``) are the
``@CEntryPoint`` s of ``DiffLoadFlowCFunctions.java``. It does not run inside the
powsybl-open-loadflow Java repository.
"""
from __future__ import annotations

from functools import partial

import jax
import jax.numpy as jnp
import numpy as np

# ---------------------------------------------------------------------------------------------------
# pypowsybl native bridge (see DiffLoadFlowCFunctions.java).
#
#   run_keep_context(network_handle, theta: np.ndarray, spec) -> (outputs: np.ndarray, ctx: int)
#       split theta into (target_v, ratio, shunt_b) by spec sizes; run the classic PV/PQ load flow
#       with the transformer/shunt outer loops OFF (the relaxed ratio/B are fixed parameters); keep the
#       converged AcLoadFlowContext (factorised Jacobian) alive; return its handle and the monitored
#       outputs = concat[V, I1, P1].
#
#   loadflow_vjp(ctx, v_bar, i_bar, p_bar: np.ndarray) -> theta_bar: np.ndarray
#       build x_bar = (dV/dx)^T v_bar + (dI1/dx)^T i_bar + (dP1/dx)^T p_bar, solve J^T lambda = x_bar
#       once, and return theta_bar = concat[ dL/dtargetV , dL/dratio , dL/dB ] (active-set: 0 for
#       saturated generators).
#
#   free_context(ctx) -> None     # release the retained context
# ---------------------------------------------------------------------------------------------------
import pp_diff  # noqa: E402  (provided by the pypowsybl build; not importable in the Java repo)


def _sizes(spec):
    """(n_gen, n_rtc, n_shunt, n_mon) lever / monitored counts carried in the static spec."""
    return spec["n_gen"], spec["n_rtc"], spec["n_shunt"], spec["n_mon"]


def _n_out(spec):
    n_gen, _, _, n_mon = _sizes(spec)
    return n_gen + 2 * n_mon  # V (n_gen) + I1 (n_mon) + P1 (n_mon)


def _split_outputs(outputs, spec):
    n_gen, _, _, n_mon = _sizes(spec)
    v = outputs[:n_gen]
    i1 = outputs[n_gen:n_gen + n_mon]
    p1 = outputs[n_gen + n_mon:]
    return v, i1, p1


@partial(jax.custom_vjp, nondiff_argnums=(1,))
def olf_solve(theta: jax.Array, spec) -> jax.Array:
    """Differentiable map: lever vector theta -> monitored outputs (classic AC LF)."""
    outputs, _ = jax.pure_callback(
        _run_keep_context,
        (jax.ShapeDtypeStruct((_n_out(spec),), theta.dtype), jax.ShapeDtypeStruct((), jnp.int64)),
        theta, spec, vmap_method=None,
    )
    return outputs


def _run_keep_context(theta, spec):
    outputs, handle = pp_diff.run_keep_context(
        spec["network_handle"], np.asarray(theta, dtype=np.float64), spec["bridge_spec"])
    return jnp.asarray(outputs), jnp.int64(handle)


def _olf_fwd(theta: jax.Array, spec):
    outputs, handle = pp_diff.run_keep_context(
        spec["network_handle"], np.asarray(theta, dtype=np.float64), spec["bridge_spec"])
    return jnp.asarray(outputs), (int(handle), theta.shape)


def _olf_bwd(spec, residual, outputs_bar: jax.Array):
    handle, theta_shape = residual
    v_bar, i_bar, p_bar = _split_outputs(outputs_bar, spec)

    def _vjp_call(vb, ib, pb):
        theta_bar = pp_diff.loadflow_vjp(
            handle,
            np.asarray(vb, dtype=np.float64),
            np.asarray(ib, dtype=np.float64),
            np.asarray(pb, dtype=np.float64),
        )
        pp_diff.free_context(handle)
        return np.asarray(theta_bar, dtype=np.float64)

    theta_bar = jax.pure_callback(
        _vjp_call,
        jax.ShapeDtypeStruct(theta_shape, outputs_bar.dtype),
        v_bar, i_bar, p_bar, vmap_method=None,
    )
    return (theta_bar,)  # gradient w.r.t. theta only (spec is non-differentiable)


olf_solve.defvjp(_olf_fwd, _olf_bwd)


# ---------------------------------------------------------------------------------------------------
# Example: the Donon-style TVC loss f = f_V + f_I + f_J differentiated end to end through the LF.
# The GNN outputs the relaxed levers (theta); the physics loss is pure JAX on the LF outputs; the
# gradient flows GNN params <- (autodiff) <- theta <- (this custom_vjp) <- (V, I, P).
# ---------------------------------------------------------------------------------------------------
def tvc_loss(theta, spec, v_lo, v_hi, i_max, eps_v=0.05, eps_i=0.05,
             lambda_i=1.0, lambda_j=1.0):
    outputs = olf_solve(theta, spec)                       # classic AC LF, differentiable
    v, i1, p1 = _split_outputs(outputs, spec)
    vn = (v - v_lo) / (v_hi - v_lo)                        # f_V: squared dead-band voltage hinge
    f_v = jnp.sum(jax.nn.relu(eps_v - vn) ** 2 + jax.nn.relu(vn - 1.0 + eps_v) ** 2)
    f_i = lambda_i * jnp.sum(jax.nn.relu(i1 / i_max - 1.0 + eps_i) ** 2)   # f_I: overload hinge
    f_j = lambda_j * jnp.sum(jnp.abs(p1))                  # f_J: Joule / loss proxy on monitored flows
    return f_v + f_i + f_j


def gnn_loss(params, gnn_apply, context_features, spec, v_lo, v_hi, i_max):
    theta = gnn_apply(params, context_features)            # GNN -> relaxed levers (pure JAX)
    return tvc_loss(theta, spec, v_lo, v_hi, i_max)


# grad_params = jax.grad(gnn_loss)(params, gnn_apply, x, spec, v_lo, v_hi, i_max)
#
# ``spec`` is a static dict carrying the JavaHandle + per-lever id lists + the counts, e.g.
#   spec = {"network_handle": h, "n_gen": 4, "n_rtc": 3, "n_shunt": 1, "n_mon": 20,
#           "bridge_spec": (controller_ids, rtc_branch_ids, shunt_bus_ids, monitored_branch_ids)}
# theta is concat[targetV(4), ratio(3), B(1)]; outputs is concat[V(4), I1(20), P1(20)].
