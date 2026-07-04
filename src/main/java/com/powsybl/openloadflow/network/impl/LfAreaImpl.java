/**
 * Copyright (c) 2024, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.util.SV;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.PerUnit;

import java.util.*;

/**
 * @author Valentin Mouradian {@literal <valentin.mouradian at artelys.com>}
 */
public class LfAreaImpl extends AbstractElement implements LfArea {

    private final Ref<Area> areaRef;

    private final String id;

    private double interchangeTarget;

    private final Set<LfBus> buses;

    private final Set<Boundary> boundaries;

    protected LfAreaImpl(Area area, Set<LfBus> buses, Set<Boundary> boundaries, LfNetwork lfNetwork, LfNetworkParameters parameters) {
        super(lfNetwork);
        this.areaRef = Ref.create(area, parameters.isCacheEnabled());
        this.id = area.getId();
        this.interchangeTarget = area.getInterchangeTarget().orElse(0.0) / PerUnit.SB;
        this.buses = buses;
        this.boundaries = boundaries;
    }

    public static LfAreaImpl create(Area area, Set<LfBus> buses, Set<Boundary> boundaries, LfNetwork network, LfNetworkParameters parameters) {
        LfAreaImpl lfArea = new LfAreaImpl(area, buses, boundaries, network, parameters);
        lfArea.getBuses().forEach(bus -> bus.setArea(lfArea));
        return lfArea;
    }

    protected LfAreaImpl(LfAreaImpl other, Set<LfBus> buses, Set<Boundary> boundaries, LfNetwork network) {
        super(network);
        this.areaRef = other.areaRef;
        this.id = other.id;
        this.interchangeTarget = other.interchangeTarget;
        this.buses = buses;
        this.boundaries = boundaries;
        this.disabled = other.disabled;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public ElementType getType() {
        return ElementType.AREA;
    }

    @Override
    public double getInterchangeTarget() {
        return interchangeTarget;
    }

    @Override
    public void setInterchangeTarget(double interchangeTarget) {
        this.interchangeTarget = interchangeTarget;
    }

    @Override
    public double getInterchange() {
        return boundaries.stream().mapToDouble(Boundary::getP).sum();
    }

    @Override
    public Set<LfBus> getBuses() {
        return buses;
    }

    @Override
    public Set<Boundary> getBoundaries() {
        return boundaries;
    }

    public static class BoundaryImpl implements Boundary {
        private final LfBranch branch;
        private final TwoSides side;

        public BoundaryImpl(LfBranch branch, TwoSides side) {
            this.branch = Objects.requireNonNull(branch);
            this.side = Objects.requireNonNull(side);
        }

        @Override
        public LfBranch getBranch() {
            return branch;
        }

        @Override
        public TwoSides getSide() {
            return side;
        }

        @Override
        public double getP() {
            if (branch.isDisabled()) {
                return 0.0;
            }
            if (branch instanceof LfTieLineBranch lfTieLineBranch) {
                // uses the electrical characteristics cached at build time by the tie line branch,
                // so this outer loop evaluation never goes back to the iidm network
                if (side == TwoSides.ONE) {
                    LfTieLineBranch.HalfParams half1 = lfTieLineBranch.getHalf1Params();
                    return otherSideP(lfTieLineBranch.getP1().eval() * PerUnit.SB, lfTieLineBranch.getQ1().eval() * PerUnit.SB,
                            lfTieLineBranch.getV1() * half1.nominalV(), Math.toDegrees(lfTieLineBranch.getAngle1()), side, half1) / PerUnit.SB;
                } else if (side == TwoSides.TWO) {
                    LfTieLineBranch.HalfParams half2 = lfTieLineBranch.getHalf2Params();
                    return otherSideP(lfTieLineBranch.getP2().eval() * PerUnit.SB, lfTieLineBranch.getQ2().eval() * PerUnit.SB,
                            lfTieLineBranch.getV2() * half2.nominalV(), Math.toDegrees(lfTieLineBranch.getAngle2()), side, half2) / PerUnit.SB;
                }
            }
            return switch (side) {
                case ONE -> branch.getP1().eval();
                case TWO -> branch.getP2().eval();
            };
        }

        /**
         * Active power at the boundary side of the half line, from the state at the network side.
         * Same behavior as {@code SV.otherSideP(boundaryLine, false)}: full AC computation when the
         * whole state is known, lossless DC approximation (other side P is just -P) otherwise
         * (typically a DC load flow where Q is not computed).
         */
        private static double otherSideP(double p, double q, double u, double a, TwoSides side, LfTieLineBranch.HalfParams half) {
            if (Double.isNaN(q) || Double.isNaN(u) || Double.isNaN(a)) {
                return -p;
            }
            return new SV(p, q, u, a, side).otherSideP(half.r(), half.x(), half.g(), half.b(), 0, 0, 1, 0);
        }
    }
}
